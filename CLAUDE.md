# CLAUDE.md — operating manual for Claude Code on Mink

Mink is a privacy-first, fully on-device Android AI Guardian (Kotlin + Jetpack
Compose). It surfaces the device-fingerprinting surface a phone exposes and runs
a local MiniCPM5-1B guardian that watches it with the owner — nothing ever leaves
the device. Product intro: [README.md](README.md). Deep design:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Modules — the boundary is compiler-enforced

- **`:guardian-core`** — a pure-JVM module (no Android SDK on the classpath). All
  deterministic guardian logic: rules, guards, baseline, grounding
  (`GroundingCheck`), alert policy, the mode router, the grounded composer, the
  narrative/companion text logic, and the LLM seam (`TextGenerator`). Runs as
  plain JVM unit tests; `java-test-fixtures` provides `FakeTextGenerator`.
- **`:app`** — everything Android-tainted: `GuardianController` (a thin adapter),
  the services/workers, `GuardianStore` + cipher, `ModelManager`, the
  `llama.cpp` JNI engine, the scanners/monitors, the companion, the Compose UI,
  and `ServiceWiring`.
- **Package names are identical across the split**, so an import never reveals
  which module a type lives in — check the module when adding a cross-module
  reference. `internal` is module-scoped; widen a core symbol to `public` only
  when a `:app` caller genuinely needs it.

## Non-negotiable invariants

- **Capability floor — the model writes prose, never scaffolding.** Rules pick
  the finding, the mood, the alert level, and the thresholds; the 1B model only
  writes a sentence. No decision-making type (`RulesEngine`, `AlertPolicy`,
  `ModeRouter`, `ThresholdRefiner`, `GuardianBus`, `SweepPipeline`) takes a
  generator or composer.
- **Grounding — verify-or-fall-back, binary.** Every model sentence is checked
  against ground truth before display; one unsupported concrete claim (a
  fabricated number, an unknown proper-noun app name) rejects the *whole* output
  to the deterministic fallback. Precision-first: a false reject (fallback shown)
  is benign; **fabricated text reaching the user is the catastrophic failure.**
- **The composer is the only generator acceptor.** `GroundedComposer` is the sole
  type in core that runs a `TextGenerator`; raw output exists only as an internal
  `Draft`, grounded prose only as a `GroundedProse` (private constructor).
  Enforced by types plus `ArchitectureTest` file scans.
- **Immutable-rule floor (lane 5).** An alert `fromImmutableRule` (today: the
  camera + microphone + location surveillance combo) always notifies — no dial,
  mute, cooldown, refiner, or model wording can touch it.
- **On-device only.** No signal value, observation, or chat message leaves the
  device. The only egress is the explicit Export action.
- **Persist-then-emit.** Each guard saves its snapshot (the commit point) before
  emitting alerts, so a failed write can't re-notify the same change every sweep.

## Build & verify

Windows, PowerShell + Git-Bash. The NDK is required for the native engine:

```
export ANDROID_NDK_HOME="C:/Users/ganes/AppData/Local/Android/Sdk/ndk/27.0.12077973"
./gradlew.bat :guardian-core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Green = 0 test failures, assemble succeeds, lint clean. Always run both
`:guardian-core:test` and `:app:assembleDebug` — a cross-module `internal`
mistake compiles in core but breaks the app. Full protocol (counts, on-device
steps, the adversarial-review gate): [VERIFICATION.md](VERIFICATION.md).

## Conventions

- Commits are **unsigned**: `git -c commit.gpgsign=false commit …`, with the
  trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Commit or
  push only when asked; branch off `main`.
- `gh` CLI is authenticated as **blokzdev**; open PRs against `main`.
- On-device verification uses **emulator-5556** (NOT 5554 — that is a parallel
  project). ADB: `C:/Users/ganes/AppData/Local/Android/Sdk/platform-tools/adb.exe`.
- Workflow: **design → adversarial review → PR-by-PR**, each PR landing green and
  reviewed before the next; don't stack the next PR on an unmerged branch. How
  the collaboration works for a human teammate: [HUMAN.md](HUMAN.md).
- `.claude/settings.local.json` holds personal permissions and is gitignored —
  never commit it.

## Where things are

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — the full technical design
  (signals, guardian, companion, monitors).
- [docs/ROADMAP.md](docs/ROADMAP.md) — shipped and open work.
- [docs/memory-architecture.md](docs/memory-architecture.md) — the six-lane
  guardian-memory ADR (capability floor, the LLM-never-writes-memory invariant).
- [docs/design/guardian-core-refactor.md](docs/design/guardian-core-refactor.md)
  — the accepted `:guardian-core` refactor + four-mode routing design, with its
  seven-PR plan.

## In flight

The guardian-core refactor (design doc §6): PR 1 (TextGenerator seam) → PR 2
(the `:guardian-core` module) → PR 3 (grounded composer + mode router +
immutable-pin) → PR 4 (agent mode: chat via the composer) — **all merged into
`main`.** Next: PR 5 (observe-only `GuardianBus`), PR 6 (companion onto the bus),
PR 7 (SweepPipeline extraction — riskiest, last; ships `docs/guardian-core.md`
and the controller test harness).
