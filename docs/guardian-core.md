# The `:guardian-core` module

`:guardian-core` is Mink's pure-JVM heart: every deterministic piece of the
guardian — the rules, the guards, the learned baseline, the grounding check, the
alert policy, the mode router, the grounded composer, the event bus, and all the
narrative/companion text logic — with **no Android SDK on its classpath**. The
compiler enforces the boundary: nothing in this module can touch a platform API,
and all of it runs as plain JVM unit tests (~460 of them). The Android-tainted
adapters — `GuardianController`, the services, `GuardianStore`, the `llama.cpp`
engine, the scanners, and the Compose UI — stay in `:app` and depend on this
module, never the reverse.

This document is the map. For the product view see [../README.md](../README.md);
for the whole-app design see [ARCHITECTURE.md](ARCHITECTURE.md); for the refactor
that produced this module see [design/guardian-core-refactor.md](design/guardian-core-refactor.md).

## Why a separate module

Two reasons, both load-bearing:

1. **The capability floor becomes structural.** Mink's core invariant is that the
   on-device 1B model writes *prose*, never scaffolding — rules pick the finding,
   the mood, the alert level, the thresholds. In a single module that's a
   convention; here it's the compiler. The decision-making types have no way to
   reach a text generator, because the only type that accepts one lives behind a
   seam they cannot import (see *Grounding*, below).
2. **The deterministic logic is exhaustively testable.** No Android means no
   Robolectric, no instrumented tests, no mocked `Context` — just fast JVM unit
   tests over pure functions, with a `FakeTextGenerator` test fixture for the
   generation surfaces.

Package names are identical across the `:app` / `:guardian-core` split, so an
import never reveals which module a type lives in — check the module when adding
a cross-module reference (`internal` is module-scoped; widen a core symbol to
`public` only when a `:app` caller genuinely needs it).

## Package map

```
com.mink.core.model     FingerprintSignal, SignalCategory, PermissionKind, Sensitivity
com.mink.guardian        GuardianContract (the Guardian interface + data model),
                         RulesEngine, GuardianAnalyzer, Baseline (learned rhythms),
                         AlertPolicy (NotificationGate), ThresholdRefiner,
                         GroundingCheck, the per-surface Guards, GuardianCarriers
com.mink.monitor         pure halves: AppAccess/AppAccessWatch, HighRiskAccess,
                         NetworkUsage (analyzeDataUsage/dataUseWindow), DnsFlow
                         (byte-level packet parse/synthesis), SensorUse models
com.mink.narrative       FingerprintNarrative, StoryNarrative, SummaryNarration
com.mink.companion       CompanionRemark (prompt + scrub), CompanionSpeechPolicy,
                         CompanionAlertRouter, CompanionMood
com.mink.guardian.llm    TextGenerator (the generation seam) + GenParams,
                         MiniCpmChatFormat
com.mink.guardian.compose Draft, GroundedProse, Author, SurfaceText, HybridSpec,
                         AgentSpec, AgentEvent, FinalReply, GroundedComposer
com.mink.guardian.route  Surface, Mode, ModePreference, ModeRouter
com.mink.guardian.bus    GuardianEvent (10 sealed events), GuardianBus, GuardianHook
testFixtures             FakeTextGenerator (a scriptable TextGenerator for tests)
```

## The load-bearing seams

### Generation — `TextGenerator`

`TextGenerator { val isLoaded; fun generate(prompt, params): Flow<String> }` is
the only door to the model. Model *lifecycle* (download, load/unload, tier
sizing) is Android-side and deliberately stays OFF this interface — the concrete
`LlmEngine` in `:app` owns it. A test injects a `FakeTextGenerator` to drive
every generation path on a plain JVM.

### Grounding — the composer is the only acceptor

Every model sentence is checked against ground truth before it can be shown, and
the type system makes skipping that check a compile error:

- `Draft` (internal constructor + internal field) — raw model output; nothing
  outside the module can create or read it.
- `GroundedProse` (private constructor) — model prose that passed the gate;
  holding one *is* the proof it was checked.
- `HybridSpec` / `AgentSpec` — a surface's whole request; they will not construct
  without the ground-truth facts and a deterministic fallback, and grounding
  policy is carried by the spec's *type* (hybrid checks numbers **and** entities;
  agent/chat is numbers-only), not a caller-remembered flag.
