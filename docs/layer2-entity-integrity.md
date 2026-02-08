# Layer 2: Entity/Relation Integrity

Layer 2 ensures entities and relations are structurally valid before writes are
considered consistent.

## Rules

- Entities require `:entity/id` and `:entity/type`.
- Relations require `:relation/id`, `:relation/from`, `:relation/to`.
- Relation endpoints must refer to existing entities.

## Module

Implementation: `src/futon1a/core/entity.clj`.
