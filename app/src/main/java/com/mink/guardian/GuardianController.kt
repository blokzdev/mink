package com.mink.guardian

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.data.LoadState
import com.mink.data.SignalStore
import com.mink.guardian.llm.GenParams
import com.mink.guardian.llm.LlamaBridge
import com.mink.guardian.llm.LlmEngine
import com.mink.guardian.llm.MiniCpmChatFormat
import com.mink.monitor.AppAccessScanner
import com.mink.monitor.SensorInUseMonitor
import com.mink.monitor.TrackedSession
import com.mink.monitor.diffAppAccess
import com.mink.monitor.toSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
) : Guardian {

    private val appContext = context.applicationContext
    private val persistence = GuardianStore(appContext)
    private val modelManager = ModelManager(appContext)
    private val llmEngine = LlmEngine()
    private val rules = RulesEngine()
    private val analyzer = GuardianAnalyzer(rules)

    /**
     * The guardian's own app-access scanner. Deliberately its own instance and
     * not shared with MinkServices/AppAccessMonitor: the guardian is constructed
     * before the UI graph and must stay self-contained.
     */
    private val appAccessScanner = AppAccessScanner(appContext)

    /**
     * Near real-time camera/microphone use watch. Sessions arrive on the
     * monitor's own handler thread and are folded into the timeline through
     * [onSensorSession]; it runs only between [enable] and [disable].
     */
    private val sensorMonitor = SensorInUseMonitor(appContext) { tracked -> onSensorSession(tracked) }

    private val capability = DeviceCapability.detect(appContext, LlamaBridge.isAvailable)

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

    private var settings = GuardianSettings()

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
                _state.value = _state.value.copy(model = model)
            }
        }

        // Restore persisted history and settings.
        scope.launch(Dispatchers.IO) {
            // Re-encrypt anything left as plaintext by a pre-encryption build.
            runCatching { persistence.migrateLegacyPayloads() }

            val obs = runCatching { persistence.loadObservations() }.getOrDefault(emptyList())
            val alertList = runCatching { persistence.loadAlerts() }.getOrDefault(emptyList())
            val chat = runCatching { persistence.loadChatLog() }.getOrDefault(emptyList())
            val storedBaseline = runCatching { persistence.loadBaseline() }.getOrNull()
            settings = runCatching { persistence.loadSettings() }.getOrDefault(GuardianSettings())

            _observations.value = obs
            _alerts.value = alertList
            _chatLog.value = chat
            if (storedBaseline != null) {
                _baseline.value = storedBaseline.summary(System.currentTimeMillis())
            }
            recomputeCounts()

            if (settings.enabled) enable()
        }
    }

    // ---- lifecycle ----

    override fun enable() {
        _state.value = _state.value.copy(enabled = true, tier = capability.tier)
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
        _state.value = _state.value.copy(enabled = false)
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
                val ruleAlerts = ruleFindingsToAlerts(store.signals.value)

                addObservations(result.observations)
                val newAlerts = (result.alerts + ruleAlerts)
                addAlerts(newAlerts)

                runCatching { persistence.saveSnapshot(snapshot) }

                // Fold this sweep into the learned baseline and publish its digest.
                val updatedBaseline = (baseline ?: GuardianBaseline.empty(now))
                    .updated(snapshot, now, trust = assessment.trust, sweepTime = sweepTime)
                runCatching { persistence.saveBaseline(updatedBaseline) }
                _baseline.value = updatedBaseline.summary(now)

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

                _state.value = _state.value.copy(lastSweepEpochMs = System.currentTimeMillis())

                // Surface the loud ones through a notification. App-access alerts
                // join the same merged pass so nothing is notified twice.
                (newAlerts + appAccessAlerts)
                    .filter { it.level == AlertLevel.WARNING || it.level == AlertLevel.CRITICAL }
                    .forEach { GuardianService.postAlertNotification(appContext, it) }
            }
        }
    }

    override fun acknowledgeAlert(id: String) {
        val updated = _alerts.value.map { if (it.id == id) it.copy(acknowledged = true) else it }
        _alerts.value = updated
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveAlerts(updated) } }
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
        _chatLog.value = (historyBefore + userMsg + reply).takeLast(MAX_HISTORY)

        val snapshot = store.signals.value
        val useLlm = capability.tier != GuardianTier.RULES_ONLY && llmEngine.isLoaded

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

            val raw = StringBuilder()
            var lastVisible = ""
            llmEngine.generate(prompt, params).collect { piece ->
                raw.append(piece)
                val parsed = MiniCpmChatFormat.parseReply(raw.toString())
                reply = reply.copy(content = parsed.content, thinking = parsed.thinking)
                updateMessage(reply)
                if (parsed.content.length > lastVisible.length) {
                    val delta = parsed.content.substring(lastVisible.length)
                    lastVisible = parsed.content
                    emit(delta)
                }
            }
            val finalParsed = MiniCpmChatFormat.parseReply(raw.toString())
            val finalContent = finalParsed.content.ifBlank { rules.answer(message, snapshot, _baseline.value) }
            reply = reply.copy(
                content = finalContent,
                thinking = finalParsed.thinking,
                streaming = false,
            )
            // If nothing streamed through (empty or think-only output), surface
            // the final content so the caller's flow still yields a reply.
            if (lastVisible.isEmpty() && finalContent.isNotEmpty()) {
                emit(finalContent)
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
                        if (alert.level == AlertLevel.WARNING || alert.level == AlertLevel.CRITICAL) {
                            // Deliberately uncapped and un-cooled-down for now:
                            // notification frequency policy belongs to the
                            // alert-hygiene iteration (task E), not here.
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
            _state.value = _state.value.copy(
                model = _state.value.model.copy(status = ModelStatus.LOADING),
            )
            val ok = runCatching {
                val nCtx = if (capability.tier == GuardianTier.MINIMAL) 1024 else 2048
                llmEngine.load(file.absolutePath, nCtx = nCtx)
            }.getOrDefault(false)
            _state.value = _state.value.copy(
                model = _state.value.model.copy(
                    status = if (ok) ModelStatus.LOADED else ModelStatus.FAILED,
                    message = if (ok) null else "The model could not be loaded on this device.",
                ),
            )
        }
    }

    private fun ruleFindingsToAlerts(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
    ): List<GuardianAlert> {
        val now = System.currentTimeMillis()
        val existingIds = _alerts.value.map { it.id }.toHashSet()
        return rules.evaluate(snapshot)
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
        val merged = (list + _observations.value).take(MAX_HISTORY)
        _observations.value = merged
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveObservations(merged) } }
    }

    private fun addAlerts(list: List<GuardianAlert>) {
        if (list.isEmpty()) return
        val existingIds = _alerts.value.map { it.id }.toHashSet()
        val fresh = list.filter { it.id !in existingIds }
        if (fresh.isEmpty()) return
        val merged = (fresh + _alerts.value).take(MAX_HISTORY)
        _alerts.value = merged
        recomputeCounts()
        scope.launch(Dispatchers.IO) { runCatching { persistence.saveAlerts(merged) } }
    }

    private fun updateMessage(message: ChatMessage) {
        _chatLog.value = _chatLog.value.map { if (it.id == message.id) message else it }
    }

    private fun recomputeCounts() {
        _state.value = _state.value.copy(
            observationCount = _observations.value.size,
            openAlertCount = _alerts.value.count { !it.acknowledged },
        )
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
    }
}
