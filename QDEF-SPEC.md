# QDEF — Quick Data Exchange Format

This file used to be a full copy of QDEF's spec, synced manually from
`mofosyne/qdef`. That copy went stale (missing the payload-slot/optional
field Map grammar and the new Bundle Type 0, among other changes) because
nothing kept it in sync automatically — so it's now a pointer instead of a
duplicate, the same anti-drift reasoning already applied to `SPEC.md`
itself elsewhere in this repo.

**The authoritative, always-current QDEF spec lives at:**
[qdef-format.github.io](https://qdef-format.github.io/index.html) —
in particular [spec.html](https://qdef-format.github.io/spec.html) for
the spec itself, and
[tools/validator.html](https://qdef-format.github.io/tools/validator.html)
for the browser validator (a headless CLI wrapper also exists,
`scripts/qdef-validate.js` in the same repo — see CLAUDE.md's version-15
history entry for how TagDrop vendors and uses it locally). The spec's
own markdown source (what `scripts/sync-qdef-spec.sh` mirrors into
`QDEF-SPEC-cached.md`, below) lives at
[github.com/qdef-format/qdef-format.github.io/blob/main/docs/QDEF-SPEC.md](https://github.com/qdef-format/qdef-format.github.io/blob/main/docs/QDEF-SPEC.md).

Related documents on that site, for context on *why* the spec looks the
way it does (not needed to implement against it, just useful background):

- [related-work.html](https://qdef-format.github.io/related-work.html) — how QDEF compares to other formats, and mechanisms tried and removed along the way.
- [implementations.html](https://qdef-format.github.io/implementations.html) — known adopters (including TagDrop) and their own reference implementations.

(Older revisions of this file pointed at `DESIGN.md`/`FINDINGS.md` on
`github.com/mofosyne/qdef` — both the org and those specific documents
are gone; the site above is the current structure.)

If TagDrop's own QDEF port ever needs to pin against a specific spec
revision rather than always-latest, note the commit hash here instead of
re-copying the file.
