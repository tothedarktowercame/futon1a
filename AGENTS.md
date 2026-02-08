# Codex Task: Prototype 1 — Runnable System

**Goal:** futon1a starts, serves HTTP, persists to XTDB, and survives a restart
cycle with data intact.

**Spec reference:** `futon3/holes/missions/M-futon1a-rebuild.md` — Section 2.6
(Canonical HTTP API) and the Prototype 1 Gate section.

Run `clj-kondo --lint src/ test/` and `clj -X:test` after each change.
All 82 existing tests must remain green (no regressions).

---

## Exit Conditions (all must be true)

1. **XTDB node lifecycle** — `system.clj` starts an embedded XTDB node with
   RocksDB persistence, writes to it, shuts down cleanly. Data dir is
   configurable (default: `data/`).

2. **HTTP ring adapter** — Routes served over HTTP via Ring + Jetty.
   `GET /health` returns 200. `POST /write` with valid payload returns `tx-id`.

3. **Read path** — At minimum `GET /entity/:id` returns an entity by UUID.
   Required for the restart cycle test. (See spec Section 2.6.6.)

4. **System context injection** — `system.clj` injects `:store` and
   `:allowed-penholders` into every request before it reaches the handler.
   HTTP callers do NOT supply `:store`. (See spec Section 2.6.7.)

5. **Restart cycle test** — Automated test: start system → write entity via
   HTTP → stop → restart → read entity back via HTTP → assert data survived.
   Uses real embedded XTDB, not stubs.

6. **No regressions** — All 82 Prototype 0 tests still pass.

---

## Implementation Steps

### 1. Add dependencies to `deps.edn`

```clojure
ring/ring-core {:mvn/version "1.12.2"}
ring/ring-jetty-adapter {:mvn/version "1.12.2"}
metosin/muuntaja {:mvn/version "0.6.10"}     ; content negotiation (EDN/JSON)
metosin/reitit-ring {:mvn/version "0.7.2"}   ; routing
```

Use your judgement on versions — these are suggestions. Reitit is optional;
plain Ring + Compojure or manual routing is fine too.

### 2. Create `src/futon1a/core/system.clj`

Owns the lifecycle of:
- XTDB node (start/stop, config with data dir)
- Ring/Jetty HTTP server (start/stop, port)
- System map that holds both

Provide:
- `(start-system! config)` → returns system map
- `(stop-system! system)` → shuts down cleanly
- Config shape: `{:xtdb {:data-dir "data/"} :http {:port 3000} :allowed-penholders #{"default"}}`

### 3. Create HTTP routing

Wire the existing handlers in `routes.clj` to HTTP paths:

| Method | Path | Handler |
|--------|------|---------|
| GET | `/health` | `routes/health` |
| POST | `/write` | `routes/write` |
| POST | `/ingest` | `routes/ingest` |
| POST | `/models` | `routes/register-model` |
| GET | `/models` | `routes/list-models` |
| POST | `/repair` | `routes/repair` |
| POST | `/repair/verify` | `routes/verify-repair` |
| GET | `/entity/:id` | **New** — read entity by UUID from XTDB |

**Critical:** Middleware must inject `:store` and `:allowed-penholders` from the
system context into each request map before it reaches the handler. The handler
functions currently expect these in the request. HTTP callers provide only
`:penholder`, `:model`, `:identity`, `:tx-ops`, etc.

### 4. Add read handler

Add to `routes.clj` (or a new `api/read.clj`):

```clojure
(defn entity
  "Read an entity by UUID from XTDB."
  [req]
  (with-error-handling
    (let [id (get-in req [:path-params :id])
          store (:store req)
          doc (xtdb/entity store id)]  ; however your store protocol exposes reads
      (if doc
        (ok doc)
        {:status 404 :body {:error {:reason :not-found :context {:id id}}}}))))
```

This is the minimal read path needed for the restart cycle test.

### 5. Create integration test

`test/futon1a/integration/restart_cycle_test.clj`

```
start system (temp data dir)
  → POST /write with entity
  → assert 200, capture tx-id
  → stop system
  → start system (same data dir)
  → GET /entity/:id
  → assert entity matches what was written
  → stop system
  → clean up temp dir
```

Use `clj-http` or raw `java.net.http` for HTTP calls, or call the Ring handler
directly as a function (handler-as-function is acceptable for Prototype 1).

### 6. Wire health to real XTDB

`routes/health` should include an XTDB status check (node is running, can
query). Pass the check function via the system context.

---

## Files to create or modify

| File | Action |
|------|--------|
| `deps.edn` | Add Ring, Jetty, routing deps |
| `src/futon1a/core/system.clj` | **New** — lifecycle |
| `src/futon1a/api/routes.clj` | Add `entity` read handler |
| `src/futon1a/api/handler.clj` | **New** (optional) — Ring handler + routing + middleware |
| `test/futon1a/integration/restart_cycle_test.clj` | **New** — restart cycle |

---

## What NOT to do

- Do NOT add Datascript mirror (XTDB-only for Prototype 1)
- Do NOT add authentication middleware (penholder in body is fine)
- Do NOT add TLS, CORS, or production hardening
- Do NOT modify existing handler logic — only wrap it for HTTP
- Do NOT weaken any invariant or bypass any layer gate
- Do NOT propose "tradeoffs" that skip the read path or the restart cycle test
