# Traceability: Evidence → Pattern → Code → Test → Doc → Error

This document satisfies the Part I gate requirement for end-to-end traceability.
Each invariant is traced from futon1 git evidence through the pattern that
motivated it, to the futon1a module that implements it, the tests that prove it,
the documentation that explains it, and the error shape it produces.

Source evidence: `futon3/holes/missions/M-futon1a-evidence.md`
PSR/PUR records: `futon3/holes/labs/futon1a/psr/` and `pur/`

---

## I0: Persistence — "What you save is what you get back"

```
Evidence:    commits 0e2b3a5, 5c51506, 5227d75
             (durability drift, watchdog ordering failures)
Pattern:     storage/durability-first, storage/durability-throughput-gate
Theory:      futon-theory/durability-first
PSR:         2026-02-07__layer0-xtdb__durability-gate.md
Module:      src/futon1a/core/xtdb.clj (DurableStore protocol, durable-write!)
             src/futon1a/core/xtdb_node.clj (XTDB adapter)
Tests:       test/futon1a/layer0/durability_gate_test.clj
             test/futon1a/invariants/proof_path_test.clj
             test/futon1a/stress/durability_stress_test.clj
             test/futon1a/integration/xtdb_integration_test.clj
             test/futon1a/integration/arxana_compat_write_http_test.clj
Doc:         docs/layer0-durability.md
             docs/evidence/arxana-store-qa-2026-02-08.md
Error:       {:error/layer 0, :error/status 503, :error/reason :durability-failure}
```

## I1: Identity — "One entity per identity, no ambiguity"

```
Evidence:    commits d1d447d, 1bda8f7
             (duplicate entities from ambiguous external IDs)
Pattern:     storage/identity-uniqueness, storage/identity-flex-uniqueness
Theory:      futon-theory/single-source-of-truth
PSR:         2026-02-07__layer1-identity__uuid-uniqueness.md
Module:      src/futon1a/core/identity.clj (validate-identity)
Tests:       test/futon1a/layer1/identity_test.clj
             test/futon1a/stress/identity_stress_test.clj
Doc:         docs/layer1-identity.md
Error:       {:error/layer 1, :error/status 409, :error/reason :invalid-id}
             {:error/layer 1, :error/status 409, :error/reason :external-id-conflict}
```

## I2: Integrity — "Startup succeeds completely or fails loudly"

```
Evidence:    commits 884df26, c5ffd75
             (partial rehydration, hidden inconsistency)
Pattern:     storage/all-or-nothing-startup, storage/startup-integrity-gate
Theory:      futon-theory/all-or-nothing, futon-theory/counter-ratchet
PSR:         2026-02-07__layer2-integrity__rehydrate-entity.md
             2026-02-07__invariants__counter-ratchet.md
Module:      src/futon1a/core/rehydrate.clj (rehydrate!)
             src/futon1a/core/entity.clj (validate-entity, validate-relations)
             src/futon1a/core/invariants.clj (counter-ratchet, layer2-error)
Tests:       test/futon1a/layer2/rehydrate_test.clj
             test/futon1a/layer2/entity_test.clj
             test/futon1a/invariants/counter_ratchet_test.clj
Doc:         docs/layer2-integrity.md
             docs/layer2-entity-integrity.md
             docs/invariants.md
Error:       {:error/layer 2, :error/status 500, :error/reason :empty-entities}
             {:error/layer 2, :error/status 500, :error/reason :missing-id}
             {:error/layer 2, :error/status 500, :error/reason :counter-ratchet}
```

## I3: Hierarchy — "Errors surface at the layer that caused them"

```
Evidence:    commits 7efbef7, e1e2c9d, a525acb
             (auth bypass, scattered error handling)
Pattern:     storage/error-layer-hierarchy, storage/guardrails-vs-tooling
Theory:      futon-theory/error-hierarchy, futon-theory/stop-the-line
PSR:         2026-02-07__layer3-4__auth-validation.md
             2026-02-07__pipeline-api__write-surface.md
Module:      src/futon1a/core/pipeline.clj (L4->L3->L2->L1->L0 ordering)
             src/futon1a/api/errors.clj (error->response)
             src/futon1a/auth/penholder.clj (Layer 3 gate)
             src/futon1a/model/validation.clj (Layer 4 gate)
Tests:       test/futon1a/cross_layer/error_hierarchy_test.clj
             test/futon1a/cross_layer/error_propagation_test.clj
             test/futon1a/cross_layer/pipeline_order_test.clj
             test/futon1a/layer3/penholder_test.clj
             test/futon1a/layer4/model_validation_test.clj
Doc:         docs/layer3-authorization.md
             docs/layer4-validation.md
             docs/api-surface.md
Error:       L4: {:error/layer 4, :error/status 400, :error/reason :missing-field}
             L3: {:error/layer 3, :error/status 403, :error/reason :forbidden}
             L2: {:error/layer 2, :error/status 500} (see I2)
             L1: {:error/layer 1, :error/status 409} (see I1)
             L0: {:error/layer 0, :error/status 503} (see I0)
```

## I4: Rapid Debugging — "Any bug diagnosable in under 10 minutes"

```
Evidence:    commits 884df26, 50383dd
             (diagnostics reduced drift, made failures actionable)
Pattern:     storage/rapid-debugging
Theory:      futon-theory/rapid-debugging
PSR:         2026-02-07__pipeline-api__write-surface.md (covers API error surface)
Module:      src/futon1a/diag/proof_path.clj (proof-path event logging)
             src/futon1a/diag/health.clj (health-report with pluggable checks)
             src/futon1a/api/errors.clj (structured layer/status/reason/context)
Tests:       test/futon1a/invariants/proof_path_test.clj
             test/futon1a/diag/health_test.clj
             test/futon1a/api/errors_test.clj
Doc:         docs/health.md
Error:       All errors carry {:error/layer N, :error/status S, :error/reason R,
             :error/context {...}} — structured context for diagnosis.
             Proof-path logs give per-write audit trail: clock-in through clock-out.
```

---

## Tension → Module → Test Mapping

| # | Tension | Evidence | Module(s) | Test(s) |
|---|---------|----------|-----------|---------|
| 1 | Durability vs throughput | 0e2b3a5, 5c51506 | `core/xtdb.clj` | `layer0/durability_gate_test`, `stress/durability_stress_test`, `integration/xtdb_integration_test` |
| 2 | Availability vs integrity | 884df26, c5ffd75 | `core/rehydrate.clj` | `layer2/rehydrate_test` |
| 3 | Identity flex vs uniqueness | d1d447d, 1bda8f7 | `core/identity.clj` | `layer1/identity_test`, `stress/identity_stress_test` |
| 4 | Open-world velocity vs validation | 7b46312, 99d54c4 | `ingest/open_world.clj` | `ingest/open_world_test` |
| 5 | Mirroring speed vs consistency | fb05441, 50383dd | `core/mirror.clj` | `core/mirror_test` |
| 6 | Guardrails vs internal tooling | 7efbef7, e1e2c9d | `auth/penholder.clj` | `layer3/penholder_test`, `cross_layer/pipeline_order_test` |
| 7 | Invariants vs repair | 5227d75, c5ffd75 | `core/invariants.clj`, `scripts/repair.clj` | `invariants/counter_ratchet_test`, `scripts/repair_test` |
| 8 | Schema evolution vs stability | 773394f | `model/registry.clj`, `model/validation.clj` | `model/registry_test`, `layer4/model_validation_test` |
| 9 | Determinism vs expansion | 7611ea2, e1301f3 | `core/pipeline.clj` (single write path) | `cross_layer/pipeline_order_test`, `api/write_test` |
