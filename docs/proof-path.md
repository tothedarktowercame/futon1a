# Proof-Path Event Logging

This document operationalizes Part II §2.1 (Theory Grounding) for futon1a.

## Event Phases

```
CLOCK_IN → OBSERVE → PROPOSE_CLAIM → APPLY_CHANGE → VERIFY
         → INVARIANT_CHECK → PROOF_COMMIT → CLOCK_OUT
```

## Minimum Event Fields

Required:
- `:path/id` (string)
- `:actor` (string)
- `:phase` (keyword)
- `:ts` (ms)

Optional:
- `:claim`
- `:detail`
- `:tx-id`
- `:error`
- `:evidence`

## Validation

- `validate-event` checks required fields and phase validity.
- `validate-path` checks schema plus strict phase ordering.
- `validate-complete-path` requires the full phase sequence.

## Serialization

- `path->edn` returns a single EDN string for the path.
- `append-edn!` appends one path per line to a file.

## Stub Integration

`core/write_pipeline.clj` demonstrates how a write can emit the full proof-path
sequence without touching storage.

## Module

Implementation scaffold: `src/diag/proof_path.clj`.
