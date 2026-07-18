# Mink architecture

Mink is an Android app written in Kotlin with Jetpack Compose and Material 3,
split across two Gradle modules: **`:app`** (everything Android-touching — the
Compose UI, services, scanners/monitors, the `llama.cpp` engine, and
persistence) and **`:guardian-core`** (a pure-JVM module holding all
deterministic guardian logic: rules, guards, baseline, grounding, the mode
router, the grounded composer, and the narrative/companion text logic, with no
Android SDK on its classpath, so it runs as plain JVM unit tests). The compiler
enforces the boundary, and package names are identical across the split. It has
three cooperating subsystems built on one shared core: the **signals** layer
(the fingerprinting surface), the **guardian** (on-device analysis and chat),
and the **companion** (the floating overlay).

The guardian's ongoing reorganization around a grounded composer, a four-mode
router, and a typed event bus — and the module split above — is specified in
[design/guardian-core-refactor.md](design/guardian-core-refactor.md); the guardian
sections below describe the behaviour, which that refactor preserves.

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

Everything hangs off `SignalCategory`, an enum of 31 fingerprinting surfaces.
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
builds all 31 and asserts every category is covered exactly once.

### Local network

`LocalNetworkProvider` (`com.mink.signals`) is the one provider that looks
outward from the phone: it browses the local Wi-Fi with `NsdManager` (multicast
DNS / DNS-SD) for a curated set of advertised service types — Chromecast,
AirPlay, Sonos, HomeKit, printers, file shares — and reports how many devices
answered plus a friendly label for each. The browse needs no runtime permission
(only the already-declared `INTERNET`); a defensive `WifiManager.MulticastLock`
(the install-time `CHANGE_WIFI_MULTICAST_STATE`) is held for the discovery
window and released in a `finally`. Because discovery is asynchronous and
continuous, the provider launches its listeners in bounded waves, waits a fixed
window, then stops every listener it started — it never leaks one, is fully
exception-safe, and never logs a device name. The mix of devices on a home
network is stable and often unique, which is why it fingerprints; nothing leaves
the phone. On the emulator (no multicast) and off Wi-Fi the browse returns empty
— expected, not an error, and reported as a count of zero.

## The store

`SignalStore` is the process-wide observable state holder (Loupe's
`CategoryStore`). It owns the providers, exposes `signals` and `loadStates` as
`StateFlow`s, guards permissioned categories through `PermissionController`, and
manages live streaming subscriptions per open detail screen.

## The guardian

The deterministic heart of the guardian — rules, guards, baseline, grounding,
the four-mode router, the grounded composer, and the typed event bus — lives in
the pure-JVM `:guardian-core` module, documented in
[guardian-core.md](guardian-core.md). `GuardianController` (in `:app`) is the
Android adapter over it: it owns the StateFlows, the sweep loop, model
lifecycle, and the bus, and threads every text surface through the composer and
every routing decision through `ModeRouter`. The sections below describe the
guardian's behaviour, which that structure preserves.

`GuardianController` implements the `Guardian` interface. On enable it:

1. Detects device capability (RAM, cores, ABI) and picks a `GuardianTier`.
2. Prepares the model through `ModelManager` (opt-in download of the tier's GGUF
   from the MiniCPM5-1B release into app-private storage).
3. Loads the model through the `llama.cpp` JNI bridge (`LlamaBridge` →
   `libmink_llm.so`) on a dedicated dispatcher, or falls back to `RulesEngine`.
4. Runs periodic sweeps (via `GuardianService`, a foreground special-use
   service) that snapshot signals, diff them over time in `GuardianAnalyzer`, and
   raise `Observation`s and `GuardianAlert`s.

Every model-authored surface — the chat reply, the companion remark, the summary
read — goes through the `GroundedComposer` in `:guardian-core`: the model writes
prose, the composer checks every concrete claim against ground truth
(`GroundingCheck`) and falls back to deterministic text on any fabrication,
budget elapse, or failure. `ModeRouter` decides, in one place, whether each
surface speaks with the model at all. The concrete `LlmEngine` (behind the
`TextGenerator` seam) formats prompts with the MiniCPM5 `<|im_start|>` template
and supports the hybrid `<think>` mode. When no model is available every guardian
capability still works through the deterministic rules engine, so the feature
never hard-fails. See [guardian-core.md](guardian-core.md) for the composer,
router, and event-bus seams.

