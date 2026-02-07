# Layer 0: Durability Gate

This module defines the Layer 0 durability interface and a proof-path wrapped
write gate. It is a stub until XTDB integration is added.

## Interface

- `DurableStore` protocol
  - `submit-tx!` submits tx ops and returns a tx-id.
  - `tx-sync!` blocks until the given tx-id is durably confirmed.

## Durable Write

`durable-write!` wraps a write with the proof-path sequence. It calls
`submit-tx!` with tx ops from `write-fn`, then `tx-sync!` to confirm durability.
The result includes:

- `:tx-id` (string)
- `:path` (proof-path event log)

If `:proof-log-path` is provided, the proof-path is appended as a single EDN
line to the file.

## XTDB Adapter

`src/futon1a/core/xtdb_node.clj` provides an XTDB-backed `DurableStore`
implementation (`XtdbStore`) with `submit-tx!` and `tx-sync!`.

## Errors

Layer 0 failures surface as 503 with `:error/layer 0`.
