# API Surface

Minimal HTTP surface for futon1a.

## Endpoints

- `GET /health` → `{:status :ok|:degraded, :counts {...}, :checks {...}}`
- `POST /write` → `{:tx-id "..."}`
- `POST /models` → register model descriptor
- `GET /models` → list model ids
- `POST /ingest` → open-world ingest
- `POST /repair` → repair entities
- `POST /repair/verify` → verify repair outcomes

## Error Mapping

Layer errors are mapped to HTTP responses by `api/errors.clj`.

## Modules

- `src/futon1a/api/errors.clj`
- `src/futon1a/api/routes.clj`
