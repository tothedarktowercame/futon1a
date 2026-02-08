# Mirror

Datascript ↔ XTDB mirroring support with an in-memory mirror store. Entities
and relations must have IDs; re-mirroring with a mismatched payload fails.

Key behaviors:
- `mirror-batch!` validates duplicate IDs and returns counts
- `InMemoryMirrorStore` records mirrored entities/relations for fast access

Module: `src/futon1a/core/mirror.clj`.