### Learned baseline

Beyond diffing the last two sweeps, the guardian learns the device's rhythms
over time in a `GuardianBaseline` (`Baseline.kt`), persisted through the same
`GuardianStore` under a `baseline` key. Per signal it records how often and at
what local hour the value changes, when it was first and last seen, and a small
LRU of previously-seen value *hashes* — **never the raw values**, so the
baseline can never become a second copy of the fingerprint data. All collections
are bounded (an 8-entry known-hash LRU, a 16-entry change-timestamp ring, a
24-bucket hour histogram, and a hard cap of 1200 tracked signals with
least-recently-seen eviction and 30-day pruning).

Once `MIN_SWEEPS_FOR_LEARNING` sweeps exist the analyzer becomes learning-aware:
naturally-volatile readings (battery, uptime) stop alerting and fold into the
sweep summary; a change to a long-stable anchor is elevated to a `WARNING` even
for passive surfaces; reverts to a previously-seen value and changes at an
unusual hour are annotated; and signals that keep flapping emit an
`ObservationKind.PATTERN`. A compact `BaselineSummary` drives the dashboard's
learning card, and a short rhythm digest is injected into the LLM system prompt
and the rules-engine fallback so Mink can answer "what have you learned about my
device?". Below the maturity threshold behaviour is byte-identical to the
original two-snapshot analyzer. All of `Baseline.kt` and `GuardianAnalyzer` stay
free of Android imports and are pure (time and time zone are injected), so the
learning logic is exhaustively unit-testable on a plain JVM.

Because this persisted history is the most sensitive artifact the app produces,
the guardian's memory is hardened three ways:

- **Encrypted at rest.** Every value `GuardianStore` writes (observations,
  alerts, the chat log, settings, the last snapshot, and the baseline) is
  encrypted with an Android Keystore AES-GCM 256-bit key (`PayloadCipher` /
  `KeystorePayloadCipher`) before it reaches the Preferences DataStore. The key
  never leaves the TEE and needs no user authentication, so locked-device
  background sweeps still work. A legacy plaintext value written before
  encryption existed is read once transparently and re-encrypted on the next
  write; a decryption failure is treated as absent data (empty/null/default),
  exactly like a parse failure.
- **Schema-versioned baseline.** `GuardianBaseline` carries a `schemaVersion`
  (`BASELINE_SCHEMA_VERSION`); `loadBaseline()` discards any baseline whose
  version does not match — including a legacy blob that decodes to `0` — rather
  than risk misreading months of learned state. Losing a baseline is safe (it
  relearns); a future version adds a forward migration in that same place.
- **Clock-trusted hour learning.** Each sweep captures a `SweepTime` (wall clock,
  monotonic `elapsedRealtime`, and tz offset). `assessSweep` cross-checks the new
  sweep against the previous one; when the wall clock has moved independently of
  the monotonic clock the sweep is `CLOCK_SUSPECT`, and the hour-of-day
  histograms are not advanced — a moved system clock cannot poison the temporal
  model, though the change itself is still recorded.

The native bridge is optional. `app/build.gradle.kts` only compiles the C++ when
`src/main/cpp/llama/` is vendored, and `LlamaBridge.isAvailable` reports whether
`libmink_llm.so` loaded. See [`../app/src/main/cpp/README.md`](../app/src/main/cpp/README.md).

### App access

`com.mink.monitor` builds a read-on-demand, on-device inverted index of *granted*
runtime permissions: capability → apps that hold it. `AppAccessScanner` reads every
visible package with `GET_PERMISSIONS` (the same `QUERY_ALL_PACKAGES` surface the
signals layer uses), maps each dangerous permission to a user-legible
`PermCapability` (Location, Camera, Microphone, …), and records per app which
capabilities are granted vs merely declared. `AppAccessReport.from` is pure and
deterministic; `AppAccessMonitor` exposes the latest report as `StateFlow` and
refreshes it on demand, guarding against overlapping scans. The read-on-demand
report is never logged — the app list stays on the phone.

