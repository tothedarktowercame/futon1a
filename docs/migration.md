# Migrations

Schema evolution is handled via registered migration steps.

Module: `src/futon1a/model/migration.clj`.

## Phase II: futon1 -> futon1a Storage Migration

futon1 typically runs XTDB backed by LMDB; futon1a runs XTDB backed by RocksDB.
Because those backends are not storage-compatible, migration must be a logical
export/import (read docs from the source node and re-`put` them into the
destination node), not a directory copy.

Tooling:
- `src/futon1a/scripts/migrate_futon1.clj` (run with `:migrate` alias)
