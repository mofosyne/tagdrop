# QDEF — Quick Data Exchange Format

This file used to be a full copy of QDEF's spec, synced manually from
`mofosyne/qdef`. That copy went stale (missing the payload-slot/optional
field Map grammar and the new Bundle Type 0, among other changes) because
nothing kept it in sync automatically — so it's now a pointer instead of a
duplicate, the same anti-drift reasoning already applied to `SPEC.md`
itself elsewhere in this repo.

**The authoritative, always-current QDEF spec lives at:**
[github.com/mofosyne/qdef/blob/main/docs/QDEF-SPEC.md](https://github.com/mofosyne/qdef/blob/main/docs/QDEF-SPEC.md)

Related documents in that repo, for context on *why* the spec looks the
way it does (not needed to implement against it, just useful background):

- [`DESIGN.md`](https://github.com/mofosyne/qdef/blob/main/docs/DESIGN.md) — mechanisms tried and removed, alternatives weighed, what's still unresolved.
- [`FINDINGS.md`](https://github.com/mofosyne/qdef/blob/main/docs/FINDINGS.md) — what round-trip testing against two independent implementations found and changed.

If TagDrop's own QDEF port ever needs to pin against a specific spec
revision rather than always-latest, note the commit hash here instead of
re-copying the file.
