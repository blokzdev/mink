# Mink roadmap

A living list of where Mink is and where it can go next. Shipped work is at the
bottom; the top is an open backlog, prioritised by leverage (value vs effort) and
fit with the ethos — the capability floor (rules decide, the 1B model only writes
prose), on-device only, precision-first grounding, and graceful rules-only
degradation. This is a guide, not a contract — reorder freely.

Most of the backlog below was captured from a systems sweep of the guardian-core
subsystem (2026-07-18): seven specialist lenses over the code, deduped and
prioritised. Each item carries its *why*, a concrete *plan*, and *where* to start
(file references) so it can be picked up and implemented maximally in one pass.
Effort is `S` (<1 day) / `M` (days) / `L` (1–2 wk) / `XL`.

## Next — prioritised backlog

### P1 · Do next — correctness & the grounding promise

- **Close the chat cancel-residue hole** — `S/high`. Ungrounded streaming chat
  prose can become the durable chat record *and re-enter the next prompt as
  authoritative history* — exactly the fabrication-reaching-the-user outcome the
  grounding type-wall exists to prevent. Latent on the sequential happy path
  (`appScope` never cancels) but reachable **today** via overlapping sends; any
  future "stop generating" control opens the cancel variant.
  **Plan:** treat `streaming == true` as never-authoritative — filter streaming
  messages out of `saveChatLog`'s persisted list *and* out of the
  `historyBefore → turns` mapping, or finalize a cancelled in-flight reply to its
  deterministic fallback in a `finally`/`invokeOnCompletion`. Serialize or ignore
  overlapping sends. Regression-test a cancelled turn + two overlapping turns once
  the `AgentEvent → message` mapping is behind a testable seam.
  *Where:* `GuardianController.chat` (reply created `streaming=true`, added to
  `_chatLog`, rewritten per `AgentEvent.Delta`; the authoritative
  `updateMessage` + `saveChatLog` are *outside* the collect; `historyBefore =
  _chatLog.value → turns`); `GuardianStore` `ChatDto.toModel` forces
  `streaming=false` on load so residue reads as finished. *(tracked: task_ac07de27)*

- **Guarantee the sweep bracket + stop a sweep throw crashing the process** —
  `S/high`. An unguarded throw between `SweepStarted` and `SweepCompleted` both
  **crashes the process** (the guardian scope's `SupervisorJob` has no
  `CoroutineExceptionHandler` → default uncaught handler) and strands the
  companion router's `inSweep` bracket for up to 60 min, which then wrongly
  batches an out-of-sweep camera/mic alert that must speak immediately. Every scan
  is already `runCatching`-wrapped and the notification post is specifically
  guarded — three core computations are the gap.
  **Plan:** wrap the sweep body so `SweepCompleted` always emits (best-effort
  counts) even on throw, mirroring the per-scan `runCatching` discipline; add a
  `CoroutineExceptionHandler` to the guardian scope so an unexpected throw degrades
  rather than crashes. Fold the same bracket guarantee into `SweepPipeline` when it
  lands.
  *Where:* `GuardianController.sweepNow` (`SweepStarted` emit → ~200 lines →
  `SweepCompleted`, no `try/finally`; unguarded `analyzer.analyze`,
  `rules.evaluate`, `GuardianBaseline.updated`); `ServiceWiring` `SupervisorJob`.

### P2 · High leverage — cheap, high value

