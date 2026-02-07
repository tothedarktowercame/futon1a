# futon1a Module Map

This module map operationalizes the mission's Pattern → Code mapping and establishes the target repo layout.

## Core Layers

| Layer | Module | Responsibility | Patterns |
|------:|--------|----------------|----------|
| 0 | `src/core/xtdb.clj` | Durable storage, write gate, tx sync | `storage/durability-first`, `storage/durability-throughput-gate` |
| 1 | `src/core/identity.clj` | UUID identity, external-id uniqueness | `storage/identity-uniqueness`, `storage/identity-flex-uniqueness` |
| 2 | `src/core/rehydrate.clj` | Startup hydration + integrity gate | `storage/all-or-nothing-startup`, `storage/startup-integrity-gate` |
| 2 | `src/core/entity.clj` | Entity/relation integrity checks | `storage/graph-memory-contract` |
| 3 | `src/auth/penholder.clj` | Authorization / penholder enforcement | `storage/guardrails-vs-tooling` |
| 4 | `src/model/registry.clj` | Model/schema registry + migrations | `storage/schema-evolution-stability` |

## Supporting Modules

| Module | Responsibility | Patterns |
|--------|----------------|----------|
| `src/core/invariants.clj` | Global invariant checks + counter-ratchet | `storage/invariants-vs-repair` |
| `src/core/mirror.clj` | Datascript ↔ XTDB mirroring | `storage/persistence-speed-mirroring` |
| `src/diag/health.clj` | Diagnostics + error context | `storage/rapid-debugging` |
| `src/ingest/open_world.clj` | Open-world ingest + validation | `storage/open-world-velocity-validation` |
| `src/api/errors.clj` | Error mapping to layers | `storage/error-layer-hierarchy` |
| `src/api/routes.clj` | HTTP API surface | `storage/canonical-interface` |
| `src/diag/proof_path.clj` | Proof-path event logging | `futon-theory/proof-path`, `futon-theory/event-protocol` |

## Test Harness Mapping

| Test Suite | Scope |
|------------|-------|
| `test/layer0` | Durability gates and XTDB sync |
| `test/layer1` | Identity + external-id uniqueness |
| `test/layer2` | Integrity + rehydration |
| `test/invariants` | Counter-ratchet + invariant proofs |
| `test/cross_layer` | Error propagation and layer ordering |

## Notes

- This map is the baseline for Part I gate requirements.
- Update this file alongside any new module additions.