The guardian now *watches* this map instead of only viewing it. Each sweep
`AppAccessWatch` reduces the scan to a minimal `AppAccessSnapshot` (package,
label, system flag, and granted-capability enum names — no versions or install
times) and `diffAppAccess` compares it to the previous sweep's snapshot.
`AppAccessGuard` maps the resulting findings to observations and alerts: a user
app gaining a sensitive capability raises a WARNING ("Chrome gained Location"),
revocations and system-app churn are observations only, and a newly installed app
already holding camera + microphone + location trips lane 5's first immutable rule
— a CRITICAL alert that is never tunable by any refiner. This is a deliberate,
documented privacy escalation: the snapshot is the first thing App Access
*persists*, stored through `GuardianStore` (encrypted at rest with a Keystore
AES-GCM key, excluded from backup) and discarded on schema-version mismatch. An
empty scan is treated as a failed scan — it never diffs or overwrites good state,
so a transient read failure cannot read as "every app was uninstalled". This is
the first persisted node of the memory architecture's lane-4 app entity graph
(keyed on package name).

The **Watched apps** screen (`ui/screens/WatchedAppsScreen.kt`) gives these
findings a distinct home, reached from the guardian dashboard. It filters
`guardian.observations` to the `app_access` category (`APP_ACCESS_CATEGORY`,
shared with the guard mapping so the UI needs no magic string) into a
reverse-chronological timeline of access changes, pairs it with a compact "who
can reach your phone now" summary drawn from the live `AppAccessReport`, and
links out to the full App Access screen. It is a filtered view plus summary — no
new persistence.

### Sensor in use

`SensorInUseMonitor` (with the pure `SensorSessionTracker`, both in
`com.mink.monitor`) is the guardian's second behavioural signal: it notices,
near real-time, when any app turns the camera or the microphone on, riding two
public platform callbacks — camera availability and audio-recording
configuration — that need neither the CAMERA permission nor RECORD_AUDIO. The
platform anonymises both signals, so Mink only ever knows *that* a sensor was
in use, never which app used it. When the user grants usage access, the app in
the foreground at that moment is offered as a best guess — always phrased as
"likely", never stated as fact, and blind to background services actually
holding the sensor. Finished sessions become `sensor_use` observations through
`SensorUseGuard`, rate-capped per sensor per hour so a chatty camera app keeps
the timeline readable, and two deterministic screen-off rules raise a WARNING:
camera use while the screen is off, and microphone use while the screen is off
lasting at least a minute (the duration floor exists because hotword assistants
legitimately blip the mic). Both thresholds are ordinary tunable rules, not
lane-5 immutables. The sessions share the Watched apps timeline with the
app-access findings.

### Alert hygiene

Notifications are decided by one pure gate (`NotificationGate` in
`AlertPolicy.kt`), applied by the controller to every alert a sweep or sensor
session raises. The user sets an **alertness dial** — Quiet notifies only
CRITICAL findings, Standard adds WARNING, Paranoid adds SUGGESTION — and can
mute whole `AlertSource` families (access changes, sensor use, signal changes,
exposure insights). Both affect notifications only: every finding still lands
in the timeline. A repeated identical non-critical alert (same category and
title) is cooled down for 30 minutes (`NOTIFICATION_COOLDOWN_MS`, an ordinary
tunable threshold, not a lane-5 immutable). Static exposure explainers from the
rules engine (`rule.*` ids) are education rather than events, so the dashboard
renders them in their own "What your phone exposes" section instead of the
Alerts list. One thing outranks the whole policy: an alert flagged
`fromImmutableRule` (today the camera + microphone + location surveillance
combo) always notifies — no dial setting, mute, or cooldown can silence a
lane-5 immutable rule.

### High-risk access

`HighRiskScanner` (`com.mink.monitor`) is the guardian's third behavioural
signal: each sweep it reads the classic device-compromise surfaces — enabled
accessibility services and notification listeners belonging to other apps,
active device admins, user-added CA certificates, the four default-app roles
(SMS, browser, keyboard, phone), and whether a device-wide VPN is routing
traffic. Every read is permission-free or covered by permissions the app
already holds, so the watcher adds no manifest permission. `diffHighRisk`
reduces two snapshots to a list of `HighRiskFinding`s, and `HighRiskGuard` maps
each to an observation and, for the surfaces that warrant it, an alert.

