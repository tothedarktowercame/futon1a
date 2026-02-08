# Best Practice: Claude + Codex Collaborative Rebuild

This document captures the workflow, roles, and discipline that emerged during
the futon1a rebuild session. It is meant to be reusable by future Claude + Codex
runs on futonic projects.

---

## 1. Roles

| Agent | Strengths | Role |
|-------|-----------|------|
| **Claude** (interactive) | Broad context, architectural reasoning, review, traceability | Reviewer, critical-fix owner, documentation lead |
| **Codex** (async batch) | Fast parallel file generation, scaffold creation | Implementer, bulk module generation |

**Key principle:** Claude reviews everything Codex produces before it merges
into the mainline. Codex never self-approves.

---

## 2. Workflow Phases

### Phase 1: Evidence Extraction

Before writing any code, extract evidence from the predecessor system's git
history. Record:

- Recurring bug patterns (with commit hashes)
- Fix patterns and implicit invariants
- Anti-patterns to avoid
- Tensions (competing design forces)

**Output:** An evidence document (e.g., `M-futon1a-evidence.md`) that grounds
every subsequent design decision.

### Phase 2: Pattern Selection + PSR/PUR Records

For each module or design decision:

1. Search the pattern library (`futon3/library/`) for applicable patterns.
2. Write a **PSR** (Pattern Selection Record) explaining which pattern was
   chosen and why.
3. After applying the pattern, write a **PUR** (Pattern Use Record) documenting
   the outcome, prediction errors, and notes.

PSR/PUR pairs live in `futon3/holes/labs/<project>/psr/` and `pur/`.

### Phase 3: Scaffold + First Implementation (Codex)

Codex generates the initial module scaffolds following the module map and
pattern assignments. Each Codex batch should:

- Target a bounded set of modules (e.g., "Layers 0-2" or "API surface")
- Include unit tests for every module
- Run `clj-kondo --lint src/ test/` before declaring done

### Phase 4: Review (Claude)

Claude reviews each Codex batch with a structured checklist:

1. **Error propagation correctness** — Does each layer throw the right
   `{:error/layer N, :error/status S, :error/reason R}` shape?
2. **Pipeline ordering** — Does the write pipeline execute L4 -> L3 -> L2 -> L1 -> L0?
3. **Test sufficiency** — Does every module have at least one test proving
   its invariant? Are edge cases covered?
4. **Architectural alignment** — Do modules match the module map and pattern
   assignments?
5. **Protocol contracts** — Do protocol implementations match their definitions?

Findings are categorized:

| Severity | Meaning | Who Fixes |
|----------|---------|-----------|
| **Critical (C)** | Invariant violated, wrong behavior | Claude (immediate) |
| **Significant (S)** | Contract drift, missing coverage | Codex (next batch) |
| **Minor (M)** | Style, naming, docs | Codex (next batch) |

### Phase 5: Handoff (AGENTS.md)

After Claude fixes critical items, remaining S/M items are written into
`AGENTS.md` with:

- Clear task list with file paths
- What each fix should accomplish
- Which files Claude has already modified (hands off)

Codex picks up `AGENTS.md` and works the list.

### Phase 6: Second Review + Fix

Claude pulls Codex's fixes, runs the same review checklist, and fixes any
remaining issues directly. This is faster than another round-trip.

### Phase 7: Stress Tests

After unit tests pass, add concurrency stress tests for hot paths:

- Use `CountDownLatch` for simultaneous thread start
- Test both correctness (every result valid) and durability (no lost writes)
- Stress tests often **reveal real bugs** (e.g., unlocked file append) —
  this is the point

### Phase 8: Traceability + Module Headers

Close the traceability chain end-to-end:

```
Evidence (git commits) → Pattern → PSR → Module → Test → Doc → Error shape
```

Every module's ns docstring should reference:
- Which invariant it serves (I0-I4)
- Which pattern it implements
- Which theory it draws from

---

## 3. Futonic Discipline

### Layered Gate Architecture

futon1a uses 5 layers, each with a dedicated error shape:

| Layer | Gate | HTTP Status | Error Reason (examples) |
|------:|------|:-----------:|-------------------------|
| 0 | Durability | 503 | `:durability-failure` |
| 1 | Identity | 409 | `:invalid-id`, `:external-id-conflict` |
| 2 | Integrity | 500 | `:empty-entities`, `:missing-id`, `:counter-ratchet` |
| 3 | Authorization | 403 | `:forbidden`, `:missing-penholder` |
| 4 | Validation | 400 | `:missing-field`, `:invalid-model` |

The write pipeline executes **L4 -> L3 -> L2 -> L1 -> L0** (outer/cheap to
inner/expensive). If layer N fails, layers below N never run.

### Error Shape Contract

Every error must carry:

```clojure
{:error/layer N
 :error/status HTTP-status
 :error/reason keyword
 :error/context map}
```

