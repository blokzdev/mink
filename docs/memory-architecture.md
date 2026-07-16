# Mink guardian memory architecture

An architecture decision record for the guardian's memory layer. It describes
what the guardian remembers today, why the current single-digest design is not
enough, and the six-lane structure we are moving toward. It is written so an
engineer joining the project can act on it.

## Context

The guardian today persists a single flat, ahead-of-time digest. That digest is
`GuardianBaseline` (`app/src/main/java/com/mink/guardian/Baseline.kt`): per
signal it holds a change count, a value-hash LRU (never the raw values), a ring
of recent change timestamps, and an hour-of-day histogram; per device it holds a
sweep count and a sweep-hour histogram. It is serialized to JSON and stored under
a single `baseline` key in a Preferences DataStore through `GuardianStore`
(`app/src/main/java/com/mink/guardian/GuardianStore.kt`).

This digest is good at detection and bad at explanation. It can tell that a long
stable anchor changed, that a value reverted, that a signal is flapping, or that
a change landed at an unusual hour, and it drives a compact `BaselineSummary` and
a short `rhythmDigest`. What it cannot do is say *what happened and when* in any
recoverable way. It is a running summary, not a record. There is no episodic log
to search, no relationship between apps and the signals they move, and no place
for facts that are neither statistics nor rules. Every commit also rewrites the
whole blob, which becomes the central constraint in the Consequences section.

The rest of this document describes the memory layer that replaces the single
digest with six cooperating lanes, and the invariants that keep the guardian
honest and useful on a device with no model at all.

## Decision: six memory lanes

We split the guardian's memory into six lanes, each with its own contents,
lifecycle, and store. The lanes are ordered by how close they sit to the raw
device: lane 1 holds the smallest, most durable facts; lane 6 is a read-only
consumer that never owns any state of its own.

| # | Lane | Contents | Store |
|---|------|----------|-------|
| 1 | Core facts | Small, always-resident device facts (model, ABI, baseline OS build, enrolment time). Deterministic compaction to a fixed token budget when the set grows. | Preferences DataStore |
| 2 | Signal baseline | Today's per-signal stats plus a continuous precision weight, an EWMA rate and variance, CUSUM change-detector state, and a warm-up observation counter. | Preferences DataStore |
| 3 | Sweep pages | Append-only, header-decorated, FTS-indexed episodic log of sweeps and events. Lifecycle Active → Warm → Cold → Archived. | SQLite + FTS |
| 4 | App entity graph | Package-keyed nodes; deterministic co-change and precedence edges between apps and the signals they move. | SQLite |
| 5 | Procedural | Immutable rules (never runtime-writable) plus Beta(α, β) feedback-trust thresholds, each clamped to fixed floors and ceilings. | Preferences DataStore |
| 6 | Context assembler | Read-only consumer: FTS + ID retrieval, provenance-tagged spans, salience-budgeted into the model's context window, then grounded by the rules engine. Owns no persisted state. | (none) |

A few notes on the lanes that carry the most weight:

**Lane 1 — core facts.** These are the handful of facts the guardian should
always know without a query: what the device is, when learning began, the OS
build it first saw. When the set exceeds its token budget, compaction is
deterministic — a pure function of the current facts, not a model call — so the
same inputs always produce the same compacted set.

**Lane 2 — signal baseline.** This is the current `GuardianBaseline`, extended.
The existing change counts, hash LRU, timestamp ring, and hour histograms stay.
Added on top: a continuous precision weight per signal (inverse-variance, with a
warm-up ramp so a signal earns trust as it accumulates observations), an EWMA of
the change rate and its variance, and CUSUM state for O(1) online change
detection. The warm-up counter is what today's discrete `MIN_SWEEPS_FOR_LEARNING`
and `STABLE_MIN_SWEEPS` thresholds become — a smooth ramp rather than a cliff.

**Lane 3 — sweep pages.** The episodic log the single digest never had. Each
page is an append-only record with a decorated header (timestamps, clock-trust
flags, sweep metadata — see Temporal integrity) and an FTS index over its
searchable text. Pages age through Active → Warm → Cold → Archived so retrieval
can favour recent history without discarding the tail.

