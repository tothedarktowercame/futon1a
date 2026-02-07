# futon1a Module Map

This module map operationalizes the mission's Pattern → Code mapping and establishes the target repo layout.

## Core Layers

| Layer | Module | Responsibility | Patterns |
|------:|--------|----------------|----------|
| 0 | `src/futon1a/core/xtdb.clj` | Durable storage, write gate, tx sync | `storage/durability-first`, `storage/durability-throughput-gate` |
| 1 | `src/futon1a/core/identity.clj` | UUID identity, external-id uniqueness | `storage/identity-uniqueness`, `storage/identity-flex-uniqueness` |
| 2 | `src/futon1a/core/rehydrate.clj` | Startup hydration + integrity gate | `storage/all-or-nothing-startup`, `storage/startup-integrity-gate` |
| 2 | `src/futon1a/core/entity.clj` | Entity/relation integrity checks | `storage/graph-memory-contract` |
| 3 | `src/futon1a/auth/penholder.clj` | Authorization / penholder enforcement | `storage/guardrails-vs-tooling` |
| 4 | `src/futon1a/model/registry.clj` | Model/schema registry + migrations | `storage/schema-evolution-stability` |

## Supporting Modules

| Module | Responsibility | Patterns |
|--------|----------------|----------|
| `src/futon1a/core/invariants.clj` | Global invariant checks + counter-ratchet | `storage/invariants-vs-repair` |
| `src/futon1a/core/mirror.clj` | Datascript ↔ XTDB mirroring | `storage/persistence-speed-mirroring` |
| `src/futon1a/core/xtdb_node.clj` | XTDB adapter for durability | `storage/durability-first` |
| `src/futon1a/diag/health.clj` | Diagnostics + error context | `storage/rapid-debugging` |
| `src/futon1a/ingest/open_world.clj` | Open-world ingest + validation | `storage/open-world-velocity-validation` |
| `src/futon1a/api/errors.clj` | Error mapping to layers | `storage/error-layer-hierarchy` |
| `src/futon1a/api/routes.clj` | HTTP API surface | `storage/canonical-interface` |
| `src/futon1a/diag/proof_path.clj` | Proof-path event logging | `futon-theory/proof-path`, `futon-theory/event-protocol` |

## Test Harness Mapping

| Test Suite | Scope |
|------------|-------|
| `test/futon1a/layer0` | Durability gates and XTDB sync |
| `test/futon1a/layer1` | Identity + external-id uniqueness |
| `test/futon1a/layer2` | Integrity + rehydration |
| `test/futon1a/invariants` | Counter-ratchet + invariant proofs |
| `test/futon1a/cross_layer` | Error propagation and layer ordering |

## Notes

- This map is the baseline for Part I gate requirements.
- Update this file alongside any new module additions.