- **First real arm64 pass (fix the UTF-8 truncation first)** — `S→L/high`. The
  single largest unknown: the arm64 native lib + live model have only run on the
  emulator, and reading the C++ surfaced a concrete latent bug — the first
  multibyte char emitted via byte-fallback tokens (em-dash, curly quotes,
  ellipsis, accented/CJK app label) halts generation mid-word and the surface
  silently degrades to fallback. Pure-ASCII English never trips it, so
  emulator + `FakeTextGenerator` never surfaced it.
  **Plan:** disambiguate the overloaded empty-string in the native seam (a
  distinct skip-and-continue sentinel, or flush `utf8_pending` on EOG) and on the
  Kotlin side stop only on an explicit EOS/`maxTokens`, never on any empty piece;
  add a JVM/native test that splits a 3-byte char across pieces. Then do the device
  pass (guardian enabled, model downloaded, live remark + streaming chat) and
  measure tok/s (feeds the throughput floor below). Merges with the older
  "release-readiness" item — see P3.
  *Where:* `mink_llm.cpp` `valid_utf8_prefix` (returns 0 on continuation-only
  bytes), `nativeSampleToken` (empty string also means EOG/context-full/decode-fail);
  `LlmEngine.kt` `if (piece.isEmpty()) break`.

- **"Security now" posture viewer** — `S/high`. Best value/effort on the list and
  a pure ethos fit: `HighRiskScanner` already reads the six compromise surfaces
  permission-free and builds a full snapshot every sweep, but only *deltas* reach
  the UI — so the guardian can't answer "what can reach my phone *right now*."
  **Plan:** expose the last saved `HighRiskSnapshot` as a StateFlow (or a
  read-on-demand scan) and add a read-only screen grouping active accessibility /
  notification-listener / admin / CA / default-app / VPN holders, reusing the
  guard's own "if you didn't set this up" copy. No new permission, persistence, or
  egress. Mirror the existing live "who can reach your phone now" summary in
  `WatchedAppsScreen`.
  *Where:* `GuardianController.sweepNow` builds `currentHr = highRiskScanner.scan`
  then only diffs+persists it; exposed StateFlows are state/alerts/observations/
  chatLog/baseline only.

- **Closed sensor/data-type lexicon in the grounding entity check** — `M/high`. A
  real **false-accept** (the catastrophic class, not the accepted grounded-but-
  misleading one): the most load-bearing privacy fact in a remark — *which*
  sensor/permission an app touched — is a lowercase common noun, so it is never
  entity-checked. If the model swaps camera→microphone or location→contacts, the
  wrong word is genuinely absent from the facts yet passes; the remark few-shots
  even prime two sensors.
  **Plan:** add a tiny *closed* lexicon (`PermissionKind` titles + a small synonym
  set: mic/microphone, gps/location, photos/media, contacts, plus known app
  labels) checked against the facts on the entity-checking surfaces even though
  lowercase; a sensor/data-type word not in the facts rejects to fallback. Closed
  set ⇒ no false-reject of ordinary vocabulary (precision-first preserved). Also
  absorbs the lowercase-brand-name miss. Regression-test: a location/microphone
  alert whose remark says "camera" must reject.
  *Where:* `GroundingCheck.ungroundedClaims` (scans NUMBER + `isProperNounCandidate`
  = capital/internal-caps only); `PermissionKind`.

- **Exclude the DNS-flow datastore from cloud backup & device transfer** —
  `S/medium`. A factual promise breach: the most browsing-history-like data Mink
  holds (the per-app DNS rollup) is uploaded to Google Drive and included in device
  transfer, while the DNS consent card says Mink "uploads nothing." Confidentiality
  currently rests solely on Keystore non-exportability — a single point of failure
  the sibling guardian store deliberately doesn't rely on.
  **Plan:** add `<exclude domain="file" path="datastore/dns_flow.preferences_pb"/>`
  to both `backup_rules.xml` and `data_extraction_rules.xml`. Better: switch to an
  allowlist (`<include>`) posture so any future datastore is excluded by default —
  opt-out is what let this slip. (The plaintext `dns_enabled` flag also leaks that
  the feature was used.)
  *Where:* `backup_rules.xml` / `data_extraction_rules.xml` (exclude only `models/`
  + `guardian.preferences_pb` today); `DnsFlowStore` `preferencesDataStore(name =
  "dns_flow")`; `AndroidManifest` `allowBackup=true`.

