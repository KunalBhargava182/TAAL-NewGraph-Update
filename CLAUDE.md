# CLAUDE.md — TAAL SDK Monorepo Session Rules

## Module documentation rule

**The `supermaster-docs` skill (`.claude/skills/supermaster-docs/SKILL.md`) is the concrete
procedure for the rule below — invoke it (or follow it directly) whenever starting work in a
module or finishing a change, rather than relying on remembering this paragraph alone.**

Every Gradle module in this repo has (or should have) a dedicated master reference file:

```
docs/master/SUPERMASTER_<MODULE>.md
```

where `<MODULE>` is the module's name from `settings.gradle.kts`, upper-cased with underscores
for hyphens: `APP`, `STEMZ_APP`, `LUNGS_APP`, `VISUALIZERTAAL_APP`, `TAAL_CORE`, `TAAL_UI_KIT`,
`TAAL_SEGMENTATION`, `TAAL_SEGMENTATION_CORE`, `TAAL_STEMZ_CORE`, `TAAL_STEMZ_UI_KIT`
(the last two are the stemz-only client SDKs; they exist on `StemzAppBranch`).

There is also a repo-wide `SUPERMASTER.md` at the root — read that first for orientation across
the whole monorepo, then read the specific module file(s) for whatever you're about to touch.

### Before starting work in a module

Read that module's `docs/master/SUPERMASTER_<MODULE>.md` **before** reading or editing its
source. If the file doesn't exist yet for a module you're working in, create it before you
finish the session — use an existing one as a template for structure and depth.

### After changing anything in a module

If you add a feature, change how something works, fix a bug that changes behavior, add/remove/
rename a screen or file, or otherwise change what a future session would need to know to work in
that module correctly — **update that module's SUPERMASTER file**, not just the git commit
message. Specifically:

1. Append a dated row to the file's `## Changelog` table (what changed, why, one-line pointer to
   detail elsewhere in the file or to a commit).
2. Update whichever main section the change actually affects (architecture, feature inventory,
   known issues, wiring/data-flow) so the file describes **current reality**, not just history.
   The changelog is a log; the rest of the file must always be readable on its own as "how does
   this module actually work right now."
3. Cite file:line for specific claims wherever practical — these files are meant to be trusted
   and acted on directly by a future session with zero other context, so vague or stale claims
   are worse than no claim at all. If something is genuinely ambiguous or you didn't verify it
   against live source, say so explicitly rather than presenting a guess as fact.

This is in addition to normal commit messages, not a replacement for them.

### Cross-module changes

If a change touches more than one module (e.g. a fix ported from `app` to `stemz-app`, or a
shared SDK API change in `taal-core` that affects every consumer app), update every affected
module's SUPERMASTER file, and note the cross-reference in each ("ported from X, see X's
changelog entry dated Y" / "consumers of this change: A, B, C").
