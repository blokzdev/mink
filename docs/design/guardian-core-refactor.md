# Guardian-Core Refactor + Four-Mode Routing — Final Design

*Accepted 2026-07-18 after a 3-proposal × 3-judge adversarial design panel + synthesis; the four
load-bearing code claims below were re-verified by hand against main `d3723e3` before acceptance.*

## 0. Verdict

Winner: **"Guardian-core as a pure JVM module with a typed post-commit event bus and surface-keyed
four-mode routing"** (structure-first proposal), with grafts from both other proposals. Deciding
facts, verified in the tree:

- The NotificationGate runs **once, merged, at the end of the sweep** (GuardianController, after
  every per-guard `addAlerts` at :308/:363/:388/:426/:450) — so a `notified: Boolean` on a single
  `AlertRaised` event at persist time is unknowable; `AlertRaised` and `AlertNotified` must be
  separate events. Both losing proposals got this wrong.
- `realignRuleAlertLevels` (:761) mutates `_alerts` **without** re-entering `addAlerts`, and
  `CompanionController` keys `announcedAlertIds` by `"id|level"` precisely so upgrades re-announce —
  a naive bus migration silently loses upgrade re-announcements. Hence the `AlertLevelRealigned`
  event.
- Exactly 8 files in `com.mink.guardian`(+`llm`) are Android-tainted (Controller, Service, Store,
  ModelManager, DeviceCapability, PayloadCipher, LlamaBridge, LlmEngine); everything else is
  Android-free and moves.
- `GuardianController(context, store, scope)` with a private `LlmEngine()` — constructor-inject with
  default lands without touching `ServiceWiring`.

Grafts applied: unforgeable `Draft`/`GroundedProse` types + policy-by-spec-type (routing-first);
downgrade-only mode lattice + truth-table test (incremental-first); advisory-bus zero-collector
invariance test + drop counter (incremental-first); `SurfaceComposed(author)` audit receipt;
composer-as-sole-acceptor of the generator; atomic `commit()` (save → state → publish);
`AlertLevelRealigned` wired to the companion; per-token thinking deltas preserved in `AgentEvent`;
PR 1 re-ordered to the smallest provably-green change.

## 1. Module layout — `:guardian-core` Gradle module, packages unchanged

`settings.gradle.kts` gains `include(":guardian-core")`: a `kotlin("jvm")` module depending only on
`kotlin-stdlib` + `kotlinx-coroutines-core`, with `java-test-fixtures` applied. `:app` adds
`implementation(project(":guardian-core"))` + `testImplementation(testFixtures(...))`. **Kotlin
package names do not change across the move**, so every `:app` import and all ~490 tests survive
byte-identical; diffs are file moves.

Stays in `:app` (Android-tainted): `GuardianController` (thin adapter), `GuardianService` +
`GuardianSweepWorker`, `GuardianStore` + `PayloadCipher`, `ModelManager`, `DeviceCapability`,
`LlamaBridge` + concrete `LlmEngine`, all scanners/monitors (`AppAccessScanner`, `HighRiskScanner`,
`NetworkUsageScanner`, `SensorInUseMonitor`, `DnsFlowMonitor`, `FlowMonitorService`, `TrackerList`,
`DnsFlowStore`, `BootReceiver`), `DnsFlowHub` (its `.report.value` read moves into
controller-supplied `SweepInputs`), `ServiceWiring`/`MinkServices`, companion, Compose UI.

`:guardian-core` contents:

