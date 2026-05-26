# Conventions (draft proposal, 2026-05-24)

This document is **a draft proposal**, not a settled standard. Edit it
freely, push back on any of it, or scrap pieces — it exists to make
the unsettled parts of futon1a's data model visible so we can converge
intentionally rather than by accident.

The trigger: M-weird-modernism's Task 6 (cross-mission dependency
graph) surfaced a wiring gap — the multi-watcher writes substrate-2
hyperedges using semantic vertex-id strings (e.g.
`futon3-d/mission/weird-modernism`), while the hinge-log-bridge writes
entities with auto-assigned UUIDs and human-name labels
(e.g. `mission|M-superpod-mark2`). The WebArxana Graph view focuses on
a UUID and queries `/api/alpha/hyperedges?end=<UUID>`, which does an
exact-string match against `:hx/endpoints` — a vertex-id string and
a UUID never meet. The data ends up in **the same XTDB**, but cannot
cross-reference because the writers use incompatible identifier
conventions. That's the problem this document is trying to prevent
from recurring.

---

## 1. The one-XTDB principle

futon1a is one XTDB instance. It serves four API surfaces — `entity`,
`relation`, `hyperedge`, `evidence` — but they are different
**document shapes**, not separate stores. Cross-references between
shapes are first-class and intentional: a hyperedge's endpoint can
*be* an entity, and a relation's src/dst points to entities by name
or id.

This works iff writers agree on identifiers. If two writers describe
the same conceptual thing under two different strings, the cross-
reference can't materialise.

## 2. The pattern convention (in production, working)

`futon1a/src/futon1a/scripts/ingest_flexiarg_pattern.clj` is the
exemplar. For each `.flexiarg` pattern it ingests:

- **One entity** for the pattern itself:
  - `:name` = pattern id (e.g. `"futon-theory/wyrd"`)
  - `:type` = `"pattern/library"`
  - `:external-id` = pattern id
  - `:source` = title
- **One entity per clause** (context, if, however, then, because, plus
  conclusion / next-steps when present):
  - `:name` = `<pattern-id>/<facet>` (e.g. `"futon-theory/wyrd/context"`)
  - `:type` = `"pattern/clause"`
  - `:external-id` = same as name
- **One relation per clause** of type `:pattern/has-<facet>` from
  pattern entity → clause entity.

Stable, name-keyed. The pattern's name *is* a meaningful string. Any
consumer that knows the pattern id can look up the entity via
`/api/alpha/entity?name=<pid>` (or via the relation graph from a
neighboring entity), and the relations resolve naturally.

## 3. The mission convention (proposed for adoption today)

For each `M-*.md` mission file ingested by the watcher
(`futon3c.watcher.file-ingest/ingest-mission-doc!`):

- **One mission-doc hyperedge** (already in production since Task 8):
  - `:hx/type` = `"code/v05/mission-doc"`
  - `:hx/endpoints` = `[<vertex-id>]` where `<vertex-id>` = `<repo-label>/mission/<mission-id>` (e.g. `"futon3-d/mission/weird-modernism"`)
  - `:hx/props` carry parsed mission fields (status, summary, cross-refs, code-paths, phase, mtime, psrs, purs, …)
- **One mission entity** (proposed for Task 6 + ongoing):
  - `:name` = the same `<vertex-id>` string as the hyperedge endpoint
  - `:type` = `"mission/doc"`
  - `:external-id` = `"M-<mission-id>"`
  - `:source` = `"mission-doc-watcher"`
  - `:props` carry a thin subset of mission metadata (title, status, repo) — the substantive data lives on the hyperedge
