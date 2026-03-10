# futon1a

`futon1a` is a ground-up rebuild of `futon1`: a deterministic storage substrate
with explicit invariants, a canonical HTTP interface, and traceable
pattern-to-code grounding. It is the flagship example of a futonic artifact in
this stack: a repo built from a mission, with the mission, evidence, PSR/PUR
records, docs, code, and tests all meant to line up.

## What it is

- XTDB-backed storage with explicit durability, identity, integrity, authorization, and validation gates.
- A read/write HTTP surface that higher futons can depend on without scraping internals.
- A worked example of futonic development discipline, centered on [M-futon1a-rebuild](/home/joe/code/futon3/holes/missions/M-futon1a-rebuild.md).

## What it is not

- It is not a full, current implementation of everything in [futon1.devmap](/home/joe/code/futon3/holes/futon1.devmap).
- It is not the old `futon1` two-store design. For Prototype 1, `futon1a` deliberately dropped the Datascript+XTDB mirroring arrangement and stayed XTDB-only, with the Datalog/cache question deferred.

## Core invariants

| Layer | Invariant | Statement |
|------|------|------|
| I0 | Persistence | What you save is what you get back |
| I1 | Identity | One entity per identity, no ambiguity |
| I2 | Integrity | Startup succeeds completely or fails loudly |
| I3 | Hierarchy | Errors surface at the layer that caused them |
| I4 | Debugging | Any bug diagnosable in under 10 minutes |

## Status

`futon1a` is usable and substantial, but its strategic status needs to be stated
more clearly than "in development."

- As an artifact, it is real: the rebuild mission produced a functioning storage substrate with docs, tests, and operational soak material.
- As a roadmap answer, it is incomplete: [futon1.devmap](/home/joe/code/futon3/holes/futon1.devmap) still describes a broader FUTON1 shape, especially around Datascript mirroring, graph-memory/query workflows, and related storage ideas that are not fully represented here.

So the open question is not just "does more work on `futon1a` need to happen?" It
is also "which parts of `futon1.devmap` are superseded by `futon1a`, which are
deferred, and which belong elsewhere?"

## Next steps

- Clarify the `futon1a` vs. `futon1.devmap` relationship in writing:
  - what `futon1a` already settles
  - what remains deferred from the old FUTON1 picture
  - whether `futon1a` is considered finished as an exemplar, active as a product, or both
- Revisit the deferred store question:
  - stay XTDB-only
  - or reintroduce a Datalog/Datascript layer later, based on explicit evaluation rather than inertia
- Consider writing up the `futon1a` development journey as a blog post or retrospective, since it is an important worked example of the futonic method

## References

- Mission: [M-futon1a-rebuild](/home/joe/code/futon3/holes/missions/M-futon1a-rebuild.md)
- Work Plan: [M-futon1a-workplan](/home/joe/code/futon3/holes/missions/M-futon1a-workplan.md)
- Evidence: [M-futon1a-evidence](/home/joe/code/futon3/holes/missions/M-futon1a-evidence.md)
- Devmap context: [futon1.devmap](/home/joe/code/futon3/holes/futon1.devmap)
- Soak runbook: [soak-operations.md](/home/joe/code/futon1a/docs/soak-operations.md)
- Soak evidence log: [soak-2026-03-02-to-2026-04-01.md](/home/joe/code/futon1a/docs/evidence/soak-2026-03-02-to-2026-04-01.md)
- Best-practice note: [README-best-practice.md](/home/joe/code/futon1a/README-best-practice.md)
