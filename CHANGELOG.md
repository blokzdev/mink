# Changelog

All notable changes to Mink are recorded here. Dates are ISO-8601.

## [Unreleased]

### Release engineering
- Explicit R8 keep rule for the on-device LLM JNI seam
  (`com.mink.guardian.llm.LlamaBridge` native methods), so a minified release
  build can never silently rename or strip the symbols the `mink_llm` native
  library binds against. Removes the missing-`proguard-rules.pro` build warning.
- Release signing config driven by a gitignored `keystore.properties`, with a
  debug-key fallback so `assembleRelease` builds without secrets (CI, fresh
  clones) — such a build is for testing only, never distribution. See
  `keystore.properties.example`.

## 1.0 — on-device privacy guardian

The first complete Mink: a calm, fully on-device guardian that shows what a
phone reveals and watches how that changes, with an on-device model that
narrates rather than decides.

- **Signals & narrative** — a 30-surface fingerprint catalog at/above Loupe
  parity, derived story cards, and a model-authored (grounded) read of the
  summary with a deterministic fallback.
- **Behavioural watches** — App Access (per-app permission holdings + change
  watch), sensor-in-use (camera/mic), high-risk security surfaces
  (accessibility, notification listeners, device admins, user CAs, default
  apps), and per-app data use.
- **Learned baseline & configurable alertness** — per-signal drift detection,
  a Quiet/Standard/Paranoid dial with per-source mutes and cooldowns, over an
  immutable-rule floor no configuration can silence.
- **Network activity (DNS-flow) monitor** — an opt-in, off-by-default, DNS-only
  `VpnService` that attributes each lookup to the app that made it (Android
  10+), forwards it unchanged to the network's real resolver, flags known
  trackers, and keeps its records on-device. Resumes after reboot when consent
  still stands; a shared Settings screen gathers alertness, mutes, and links.
- **On-device MiniCPM5-1B** — a native llama.cpp engine (both 64-bit ABIs)
  powering an LLM-driven companion and guardian chat where the rules pick the
  emotion and the model only writes the sentence, always with a deterministic
  fallback.
- **Memory hardening** — Keystore-encrypted persistence, schema versioning,
  clock-trust, and a six-lane memory architecture.