- `GroundedComposer` — the sole public type in core that accepts a
  `TextGenerator`. Every failure path (budget elapse, blank/think-only output, a
  fabricated number or unknown proper noun, an engine exception) returns the
  deterministic fallback as `Author.DETERMINISTIC`; only caller cancellation
  propagates. Enforced by `GroundingCheck` plus `ArchitectureTest` file scans.

`GroundingCheck` itself is precision-first: verify-or-fall-back, binary. One
unsupported concrete claim rejects the *whole* output. A false reject (fallback
shown) is benign; fabricated text reaching the user is the catastrophic failure.

### Routing — `ModeRouter`

`ModeRouter.resolve(surface, tier, modelLoaded, immutableAlert, preference)` is a
total, **downgrade-only** lattice over four modes (`NOTIFICATION` < `SCRIPT` <
`HYBRID` < `AGENT`). It centralizes the tier/loaded checks that were once
scattered across chat, remark, and narration, and it can only ever *lower* a
surface's model involvement — configuration that adds model text is
unrepresentable. The one intentional pin: an immutable-rule alert on the
companion remark always resolves to `SCRIPT` (the model never re-words a lane-5
finding).

### Events — `GuardianBus`

`GuardianBus` is a typed, post-commit, **advisory** event bus. Ten sealed
`GuardianEvent`s (sweep start/complete, signal changed, alert raised/notified/
realigned/acknowledged, baseline updated, model-state changed, surface composed)
are published *after* the canonical StateFlow update — with zero hooks attached
the bus is behaviorally inert. Dispatch never blocks the emitter: `emit` stamps a
monotonic `seq` and fans out directly to each hook's own bounded channel with a
non-blocking `trySend`, so a wedged or throwing hook is isolated (its own tail
dropped and counted, siblings untouched). A `GuardianHook` is a read-out only —
it holds no actuator handle, so an event can never invoke the model, persist, or
notify. `seq` is for gap detection, not strict delivery; a consumer that sees a
gap resyncs from the canonical StateFlows (as `CompanionAlertRouter` does).

## What stays in `:app`

Everything that touches Android: `GuardianController` (the adapter that owns the
StateFlows, the sweep loop, model lifecycle, and the bus), `GuardianService` +
`GuardianSweepWorker`, `GuardianStore` + `PayloadCipher` (Keystore-encrypted
persistence), `ModelManager`, `LlamaBridge` + the concrete `LlmEngine`, the
scanners/monitors (`AppAccessScanner`, `HighRiskScanner`, `NetworkUsageScanner`,
`SensorInUseMonitor`, `FlowMonitorService`), `CompanionController` + the overlay,
`ServiceWiring`, and the Compose UI.

## Building and testing

```
export ANDROID_NDK_HOME="…/ndk/27.0.12077973"
./gradlew.bat :guardian-core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`:guardian-core:test` is fast and needs no device. Always run `:app:assembleDebug`
too — a cross-module `internal` mistake compiles in core but breaks the app. See
[../VERIFICATION.md](../VERIFICATION.md) for the full definition of done.

## Remaining refactor work

The guardian-core refactor (see the design doc's seven-PR plan) delivered the
seams above through PR 6. One internal cleanup remains, deliberately deferred as
its own focused effort because it is the riskiest change to the guardian's core
loop:

- **`SweepPipeline` extraction.** `GuardianController.sweepNow`'s ~200-line body
  still orchestrates the sweep inline. The plan (design §5) moves it into a pure
  `com.mink.guardian.sweep.SweepPipeline.run(inputs, ports)` behind enumerated
  ports (`SweepPersistence`, `SweepStateSink`, scanners, `notify`, `bus`,
  `clock`), with a `GuardianPorts.commit(save, apply)` construct making
  save → state → publish one atomic persist-then-emit rail. Method:
  characterization tests against the current behavior *first*, then a verbatim
  body move. This is where the controller-integration invariants the earlier PRs
  deferred — persist-then-emit ordering, and `AlertRaised(immutable)` always
  followed by `AlertNotified` in the same sweep — finally get a test harness.
```
