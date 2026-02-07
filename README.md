# futon1a

Ground-up reconstruction of futon1, built as a demonstration of futonic best
practices. Storage layer with unbreakable core invariants.

## Dual Goals

1. **Product**: A storage layer where persistence, identity, and integrity are
   guaranteed by design, not hope.
2. **Process**: Demonstration of pattern-based design with full traceability
   from theory to code to test.

## Core Invariants

| Layer | Invariant | Statement |
|-------|-----------|-----------|
| I0 | Persistence | What you save is what you get back |
| I1 | Identity | One entity per identity, no ambiguity |
| I2 | Integrity | Startup succeeds completely or fails loudly |
| I3 | Hierarchy | Errors surface at the layer that caused them |
| I4 | Debugging | Any bug diagnosable in under 10 minutes |

## Architecture

```
Layer 4: Model Validation     ─── 400 Bad Request
    ↑
Layer 3: Authorization        ─── 403 Forbidden
    ↑
Layer 2: Integrity            ─── 500 Internal Error
    ↑
Layer 1: Identity             ─── 409 Conflict
    ↑
Layer 0: Durability           ─── 503 Unavailable
```

Each layer is a gate. If Layer N fails, Layers N+1 never run.

## Pattern Grounding

Every module traces to:
- **Theory pattern** (abstract, from `futon3/library/futon-theory/`)
- **Storage pattern** (concrete, from `futon3/library/storage/`)
- **Git evidence** (commits that drove the design)

See: `futon3/holes/missions/M-futon1a-rebuild.md`

## Development

Built with PSR/PUR discipline:
- Every design decision has a Pattern Selection Record
- Every implementation has a Pattern Use Record
- Records stored in `futon3/holes/labs/futon1a/`

## Status

🚧 **In Development** - Part I (Process) phase

## References

- Mission: `futon3/holes/missions/M-futon1a-rebuild.md`
- Work Plan: `futon3/holes/missions/M-futon1a-workplan.md`
- Evidence: `futon3/holes/missions/M-futon1a-evidence.md`
- Theory: `futon3/library/futon-theory/`
- Storage Patterns: `futon3/library/storage/`
