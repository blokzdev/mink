# HUMAN.md — working with Claude on Mink

The human-facing companion to [CLAUDE.md](CLAUDE.md). It explains how Claude —
your AI co-engineer on Mink — actually operates, so you can steer it well and
know what to expect.

## The working model

- **One PR at a time, each green before the next.** Substantial work is broken
  into a sequence of small PRs. Every PR lands with the build, the full test
  suite, and lint green, and is reviewed before the next one starts. The
  sequencing is deliberate: later PRs build on merged foundations, not stacked
  unmerged branches.
- **Design before code for anything substantial.** An approach is proposed —
  often by an independent multi-agent panel that scores competing designs — you
  approve the direction, then it's built. Accepted designs live in `docs/design/`.
- **Adversarial review is standard.** Before a PR merges, its diff is reviewed
  across several dimensions (correctness, parity, concurrency, tests, security…),
  and each finding is refuted through three independent lenses: does the
  mechanism actually exist, can the inputs actually arise, and does it matter for
  Mink's priorities. Only findings that survive are treated as real. When the
  automated panel can't finish (e.g. a usage cap), Claude hand-adjudicates and
  says so. The full reasoning is in each PR's review comment.
- **Honesty over polish.** Claude reports what actually happened — failing tests,
  skipped steps, deviations from parity, known gaps — plainly, and flags any
  deviation from a stated contract in the PR rather than burying it. The same
  grounding discipline the product enforces on the model, Claude applies to its
  own claims.

## What Claude brings to you rather than assuming

- **Merges.** Claude lands each PR reviewed and green and opens it against `main`.
  The `Bash(gh pr *)` allow in `.claude/settings.local.json` currently lets it
  merge autonomously; remove that entry if you want the merge gate back. (Claude
  cannot grant itself permissions — editing that file is blocked by design.)
- **Genuine forks and irreversible/outward actions.** A real change in direction,
  or anything destructive, comes to you rather than being assumed.
- **Out-of-scope issues it notices.** These become tracked task chips rather than
  creeping into the current PR.

## How to steer

- Point Claude at a goal; it will scout the code, propose an approach, and check
  in only on genuine forks. You don't need to spell out every step.
- "Proceed autonomously" lets it run the design → review → PR loop without
  pausing for confirmation on reversible steps.
- Ask for the TLDR any time — the first line of a report answers "what happened."
- Durable context (conventions, decisions, current state) lives in Claude's
  memory and in [CLAUDE.md](CLAUDE.md); the living plan is
  [docs/ROADMAP.md](docs/ROADMAP.md).

## Where the project stands

The product is at [Loupe](https://github.com/blokzdev/loupe) (iOS) parity plus
behavioural watches iOS can't do and an on-device guardian. The current thread is
reorganizing that guardian around the grounding pass and four-mode routing — see
[docs/ROADMAP.md](docs/ROADMAP.md) for shipped and open work, and
[docs/design/guardian-core-refactor.md](docs/design/guardian-core-refactor.md)
for the refactor in flight.
