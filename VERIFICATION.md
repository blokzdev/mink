# VERIFICATION.md — how a change is verified on Mink

The shared definition of "done" for both human and AI contributors. A change is
not finished until it clears these gates.

## The build gate

Windows, from the repo root, with the NDK exported for the native engine:

```
export ANDROID_NDK_HOME="C:/Users/ganes/AppData/Local/Android/Sdk/ndk/27.0.12077973"
./gradlew.bat :guardian-core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- **`:guardian-core:test`** — pure-JVM unit tests for all deterministic logic
  (rules, guards, baseline, grounding, router, composer). Fast; no device.
- **`:app:testDebugUnitTest`** — the app-side unit tests.
- **`:app:assembleDebug`** — the debug APK compiles (catches Android-side
  breakage the JVM tests cannot, including cross-module `internal` mistakes).
- **`:app:lintDebug`** — Android lint reports no errors.

**Green means: 0 test failures, assemble succeeds, lint clean.** Read the *actual*
result — a piped `| tail` can mask a non-zero exit. Redirect output to a log and
echo the exit code, or check the JUnit XML under `*/build/test-results/`.

## Watch for

- **`internal` visibility across the module split.** A core type used by `:app`
  must be `public` in `:guardian-core`. The compiler catches this at
  `assembleDebug`, not at `:guardian-core:test` — so always run both.
- **`git add -A` sweeping build artifacts.** Add named paths. `guardian-core/build/`
  is gitignored, but be deliberate.
- **Comment-only edits after a green run.** Re-run — it is cheap, and confirms a
  KDoc/comment change did not ride alongside a stale assumption.

## On-device verification

The emulator's `/data` cannot hold the model, so the live-LLM paths (companion
remark, streaming chat, model download) are verified on **emulator-5556** (NOT
5554 — a parallel project) with a real model loaded, and ultimately on an
**arm64 device** — the native library and the live LLM have never run on real
hardware (see [docs/ROADMAP.md](docs/ROADMAP.md)). ADB:
`C:/Users/ganes/AppData/Local/Android/Sdk/platform-tools/adb.exe`.

## Adversarial review (for substantial changes)

Beyond the build gate, a substantial PR is reviewed across dimensions
(correctness, parity, concurrency, tests, security…), and each finding is refuted
through three independent lenses:

- **mechanism** — does the code actually do what the finding claims?
- **plausibility** — can the claimed inputs/state actually arise?
- **impact** — does it matter for Mink's priorities?

A finding survives only if at least two of the three lenses fail to refute it.
Confirmed findings are fixed and the suite re-run; findings that are adjudicated
but not fixed, and known boundaries, are recorded in the PR's review comment.
Precision-first weighting applies: a false reject (deterministic fallback shown)
is benign, unchecked model text reaching the user is catastrophic — the review
weights the catastrophic direction.

## Definition of done

1. The build gate is green (all four tasks).
2. New behaviour has tests; any deviation from parity is flagged.
3. Substantial changes have passed adversarial review; confirmed findings fixed.
4. Docs are updated if the change alters architecture or conventions.
5. The PR is opened against `main` with a body stating what changed, why, and any
   deliberate deviations.
