# Layer 1: Identity Gate

Layer 1 enforces UUID identity and unique external-id mapping before any write.

## Rules

- Entity IDs must be UUIDs.
- External IDs are normalized (trimmed) and must be unique.
- Duplicate external IDs are rejected before write.

## Module

Implementation: `src/futon1a/core/identity.clj`.