### P3 · Near-term depth & release-hardening

- **Gate LLM surfaces on a measured throughput floor + scale `maxTokens` by tier**
  — `M/high`. Chat runs 512–768 tokens against a fixed 60s wall budget on CPU-only
  inference; a 1B Q4 on a low/mid phone can miss the throughput, so the budget
  elapses, the whole generation is discarded for the rules fallback after multiple
  cores ran flat-out (max battery/heat, zero output), and the user watches the
  streamed draft get swapped for an unrelated answer. The weakest devices pay the
  most for the lowest hit rate. Measure this on the first device pass.
  **Plan:** on model load, time a short warm-up generation and record tok/s; if a
  surface's `maxTokens` can't plausibly finish in its budget, route straight to
  rules (skip the wasted generation). Scale `maxTokens` by tier and/or make the
  budget a function of measured tok/s; consider a time-to-first-token bail.
  *Where:* REMARK/NARRATION/CHAT budgets + `maxTokens` (`GuardianController`,
  `TextGenerator`); `GroundedComposer.agent` discards on `completed=false`;
  `LlmEngine.load` `nGpuLayers=0` (CPU only).

- **Export the guardian's actual work product + a purge control** — `M/high`. The
  one sanctioned egress ships only the device-fingerprint snapshot; observations,
  alerts, the high-risk posture, the tracker-tagged DNS rollup, and the learned
  baseline are all excluded — so a user documenting stalkerware for an advocate
  taps Export and gets a fingerprint dump, not the timeline that is the whole
  point. The memory ADR makes user-inspectable/user-purgeable a stated principle.
  **Plan:** add guardian sections to the exported JSON/text (recent
  observations+alerts, current posture, tracker-tagged DNS rollup, `rhythmDigest`),
  reusing the baseline's scrubbing discipline so raw values it refuses to store
  never leak. Same user-initiated Export + `FileProvider`. Pair with a
  delete-all/purge control.
  *Where:* `ReportBuilder` (serializes `SignalCategory → signals` only);
  `ExportScreen`; guardian `GuardianStore` / `DnsFlowStore` / `HighRiskSnapshot`.

- **Second bus consumer: `GuardianTelemetry`** — `S/medium`. The bus has exactly
  one consumer (the companion), so the `SurfaceComposed` "runtime audit" the
  refactor design named as one of three defenses against grounding erosion is
  emitted from six sites and observed by nobody — that drift-guard *doesn't
  actually exist yet* (only the static `ArchitectureTest` scan does). Drop/exception
  counters are also read nowhere. Cheapest activation of a seam that exists.
  **Plan:** add a read-only `GuardianTelemetry` hook aggregating `SurfaceComposed`
  author/mode ratios (how often the model is grounded vs falls back) + drop/
  exception counts into an in-memory diagnostics StateFlow on a debug/About screen.
  Lossy-tolerant, per the advisory-bus charter. Doubles as the ADR's mandatory
  user-inspectable debug view.
  *Where:* `GuardianBus.attach` (sole caller is `CompanionController`);
  `SurfaceComposed` emit sites in `GuardianController`; `GuardianBus.droppedCount`
  + per-hook counters (exposed, unread).

- **Pin a per-quant SHA-256 for the downloaded GGUF** — `M/medium`. The multi-GB
  weights are validated only by a 4-byte magic before being handed to the
  llama.cpp GGUF parser (a real memory-safety CVE surface). HTTPS defeats passive
  MITM, but a changed/compromised HuggingFace repo or an active MITM via a
  user-installed root CA — precisely the vector Mink itself monitors — could
  substitute the file.
  **Plan:** pin a `SHA-256` constant per known GGUF and verify the completed `.part`
  against it in the VERIFYING step before `renameTo`/load, failing closed on
  mismatch. Optionally add public-key pinning for `huggingface.co`.
  *Where:* `ModelManager` (`isDownloaded` = exists+length+magic; download/verify);
  `LlamaBridge.nativeInit`.

