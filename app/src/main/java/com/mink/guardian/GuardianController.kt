package com.mink.guardian

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mink.companion.CompanionRemark
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.data.LoadState
import com.mink.data.SignalStore
import com.mink.guardian.compose.AgentEvent
import com.mink.guardian.compose.AgentSpec
import com.mink.guardian.compose.Author
import com.mink.guardian.compose.FinalReply
import com.mink.guardian.compose.GroundedComposer
import com.mink.guardian.compose.HybridSpec
import com.mink.guardian.compose.SurfaceText
import com.mink.guardian.llm.GenParams
import com.mink.guardian.llm.LlamaBridge
import com.mink.guardian.llm.LlmEngine
import com.mink.guardian.llm.MiniCpmChatFormat
import com.mink.guardian.llm.TextGenerator
import com.mink.monitor.AppAccessScanner
import com.mink.monitor.DataUseDecision
import com.mink.monitor.DnsFlowHub
import com.mink.monitor.HighRiskScanner
import com.mink.monitor.NetworkUsageScanner
import com.mink.monitor.SensorInUseMonitor
import com.mink.monitor.TrackedSession
import com.mink.monitor.TrackerList
import com.mink.monitor.analyzeDataUsage
import com.mink.monitor.dataUseWindow
import com.mink.monitor.diffAppAccess
import com.mink.monitor.diffHighRisk
import com.mink.guardian.route.Mode
import com.mink.guardian.route.ModeRouter
import com.mink.guardian.route.Surface
import com.mink.monitor.toSnapshot
import com.mink.narrative.SummaryNarration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The concrete guardian. It wires the device capability to a model or the rules
 * engine, runs sweeps that diff the device over time, routes chat to the local
 * model (or a templated rules reply), and persists everything through
 * [GuardianStore]. Nothing it does leaves the device.
 */
