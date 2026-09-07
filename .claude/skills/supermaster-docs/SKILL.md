---
name: supermaster-docs
description: "Use whenever starting work in any Gradle module of this TAAL monorepo (read the module's master doc first) and whenever finishing a change that affects how a module works (log it there too) — a feature, a wiring change, a bug fix that changes behavior, a new/renamed/removed file or screen. Also use to create a module's master doc if it doesn't have one yet, or to run a full re-audit of one. Triggers on: starting a task in app/, stemz-app/, lungs-app/, visualizertaal-app/, taal-core/, taal-ui-kit/, taal-segmentation/, taal-segmentation-core/; finishing an edit in one of those; 'update the docs', 'check the master doc', 'supermaster'."
---

# /supermaster-docs

Keeps `docs/master/SUPERMASTER_<MODULE>.md` — one per Gradle module — accurate and current. This
is the concrete procedure behind the rule in this repo's root `CLAUDE.md`; that file states the
policy, this skill is how you actually execute it well, every time, without missing a step.

**Why this exists:** this repo has a long history of docs going stale within days (see any
memory file tagged "Docs Staleness Warning") because updates lived only in commit messages or
scattered `docs/notes/*.md` files nobody re-reads. The per-module files fix that IF they're kept
current — this skill is the enforcement mechanism.

## Module name mapping

`docs/master/SUPERMASTER_<MODULE>.md`, where `<MODULE>` is the `settings.gradle.kts` module
name, upper-cased, hyphens→underscores:

| Gradle module | File |
|---|---|
| `app` | `SUPERMASTER_APP.md` |
| `stemz-app` | `SUPERMASTER_STEMZ_APP.md` |
| `lungs-app` | `SUPERMASTER_LUNGS_APP.md` |
| `visualizertaal-app` | `SUPERMASTER_VISUALIZERTAAL_APP.md` |
| `taal-core` | `SUPERMASTER_TAAL_CORE.md` |
| `taal-ui-kit` | `SUPERMASTER_TAAL_UI_KIT.md` |
| `taal-segmentation` | `SUPERMASTER_TAAL_SEGMENTATION.md` |
| `taal-segmentation-core` | `SUPERMASTER_TAAL_SEGMENTATION_CORE.md` |

Root `docs/../SUPERMASTER.md` (repo root, no module suffix) is the whole-monorepo index —
skim it for orientation across modules, but it is NOT where module-specific detail belongs.

If a task touches files under more than one module's source tree, this applies to **every**
module touched, independently — do not merge unrelated modules' logs into one entry.

## Mode 1 — Starting work in a module

1. Identify which module(s) the task's target files live under (map the path yourself; a
   session that only touched `stemz-app/src/...` only needs that module's file).
2. Read that module's `docs/master/SUPERMASTER_<MODULE>.md` in full before reading or editing
   its source. If it doesn't exist yet, skip to **Mode 3** first, then continue.
3. Treat it as more current than any older doc (root `SUPERMASTER.md`, `docs/notes/*.md`,
   `CODEBASE_MAP.md`, memory files) wherever they disagree — but still spot-check anything
   you're about to rely on heavily against live source; these files say when they were last
   verified, and code moves faster than docs.

## Mode 2 — After finishing a change

Do this before ending the turn/session, for every module you changed anything in:

1. **Append a dated row to the `## Changelog` table** — today's date, a one-line description of
   what changed, and a `Notes` cell pointing at the section you updated or a commit hash if one
   exists. Never skip this even for a "small" change; small changes are exactly what gets lost.
2. **Update whichever main section the change actually affects** — architecture, feature
   inventory, wiring/data-flow, known issues — so the file reads correctly as "how this module
   works right now," not just "here's a history of edits." A changelog entry alone is not
   enough if the file's main body would now be misleading to someone who only reads that.
3. **Cite file:line** for anything specific wherever practical. These files exist so a future
   session can act on them with zero other context — an unverifiable claim is worse than no
   claim. If you didn't verify something against current source, say so explicitly.
4. **Bug found but not fixed?** Log it under the file's "Known Issues / Gotchas" section anyway,
   with a file:line pointer — don't wait until it's fixed to write it down.
5. **Cross-module change** (e.g. a fix ported from `app` to `stemz-app`, or a shared `taal-core`
   API change affecting every consumer app): update every affected module's file, and
   cross-reference them ("ported from X, see X's changelog entry dated Y" / "consumers of this
   change: A, B, C").

## Mode 3 — Creating a module's file (doesn't exist yet)

1. Pick an existing file as a structural template — `SUPERMASTER_STEMZ_APP.md` and
   `SUPERMASTER_APP.md` are both solid, complete examples (module overview → config → nav/wiring
   → feature-by-feature detail → data layer → known issues/gotchas → changelog).
2. Read the module's actual source — `build.gradle.kts`, manifest, nav graph, every top-level
   package — don't summarize from memory or from an older doc; verify directly. Cite file:line.
3. Explicitly call out anything ambiguous or genuinely unverified rather than presenting a guess
   as fact — a future session needs to know the difference.
4. First changelog row: `<date> | Initial SUPERMASTER_<MODULE>.md created | Full source audit`.
5. If this is a large module, this is a good candidate to delegate to a fresh general-purpose
   agent (or several in parallel, one per module) rather than doing the research inline — that's
   how the first batch of these files was built (2026-09-08). Give the agent this skill's
   template structure and the module's path, and tell it to write the file directly.

## Mode 4 — Full re-audit of one module's file

Use when a file is suspected badly stale (long time since its last changelog entry, or a lot of
undocumented drift). Re-run Mode 3's research process against the EXISTING file instead of from
scratch: verify every claim in it against current source, correct what's wrong, add what's
missing, and add a changelog row noting the re-audit and what it changed/corrected.

## What NOT to do

- Don't let this replace normal git commit messages — it's in addition to them, not instead.
- Don't write vague changelog rows ("updated stuff") — say what changed and where.
- Don't copy another module's file's claims into a different module's file without verifying
  them independently; these modules diverge in real, specific ways (see any file's own
  "Known Issues" section for examples already caught this way).