The read model matters. Each surface is read independently, and a read that
*throws* carries the previous snapshot's value forward, so a transient failure
never diffs as a removal; a read that *succeeds but is empty* stays empty, so a
genuine removal still surfaces. `runCatching` is what distinguishes the two.
Persistence follows the app-access pattern: scan, diff, and save happen
together, the saved `HighRiskSnapshot` (encrypted at rest through
`GuardianStore`, excluded from backup, discarded on schema-version mismatch) is
the commit point, and observations and alerts are emitted only once it is saved
— persist-then-emit. Because the scanner already carries surfaces forward on
failure, a fully-failed scan simply re-persists the carried-forward state and
diffs to nothing, so there is no separate empty-scan guard.

Every one of these findings is a WARNING under the alertness dial and a new
per-source mute (`SECURITY_CHANGES`), never a lane-5 immutable rule. This is
deliberate: each surface has a legitimate use — accessibility apps, corporate
MDM device admins, developer or proxy CAs, third-party keyboards, personal VPNs
— so a never-tunable CRITICAL would misfire on real users, and the copy
therefore informs ("if you did not set this up…") rather than accuses. The
surveillance combo stays the only immutable rule; a cross-surface spyware combo
(one new app holding accessibility, notification access, and device admin at
once) is a candidate future immutable rule, deliberately not added now. Two
honest limits bound the VPN surface: a device-wide VPN is detectable but a
normal app cannot learn which app owns it, so Mink never names one, and a
per-app VPN that excludes Mink may be missed entirely.

### Data use

`NetworkUsageScanner` (`com.mink.monitor`) is the guardian's per-app
data-volume awareness — the always-on, volumes-only complement to the opt-in
Network activity monitor below: rather than intercept traffic to learn
destinations, it reads how *much* each app moved from `NetworkStatsManager` and
never learns where any of it went. Each scan queries per-uid summaries over two passes — `TYPE_WIFI` then
`TYPE_MOBILE` — accumulates rx+tx per uid, splits the cellular total into its
roaming and background (`STATE_DEFAULT`) subsets, then resolves each uid to a
labelled app through `PackageManager`. The read runs off the main thread and the
whole scan is wrapped so a `SecurityException` (usage access not granted), a
`RemoteException` (the stats service died), or a null `querySummary` all yield an
empty report for the window rather than an error. It reuses the same usage-access
grant the sensor watcher already asks for, so it adds no manifest permission.

The watcher is delta-native. `GuardianStore` persists a single
`lastNetworkCheckMs` cursor, and each sweep scans only the interval *since* the
last check, so ongoing heavy usage is noticed once rather than re-alerted every
sweep. Persist-then-emit holds as elsewhere: the cursor is saved before any alert
is emitted, so a failed emit re-detects next interval rather than re-alerting,
and a first run with no prior cursor just seeds it — no alert on pre-existing
usage, matching the App Access "first sweep records baseline" rule.
`analyzeDataUsage` (pure, in `NetworkUsage.kt`) reduces one interval's volumes to
findings and `NetworkUsageGuard` maps each to an observation and a WARNING: a
user app that moved a lot of cellular data in the background, or a lot while
roaming. Both thresholds are ordinary tunable rules, not lane-5 immutables, and
only user apps are considered — a system app's background and roaming traffic is
expected. The **Data use** screen (`ui/screens/NetworkUsageScreen.kt`) shows the
top apps by total over the last week, each split into cellular, Wi-Fi, roaming,
and background.

The honesty limit is hard and load-bearing: the Data use screen is volumes only.
`NetworkStatsManager` does not reveal which servers or hosts an app talked to, so
no copy on that screen — finding, alert, or screen — implies a destination, an
exfiltration target, or "where your data went". Background is the platform's
coarse per-uid state counter, reported as "in the background", never as a claim
about what the owner was doing while it ran. The one place destinations *are*
visible is the separate, opt-in Network activity monitor below — and only the
names an app resolves, never the traffic itself.

### Network activity (DNS flow)