```
com.mink.core.model          FingerprintSignal, SignalCategory, PermissionKind, Sensitivity
com.mink.guardian            GuardianContract, AlertPolicy (NotificationGate), GroundingCheck,
                             Baseline, RulesEngine, GuardianAnalyzer, ThresholdRefiner,
                             AppAccess/SensorUse/HighRisk/NetworkUsage/DnsFlow Guards,
                             GuardianSettings/RefinerState carriers
com.mink.monitor             pure halves only: AppAccess, AppAccessWatch, HighRiskAccess,
                             NetworkUsage (analyzeDataUsage/dataUseWindow), SensorUse/DnsFlow models
com.mink.narrative           SummaryNarration, FingerprintNarrative, StoryNarrative
com.mink.companion           CompanionRemark (prompt builder / post-processor)
com.mink.guardian.llm        TextGenerator (interface), GenParams, MiniCpmChatFormat
com.mink.guardian.compose    Draft, GroundedProse, Author, SurfaceText, HybridSpec, AgentSpec,
                             AgentEvent, FinalReply, GenerationRunner (internal), GroundedComposer
com.mink.guardian.route      Surface, Mode, ModePreference, ModeRouter
com.mink.guardian.bus        GuardianEvent (sealed), GuardianBus, GuardianHook
com.mink.guardian.sweep      SweepPipeline, SweepInputs, SweepPersistence, SweepStateSink,
                             GuardianPorts, SweepClock
testFixtures                 FakeTextGenerator, InMemorySweepPersistence, RecordingStateSink,
                             RecordingBus
```

Backstop enforcement: plain JVM file-scan arch tests (no new lint deps): (a) no `import android.`
under `:guardian-core`; (b) `.generate(` only in `com.mink.guardian.compose` + the `:app` engine;
(c) `TextGenerator` imported only by `GroundedComposer` + the `:app` engine impl.

## 2. Event bus — typed, post-commit, advisory

Sealed vocabulary (exactly ten; adding one is a reviewed core API change):
`SweepStarted(trigger)`, `SweepCompleted(newObservations, newAlerts, durationMs)`,
`SignalChanged(categoryId, observation)`, `AlertRaised(alert, source)` (fresh only, post-commit),
`AlertNotified(alertId)` (after the merged gate pass), `AlertLevelRealigned(alertId, from, to)`,
`AlertAcknowledged(alertId)`, `BaselineUpdated(summary)`, `ModelStateChanged(status)`,
`SurfaceComposed(surface, mode, author)`. All carry bus-assigned monotonic `seq` + `atEpochMs`.

Contract:
- **Post-commit receipts, never commands.** Publishing happens only from core code, only after the
  corresponding durable write / state update (`GuardianPorts.commit()` enforces the ordering).
- **Advisory.** The `Guardian` StateFlows remain canonical; NotificationGate, persistence, and
  StateFlow updates stay synchronous direct calls. Permanent test: a bus with zero collectors
  produces byte-identical persisted state, StateFlow contents, and notification decisions.
- **Dispatch.** Non-suspending `tryEmit` into `MutableSharedFlow(replay=0, extraBufferCapacity=256,
  DROP_OLDEST)` + an exposed dropped-event counter. Sweep-path events are strictly ordered (single
  coroutine under `sweepMutex`); cross-path events get monotonic `seq` but delivery interleaving near
  buffer boundaries may present out-of-order — `seq` is gap-detection, not strict delivery. A
  consumer that detects a gap resyncs from the StateFlows.
- **Hooks.** `fun interface GuardianHook { suspend fun onEvent(e: GuardianEvent) }`; each attached
  hook gets its own `Channel(64, DROP_OLDEST)` fed by one fan-out collector. A wedged hook drops its
  own tail, never backpressures the sweep or a sibling; exceptions are caught and counted. Hooks
  hold no actuator handles — a hook is a read-out, not a way for events to invoke the model.

## 3. Four-mode contract + router

```kotlin
enum class Mode { NOTIFICATION, SCRIPT, HYBRID, AGENT }
enum class Surface { SYSTEM_NOTIFICATION, TIMELINE, COMPANION_REMARK, SUMMARY_NARRATION, CHAT }
enum class ModePreference { AUTO, DETERMINISTIC }
```

Intrinsic table: `SYSTEM_NOTIFICATION → NOTIFICATION`, `TIMELINE → SCRIPT`,
`COMPANION_REMARK → HYBRID`, `SUMMARY_NARRATION → HYBRID`, `CHAT → AGENT`.

`ModeRouter.resolve` — a total, downgrade-only lattice (first match wins):
1. Intrinsic `NOTIFICATION`/`SCRIPT` → intrinsic. Zero model, unconditionally.
2. **Immutable pin:** immutable-rule alert + `COMPANION_REMARK` → `SCRIPT`. Third leg of the
   evaluation-drift guard (with the gate short-circuit and refiner exclusion): immutable-rule alerts
   are never re-worded by the model anywhere. **The plan's single intentional behavior change**;
   lands flagged in PR 3.
