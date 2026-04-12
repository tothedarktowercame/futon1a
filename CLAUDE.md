# futon1a

XTDB-backed data store for the futon stack. Serves entity, relation, hyperedge, and evidence APIs.

## Critical rules

- **NEVER kill or restart the running futon1a server.** It is orchestrated by futon3c with specific env vars and startup dependencies. Killing it can crash the entire stack.
- To reload code changes, use Drawbridge (nREPL over HTTP) where possible. Otherwise, note the change will take effect at next natural restart.
- The write pipeline (pipeline.clj) enforces layered invariants (L4 model validation, L3 authorization, L2 entity integrity, L1 identity uniqueness, L0 durable write). All writes go through `run-write!`. Do not bypass it.
- The `x-penholder` header is required for all mutating requests.