- **Release-readiness (the rest)** — `M/high`. Beyond the arm64 pass + throughput +
  GGUF pin above: **R8/ProGuard keep rules for the JNI surface** —
  `isMinifyEnabled=true` can rename/strip `LlamaBridge`'s `external fun`s whose
  native lookup needs the exact `Java_com_mink_guardian_llm_LlamaBridge_*` symbol
  names, silently breaking the model; release signing config, `versionCode`/
  `versionName` bump, a `CHANGELOG`; onboarding + permissions UX scrutiny on a real
  device. Also open from the DNS work: **real-device DNS coverage measurement**
  (how much DNS is plaintext under a normal config — verdict gate 2, needs
  hardware).

### P4 · Structure & depth

- **`SweepPipeline` extraction** — `L/high`. The ~200-line sweep loop and every
  integration invariant it upholds (persist-then-emit ordering, the immutable-alert
  same-sweep notify, the sweep bracket, the five-list notify-merge de-dupe) have
  zero coverage; the pure pieces are each tested, their composition is not. This is
  the correct vehicle for a controller characterization harness. Deliberately
  deferred as the riskiest change to the core loop — do it characterization-first
  (pin behaviour through in-memory ports, then move verbatim).
  **Plan:** pull `sweepNow`'s body behind pure ports (snapshot source, persistence,
  clock, notifier, bus) with a `commit(save, apply)` construct making
  save→state→publish one atomic persist-then-emit rail. Cheap interim win if the
  full extraction stays deferred: extract the notify-merge into a pure function
  returning `(notified, suppressed)` and test it directly. *(tracked: task #14;
  full plan in `design/guardian-core-refactor.md` §5)*

- **Memory architecture — lane 1 then lane 3** — `L/high`. Lane 3 (the episodic
  SQLite+FTS log) is the single highest-leverage structural expansion: the
  "timeline" is a 100-item StateFlow rewritten as one JSON blob per save, so
  history beyond 100 is silently dropped and lanes 4 (co-change edges) and 6 (the
  context assembler — the only lane the model reads) are fully blocked on it.
  Genuine infrastructure, correctly sequenced with/after `SweepPipeline`.
  **Plan:** lane 1 first (model/ABI/enrolment core-facts, an `S` Preferences add
  that lane 6 also needs). Then a lane-3 SQLite+FTS store behind a pure port,
  written **on the `SweepPipeline` commit rail — never the lossy advisory bus** —
  encrypted at rest, `schemaVersion` + forward migration, retention lifecycle, user
  read/purge path. Then lane-4 co-change/precedence edges as a pure fold over it.
  *Where:* `docs/memory-architecture.md`; `GuardianController.addObservations`
  (`take(MAX_HISTORY=100)`); lane-4 nodes exist (`AppAccessSnapshot`/
  `HighRiskSnapshot`) with no edges.

- **Per-surface "Mink's voice: model / rules-only" toggle** — `S/medium`. A
  high-trust, self-contained feature where the risky routing invariant is already
  built and property-tested (the lattice can only ever *remove* model involvement);
  only the UI end is missing.
  **Plan:** add `GuardianSettings.surfaceModes: Map<Surface, ModePreference>`
  (default `AUTO`), persist it, thread it into the three `resolve()` calls keyed by
  surface, add a compact per-surface toggle group to `SettingsScreen`.
  *Where:* `ModeRouter.resolve` (already accepts a `preference`, lowers to `SCRIPT`
  on `DETERMINISTIC`; all call sites pass `AUTO`); `GuardianSettings`;
  `SettingsScreen`. *(design "PR 8")*