3. Capability ceiling: `tier == RULES_ONLY || !modelLoaded` → `SCRIPT` (centralizes today's three
   scattered checks).
4. User preference: `DETERMINISTIC` → `SCRIPT`. No raising variant exists — config lowering-only is
   unrepresentable to violate.
5. Otherwise → intrinsic.

Tested two ways: a full truth table asserting parity with the old literal conditionals, and a
property test that `resolve` never returns a more model-ful mode than `intrinsic(surface)`. Runtime
degradation *within* HYBRID/AGENT (budget, blank, ungrounded) is the composer's job and lands on the
same deterministic fallback a SCRIPT resolution would produce. Every resolution emits
`SurfaceComposed(surface, mode, author)`.

## 4. Grounding enforcement — unforgeable types, policy by spec type

```kotlin
class Draft internal constructor(internal val raw: String)      // only GenerationRunner creates one
class GroundedProse private constructor(val text: String)       // only the composer's gate constructs
enum class Author { MODEL_GROUNDED, DETERMINISTIC }
data class SurfaceText(val text: String, val author: Author)

class HybridSpec(prompt, facts /* non-null */, fallback /* non-null */, postProcess, budgetMs, params)
class AgentSpec(prompt, facts, fallback, budgetMs, params)      // numbers-only BY TYPE

sealed interface AgentEvent {
    data class Delta(val visibleDelta: String, val thinkingSoFar: String?) : AgentEvent
    data class Final(val reply: FinalReply) : AgentEvent        // MANDATORY terminal event
}
sealed interface FinalReply {
    data class Grounded(val prose: GroundedProse, val thinking: String?) : FinalReply
    data class Fallback(val text: String, val thinking: String?) : FinalReply
}

class GroundedComposer(gen: TextGenerator, clock: () -> Long) { // ONLY public acceptor of a generator
    suspend fun compose(spec: HybridSpec): SurfaceText
    fun agent(spec: AgentSpec): Flow<AgentEvent>
}
```

Why it cannot be skipped: the raw generator is unreachable from surface code; `Draft.raw` is
`internal` (compiler-enforced across the module boundary from the day it exists); grounding policy
is the spec's *type* (no caller-remembered flag); specs won't construct without facts + fallback;
every failure path returns `Author.DETERMINISTIC`; chat's only log-commit function accepts
`FinalReply`, so persisted chat can never contain unchecked model text (`Delta` is a distinct type
no persistence accepts — the live-bubble hole is deliberate and matches today's documented
semantics). Core invariant test: for any fake output with an unsupported number/entity, the composer
never returns `MODEL_GROUNDED`. The decision-making types (`ModeRouter`, `SweepPipeline`,
`RulesEngine`, `AlertPolicy`, `ThresholdRefiner`, `GuardianBus`) have no generator/composer
parameter anywhere — the capability floor at type level.

`Guardian` interface preserved: `composeRemark`/`narrate` keep returning `String?`; budgets move
verbatim (REMARK 20s / NARRATION 40s / CHAT 60s, covering mutex wait + generation).

## 5. TextGenerator + GuardianController's fate

```kotlin
interface TextGenerator {
    val isLoaded: Boolean
    fun generate(prompt: String, params: GenParams): Flow<String>
}
```

`load`/`unload`/`isBridgeAvailable` stay OFF the interface — lifecycle is Android-side, owned by the
controller against the concrete engine. The concrete `LlmEngine : TextGenerator` keeps its
single-thread executor, `genMutex`, and reset-in-finally semantics verbatim.

`FakeTextGenerator` (testFixtures): `Tokens(list, perTokenDelayMs)` / `Hang` / `Empty` / `Fail` + a
prompt log. Closes the named timeout-coverage gap with virtual-time JVM tests: budget-elapse
mid-stream (all three budgets), blank/think-only → fallback, ungrounded → fallback,
show-then-correct ordering, thinking deltas, mutex-contention, cancellation propagation.

