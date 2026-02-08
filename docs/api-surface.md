# API Surface (Stub)

Minimal HTTP surface for futon1a.

## Endpoints

- `GET /health` → `{status: "ok"}`
- `POST /write` → `{tx-id: "..."}`

## Error Mapping

Layer errors are mapped to HTTP responses by `api/errors.clj`.

## Modules

- `src/futon1a/api/errors.clj`
- `src/futon1a/api/routes.clj`
