# API Surface

Minimal HTTP surface for futon1a.

## Endpoints

- `GET /health` → `{:status :ok|:degraded, :counts {...}, :checks {...}}`
- `POST /write` → `{:tx-id "..."}`
- `POST /models` → register model descriptor
- `GET /models` → list model ids
- `POST /ingest` → open-world ingest (requires `store`, `penholder`, `allowed-penholders`, `tx-ops`)
- `POST /repair` → repair entities
- `POST /repair/verify` → verify repair outcomes

## Futon1 / Arxana Compatibility

These endpoints exist to support `futon4/dev/arxana-store.el` and futon3 bridge
clients. They are served under both `/api/alpha/*` and `/api/*` (alias) where
noted.

- `GET /healthz` → health alias
- `POST /api/alpha/entity` (alias: `POST /api/entity`) → futon1-shaped entity upsert
- `POST /api/alpha/relation` (alias: `POST /api/relation`) → futon1-shaped relation upsert
- `POST /api/alpha/relations/batch` (alias: `POST /api/relations/batch`) → batch relation upsert
- `POST /api/alpha/hyperedge` (alias: `POST /api/hyperedge`) → Arxana hyperedge upsert
- `POST /api/alpha/media/lyrics` (also: `POST /api/media/lyrics`) → media lyrics upsert
- `POST /api/alpha/snapshot` (alias: `POST /api/snapshot`) → export graph docs to `<data-dir>/snapshots/`
- `POST /api/alpha/snapshot/restore` (alias: `POST /api/snapshot/restore`) → restore graph docs via open-world pipeline
- `POST /api/alpha/lab/session` and `GET /api/alpha/lab/session/:id` → futon3 lab session persistence
- `GET /api/alpha/entity?source=S&external-id=E` (alias: `GET /api/entity?...`) → external-id lookup

## Error Mapping

Layer errors are mapped to HTTP responses by `api/errors.clj`.

## Modules

- `src/futon1a/api/errors.clj`
- `src/futon1a/api/routes.clj`