`GuardianController` → ~300-line Android adapter. Keeps: `sweepMutex`, StateFlows,
enable/disable/prepareModel/loadModel, FGS + WorkManager scheduling, SensorInUseMonitor,
init-restore, GuardianStore ownership, ChatMessage bookkeeping + the RULES_ONLY word-streaming
branch, settings persistence. Loses: chat/remark/narrate bodies (→ `GroundedComposer`) and the
~200-line sweep body (→ `SweepPipeline`).

Ports (enumerated): `SweepPersistence` (GuardianStore shape-for-shape: snapshot, baseline,
app-access, high-risk, lastNetworkCheckMs, refinerState), `SweepStateSink` (controller-implemented:
`addAlerts` returning the fresh subset, `addObservations`, `applyRealignments` returning applied
changes, `updateBaseline`, `updateState`), `notify` (GuardianService::postAlertNotification), `bus`,
`clock` (wall + elapsed). `GuardianPorts.commit(save, apply)` makes save → state → publish one
atomic construct — the persist-then-emit rail. The pipeline preserves verbatim: per-guard
persist-then-emit with each guard's distinct empty-scan semantics, `ruleFindingsToAlerts` +
`realignRuleAlertLevels` interplay (characterization tests pin outcomes), refiner cadence with the
`shouldRefine` re-fire guard, the merged gate pass on `elapsedRealtime`, and gate precedence
untouched.

## 6. PR-by-PR plan (each keeps build + ~490 tests + lint green)

1. **TextGenerator extraction** (~150 lines): interface + GenParams split (still in `:app`);
   `LlmEngine : TextGenerator`; constructor-inject with default; FakeTextGenerator + contract test.
   Zero behavior change.
2. **`:guardian-core` module**: commit (a) true file moves only, packages unchanged, tests move,
   testFixtures wired; commit (b) file surgery (pure monitor halves carved out; llm types into
   place). The JVM compiler is the reviewer.
3. **Grounding core + hybrid mode + router v1**: compose + route packages; `composeRemark`/`narrate`
   become 5-line delegations building HybridSpecs (prompts/budgets/facts/post-processors/fallbacks
   copied character-for-character); tier checks → `ModeRouter.resolve`. Contains the immutable-pin
   behavior change, called out and tested. `Draft.raw` internal is compiler-enforced from this PR.
4. **Agent mode**: chat's LLM branch → `agent()`; characterization tests for streaming written
   against the old code first; chat-log commit accepts only `FinalReply`.
5. **GuardianBus, observe-only**: ten events published from existing commit points; zero consumers ⇒
   behaviorally inert. Tests: ordering, persist-then-emit, zero-collector invariance, drop counter,
   wedged hook isolation, and the permanent invariant: an `AlertRaised(immutable)` is always
   followed by `AlertNotified` in the same sweep, under any settings/multiplier.
6. **Companion onto the bus**: `filterIsInstance<AlertRaised>()` + upward `AlertLevelRealigned`
   (preserving id|level upgrade re-announcement); atomic swap; one-release `"id|level"` seen-set +
   seq-gap resync from `guardian.alerts.value`; `ServiceWiring` companion factory gains the bus.
7. **SweepPipeline extraction (riskiest, last)**: commit 1 = characterization tests against the OLD
   code through in-memory ports; commit 2 = verbatim body move into `SweepPipeline.run(inputs,
   ports)` with `commit()`. Ship `docs/guardian-core.md` here.
8. *(Optional, post-release)*: `GuardianSettings.surfaceModes` + a per-surface "Mink's voice:
   model / rules-only" toggle. The lattice guarantees config can only remove the model.

## 7. Top risks

1. **Sweep-extraction drift (PR 7)** — characterization-first, verbatim move, `commit()` construct,
   permanent named invariants, instrumented suite gates the merge, deliberately last.
2. **Companion migration regression (PR 6)** — `AlertLevelRealigned` for upgrades; atomic swap; the
   seen-set + seq-gap resync + drop counter; trivially revertible to StateFlow diffing since the bus
   is advisory.
3. **Grounding bypass / bus erosion** — composer-only generator acceptance (structural), compiler-
   fenced types from PR 3, arch tests, `SurfaceComposed(author)` runtime audit, zero-collector
   invariance + "StateFlows are canonical" charter. Residual accepted hole: `AgentEvent.Delta` is
   raw model text for the live bubble only, matching today's documented semantics.