This is non-negotiable. It enables I3 (Hierarchy) and I4 (Rapid Debugging).

### Proof-Path Protocol

Every durable write is wrapped in a proof-path with 8 ordered phases:

```
clock-in → observe → propose-claim → apply-change →
verify → invariant-check → proof-commit → clock-out
```

This provides a per-write audit trail for diagnosis.

### Counter-Ratchet Invariant

Counts (entities, relations, etc.) must never decrease. The counter-ratchet
check runs during repair verification to ensure repairs don't lose data.

### PSR/PUR Discipline

Every non-trivial design choice gets a PSR before implementation and a PUR
after. This creates an evidence chain that future agents can trace when they
ask "why was this built this way?"

---

## 4. Checkpointing

After each significant milestone, append a checkpoint to the mission document:

```markdown
### Checkpoint N — <date>

**What was done:**
- Bullet list of accomplishments

**Test state:** N tests, M assertions, 0 failures
**Next:** What comes next
```

Checkpoints help both agents and humans understand progress without reading
the full git log.

---

## 5. Common Pitfalls

| Pitfall | Mitigation |
|---------|------------|
| Codex adds phantom dependencies | Claude verifies `deps.edn` on every pull |
| Pipeline ordering gets scrambled | Cross-layer test asserts layer order explicitly |
| Tests pass in isolation but fail together | Use `:each` fixtures for global state (registries, atoms) |
| Error handler only catches `ExceptionInfo` | Use `with-error-handling` macro that catches both |
| Module headers drift from patterns | Traceability doc is the source of truth; headers reference it |
| Forgot to push companion repo (futon3) | Always commit+push both repos at checkpoint |

---

## 6. Mission Diagram Validation

futon1a is the first mission to use machine-checkable architecture diagrams.
The mermaid diagram in M-futon1a-rebuild.md is human-reviewable; the EDN in
`docs/mission-diagram.edn` is the machine-checkable ground truth.

### Why

The futon1a rebuild demonstrated that specifying internal correctness
(invariants, gates, proofs) without specifying boundary types produces systems
that work internally but can't compose with anything. The mission diagram
validator catches this class of error before humans have to.

### What the validator checks

| Check | What it catches |
|-------|----------------|
| **Completeness** | Output port with no path from any input |
| **Coverage** | Internal component with no path to any output (dead code) |
| **No orphan inputs** | Input port connected to nothing |
| **Type safety** | Wire type incompatible with source `:produces` or dest `:accepts` |
| **Spec coverage** | Output port missing `:spec-ref` (stop-the-line) |

### How to validate

```clojure
;; From the futon5 repo:
(require '[futon5.ct.mission :as m]
         '[clojure.edn :as edn])

(def spec (edn/read-string (slurp "../futon1a/docs/mission-diagram.edn")))
(def diagram (m/mission-diagram spec))

(m/validate diagram)
;; => {:all-valid true, :checks [...], :mission/id :futon1a-rebuild}

;; Regenerate mermaid from EDN (should match the mission doc):
(println (m/diagram->mermaid diagram))
```

### Key insight: components are morphisms

The first validation run against futon1a's diagram found 12 type errors.
Components had a single `:type` field that confused "what it is" (e.g.,
`:clj-namespace`) with "what it accepts/produces" (e.g., `:http-request` →
`:error-response`). The fix: components declare `:accepts` (domain) and
`:produces` (codomain), which is exactly what category theory says — a morphism
is defined by its source and target objects, not by what it's made of.

### When to update

Update `docs/mission-diagram.edn` at every prototype gate. The validator
should pass before the gate is considered met.

---

## 7. Tool Chain

```
clj-kondo --lint src/ test/    # Lint before every commit
clj -X:test                    # Run all tests (cognitect test-runner)
git log --oneline -10           # Check recent commit style
```

---

## 8. File Layout Reference

```
src/futon1a/
  core/       # Layers 0-2, pipeline, mirror, invariants
  auth/       # Layer 3
  model/      # Layer 4 + registry
  api/        # HTTP surface + error mapping
  diag/       # Proof-path, health
  ingest/     # Open-world ingest
  scripts/    # Repair/backfill

test/futon1a/
  layer0-4/   # Per-layer unit tests
  cross_layer/ # Error hierarchy + propagation
  invariants/ # Counter-ratchet, proof-path proofs
  stress/     # Concurrency stress tests
  integration/ # XTDB integration
  api/        # API surface tests
  core/       # Mirror tests
  diag/       # Health tests
  ingest/     # Open-world tests
  model/      # Registry tests
  scripts/    # Repair tests

docs/
  traceability.md   # End-to-end evidence chain (Part I gate)
  module-map.md     # Pattern → Module → Test mapping
  layer0-4 docs     # Per-layer explanation
  health.md         # Diagnostics
  api-surface.md    # API reference
  invariants.md     # Invariant definitions
```
