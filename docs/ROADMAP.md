# Mink roadmap

A living list of where Mink is and where it can go next. Shipped work is at the
bottom; the top is what's open, roughly ordered by value-per-effort. This is a
guide, not a contract — reorder freely.

## Next (open, unordered within tiers)

### Ship it
- **Release-readiness + real-device dogfood.** The app has only ever run on the
  x86_64 emulator and instrumented tests; the **arm64 native library and the
  live LLM (companion remark, streaming chat, model download) have never
  executed on real hardware.** Before a release:
  - **R8 / ProGuard keep rules for the JNI surface** — `isMinifyEnabled = true`
    in release can rename or strip `com.mink.guardian.llm.LlamaBridge`'s
    `external fun`s, whose native lookup depends on the exact
    `Java_com_mink_guardian_llm_LlamaBridge_*` symbol names. Without keep rules a
    release build can **silently break the on-device model.** Add and verify.
  - Release signing config, `versionCode`/`versionName` bump, a `CHANGELOG`.
  - A genuine **arm64 device pass**: guardian enabled, model downloaded, a live
    LLM-authored companion remark, streaming chat — the things the emulator
    couldn't show (its `/data` can't hold the model).
  - Onboarding + permissions UX scrutiny on a real device.

### More capability
- **Network activity (DNS-flow) monitor — in progress.** An on-device probe
  (2026-07-16) refuted the assumed blocker: no-root per-app `(app → domain)`
  attribution *works* on Android 10+ (`getConnectionOwnerUid` returns the real
  requesting app, not the system resolver). PR-1 ships the opt-in, off-by-default
  DNS-only `VpnService`: it routes only its sentinel resolver `/32`, attributes
  each lookup, forwards it unchanged, and shows a live `(app → host)` list —
  in-memory only, no alerts. Costs are real and disclosed: it holds the single
  VPN slot (replaces any other VPN) and shows the key icon. Follow-ups:
  - **PR-2 (done):** encrypted-DataStore `(app, host)` rollup with retention +
    debounced flush (**no SQLCipher** — a dedicated `DnsFlowStore` reusing the
    Keystore cipher; history survives restart and shows when the monitor is off),
    bundled known-tracker classification (`TrackerList`, tagged in the UI), and
    `AlertSource.DNS_FLOW` + a tunable, quiet `DnsFlowGuard` insight (a user app
    that looked up several known trackers → `SUGGESTION`, once per app per run,
    no lane-5 immutable).
  - **PR-3 (open):** onboarding/settings polish, boot-restart, real-device
    coverage measurement (how much DNS is plaintext under a normal user's
    config), and a larger/updatable tracker list.
  (`NetworkStatsManager` data-use, already shipped, is the always-on volumes-only
  view; this is the opt-in per-destination complement.)

### Depth / "learns and evolves"
- **Guardian-core refactor + four-mode routing.** Split `guardian-core` from the
  app over a typed event bus (`sweep:start`, `signal:changed`, `alert:raised`)
  with four-mode execution routing (notification / script / hybrid / agent) and
  the **grounding pass** — every concrete LLM claim (package, permission, number)
  must exist in the snapshot before display (rules engine as adversarial
  evaluator of the model). See `docs/memory-architecture.md`.
- **Two-loop threshold refiner.** Turn the alert ack/dismiss/snooze signal that
  alert-hygiene (E) already collects into adaptive thresholds — a slow refiner
  (every F sweeps) with keep-or-rollback triage. Deterministic/statistical only;
  the 1B model never authors its own scaffolding (capability floor).

### Product polish
- A real **Settings** screen (alertness lives on the guardian dashboard today;
  model management, mutes, companion, export could gather there).
- **Accessibility** pass (WCAG: contrast, touch targets, screen-reader labels,
  the companion overlay's a11y story).
- Export improvements, richer empty states, iconography, first-run delight.
- A current-state **"Security settings now"** viewer for the high-risk surfaces
  (accessibility services, CAs, device admins) — today only their *changes*
  surface; a posture snapshot would complement it.
- A **"Data use" posture** on the network screen beyond the 7-day list
  (per-app trends, a total, cellular-vs-wifi split visual).

## Shipped

The Loupe-parity guardian 2.0 roadmap and a working on-device brain, all private
and on-device:

- **Signals + narrative**: 30-surface fingerprint catalog at/above Loupe parity;
  derived story cards (travel, gear-owner-name, device age, app inference).
- **Last Loupe gaps closed**: local-network mDNS/Bonjour discovery via
  `NsdManager` (Chromecast, Sonos, printers, Apple devices — no runtime
  permission, a defensive multicast lock), plus three narrative-only cards over
  data already collected — languages you use, accessibility settings, and a
  formatting-versus-region mismatch. Everything else Loupe still does is
  genuinely iOS-only and N/A on Android (Apple Account token,
  Keychain-survives-reinstall, Reminders, pasteboard change-counter, Lockdown
  Mode).
- **Behavioural watches Loupe lacks**: App Access (per-app permission holdings +
  change watch), sensor-in-use (camera/mic, no RECORD_AUDIO/CAMERA needed),
  high-risk security surfaces (accessibility, notification listeners, device
  admins, user CAs, default apps, VPN), per-app data use (NetworkStatsManager).
- **Learned baseline** (per-signal stats, drift detection) and **configurable
  alertness** (Quiet/Standard/Paranoid dial + per-source mutes + cooldown) with
  an **immutable-rule floor** no configuration can silence.
- **Memory hardening**: Keystore-encrypted persistence, schema versioning,
  clock-trust; a six-lane memory architecture ADR.
- **On-device MiniCPM5-1B**: native llama.cpp engine (both ABIs), verified
  inference; an **LLM-driven companion** and **guardian chat** where the rules
  pick the emotion and the model only writes the sentence, always with a
  deterministic fallback.
- **Model narration of the summary**: a grounded, model-authored read of the
  fingerprint summary on the Summary screen — fed only real facts (uniqueness
  score, top categories, story cards), scrubbed and capped, with the model
  writing prose over a deterministic backbone and the `FingerprintNarrative`
  shown alongside as the fallback.