- **One mission-cross-ref hyperedge per cross-ref** (already in
  production after today's work):
  - `:hx/type` = `"code/v05/mission-cross-ref"`
  - `:hx/endpoints` = `[<source-vertex-id> <target-vertex-id>]`
  - `:hx/props` carry source / target mission ids

The keystone: **the hyperedge endpoint string and the entity name are
the same string.** When the Graph view focuses on the mission entity
(name-keyed), it can discover the mission's hyperedges via
`/hyperedges?end=<name>` — directly, no aliasing needed.

The proposed name `mission/doc` for the entity type is a guess —
alternatives `mission`, `mfuton/mission`, `mission-doc` all exist in
neighbouring data. Pick one in a comment review and stick with it.

## 4. Server-side smart-resolve (proposed for adoption today)

To bridge the **legacy** identifier gap (hinge-bridged entities have
UUIDs, hyperedges have name strings — they don't match without help),
extend `futon1a.api.routes/hyperedges-by-end` so that when `?end=<id>`
matches a UUID-shaped string, it first looks up the entity, finds its
`:name`, then runs the existing exact-string match using the name.

This means:
- Old data (hinge-bridged) keeps working with UUID lookups.
- New data (the mission convention above) works because hyperedge
  endpoints already match entity names directly.
- Future writers don't have to be aware of UUID vs name plumbing.

The smart-resolve is one branch in one function (~15 lines). It does
not change any write paths.

## 5. Open question: convention realignment across all writers (not shipping today)

A deeper cleanup the stack is not undertaking today, but should
eventually:

The hinge-log-bridge writes entities with names like
`mission|M-superpod-mark2`. The mission watcher writes hyperedges
with endpoints like `futon3-d/mission/superpod-mark2`. Same
conceptual entity, different names. These ought to converge —
either:

- The hinge-log-bridge migrates to the mission convention (rename its
  8 existing entities, or retire and re-ingest with the new
  convention).
- Both writers adopt a third common convention.
- The smart-resolve from §4 stays in place as a bridge indefinitely
  (acceptable but adds permanent server complexity).

Recommended direction: **retire the hinge-log-bridge for
mission entities** (its 8 entries are exploratory; the mission-doc
watcher handles all 187+ missions). Hinge-log entities — the actual
hinge-content, separate from mission identity — can continue to
exist, but the `mission|M-...` name pattern they introduced is no
longer the canonical mission identity.

This is open for design discussion. Not blocking the work shipping
today.

## 6. How to add a new convention

When a new kind of thing needs to live in futon1a, add a section
above and answer:

1. What is the canonical **identifier string** for an instance?
2. Is the primary write a **hyperedge** (multi-endpoint, rich props)
   or **entity + relations** (binary graph)?
3. If both, are the **identifiers identical** so cross-references
   resolve without an alias layer?
4. Which writer is the **source of truth** for this kind of thing?
5. What `:source` tag distinguishes it from other writers?

Answers go in a section here. Then the writer is implemented to match.

## Pointers

- Pattern ingest script: `futon1a/src/futon1a/scripts/ingest_flexiarg_pattern.clj`
- Mission ingest watcher: `futon3c/src/futon3c/watcher/file_ingest.clj`
- Hyperedge query routes: `futon1a/src/futon1a/api/routes.clj` (`hyperedges-by-end`, `hyperedges-by-type`)
- Entity API routes: `futon1a/src/futon1a/http/app.clj:284` (POST `/api/alpha/entity`)
- Relation API routes: `futon1a/src/futon1a/http/app.clj:307` (POST `/api/alpha/relation`)
- Substrate-2 substrate documentation: `README-reflection.md`

## Open threads in this draft

- Choice of entity `:type` value for missions (`mission/doc` vs
  `mission` vs `mfuton/mission`).
- Smart-resolve heuristic: how confidently we detect "UUID-shaped"
  input — strict regex match on the 8-4-4-4-12 hex pattern is the
  obvious starting point.
- Whether the watcher's `:props` on the entity should be a thin
  subset of the hyperedge props or empty (i.e. always navigate to
  hyperedge for the substantive data).
- The §5 question of convention realignment: which writer wins, or
  do we adopt a third name?
- Whether evidence records have their own identifier convention and
  should be documented here too.
