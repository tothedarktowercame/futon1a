# Evidence: Futon4 Arxana Non-Interactive QA (2026-02-08)

Goal: exercise Futon4's *standard* client write paths against futon1a over HTTP,
using a disposable persistent store, and verify read-back durability (lyrics)
as well as higher-level write surfaces (batch relations, hyperedge, snapshot).

## Harness

- Runner: `futon4/dev/run-arxana-store-qa.sh`
- QA script: `futon4/dev/arxana-store-qa.el`
- Uses: a fresh futon1a on `127.0.0.1:18080` with data-dir under
  `~/code/storage/futon1a-qa/<timestamp>`

## Endpoints Exercised

- `POST /api/alpha/entity` (also `/api/entity` alias)
- `POST /api/alpha/relation` (also `/api/relation` alias)
- `POST /api/alpha/relations/batch` (also `/api/relations/batch` alias)
- `POST /api/alpha/hyperedge` (also `/api/hyperedge` alias)
- `POST /api/alpha/media/lyrics` (also `/api/media/lyrics`)
- `POST /api/alpha/snapshot` and `POST /api/alpha/snapshot/restore`

## Observed Output (Pass)

```
QA OK: ensure-entity: <uuid> <uuid>
QA OK: create-relation
QA OK: relations-batch
QA OK: hyperedge
QA OK: media/lyrics upsert + readback
QA OK: snapshot save
QA OK: snapshot restore
QA OK: all checks passed
```

## Code Landed

- futon1a commit `388d051` (hyperedge + snapshot endpoints, snapshot storage)
- futon4 commit `06f369d` (QA covers hyperedge + snapshot)

