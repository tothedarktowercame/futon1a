# Changelog

## Review pass — Claude (post 20bdaeb)

### Fixes

- **proof_path.clj: forward reference** — `event-seq` and `complete?` were
  defined after `validate-complete-path` which calls `event-seq`. Moved them
  above so the symbol resolves at compile time.

- **proof_path.clj: extra closing paren** — `validate-path` had 6 closing
  parens where only 5 were needed. clj-kondo caught this as "Unmatched
  bracket" at line 192.

- **xtdb.clj: catch Exception, not Throwable** — `durable-write!` was
  catching `Throwable`, which swallows `OutOfMemoryError`,
  `InterruptedException`, etc. Changed to `Exception`.

### Cleanup

- **Deleted `write_pipeline.clj`** — Its proof-path event sequence was
  duplicated from `xtdb.clj`'s `durable-write!`. It wasn't in the module map
  and was superseded. Updated `proof_path_test.clj` to use
  `xt/durable-write!` with `StubStore` instead of `write/run-write`.

### Resolved by Codex (b55b1aa..0da153a)

- Namespace/filename mismatch — fixed by moving files to `src/futon1a/`
- `deps.edn` added (Clojure 1.11.1, XTDB 1.24.0)
- nil write-fn — now requires `write-fn` unless `allow-noop?` is set

## Review pass 2 — Claude (post 0da153a)

### Fixes

- **proof_path_test.clj: wrong write-fn return** — `(fn [] :ok)` didn't
  match the updated `durable-write!` contract (write-fn should return
  tx-ops vector). Changed to `(fn [] [{:op :noop}])`.

- **identity.clj: add `layer1-error` helper** — Layer 1 throws now use
  `{:error (layer1-error reason context)}` matching Layer 0's
  `{:error/layer 1 :error/status 409 ...}` shape. Needed for cross-layer
  error propagation.

- **rehydrate.clj: add `layer2-error` helper** — All throws now use
  `{:error (layer2-error reason context)}` with
  `{:error/layer 2 :error/status 500 ...}`. Removed unused `res` binding
  flagged by clj-kondo.

### Open items for Codex

- **Use `clj-kondo --lint src/ test/` before committing.** It's installed
  (`v2026.01.19`). It caught the extra paren in round 1 and the unused
  binding in round 2. Consider adding a `.clj-kondo/` config or pre-commit
  hook.

- **`validate-identity` contract** — `existing-by-external` is a truthiness
  gate: any truthy value + an ext-id triggers conflict. Caller must do the
  lookup. Document this clearly or consider renaming the param.

- **`uuid-string?`** returns `true`/`false`/`nil` (mixed). Works in boolean
  contexts but inconsistent for a `?`-suffixed predicate.
