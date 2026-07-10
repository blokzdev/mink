# Mink architecture

Mink is a single-module Android app (`:app`) written in Kotlin with Jetpack
Compose and Material 3. It has three cooperating subsystems built on one shared
core: the **signals** layer (the fingerprinting surface), the **guardian**
(on-device analysis and chat), and the **companion** (the floating overlay).

```
com.mink
├── core/model       Fixed data model: SignalCategory, Sensitivity,
│                    PermissionKind, FingerprintSignal, DisplayHint, SignalEntry
├── core/provider    SignalProvider / LiveSignalProvider contracts, ProviderContext
├── signals          One provider per category (~30), mapping Android APIs to signals
├── data             SignalStore, PermissionController, ProviderRegistry,
│                    MinkServices, ServiceWiring (the integration seam)
├── guardian         Guardian interface + GuardianController, capability tiering,
│   └── llm          model manager, llama.cpp JNI bridge, MiniCPM chat format, engines
├── companion        Companion interface + overlay service, pixel-art sprite
├── narrative        FingerprintNarrative: readable summary generation
└── ui               Compose screens, navigation, components, theme
```

## Core model

Everything hangs off `SignalCategory`, an enum of 30 fingerprinting surfaces.
Each case declares its title, subtitle, icon, `Sensitivity` tier, and (for
gated surfaces) a `PermissionKind`. This is the Android analogue of Loupe's
`SignalCategory` and drives the whole UI: the home list simply iterates the enum
grouped by tier.

A `FingerprintSignal` is one row: a stable `id`, a `name`, the raw `value`, a
`rationale` that teaches why it leaks identity, and a `DisplayHint` telling the
UI how to lay it out (plain, key/value, axis vector, chips, or compound).

## Providers

Each surface implements `SignalProvider.collect(): List<FingerprintSignal>`, a
one-shot read that is safe off the main thread and never throws (risky reads are
wrapped and degraded). Time-varying surfaces (battery, location, activity) also
implement `LiveSignalProvider.stream(): Flow<List<FingerprintSignal>>`.

Providers are constructed uniformly with a `ProviderContext`. `ProviderRegistry`
builds all 30 and asserts every category is covered exactly once.

## The store

`SignalStore` is the process-wide observable state holder (Loupe's
`CategoryStore`). It owns the providers, exposes `signals` and `loadStates` as
`StateFlow`s, guards permissioned categories through `PermissionController`, and
manages live streaming subscriptions per open detail screen.

## The guardian

`GuardianController` implements the `Guardian` interface. On enable it:

1. Detects device capability (RAM, cores, ABI) and picks a `GuardianTier`.
2. Prepares the model through `ModelManager` (opt-in download of the tier's GGUF
   from the MiniCPM5-1B release into app-private storage).
3. Loads the model through the `llama.cpp` JNI bridge (`LlamaBridge` →
   `libmink_llm.so`) on a dedicated dispatcher, or falls back to `RulesEngine`.
4. Runs periodic sweeps (via `GuardianService`, a foreground special-use
   service) that snapshot signals, diff them over time in `GuardianAnalyzer`, and
   raise `Observation`s and `GuardianAlert`s.

Chat is routed through `LlmEngine`, which formats prompts with the MiniCPM5
`<|im_start|>` template and supports the hybrid `<think>` mode. When no model is
available every guardian capability still works through the deterministic rules
engine, so the feature never hard-fails.

The native bridge is optional. `app/build.gradle.kts` only compiles the C++ when
`src/main/cpp/llama/` is vendored, and `LlamaBridge.isAvailable` reports whether
`libmink_llm.so` loaded. See [`../app/src/main/cpp/README.md`](../app/src/main/cpp/README.md).

## The companion

`CompanionController` implements `Companion`. It manages the overlay permission,
starts `CompanionOverlayService` (a foreground service that hosts a Compose
overlay in a `TYPE_APPLICATION_OVERLAY` window), and observes the guardian's
alerts to speak the important ones. `MinkSprite` renders the retro 8-bit mink on
a Compose canvas with per-mood animation frames.

## Integration seam

The subsystems are developed against interfaces and wired in exactly one place.
`MinkApplication` registers `guardianFactory` and `companionFactory` with
`ServiceWiring`, which builds `MinkServices` (store, permissions, scope,
guardian, companion). Guardian and companion are nullable, so the app degrades to
signals-only if a subsystem is unavailable. The UI depends only on
`MinkServices` and the subsystem interfaces, never on concrete controllers.

## Privacy posture

No signal value, observation, or chat message leaves the device. The model runs
locally. Cloud backup excludes the model cache and guardian data. The only egress
is the explicit **Export** action, which writes a report the user shares
deliberately through a `FileProvider`.
