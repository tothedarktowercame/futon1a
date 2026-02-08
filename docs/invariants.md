# Invariants

This document defines cross-layer invariants that bind the stack together.

## Invariant Traceability

Each invariant is traced to a futon-theory pattern (abstract) and a storage
pattern (concrete).

### I0: Persistence

- Theory: `futon-theory/durability-first`
- Storage: `storage/durability-first`, `storage/durability-throughput-gate`
- Implementation: `src/futon1a/core/xtdb.clj`, `src/futon1a/core/xtdb_node.clj`
- Proof: `test/futon1a/layer0/durability_gate_test.clj`

### I1: Identity

- Theory: `futon-theory/single-source-of-truth`
- Storage: `storage/identity-uniqueness`, `storage/identity-flex-uniqueness`
- Implementation: `src/futon1a/core/identity.clj`
- Proof: `test/futon1a/layer1/identity_test.clj`

### I2: Integrity

- Theory: `futon-theory/fail-loud`
- Storage: `storage/startup-integrity-gate`
- Implementation: `src/futon1a/core/rehydrate.clj`, `src/futon1a/core/entity.clj`
- Proof: `test/futon1a/layer2/rehydrate_test.clj`, `test/futon1a/layer2/entity_test.clj`

### I3: Hierarchy (Error Propagation)

- Theory: `futon-theory/error-hierarchy`
- Storage: `storage/error-layer-hierarchy`
- Implementation: `src/futon1a/api/errors.clj`, `src/futon1a/core/pipeline.clj`
- Proof: `test/futon1a/cross_layer/error_hierarchy_test.clj`,
  `test/futon1a/cross_layer/error_propagation_test.clj`

### I4: Rapid Debugging

- Theory: `futon-theory/rapid-debugging`
- Storage: `storage/rapid-debugging`
- Implementation: `src/futon1a/diag/health.clj`, `src/futon1a/api/routes.clj`
- Proof: `test/futon1a/invariants/rapid_debugging_test.clj`

## Counter-Ratchet

Counts for key collections must not drop unexpectedly. A drop is treated as a
Layer 2 integrity failure.

Implementation: `src/futon1a/core/invariants.clj`.