- **Cross-surface spyware-combo rule** — `M/high`. The natural sibling of the
  shipped `SURVEILLANCE_COMBO`: one newly-seen package holding accessibility +
  notification-listener + device-admin at once is the stalkerware-at-install
  surface the guardian exists to catch. Both the guard KDoc and the ADR name it a
  deferred candidate.
  **Plan:** heed exactly why it was withheld — enterprise MDM legitimately trips all
  three, and a misfiring immutable CRITICAL on corporate devices is the failure
  mode. Scope tightly (the triad on a newly-seen package in one sweep AND exclude
  device-owner/profile-owner admins) and ship as a high-severity **tunable
  WARNING** first; promote to an immutable lane-5 rule only after real-device
  evidence.
  *Where:* `HighRiskGuard` KDoc; `HighRiskFinding.DeviceAdminAdded` (carries
  `packageName`); `SURVEILLANCE_COMBO` precedent in `AppAccessWatch`.

- **Unload the model under memory pressure** — `M/medium`. The model is loaded
  eagerly and pinned resident by the foreground service with no voluntary release
  path, so on MINIMAL (3 GiB) / LITE (4 GiB) it is a prime low-memory-killer
  target — which then drops sweeps and the model until relaunch. Graceful
  shrink-under-pressure is missing from a stated graceful-degradation ethos.
  **Plan:** register a `ComponentCallbacks2` that unloads `LlmEngine` on
  `TRIM_MEMORY_RUNNING_LOW/CRITICAL` (and optionally when backgrounded on
  MINIMAL/LITE) and lazily reloads on next use — surfaces already fall back to
  rules when `isLoaded` is false, so an unload degrades cleanly.
  *Where:* `loadModel` (eager; unload only in `disable()`); `GuardianService`;
  no `onTrimMemory`/`onLowMemory` anywhere today.

- **Accessibility (WCAG) pass — overlay first** — `L/high`. A real inclusion gap
  for a privacy tool whose users disproportionately need assistive tech, and a
  Play-policy exposure. The overlay is the weakest point (sprite canvas invisible
  to TalkBack; bubble action target well under 48dp; pervasive alpha-dimmed body
  text risking AA contrast; `TYPE_APPLICATION_OVERLAY` focus-order issues).
  **Plan:** merge row semantics on interactive cards, give the sprite a stateful
  `contentDescription` (mood + last utterance), enforce 48dp targets, audit
  alpha-dimmed text against 4.5:1 in both themes, adapt the overlay to dark theme,
  fix overlay focus order. Cheap early win: enable Compose a11y lint in `:app`.
  *Where:* ~31 `contentDescription` across 16 ui files (mostly back buttons);
  `CompanionBubble` (no semantics, `TextButton` `contentPadding 0/0`);
  `onSurface.copy(alpha=0.6–0.7f)` body text.

- **Pure-JVM DTO round-trip tests** — `S/medium`. Hand-written per-field DTO
  mirrors mean a field added to a model but not its DTO is silently dropped on
  persist with no compiler error and no test (e.g. `fromImmutableRule` or
  `acknowledged` failing to survive a save).
  **Plan:** extract `from()`/`toModel()` into pure functions and add a plain-JVM
  round-trip test per type (model with every field non-default → DTO → JSON → DTO →
  model, assert persisted-field equality; assert `streaming` is intentionally
  reset). No Robolectric/Context needed.
  *Where:* `GuardianStore` `ObservationDto`/`AlertDto`/`ChatDto`/`SettingsDto`.

- **Extract a pure `CompanionReaction` + test the controller glue** — `M/medium`.
  The router extraction succeeded but the controller glue that consumes it is
  untested Android code with a genuine guard (stay silent if the user disabled the
  companion while `composeRemark` ran) — exactly the composition bugs (mood set but
  no speak, speak after disable, double-seed) unit tests catch.
  **Plan:** extract a pure `CompanionReaction` decision into guardian-core mirroring
  the router: given `(fresh batch, enabled, nowMs)` return the mood + an optional
  alert-to-speak, leaving only overlay/DataStore side effects in the controller.
  Unit-test the enabled-gating and mood-vs-speak split.
  *Where:* `CompanionController.react`/`speakAlert` (disable guard), sole
  `bus.attach`, constructor `seed`.

