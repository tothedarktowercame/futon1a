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

## Module

Implementation scaffold: `src/diag/proof_path.clj`.
