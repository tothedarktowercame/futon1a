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

### Open issues for Codex

- **Namespace/filename mismatch** — clj-kondo reports errors like
  `Namespace name does not match file name: futon1a.core.xtdb` because the
  ns includes the `futon1a.` prefix but files sit directly under `src/core/`.
  Either move files to `src/futon1a/core/` or drop the prefix from ns decls.
  This affects all src and test files.

- **No `deps.edn`** — Tests can't run yet. Next step should include a
  `deps.edn` with at minimum `org.clojure/clojure` so the test harness is
  executable.

- **Use `clj-kondo`** — It's installed (`clj-kondo v2026.01.19`). Run
  `clj-kondo --lint src/ test/` before committing to catch syntax errors
  like the extra paren and forward references. Consider adding a
  `.clj-kondo/` config and/or a pre-commit hook.

- **nil write-fn** — `durable-write!` silently skips when `write-fn` is nil.
  A durability gate with no write is suspicious; consider throwing instead.
