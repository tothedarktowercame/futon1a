# Layer 0: Durability Gate

This module defines the Layer 0 durability interface and a proof-path wrapped
write gate. It is a stub until XTDB integration is added.

## Interface

- `DurableStore` protocol
  - `tx-sync!` blocks until a durable commit is confirmed and returns a tx-id.

## Durable Write

`durable-write!` wraps a write with the proof-path sequence and calls
`tx-sync!` to confirm durability. The result includes:

- `:tx-id` (string)
- `:path` (proof-path event log)

If `:proof-log-path` is provided, the proof-path is appended as a single EDN
line to the file.

## Errors

Layer 0 failures surface as 503 with `:error/layer 0`.