`FlowMonitorService` (`com.mink.monitor`) is the one monitor that looks at where
apps reach, and it is deliberately the most guarded thing Mink does: strictly
opt-in, off by default, and behind an explicit consent screen. It is a
**DNS-only** local `VpnService`. Rather than tunnel all traffic, it hands apps a
sentinel resolver address and routes *only that address* (`/32`) into the tunnel;
every other packet the device sends bypasses Mink entirely, so connectivity and
battery are barely touched. For each DNS query it reads the requested host name,
attributes it to the requesting app with `ConnectivityManager.getConnectionOwnerUid`
(Android 10+, which is why the feature is gated to API 29+ — an on-device probe
confirmed this returns the real requesting app, not the system resolver), records
the `(app, host)` pair in the process-global `DnsFlowHub`, then forwards the query
unchanged to the network's real resolver and writes the answer back so nothing
breaks. The resolver is tracked live through a `ConnectivityManager` network
callback, so a session that starts before any network is up (a boot resume) or
that outlives a wifi-to-cellular switch keeps forwarding to the resolver the
current network actually chose — the public fallback serves only the window in
which no network has advertised one. Pure byte-level parsing and response
synthesis (IPv4/UDP, the DNS QNAME, the IPv4 header checksum) live in
`DnsFlow.kt` and are unit-tested; the service is the Android glue.

The rails are explicit. It never inspects payloads and never proxies non-DNS
traffic — it sees the names an app looks up, the same queries the resolver would
send anyway, and nothing leaves the device. It holds the single system VPN slot
while active (so it replaces any other VPN — an unavoidable cost of any
`VpnService`) and says so, with the status-bar key icon, before the user turns it
on. Because Mink itself then holds the VPN, `HighRiskScanner.readVpnActive`
returns false while the monitor runs, so the guardian never flags Mink's own VPN
as a suspicious third-party one. Coverage is honest about its limits: queries
that use Private DNS (DoT) or a browser's own DoH bypass the plaintext lookup and
are not seen.

The rollup is persisted so history survives a restart. `DnsFlowStore` keeps a
schema-versioned, retention-pruned (`(app, host)`) snapshot in its own DataStore,
encrypted with the same Keystore AES-GCM cipher as the rest of Mink — no SQLite
or native database dependency; the rollup is small and rewritten as one blob,
debounced so a burst of lookups is a single write. `DnsFlowMonitor` restores it
into the hub at startup (so the Network activity screen shows recent history even
while the monitor is off) and autosaves changes thereafter.

`TrackerList` classifies each host against a bundled, offline list of well-known
advertising and analytics domains (a host matches on an exact or parent-domain
hit), and the screen tags those rows. On the guardian side, `DnsFlowGuard`
(`analyzeDnsFlows`) turns "a user app that looked up several known trackers" into
a quiet `SUGGESTION` — an insight, never a `CRITICAL`, never an immutable rule,
raised at most once per app per run. It fires only while the monitor is on and
only during a sweep, folding into the same notification gate as every other
finding, so the alertness dial and per-source mute (`AlertSource.DNS_FLOW`, shown
as "Network activity" in Settings) apply. The capability floor holds throughout:
the tracker list and thresholds are deterministic and tunable — rules decide, the
model never authors the scaffolding.

The monitor remembers whether it was on (`DnsFlowStore` keeps a small `enabled`
flag beside the rollup), and a `BootReceiver` resumes it after a reboot or an app
update — best-effort, only when the VPN consent *definitively* still stands (an
indeterminate consent check fails closed) and the flag is set. The service is the
single writer of that flag: `false` on any explicit stop command — including the
ongoing notification's Stop action — and `true` only once a tunnel is really up,
so a stop from anywhere sticks across reboots and a denied consent dialog can
never arm a resume. For a guaranteed resume across every boot, the Settings
screen points the user at Android's own always-on VPN toggle, and says what that
trades away: the system then restarts the monitor even after an in-app Stop, so
the off switch moves to system settings (the running screen detects always-on and
says the same). Because the monitor may be started from that background boot
context, `FlowMonitorService.start` uses `startForegroundService` and the service
promotes itself to the foreground before establishing the tunnel; a denied
promotion fails into a clean stop instead of a crash.

A shared **Settings** screen (a gear on the home bar) gathers the alertness dial
and per-source mutes — the same control as on the guardian dashboard, extracted
into one composable so both stay in step — alongside the network-activity note
and quick links to permissions, export, and about.

## The companion