### P5 · Polish & longer bets

- **Native inference hygiene** — `S/low`. Two confirmed defects on the never-run
  path. (1) Oversized-prompt truncation erases from the *front* where the persona
  lives, so on MINIMAL (`n_ctx=1024`, keep ~512) the model answers the weakest
  tier's chats with no instructions (quality-only; the grounding gate still blocks
  fabricated figures). (2) The sampler chain is rebuilt and re-seeded with a
  constant seed every token, so the RNG never advances (diversity undercut) plus a
  per-token malloc. **Plan:** on truncation always keep the leading system block +
  newest user turn (or cap `MAX_HISTORY_TURNS` by tier before native); build the
  sampler once per generation and seed once. Re-validate on the first device run.
  *Where:* `mink_llm.cpp` (`toks.erase` from begin; `nativeSampleToken` rebuild +
  seed + free every call).

- **Grounding honesty & safe precision tweaks** — `S/low`. (1) Spelled-out specific
  figures ("seventy-three percent") are an *unchecked* fabrication surface — but
  adding claim-side spelled-number checks fights precision-first, so this is
  doc-honesty only (correct the KDoc to say plainly they're unchecked). (2) Chat
  folds the user's own message into the numeric facts, letting the model "confirm"
  a figure the user only hypothesised ("Am I using 5 GB?"→"Yes, 5 GB") — fold only
  *asserted* numbers, not asked ones. (3) Add a one-line design note that the
  composer clock was intentionally dropped (`withTimeoutOrNull` enforces the budget)
  so a future reader doesn't "restore" it. Do NOT pursue whole-snapshot entity
  checking for chat — that's a documented, ethos-consistent accepted bound.
  *Where:* `GroundingCheck` KDoc; `chatGroundingFacts`; `GroundedComposer` ctor.

- **Export plaintext cleanup + signed importable tracker list** — `S/low`. (1)
  Export writes the full raw fingerprint as plaintext into `cacheDir/exports` and
  never deletes it — a plaintext side-door to the Keystore-at-rest story. Delete
  each exported file after the share intent resolves (or TTL-prune). (2) The tracker
  list is bundled-only, so `DnsFlowGuard` accuracy freezes each release — but any
  background/remote fetch contradicts "uploads nothing," so the right shape is a
  *user-initiated signed import* (a bundled-key signature, merge; the mirror of
  Export), never an auto-fetch.
  *Where:* `ExportScreen.writeReportFiles` → `cacheDir/exports`; `TrackerList.load`
  (single bundled `assets/trackers.txt`).

- **Conversational companion via the existing `ChatPrefill` relay** — `L/medium`.
  The bubble is one-way today (a single "See details" deep-link). Low-risk next
  step: a second action ("Ask about this") that populates `ChatPrefill` with a
  grounded question about the spoken alert and deep-links into
  `GuardianChatScreen`, reusing the relay the dashboard already uses. Defer true
  in-overlay typing (IME focus on `TYPE_APPLICATION_OVERLAY` is the hard part).
  **Do the chat cancel-residue fix (P1) first** so a conversational surface doesn't
  amplify that hole. *Where:* `CompanionController.speakAlert`; `ChatPrefill` relay
  in `MinkNav`.

- **UI/product polish.** A consolidated **Settings** screen (model management,
  mutes, companion, export gathered); a **"Data use" posture** beyond the 7-day
  list (per-app trends, total, cellular-vs-wifi split); richer empty states,
  iconography, first-run delight.

### Known blind spots — need their own sweep before trusting

The 2026-07-18 sweep converged on guardian-core and the two controllers; these
surfaces were barely probed and share the "never run on hardware" risk:

- **Android scanner/monitor adapters on real hardware** — the `FlowMonitorService`
  VpnService packet-parse path, `NetworkUsageScanner`, and each scanner's behaviour
  under real permission-denial. Pure halves are tested; device behaviour is not.
- **Internationalization** — `GroundingCheck`'s STOPLIST and singulariser are
  English-only, so a non-English device or non-English app labels would collapse
  grounding *recall* (mass false-rejects). Ethos-relevant, unraised by any lens.
- **Always-on cost & platform compliance** — sweep-loop + DNS VpnService battery /
  wakelock / Doze behaviour; Android 13+ notification + foreground-service-type
  declarations; Play-policy for VpnService and (future) accessibility usage.
- **Crypto implementation & build/release** — `PayloadCipher`/Keystore IV handling
  and key rotation; llama.cpp submodule/NDK/ABI reproducibility; R8 rules for the
  reflection surface (beyond the JNI keep rules already flagged).
- **Guard/rules false-positive rates** — the semantic correctness of
  `RulesEngine`/`ThresholdRefiner`/the guards was taken as "well-tested," not
  re-audited against real-device data.

## Shipped

The Loupe-parity guardian 2.0 roadmap and a working, grounded on-device brain,
all private and on-device:

- **Guardian-core refactor + four-mode routing (grounding pass)** — the guardian
  reorganised around a compiler-enforced boundary: the deterministic heart split
  into a pure-JVM `:guardian-core` module; every model surface routed through a
  `GroundedComposer` that checks each concrete claim against ground truth and falls
  back to deterministic text on any fabrication (unforgeable `Draft`/`GroundedProse`
  types make skipping the gate a compile error); a `ModeRouter` downgrade-only
  lattice deciding where the model speaks at all; a typed advisory `GuardianBus`
  (10 post-commit events, per-hook isolation) with the companion as its first
  consumer; documented in `docs/guardian-core.md`. One internal cleanup remains —
  the `SweepPipeline` body extraction (see P4).
- **Signals + narrative**: 30-surface fingerprint catalog at/above Loupe parity;
  derived story cards (travel, gear-owner-name, device age, app inference);
  local-network mDNS/Bonjour discovery; languages/accessibility/formatting cards.
- **Behavioural watches Loupe lacks**: App Access (per-app permission holdings +
  change watch), sensor-in-use (camera/mic, no RECORD_AUDIO/CAMERA needed),
  high-risk security surfaces (accessibility, notification listeners, device
  admins, user CAs, default apps, VPN), per-app data use (NetworkStatsManager).
- **Network activity (DNS-flow) monitor**: opt-in, off-by-default, DNS-only
  `VpnService` (sentinel `/32`, per-app attribution via `getConnectionOwnerUid`,
  forwards unchanged); encrypted `DnsFlowStore` rollup with retention + debounced
  flush (no SQLCipher); bundled tracker classification + a quiet `DnsFlowGuard`
  insight; boot-restart when consent stands; a shared Settings screen.
- **Learned baseline** (per-signal stats, drift detection) and **configurable
  alertness** (Quiet/Standard/Paranoid dial + per-source mutes + cooldown) with an
  **immutable-rule floor** no configuration can silence; a **two-loop threshold
  refiner** turning alert engagement into adaptive, quieter-only cooldowns
  (deterministic/statistical, keep-or-rollback — the model never authors its own
  scaffolding).
- **Memory hardening**: Keystore-encrypted persistence, schema versioning,
  clock-trust; a six-lane memory architecture ADR (`docs/memory-architecture.md`).
- **On-device MiniCPM5-1B**: native llama.cpp engine (both ABIs), verified
  inference on the emulator; an LLM-driven **companion** and **guardian chat** and a
  grounded **summary narration**, where rules pick the finding/mood and the model
  only writes the sentence — always over a deterministic fallback.