class GuardianController(
    context: Context,
    private val store: SignalStore,
    private val scope: CoroutineScope,
    // The concrete engine owns model LIFECYCLE (load/unload/tier sizing) — an
    // Android/native concern that stays off the generation seam. The generator
    // is the GENERATION seam every text surface speaks through; it defaults to
    // the same engine, and tests inject a fake here to drive the chat/remark/
    // narrate paths (budgets, grounding, fallbacks) on a plain JVM.
    private val llmEngine: LlmEngine = LlmEngine(),
    private val generator: TextGenerator = llmEngine,
) : Guardian {

    private val appContext = context.applicationContext
    private val persistence = GuardianStore(appContext)
    private val modelManager = ModelManager(appContext)
    private val rules = RulesEngine()
    private val analyzer = GuardianAnalyzer(rules)

    /**
     * Decides which alerts become notifications. Stateful for the cooldown and
     * single-threaded by contract, so it is only called under [sweepMutex].
     */
    private val notificationGate = NotificationGate()

    /**
     * The guardian's own app-access scanner. Deliberately its own instance and
     * not shared with MinkServices/AppAccessMonitor: the guardian is constructed
     * before the UI graph and must stay self-contained.
     */
    private val appAccessScanner = AppAccessScanner(appContext)

    /**
     * The guardian's high-risk security-surface scanner. Its own instance for the
     * same reason as [appAccessScanner]: the guardian is constructed before the UI
     * graph and must stay self-contained.
     */
    private val highRiskScanner = HighRiskScanner(appContext)

    /**
     * The guardian's per-app data-use scanner. Its own instance for the same
     * reason as [appAccessScanner]: the guardian is constructed before the UI
     * graph and must stay self-contained.
     */
    private val networkScanner = NetworkUsageScanner(appContext)

    /** Bundled known-tracker list, for classifying observed DNS lookups. Loaded once. */
    private val trackerList by lazy { TrackerList.load(appContext) }

    /**
     * User-app uids already surfaced as tracker-contacting this process. Kept in
     * memory (guarded by [sweepMutex]) so the quiet insight fires at most once per
     * app per run rather than every sweep; a restart may re-note it once.
     */
    private val reportedTrackerUids = mutableSetOf<Int>()

    /**
     * Near real-time camera/microphone use watch. Sessions arrive on the
     * monitor's own handler thread and are folded into the timeline through
     * [onSensorSession]; it runs only between [enable] and [disable].
     */
    private val sensorMonitor = SensorInUseMonitor(appContext) { tracked -> onSensorSession(tracked) }

    private val capability = DeviceCapability.detect(appContext, LlamaBridge.isAvailable)

    /**
     * The one gate between the generator and the hybrid text surfaces: budget,
     * post-process, grounding check, deterministic fallback all live in core.
     * The remark and narration delegations below only build [HybridSpec]s.
     */
    private val composer = GroundedComposer(generator)

    private val _state = MutableStateFlow(
        GuardianState(tier = capability.tier),
    )
    override val state: StateFlow<GuardianState> = _state.asStateFlow()

    private val _alerts = MutableStateFlow<List<GuardianAlert>>(emptyList())
    override val alerts: StateFlow<List<GuardianAlert>> = _alerts.asStateFlow()

    private val _observations = MutableStateFlow<List<Observation>>(emptyList())
    override val observations: StateFlow<List<Observation>> = _observations.asStateFlow()

    private val _chatLog = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val chatLog: StateFlow<List<ChatMessage>> = _chatLog.asStateFlow()

    private val _baseline = MutableStateFlow<BaselineSummary?>(null)
    override val baseline: StateFlow<BaselineSummary?> = _baseline.asStateFlow()

    // Written from main-thread setters and IO restore/download paths; volatile
    // keeps cross-thread reads current (full mutual exclusion is deliberately
    // not attempted — last-write-wins is acceptable for config).
    @Volatile
    private var settings = GuardianSettings()

    /** Tunables for the slow-loop threshold refiner. */
    private val refinerConfig = RefinerConfig.DEFAULT

    /**
     * The learned per-source cooldown adjustment. Read on every notify decision,
     * rewritten once every [RefinerConfig.periodSweeps] sweeps — both only ever
     * under [sweepMutex], so the refiner never races. An empty default means
     * every multiplier is 1, i.e. exactly the pre-refiner behaviour, until the
     * first tick learns otherwise.
     */
    @Volatile
    private var refinerState = RefinerState()

    /**
     * Completed once the init restore has finished loading persisted settings,
     * successfully or not. Sweeps wait on it (bounded) so a WorkManager-spawned
     * process does not run the notification gate against default settings.
     */
    private val restored = CompletableDeferred<Unit>()

    /**
     * Serialises sweeps. Four triggers can call [sweepNow] concurrently (the
     * button, enable, the service loop, and the worker); the baseline is a
     * read-modify-write accumulator, so overlapping sweeps would silently lose
     * learned history without this.
     */
    private val sweepMutex = Mutex()

    init {
        GuardianServiceHost.controller = this

        if (capability.tier == GuardianTier.RULES_ONLY) {
            modelManager.markUnsupported("This device runs the rules guardian.")
        } else {
            modelManager.markReadyIfPresent(capability.tier)
        }

        // Fold the model manager state into the guardian state for the UI.
        scope.launch {
            modelManager.state.collect { model ->
                _state.update { it.copy(model = model) }
            }
        }

        // Restore persisted history and settings.
        scope.launch(Dispatchers.IO) {
            try {
                // Re-encrypt anything left as plaintext by a pre-encryption build.
                runCatching { persistence.migrateLegacyPayloads() }

                val obs = runCatching { persistence.loadObservations() }.getOrDefault(emptyList())
                val alertList = runCatching { persistence.loadAlerts() }.getOrDefault(emptyList())
                val chat = runCatching { persistence.loadChatLog() }.getOrDefault(emptyList())
                val storedBaseline = runCatching { persistence.loadBaseline() }.getOrNull()
                settings = runCatching { persistence.loadSettings() }.getOrDefault(GuardianSettings())
                refinerState = runCatching { persistence.loadRefinerState() }.getOrNull() ?: RefinerState()

                _observations.value = obs
                _alerts.value = alertList
                _chatLog.value = chat
                _state.update {
                    it.copy(
                        alertness = settings.alertness,
                        mutedSources = settings.mutedSources,
                    )
                }
                if (storedBaseline != null) {
                    _baseline.value = storedBaseline.summary(System.currentTimeMillis())
                }
                recomputeCounts()

                if (settings.enabled) enable()
            } finally {
                // Always completes, even if a load throws, so waiting sweeps
                // never stall on a failed restore.
                restored.complete(Unit)
            }
        }
    }

    // ---- lifecycle ----

    override fun enable() {
        // Re-sync the configuration into state alongside the tier so a UI
        // observing state never sees stale config.
        _state.update {
            it.copy(
                enabled = true,
                tier = capability.tier,
                alertness = settings.alertness,
                mutedSources = settings.mutedSources,
            )
        }
        persistSettings(settings.copy(enabled = true))

        if (capability.tier != GuardianTier.RULES_ONLY) {
            modelManager.markReadyIfPresent(capability.tier)
            if (modelManager.isDownloaded(capability.tier)) {
                loadModel()
            }
        }

        startService()
        scheduleSweeps()
        runCatching { sensorMonitor.start() }
        sweepNow()
    }

    override fun disable() {
        _state.update { it.copy(enabled = false) }
        persistSettings(settings.copy(enabled = false))
        stopService()
        runCatching { sensorMonitor.stop() }
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(SWEEP_WORK)
        }
        scope.launch { runCatching { llmEngine.unload() } }
    }

    override fun prepareModel() {
        if (capability.tier == GuardianTier.RULES_ONLY) {
            modelManager.markUnsupported("This device runs the rules guardian.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = modelManager.download(capability.tier)
            if (ok) {
                persistSettings(settings.copy(modelDownloaded = true))
                if (_state.value.enabled) loadModel()
            }
        }
    }

    override fun sweepNow() {
        scope.launch(Dispatchers.Default) {
            sweepMutex.withLock {
                // Wait for the init restore so the notification gate reads the
                // persisted settings, not defaults. Bounded so a wedged restore
                // can never stall sweeps; after the timeout the gate runs with
                // defaults, which only risks one notification filtered by
                // default policy.
                withTimeoutOrNull(RESTORE_WAIT_MS) { restored.await() }

                // Refresh the store and wait for the fresh collection to settle so the
                // first sweep after enable diffs against real data, not an empty or
                // stale snapshot. collectAll launches per-category work, so we await
                // the load states settling before reading what is present now.
                runCatching {
                    store.collectAll()
                    awaitCollectionSettled()
                }
                val snapshot = snapshotOf(store.signals.value)
                val previous = runCatching { persistence.loadSnapshot() }.getOrNull()
                val baseline = runCatching { persistence.loadBaseline() }.getOrNull()
                val now = System.currentTimeMillis()
                val sweepTime = SweepTime(
                    wallMs = now,
                    elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                    tzOffsetSeconds = java.util.TimeZone.getDefault().getOffset(now) / 1000,
                )
                val assessment = assessSweep(baseline?.lastSweepTime, sweepTime)

                val result = analyzer.analyze(previous, snapshot, now, baseline)
                val ruleFindings = rules.evaluate(store.signals.value)
                val ruleAlerts = ruleFindingsToAlerts(ruleFindings)

                addObservations(result.observations)
                realignRuleAlertLevels(ruleFindings)
                val newAlerts = (result.alerts + ruleAlerts)
                addAlerts(newAlerts)

                runCatching { persistence.saveSnapshot(snapshot) }

                // Fold this sweep into the learned baseline and publish its digest.
                val updatedBaseline = (baseline ?: GuardianBaseline.empty(now))
                    .updated(snapshot, now, trust = assessment.trust, sweepTime = sweepTime)
                runCatching { persistence.saveBaseline(updatedBaseline) }
                _baseline.value = updatedBaseline.summary(now)

                // Slow loop: once every RefinerConfig.periodSweeps sweeps, fold the
                // user's engagement (acknowledged alerts) into per-source cooldown
                // adjustments. Reuses the baseline's own sweep counter so there is no
                // second clock to drift; the shouldRefine guard keeps a failed baseline
                // save from re-firing the tick at the same count. Reads the live alert
                // list — this sweep's fresh alerts are excluded by the refiner's
                // maturity gate, and muted sources are excluded from the sample, so no
                // contamination.
                if (shouldRefine(updatedBaseline.sweepCount, refinerState.lastRefinedSweepCount, refinerConfig)) {
                    val sample = engagementSampleOf(
                        _alerts.value,
                        notifyFloor(settings.alertness),
                        settings.mutedSources,
                        now,
                        refinerConfig,
                    )
                    refinerState = refineThresholds(
                        refinerState,
                        sample,
                        RefinerContext(settings.alertness, settings.mutedSources),
                        refinerConfig,
                    ).copy(lastRefinedSweepCount = updatedBaseline.sweepCount)
                    runCatching { persistence.saveRefinerState(refinerState) }
                }

                // Watch who can reach what: diff granted capabilities across sweeps.
                // An empty scan is a failed scan, so it neither diffs nor saves —
                // it must not wipe the previous state or read as mass uninstall.
                val appAccessAlerts = runCatching {
                    val currentAccess = appAccessScanner.scan(now).toSnapshot(now)
                    if (currentAccess.apps.isEmpty()) {
                        emptyList()
                    } else {
                        val previousAccess = runCatching { persistence.loadAppAccessSnapshot() }.getOrNull()
                        val findings = diffAppAccess(previousAccess, currentAccess)
                        // Persist first: the saved snapshot is the commit point.
                        // Emit findings only once the new state is durable, so a
                        // failed write cannot re-diff and re-notify the same change
                        // on every following sweep. If the save fails, the change is
                        // simply re-detected and emitted once when it next succeeds.
                        val saved = runCatching { persistence.saveAppAccessSnapshot(currentAccess) }.isSuccess
                        if (saved && findings.isNotEmpty()) {
                            val mapped =
                                appAccessFindingsToGuardian(findings, now) { UUID.randomUUID().toString() }
                            addObservations(mapped.observations)
                            addAlerts(mapped.alerts)
                            mapped.alerts
                        } else {
                            emptyList()
                        }
                    }
                }.getOrDefault(emptyList())

                // Watch the classic device-compromise surfaces: accessibility
                // services, notification listeners, device admins, user CAs, default
                // apps, and a device-wide VPN. The scanner already carries each
                // surface forward on a read failure, so there is no separate
                // empty-scan guard — a fully-failed scan simply re-persists the
                // carried-forward previous and diffs to nothing. Persist-then-emit:
                // the saved snapshot is the commit point, so a failed write cannot
                // re-diff and re-notify the same change on every following sweep.
                val highRiskAlerts = runCatching {
                    val previousHr = runCatching { persistence.loadHighRiskSnapshot() }.getOrNull()
                    val currentHr = highRiskScanner.scan(now, previousHr)
                    val findings = diffHighRisk(previousHr, currentHr)
                    val saved = runCatching { persistence.saveHighRiskSnapshot(currentHr) }.isSuccess
                    if (saved && findings.isNotEmpty()) {
                        val mapped =
                            highRiskFindingsToGuardian(findings, now) { UUID.randomUUID().toString() }
                        addObservations(mapped.observations)
                        addAlerts(mapped.alerts)
                        mapped.alerts
                    } else {
                        emptyList()
                    }
                }.getOrDefault(emptyList())

                // Watch how much data each app used since the last check: per-app
                // volumes over WiFi and cellular, roaming, and background cellular —
                // never where the data went, which Android does not expose to a normal
                // app. Data use is a continuous metric, not an event diff, so the check
                // runs on its own longer cadence (dataUseWindow): a 6h minimum interval
                // throttles a chronically heavy app that the E notification cooldown
                // (30 min) cannot, and a gap over the clamp reseeds without alerting.
                // Persist-then-emit: the cursor is saved before emitting, so a failed
                // emit re-detects next window rather than re-alerting the same usage, and
                // a first run with no prior cursor just seeds it (no alert on pre-existing
                // usage, matching the App Access baseline rule).
                val dataUseAlerts = runCatching {
                    if (!NetworkUsageScanner.hasUsageAccess(appContext)) {
                        emptyList()
                    } else {
                        val last = runCatching { persistence.loadLastNetworkCheckMs() }.getOrNull()
                        when (val decision = dataUseWindow(last, now)) {
                            is DataUseDecision.Seed -> {
                                runCatching { persistence.saveLastNetworkCheckMs(decision.cursorMs) }
                                emptyList()
                            }
                            DataUseDecision.Skip -> emptyList()
                            is DataUseDecision.Analyze -> {
                                val report = networkScanner.scan(decision.startMs, decision.endMs)
                                val findings = analyzeDataUsage(report.apps)
                                val saved =
                                    runCatching { persistence.saveLastNetworkCheckMs(decision.endMs) }.isSuccess
                                if (saved && findings.isNotEmpty()) {
                                    val mapped =
                                        dataUsageFindingsToGuardian(findings, now) { UUID.randomUUID().toString() }
                                    addObservations(mapped.observations)
                                    addAlerts(mapped.alerts)
                                    mapped.alerts
                                } else {
                                    emptyList()
                                }
                            }
                        }
                    }
                }.getOrDefault(emptyList())

                // DNS flow (opt-in): while the monitor runs, note a user app that
                // contacted several known trackers. A quiet SUGGESTION, once per app
                // per run (reportedTrackerUids), skipped entirely when the monitor is off.
                val dnsFlowAlerts = runCatching {
                    if (!DnsFlowHub.running.value) {
                        emptyList()
                    } else {
                        val lookups = DnsFlowHub.report.value.lookups
                        val findings = analyzeDnsFlows(lookups, trackerList::isTracker, reportedTrackerUids)
                        if (findings.isNotEmpty()) {
                            val mapped =
                                dnsFlowFindingsToGuardian(findings, now) { UUID.randomUUID().toString() }
                            reportedTrackerUids += mapped.reportedUids
                            addObservations(mapped.observations)
                            addAlerts(mapped.alerts)
                            mapped.alerts
                        } else {
                            emptyList()
                        }
                    }
                }.getOrDefault(emptyList())

                _state.update { it.copy(lastSweepEpochMs = System.currentTimeMillis()) }

                // Surface alerts through notifications as the gate decides. App-access,
                // high-risk, data-use, and dns-flow alerts join the same merged pass so
                // nothing is notified twice. The gate's cooldown runs on the monotonic
                // clock, so wall-clock jumps cannot stretch or cancel the suppression window.
                (newAlerts + appAccessAlerts + highRiskAlerts + dataUseAlerts + dnsFlowAlerts)
                    .filter {
                        notificationGate.shouldNotify(
                            it,
                            settings,
                            android.os.SystemClock.elapsedRealtime(),
                            refinerState.cooldownMultiplier(alertSource(it)),
                        )
                    }
                    .forEach { GuardianService.postAlertNotification(appContext, it) }
            }
        }
    }

    override fun acknowledgeAlert(id: String) {
        var updated: List<GuardianAlert> = emptyList()
        _alerts.update { current ->
            current.map { if (it.id == id) it.copy(acknowledged = true) else it }
                .also { updated = it }
        }
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveAlerts(updated) } }
    }

    override fun setAlertness(alertness: Alertness) {
        _state.update { it.copy(alertness = alertness) }
        persistSettings(settings.copy(alertness = alertness))
    }

    override fun setSourceMuted(source: AlertSource, muted: Boolean) {
        val next = if (muted) settings.mutedSources + source else settings.mutedSources - source
        _state.update { it.copy(mutedSources = next) }
        persistSettings(settings.copy(mutedSources = next))
    }

    // ---- chat ----

    override fun chat(message: String): Flow<String> = flow {
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = message,
            epochMs = now,
        )
        val replyId = UUID.randomUUID().toString()
        var reply = ChatMessage(
            id = replyId,
            role = ChatRole.GUARDIAN,
            content = "",
            epochMs = now + 1,
            streaming = true,
        )
        val historyBefore = _chatLog.value
        // Cap the persisted chat log like observations and alerts so it does not
        // grow without bound. The reply is the last element, so takeLast always
        // keeps the in-flight message that updateMessage rewrites as it streams.
        _chatLog.update { current -> (current + userMsg + reply).takeLast(MAX_HISTORY) }

        val snapshot = store.signals.value
        val useLlm =
            ModeRouter.resolve(Surface.CHAT, capability.tier, generator.isLoaded) == Mode.AGENT

        if (useLlm) {
            val thinking = capability.tier == GuardianTier.FULL
            val turns = historyBefore.takeLast(MAX_HISTORY_TURNS).mapNotNull { m ->
                when (m.role) {
                    ChatRole.USER -> MiniCpmChatFormat.Turn(MiniCpmChatFormat.ROLE_USER, m.content)
                    ChatRole.GUARDIAN -> MiniCpmChatFormat.Turn(MiniCpmChatFormat.ROLE_ASSISTANT, m.content)
                    ChatRole.SYSTEM -> null
                }
            }
            val prompt = MiniCpmChatFormat.buildPrompt(systemPrompt(), turns, message, thinking)
            val params = if (thinking) GenParams.think() else GenParams.noThink()

            // The composer owns the budget, the reasoning/visible split, the
            // numbers-only grounding, and the deterministic fallback. Chat ranges
            // over the whole snapshot, so only cited figures are checked (a
            // proper-noun check over hundreds of readings would be low-value and
            // false-positive-prone). The fallback — the same deterministic rules
            // answer empty output already drops to — is built up front; it is a
            // pure query, so computing it eagerly is free of side effects.
            val spec = AgentSpec(
                prompt = prompt,
                facts = chatGroundingFacts(snapshot, message),
                fallback = rules.answer(message, snapshot, _baseline.value),
                budgetMs = CHAT_GEN_BUDGET_MS,
                params = params,
            )

            // Show-then-correct: Deltas stream the provisional draft to the UI and
            // the observed chatLog; the single Final carries the authoritative
            // reply (grounded prose or fallback) that becomes the committed record.
            // Each Delta's visibleSoFar is the whole parsed reply so far, assigned
            // straight onto the message (exactly the old reply.content = parsed
            // .content); lastEmitted tracks what the token stream has already
            // yielded so it emits only the newly-grown suffix.
            var lastEmitted = ""
            composer.agent(spec).collect { event ->
                when (event) {
                    is AgentEvent.Delta -> {
                        reply = reply.copy(content = event.visibleSoFar, thinking = event.thinkingSoFar)
                        updateMessage(reply)
                        if (event.visibleSoFar.length > lastEmitted.length) {
                            emit(event.visibleSoFar.substring(lastEmitted.length))
                            lastEmitted = event.visibleSoFar
                        }
                    }
                    is AgentEvent.Final -> {
                        reply = reply.commit(event.reply)
                        // If nothing streamed (empty or think-only output), surface
                        // the final content so the caller's flow still yields a reply.
                        if (lastEmitted.isEmpty() && reply.content.isNotEmpty()) emit(reply.content)
                    }
                }
            }
        } else {
            val text = rules.answer(message, snapshot, _baseline.value)
            val words = text.split(" ")
            val acc = StringBuilder()
            for ((index, word) in words.withIndex()) {
                if (index > 0) acc.append(' ')
                acc.append(word)
                reply = reply.copy(content = acc.toString())
                updateMessage(reply)
                emit(if (index == 0) word else " $word")
            }
            reply = reply.copy(streaming = false)
        }

        updateMessage(reply)
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveChatLog(_chatLog.value) } }
    }.flowOn(Dispatchers.Default)

    /**
     * Author one calm companion remark for [alert] on the on-device model, or
     * null to fall back to the alert title. The router decides whether the model
     * speaks at all — an immutable-rule alert is pinned to SCRIPT so the model
     * never re-words a lane-5 finding — and the composer owns the budget, the
     * post-process, the grounding gate, and the deterministic degrade. No
     * chat-log persistence — this is a side utterance, not a conversation turn.
     */
    override suspend fun composeRemark(alert: GuardianAlert): String? {
        val mode = ModeRouter.resolve(
            Surface.COMPANION_REMARK,
            capability.tier,
            generator.isLoaded,
            immutableAlert = alert.fromImmutableRule,
        )
        if (mode != Mode.HYBRID) return null
        return composer.compose(
            HybridSpec(
                prompt = CompanionRemark.buildRemarkPrompt(alert),
                facts = GroundingCheck.factsOf(alert.title, alert.body),
                fallback = alert.title,
                postProcess = CompanionRemark::postProcessRemark,
                budgetMs = REMARK_GEN_BUDGET_MS,
                params = GenParams.noThink(maxTokens = CompanionRemark.REMARK_MAX_TOKENS),
            ),
        ).modelTextOrNull()
    }

    /**
     * Author a grounded plain-language read of the fingerprint summary from a
     * pre-built [prompt] on the on-device model, or null to fall back to the
     * deterministic narrative. The composer owns the budget, the post-process,
     * the grounding gate (the read's numbers and named surfaces must trace back
     * to the same [facts] string the prompt was built from), and the
     * deterministic degrade. No chat-log persistence — this is a side read, not
     * a conversation turn.
     */
    override suspend fun narrate(prompt: String, facts: String): String? {
        val mode = ModeRouter.resolve(Surface.SUMMARY_NARRATION, capability.tier, generator.isLoaded)
        if (mode != Mode.HYBRID) return null
        return composer.compose(
            HybridSpec(
                prompt = prompt,
                // This surface's deterministic text (the report detail) lives with
                // the caller, which substitutes it on null — so the spec's fallback
                // is never displayed and stays empty. modelTextOrNull maps the
                // composer's DETERMINISTIC result back to the contract's null.
                facts = GroundingCheck.factsOf(facts),
                fallback = "",
                postProcess = SummaryNarration::postProcessNarration,
                budgetMs = NARRATION_GEN_BUDGET_MS,
                params = GenParams.noThink(maxTokens = SummaryNarration.NARRATION_MAX_TOKENS),
            ),
        ).modelTextOrNull()
    }

    /**
     * Map a composed surface text back to this interface's `String?` contract:
     * model-authored prose passes through, a deterministic result becomes null
     * so each caller applies its own fallback (the alert title, the report
     * detail) exactly as before the composer existed.
     */
    private fun SurfaceText.modelTextOrNull(): String? =
        if (author == Author.MODEL_GROUNDED) text else null

    // ---- internals ----

    /**
     * Suspend until the store's one-shot collection has settled, i.e. no
     * category is still Loading, or until a bounded timeout elapses. collect()
     * flips each permitted category to Loading synchronously before launching
     * its work, so by the time collectAll() returns the pending categories are
     * already marked Loading and this poll waits for them to finish. The
     * timeout keeps a slow or stuck provider from stalling the sweep forever.
     */
    private suspend fun awaitCollectionSettled() {
        withTimeoutOrNull(COLLECT_SETTLE_TIMEOUT_MS) {
            while (store.loadStates.value.values.any { it is LoadState.Loading }) {
                delay(COLLECT_SETTLE_POLL_MS)
            }
        }
    }

    /**
     * Fold one sensor-use session into the timeline. Takes [sweepMutex]
     * because addObservations/addAlerts are read-modify-write on StateFlow
     * and sweeps already serialise through it.
     */
    private fun onSensorSession(tracked: TrackedSession) {
        scope.launch(Dispatchers.Default) {
            sweepMutex.withLock {
                runCatching {
                    val now = System.currentTimeMillis()
                    val result = sensorSessionToGuardian(tracked, now) { UUID.randomUUID().toString() }
                    addObservations(listOf(result.observation))
                    result.alert?.let { alert ->
                        addAlerts(listOf(alert))
                        // The gate's cooldown is the frequency policy once deferred from
                        // here; it runs on the monotonic clock, not the wall clock.
                        val allowed = notificationGate.shouldNotify(
                            alert,
                            settings,
                            android.os.SystemClock.elapsedRealtime(),
                            refinerState.cooldownMultiplier(alertSource(alert)),
                        )
                        if (allowed) {
                            GuardianService.postAlertNotification(appContext, alert)
                        }
                    }
                }
            }
        }
    }

    private fun loadModel() {
        scope.launch(Dispatchers.IO) {
            val file = modelManager.modelFile(capability.tier)
            if (!file.exists()) return@launch
            _state.update {
                it.copy(model = it.model.copy(status = ModelStatus.LOADING))
            }
            val ok = runCatching {
                val nCtx = if (capability.tier == GuardianTier.MINIMAL) 1024 else 2048
                llmEngine.load(file.absolutePath, nCtx = nCtx)
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    model = it.model.copy(
                        status = if (ok) ModelStatus.LOADED else ModelStatus.FAILED,
                        message = if (ok) null else "The model could not be loaded on this device.",
                    ),
                )
            }
        }
    }

    private fun ruleFindingsToAlerts(
        findings: List<RulesEngine.RuleFinding>,
    ): List<GuardianAlert> {
        val now = System.currentTimeMillis()
        val existingIds = _alerts.value.map { it.id }.toHashSet()
        return findings
            .map { finding ->
                GuardianAlert(
                    id = "rule.${finding.key}",
                    level = finding.level,
                    title = finding.title,
                    body = finding.body,
                    categoryId = finding.categoryId,
                    createdAtEpochMs = now,
                )
            }
            .filter { it.id !in existingIds }
    }

    /**
     * A rules-engine finding's severity is code, not history: when a release
     * re-grades a finding, the persisted alert follows it. The stored alert
     * shares its id with the finding and is skipped by the new-alert filter,
     * so this is how WARNING -> SUGGESTION demotions reach existing installs.
     */
    private fun realignRuleAlertLevels(findings: List<RulesEngine.RuleFinding>) {
        val levelById = findings.associate { "rule.${it.key}" to it.level }
        var realigned: List<GuardianAlert>? = null
        _alerts.update { current ->
            var changed = false
            val next = current.map { alert ->
                val level = levelById[alert.id]
                if (level != null && level != alert.level) {
                    changed = true
                    alert.copy(level = level)
                } else {
                    alert
                }
            }
            realigned = if (changed) next else null
            if (changed) next else current
        }
        val merged = realigned ?: return
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveAlerts(merged) } }
    }

    private fun snapshotOf(
        signals: Map<SignalCategory, List<FingerprintSignal>>,
    ): GuardianSnapshot {
        val categories = signals.entries.associate { (category, list) ->
            category.id to list.map { SignalSnap(it.id, it.name, it.value) }
        }
        return GuardianSnapshot(epochMs = System.currentTimeMillis(), categories = categories)
    }

    private fun addObservations(list: List<Observation>) {
        if (list.isEmpty()) return
        var merged: List<Observation> = emptyList()
        _observations.update { current ->
            ((list + current).take(MAX_HISTORY)).also { merged = it }
        }
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveObservations(merged) } }
    }

    private fun addAlerts(list: List<GuardianAlert>) {
        if (list.isEmpty()) return
        var merged: List<GuardianAlert>? = null
        _alerts.update { current ->
            val existingIds = current.map { it.id }.toHashSet()
            val fresh = list.filter { it.id !in existingIds }
            if (fresh.isEmpty()) {
                merged = null
                current
            } else {
                ((fresh + current).take(MAX_HISTORY)).also { merged = it }
            }
        }
        val persisted = merged ?: return
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveAlerts(persisted) } }
    }

    private fun updateMessage(message: ChatMessage) {
        _chatLog.update { log -> log.map { if (it.id == message.id) message else it } }
    }

    /**
     * Fold a composer [FinalReply] into the committed reply message: this is the
     * only way authoritative chat text is produced, so the persisted log can
     * never hold model prose the grounding gate did not pass — an [AgentEvent]
     * `Delta` (the advisory live draft) has no path here. Both arms end the
     * stream (streaming = false).
     */
    private fun ChatMessage.commit(final: FinalReply): ChatMessage = when (final) {
        is FinalReply.Grounded -> copy(content = final.prose.text, thinking = final.thinking, streaming = false)
        is FinalReply.Fallback -> copy(content = final.text, thinking = final.thinking, streaming = false)
    }

    private fun recomputeCounts() {
        _state.update { current ->
            current.copy(
                observationCount = _observations.value.size,
                openAlertCount = _alerts.value.count { !it.acknowledged },
            )
        }
    }

    private fun persistSettings(next: GuardianSettings) {
        settings = next
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveSettings(next) } }
    }

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GuardianService::class.java),
            )
        }
    }

    private fun stopService() {
        runCatching { appContext.stopService(Intent(appContext, GuardianService::class.java)) }
    }

    private fun scheduleSweeps() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<GuardianSweepWorker>(
                SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                SWEEP_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /**
     * The ground truth a chat reply is checked against: every signal name and
     * value the phone actually read, the learned-rhythm digest, and the user's
     * own message (so echoing a figure they raised is grounded). Kept broad on
     * purpose — a wide, real fact set is what keeps the numbers check from
     * wrongly rejecting a genuinely grounded answer.
     */
    private fun chatGroundingFacts(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        message: String,
    ): GroundingCheck.GroundingFacts {
        val sources = buildList {
            add(message)
            _baseline.value?.let { add(rhythmDigest(it)) }
            snapshot.values.forEach { signals ->
                signals.forEach { signal ->
                    add(signal.name)
                    add(signal.value)
                    signal.entries?.forEach { add(it.label); add(it.value) }
                }
            }
        }
        return GroundingCheck.factsOf(sources)
    }

    /** The guardian's persona. Same calm, on-device voice as the UI copy. */
    private fun systemPrompt(): String {
        val persona =
            "You are Mink, a calm privacy guardian that runs entirely on this Android phone. " +
                "You never send data off the device and you never can. You read what the phone " +
                "exposes about its owner and you explain it in plain English, second person, " +
                "present tense. You warn when something is exposed, you suggest what the owner " +
                "can do, and you stay factual and quiet. Do not use hype or exclamation marks. " +
                "Keep answers short and concrete. Trackers do not need a name, email, or location " +
                "to recognise someone; explain how ordinary readings add up to a fingerprint."
        val digest = _baseline.value?.let { rhythmDigest(it) }.orEmpty()
        return if (digest.isEmpty()) persona else "$persona\n\n$digest"
    }

    companion object {
        const val SWEEP_WORK = "mink-guardian-sweep"
        const val SWEEP_INTERVAL_MINUTES = 60L
        private const val MAX_HISTORY = 100
        private const val MAX_HISTORY_TURNS = 8
        private const val COLLECT_SETTLE_TIMEOUT_MS = 5000L
        private const val COLLECT_SETTLE_POLL_MS = 40L
        private const val RESTORE_WAIT_MS = 2000L

        // Wall-clock budgets for one model generation, after which the surface
        // falls back to its deterministic text. On-device token throughput varies
        // wildly by chip, so an unbounded generation is an unbounded spinner on a
        // slow device; these bound it while leaving generous headroom for a normal
        // mid-range phone (which finishes well under them). The fallback is the same
        // deterministic text a fabrication or an empty reply drops to, so a budget
        // that trips only degrades quietly. Tunable copy/latency budgets, not lane-5
        // immutables. The companion remark is a background utterance (shortest
        // budget); the read and chat are user-initiated (longer). Two honest bounds:
        // (1) the budget covers the wait for the engine's generation mutex plus the
        // generation itself, so a surface contending for a busy engine can time out
        // to its fallback having produced nothing — benign, since the fallback is the
        // deterministic text either way. (2) Enforcement is cooperative — the timeout
        // can only cancel between the engine's blocking native calls (each token, and
        // the one-shot prompt prefill), so a single pathologically slow call can
        // overrun it before the next check. On real hardware prefill is sub-second
        // and tokens are quick, so in practice the budget bounds the dominant slow
        // path (a long token stream).
        private const val REMARK_GEN_BUDGET_MS = 20_000L
        private const val NARRATION_GEN_BUDGET_MS = 40_000L
        private const val CHAT_GEN_BUDGET_MS = 60_000L
    }
}