`CompanionController` implements `Companion`. It manages the overlay permission,
starts `CompanionOverlayService` (a foreground service that hosts a Compose
overlay in a `TYPE_APPLICATION_OVERLAY` window), and observes the guardian's
alerts to speak the important ones. `MinkSprite` renders the retro 8-bit mink on
a Compose canvas with per-mood animation frames.

### The companion speaks

The companion says something only when a finding matters, and the split is the
whole design: **rules pick the mood, the model only writes the sentence.** Every
fresh alert sets the sprite's mood at once through `CompanionRemark.moodForAlert`,
a pure rule over the alert's level and category — CRITICAL and the sensor,
high-risk, and data-use WARNINGs read as an attentive ALERT, other WARNINGs and
suggestions as CURIOUS, INFO idles — so the animation is deterministic and never
waits on a model. Only the one-line remark is model-authored: `composeRemark`
builds a calm-bystander prompt (`CompanionRemark.buildRemarkPrompt`), runs it
through the on-device engine at a short no-think budget, and passes the raw text
through `postProcessRemark`, which drops any `<think>` span, keeps a single
sentence, and hard-caps its length. When no model is loaded, or the call fails,
or nothing usable comes back, the companion falls back to the alert title, so it
always has a line to say. The model never picks the mood and never gates a finding.

Which fresh alert is spoken, and when, is a separate decision made by a calm
engine (`CompanionSpeechPolicy`): animate always, speak rarely. It burst-merges
one sweep's batch to the single richest alert — highest severity, then longest
body, then newest — so camera, microphone, and location arriving together become
one remark, then a 10-second throttle and a 5-second same-key dedup keep it from
chattering; a suppressed finding still animates, it just stays quiet. One rule
outranks the engine: a CRITICAL flagged `fromImmutableRule` — the lane-5
surveillance combo — bypasses throttle and dedup and always speaks, because a real
risk is never fully muted. After five quiet minutes the sprite settles into a calm
SLEEPING look that any remark or fresh alert wakes; alarm is carried by posture,
never an alert badge.

Guardian chat gained two calm affordances. When the device supports a model but
none is loaded, a slim card above the input offers to download it; a loaded model
shows a quiet on-device caption, and a rules-only device says so in one line. And
each dashboard alert offers an "Ask Mink about this" button that seeds a grounded
question into the chat draft through a consume-once relay — it fills the input, it
does not send.

## The narrative

`FingerprintNarrative` (`com.mink.narrative`) turns the signal snapshot into the
readable summary the Summary screen shows — a uniqueness read, the signals that
matter most, and a short closing note — derived on device from the store's
snapshot, collecting nothing of its own.

### The story your phone tells

Alongside that summary, `StoryNarrative` derives a set of "story" cards: what the
readings add up to about the person or the device, not what any single sensor
reads. Nine are derived — possible travel (a time zone that disagrees with the
region setting), a paired device that carries its owner's name, a region-versus-SIM
mismatch, how long the phone has been running, how long it has been yours (from the
oldest installed app), what the mix of installed apps hints at, the languages you
use, the accessibility settings you have on, and a formatting-versus-region
mismatch. Each is a pure,
deterministic function over signals Mink already collects plus the app-access
report, with the clock injected, and fires only when its real inputs are present —
the owner card never invents a name a regex did not capture. This closes the last
real gap against Loupe's narrative. Nothing new is read from the OS, and nothing
leaves the device.

### Mink's read

The Summary screen can turn its static uniqueness cards into a few sentences of
plain language, and the same split holds as with the companion: **the model only
writes the prose.** `SummaryNarration` (`com.mink.narrative`, pure JVM) reduces the
real report to a compact fact list — the uniqueness score, the top-identifying
categories, and the already-grounded story sentences, with no raw signal values —
and wraps it in a calm-guardian prompt that tells the model to use only those facts
and never invent an app, a value, or a number. `GuardianController.narrate` runs
the prompt through the on-device engine at a short no-think budget and passes the
raw text through `postProcessNarration`, which drops any `<think>` span, strips
markdown and list markers, and hard-caps the result on a sentence boundary. Every
value the model is shown is real, so there is little for it to fabricate, and the
deterministic `FingerprintNarrative` stays on screen as the grounded backbone —
and is the fallback whenever no model is loaded, the call fails, or nothing usable
comes back.

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
