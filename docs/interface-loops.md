# Interface Loops

Each layer boundary exposes an interface loop: input → validation/error →
context for repair. The loop is closed when the response includes enough
context to act without spelunking logs.

## Boundaries

- **L4 (Model validation)**: missing required fields return `:field` context.
- **L3 (Authorization)**: forbidden penholder returns `:penholder` context.
- **L2 (Integrity)**: missing IDs or relation endpoints return `:field`/`:missing`.
- **L1 (Identity)**: invalid UUID returns `:id` context; external-id conflict returns `:external-id`.
- **L0 (Durability)**: storage failure returns diagnostic `:message`.

## Proof

`test/futon1a/cross_layer/interface_loop_test.clj` asserts each boundary’s
error response includes the required context.