**Lane 4 — app entity graph.** Package-keyed nodes with deterministic edges:
co-change (this app appeared and this signal moved in the same sweep) and
precedence (this app's install or permission grant preceded that change). The
edges are computed by fixed rules over lane 3, not inferred by a model.

**Lane 6 — context assembler.** The only lane the model touches, and it touches
it read-only. It retrieves from lanes 3–4 by FTS and by id, tags every retrieved
span with its provenance — `<observation>` for episodic facts, `<baseline>` for
statistics, `<rule>` for procedural knowledge — fits them to a salience budget
inside MiniCPM's 1–2k context window, and hands the result to the model. Whatever
the model returns is then grounded by the rules engine before it reaches the user.

## The degradation contract

State it as an invariant:

> **Lanes 1–5 are the entire guardian with zero model. Lane 6 is pure upside.
> The LLM reads memory; it never writes it.**

Detection, scoring, the refiner, and retrieval are all deterministic and live
entirely in lanes 1–5. On a device where no model is present — the rules-only
tier — the guardian loses lane 6 and nothing else. It still sweeps, still learns
the baseline, still detects change, still builds the entity graph, still fires
rules, still answers "what have you learned about my device?" from the
`rhythmDigest`. The `RulesEngine` fallback already works this way today; the
lane structure generalizes it.

Why insist on this? Because the guardian must work *identically* on the
rules-only tier. A privacy tool that silently gets weaker when the model is
absent is a tool that fails the users who need it most — old hardware, low RAM,
locked-down builds. If the model could write memory, then memory would diverge
between the model-present and model-absent tiers, and the rules-only guardian
would be running on state it could not have produced. Keeping every write path
deterministic guarantees that the model is an explainer bolted onto a complete
system, never a load-bearing part of it. This is also why the write path can be
cheap and dumb (see Rejected alternatives): correctness never depends on it being
smart.

## Temporal integrity

The baseline learns *when* things happen — hour-of-day histograms drive the
unusual-hour signal. That learning is only as trustworthy as the clock, and the
wall clock on a phone is not trustworthy: the user can set it, the network can
move it, and daylight-saving and timezone changes move it legitimately.

The rule: **every persisted timestamp is paired with a monotonic
elapsed-realtime reading and a timezone offset.** A sweep records not just its
wall-clock epoch but the elapsed-realtime counter at the same instant and the
offset in effect. When two sweeps disagree — the wall clock moved by an amount
that the elapsed-realtime delta does not account for — the later sweep is marked
**clock-suspect**. Clock-suspect sweeps still record their episodic page, but
they **do not advance hour-of-day learning**: the sweep-hour and change-hour
histograms are left untouched, so a clock jump cannot teach the guardian a false
rhythm.

Name the honest limitations. There are two.

The monotonic elapsed-realtime clock resets to zero on reboot. Across a reboot
there is no monotonic reading to compare against, so a clock manipulation
performed while the device is off — or in the window spanning a reboot — is
**not detectable by this method**. We do not claim otherwise.

The check is also per-sweep, against the immediately preceding sweep. A clock
moved by less than the skew tolerance between two sweeps drifts without ever
being marked suspect. Anchoring to a fixed origin instead would not close this:
the anchor's monotonic reading is invalidated by the next reboot, which an
attacker controls. We accept the bound rather than build a mitigation that does
not hold, and rely on the immutable rules of lane 5, which never consult the
learned hour histograms.

The guarantee is therefore narrow and precise: within a single boot session,
wall-clock motion that is inconsistent with elapsed time by more than the
tolerance is caught and quarantined from temporal learning. A signal that
changed is still recorded; only the *when* is refused. That asymmetry is
deliberate — losing a change record would blind the guardian, whereas losing a
timestamp only blunts one heuristic.

## Spatial awareness without location logging

This is a first-class principle, not a feature note. The guardian may legitimately
become spatially aware. Two examples worth detecting:

- An app reading location in a place the device has never been.
- SSID and timezone offset shifting together — a coherent travel event rather
  than either changing alone.

Both are real signals of interest. But the guardian **must not persist raw
coordinates.** It may derive only anonymous place-cluster tokens or low-precision
geohash prefixes — enough to say "somewhere new" or "the usual place," never
enough to reconstruct a movement history. A privacy guardian that keeps a precise
record of where its user has been is a contradiction in terms; the value of the
detector can never be worth becoming the thing we protect against.

Because the safe representation — the place-cluster tokens — does not exist yet,
the spatial detector is **deliberately deferred**. We do not ship a location-aware
detector that logs coordinates "for now." The detector waits until the clustering
that lets it forget the coordinates is built.

## Rejected alternatives

We considered several richer designs from the recent memory-systems literature.
Each is rejected below with the reason and, where we have it, the number that
settled the question. The papers get credit for what they got right.

### On-device embeddings / vector search

Candidates: EmbeddingGemma-300M with sqlite-vec, or ObjectBox HNSW. Rejected.
This ships a second always-loaded model of roughly 200–300 MB to solve a recall
problem that keyword search already wins, and that model is dead weight on the
rules-only tier — it cannot run there at all, so any capability built on it
violates the degradation contract. The GAM ablation (arXiv 2511.18423) reports
BM25 alone at 48.6 average F1 versus dense embedding alone at 32.3: on this kind
of workload, lexical retrieval is not a compromise, it is the stronger method.
Lane 3's FTS index is the whole of what we need.

### LLM-maintained memory

Candidates: MIRIX (arXiv 2507.07957), with one router plus up to six manager LLM
calls per write; MAGMA (arXiv 2601.03236), with LLM-inferred causal and entity
edges; and GAM's (arXiv 2511.18423) agentic Researcher loop. Rejected. Every one
requires a model on the write path, and the model is often absent on our target
hardware. Worse, GAM's own ablation shows the agentic researcher collapses below
7B — 9.1 average F1 at 0.5B against 53.2 at 14B — and MiniCPM-1B sits far below
that floor. An LLM-maintained memory would be at its worst exactly where Mink
runs.

Give GAM its due on the point that shaped our design: it found the *Memorizer*
barely depends on model size, even as the Researcher collapses. The write path
can be cheap and dumb. That is precisely why ours is deterministic — the
expensive, size-sensitive intelligence is confined to lane 6's read path, where
its absence degrades gracefully instead of corrupting stored state.

### Count-min sketch / HyperLogLog / Bloom filters

Rejected. These structures are calibrated for millions of events, where exact
counting is infeasible and bounded approximation error is the price of
tractability. Mink has about 30 signals and tens to low hundreds of apps. At that
scale an exact `HashMap` is smaller, faster, and error-free; the sketches would
only inject approximation error to solve a problem we do not have. Lane 2's exact
per-signal map (`Map<String, SignalStats>` today) is the right structure.

### The information-geometric stack of SuperLocalMemory V3

Candidate: SuperLocalMemory V3 (arXiv 2603.14588) — Fisher-Rao geodesics, a
Poincaré/Langevin lifecycle, sheaf cohomology for consistency, Hopfield
retrieval. Rejected as a stack. The paper itself concedes that the
Langevin/hyperbolic layer is not present in its evaluated system and that the
sheaf-cohomology check collapses in practice to a scalar threshold. Its own
ablation shows a cross-encoder reranker does far more work (−30.7 pp when removed)
than the Fisher machinery (−10.8 pp). The elaborate geometry is not what carries
the results.

What survives is the principle, and we keep it: inverse-variance precision
weighting with a warm-up ramp. That is exactly lane 2's precision weight. Be fair
about the headline, too. SuperLocalMemory V3's "zero-LLM" claim is materially
caveated: its true zero-LLM mode scores 60.4%, and the headline figure of roughly
75% is reached only by piping retrieved facts to a cloud model for synthesis.
Our zero-model tier is genuinely zero-model, which is the whole point of the
degradation contract — so we take the precision-weighting idea and leave the
headline behind.

### Other rejected mechanisms

- **BOCPD** (Bayesian online change-point detection): rejected in favour of
  CUSUM, which dominates at O(1) per observation for the change-detection job
  lane 2 needs. BOCPD's run-length posterior buys resolution we do not use.
- **Blanket t-digest thresholds**: rejected because they are meaningless for
  categorical signals. A quantile threshold assumes an ordered distribution;
  signals like SSID or a permission set have no percentile, so a t-digest over
  them produces a number with no meaning.

## Consequences

The choice of store per lane is forced by a single fact about Preferences
DataStore: **it rewrites the entire blob atomically on every commit.** For the
current single-digest baseline that is fine — the blob is bounded and rewritten
in full each sweep anyway. But an append-only episodic log on top of DataStore
would cost O(n) bytes written per sweep, where n is the whole history: flash
wear, battery drain, and commit latency all scaling with how long the guardian
has been running. That is the wrong asymptotics for a log that only ever grows.

Therefore lanes 3 and 4 require SQLite, where a row insert is O(1) regardless of
history size. The requirements on that SQLite layer:

- **Encrypted at rest** — SQLCipher, or a Keystore-wrapped `EncryptedFile`. The
  episodic log and entity graph are more revealing than the digest they replace,
  so they must not sit in plaintext.
- **A `schemaVersion` per lane, with forward migrations.** Each lane evolves
  independently; a version stamp and a migration path per lane keep an upgrade
  from silently corrupting stored history.

Two further consequences hold regardless of store:

- **The memory must be user-inspectable and user-purgeable.** A privacy tool
  whose memory the user cannot see or erase is itself a privacy problem. Every
  lane needs a read path to the UI and a delete path the user controls.
- **A device compromised at install poisons every statistical baseline from
  t = 0.** Lanes 2–4 learn "normal" from what they observe; if the device was
  already compromised when learning began, they learn the compromise as normal
  and will never flag it. In that case only lane 5's immutable rules can fire —
  they encode what is wrong independently of what this device happens to have
  seen. This is the deepest reason lane 5 exists and is never runtime-writable.

## Status

What has landed:

- **Schema versioning** and exception-safe reads in `GuardianStore` (every read
  returns a sane empty default on parse failure).
- **Clock-trust gating** in principle in the analyzer's injected-time design
  (`Baseline.kt` is pure, with `nowMs` and `ZoneId` injected), the substrate the
  clock-suspect rule builds on.
- **Encryption at rest** for guardian data, and exclusion of guardian data and
  the model cache from cloud backup (see the Privacy posture section of
  `docs/ARCHITECTURE.md`).
- **The lane-2 baseline**: `GuardianBaseline` / `SignalStats` with change
  counts, value-hash LRU, change-timestamp ring, hour-of-day histograms, stable
  anchor and expected-volatile classification, flap detection, and the
  `BaselineSummary` / `rhythmDigest` surfaces.
- **Lane 4's first persisted node**: the app-access snapshot
  (`AppAccessSnapshot`), persisted encrypted through `GuardianStore` and diffed
  across sweeps by `diffAppAccess` to raise capability-gain observations and
  alerts. The wider app entity graph is still ahead, but the app is now a
  remembered, diffable entity keyed on package name.
- **Lane 5's first immutable rule**: a newly installed app holding camera +
  microphone + location together always raises a CRITICAL alert
  (`SURVEILLANCE_COMBO` in `AppAccessWatch`). Like every lane-5 rule it is not
  runtime-writable — no learned state, user feedback, or future refiner may tune,
  weaken, or disable it.
- **Lane 4's second behavioural event source**: sensor-in-use sessions
  (`SensorInUseMonitor` / `SensorSessionTracker`), near real-time camera and
  microphone use observed through anonymised platform callbacks. Lane 5 is
  unchanged: the screen-off rules are ordinary deterministic rules, deliberately
  not immutable — the microphone rule has a duration floor precisely because
  hotword assistants legitimately blip the mic.
- **Lane 5's enforcement hook in configuration**: immutable-rule alerts carry
  `fromImmutableRule` (`GuardianAlert`), and the notification gate
  (`AlertPolicy.kt`) exempts them from the alertness dial, the per-source
  mutes, and the repeat cooldown — user configuration can never silence them.
- **Lane 4's security-posture watch**: a per-sweep watch over six
  device-compromise surfaces — enabled accessibility services and notification
  listeners, active device admins, user-added CA certificates, the default-app
  roles, and a device-wide VPN (`HighRiskScanner` / `diffHighRisk` /
  `HighRiskGuard`), persisted as an encrypted, schema-versioned
  `HighRiskSnapshot` and diffed across sweeps like the app-access node. Lane 5
  is unchanged: every one of these surfaces has a legitimate use, so all
  findings are ordinary WARNINGs under the alertness dial and a new per-source
  mute, with no new immutable rule — deliberately, since a never-tunable
  CRITICAL would misfire (a cross-surface spyware combo is a candidate future
  immutable rule; see `HighRiskGuard`).

What is designed but not built:

- **Lanes 3 and 6** — the sweep-page log and the context assembler — and the
  fuller **lane-4 app entity graph** beyond its first persisted node.
- **The spatial detector**, deferred until place-cluster tokens exist.
- **The Beta-trust refiner** — the Beta(α, β) feedback-trust thresholds of
  lane 5.
- The continuous **precision weight, EWMA, CUSUM, and warm-up ramp** that
  extend lane 2 beyond its current discrete thresholds.
