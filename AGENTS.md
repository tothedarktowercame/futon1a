# Codex Task: Review Fixes (S1–S5, M1–M3)

The critical fixes (C1–C3) have been applied in this commit. The remaining
significant and minor items from the review are listed below for Codex.

Run `clj-kondo --lint src/ test/` and `clj -X:test` after each change.

---

## Significant

### S1. `routes/write` only catches `ExceptionInfo` (`api/routes.clj:35`)

Add an outer `(catch Exception e ...)` that returns a generic 500 response with
the exception message. Without this, a `RuntimeException` or `IOException` from
a store implementation escapes unhandled instead of returning a structured error
body.

### S2. `error->response` fragile on unexpected ex-data (`api/errors.clj`)

If `ex-data` lacks an `:error` key the response body is all nils. Add a guard:
if `error` is nil, return `{:status 500 :body {:error {:reason :unknown}}}` (or
similar).

### S3. Docstring warning on `durable-write!` catch block (`core/xtdb.clj:70-73`)

The `catch Exception` in `durable-write!` wraps everything as Layer 0. This is
correct today because the pipeline runs higher-layer checks outside of
`durable-write!`, but if `write-fn` ever calls code that throws with layer info,
that info is lost. Add a docstring note or, better, check whether the caught
exception already carries `:error/layer` and re-throw it unwrapped if so.

### S4. Consolidate `layer2-error` to one definition

`layer2-error` is defined independently in three files:

- `core/rehydrate.clj` (public)
- `core/entity.clj` (private)
- `core/invariants.clj` (public)

Pick one canonical location (suggest `core/invariants.clj` since it's the
cross-layer module) and have the other two `require` it. Delete the duplicates.

### S5. `counter-ratchet` silently passes on non-numeric inputs (`core/invariants.clj`)

`(counter-ratchet {:prev nil :next 0 :label :entities})` returns `{:ok? true}`.
Either throw on non-numeric `prev`/`next`, or document the "only enforce when
both are numeric" behavior. Add tests for nil and non-numeric inputs.

---

## Minor

### M1. Module map is stale (`docs/module-map.md`)

- Layer 4 lists `model/registry.clj` — actual file is `model/validation.clj`.
- Missing from the tables: `core/pipeline.clj`, `api/routes.clj`, `api/errors.clj`.
- Test harness mapping omits `test/layer3`, `test/layer4`, `test/api`, `test/stress`.

Update the tables to match the current repo layout.

### M2. Missing edge-case tests

Add tests for these cases:

**Layer 3 (`test/layer3/penholder_test.clj`):**
- Whitespace-only penholder `"   "` treated as nil -> 403
- Empty `allowed-penholders #{}` always rejects

**Layer 4 (`test/layer4/model_validation_test.clj`):**
- `nil` model
- Non-map model (e.g., a string)

**Entity (`test/layer2/entity_test.clj`):**
- `nil` entity map

**Counter-ratchet (`test/invariants/counter_ratchet_test.clj`):**
- `nil` inputs
- `{:prev 0 :next 0}` (equal at zero)

**Pipeline (`test/api/write_test.clj` or new `test/cross_layer/pipeline_order_test.clj`):**
- Request that fails both L4 and L3 returns 400 (not 403), proving ordering
- Identity conflict (L1) surfaces as 409 through `routes/write`
- Entity error (L2) surfaces as 500 through `routes/write`

### M3. Delete placeholder test files

These are empty shells left over from Phase 0. They can be removed now that real
tests exist:

- `test/futon1a/layer0/placeholder_test.clj`
- `test/futon1a/layer1/placeholder_test.clj`
- `test/futon1a/layer2/placeholder_test.clj`
- `test/futon1a/cross_layer/placeholder_test.clj`
- `test/futon1a/invariants/placeholder_test.clj`

(Check each — if any contain real tests, keep them.)

---

## Files touched by this commit (do NOT modify)

These files were changed in the critical-fix commit. Do not re-modify them
unless a test fails:

- `src/futon1a/core/pipeline.clj` — layer ordering fixed (C1, C2)
- `test/futon1a/cross_layer/error_hierarchy_test.clj` — L0 fields verified, L3/L4 added (C3)
