# Layer 2: Integrity / Rehydration Gate

Layer 2 enforces all-or-nothing startup integrity. Rehydration failures abort
startup and return explicit error context.

## Rules

- Rehydration must return a non-empty `:entities` vector.
- `:relations` must be a vector if present.
- If a `validate-fn` is provided, it must return `{:ok? true}`.

## Module

Implementation: `src/futon1a/core/rehydrate.clj`.
