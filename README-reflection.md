# Reflection: exercising the substrate-2 stack

This note documents how to *use* the live geometric hypergraph layer
(substrate-2) that now sits on top of futon1a. It lives here because futon1a
is the storage: every `code/v05/*` edge described below is queryable via the
hyperedge API on `localhost:7071`.

The mission that produced this is
[M-live-geometric-stack](/home/joe/code/futon3/holes/missions/M-live-geometric-stack.md)
(CLOSED 2026-04-28). The follow-on mission for PSR/PUR/PAR-as-tangent-vectors
is
[M-reflective-discipline](/home/joe/code/futon2/holes/missions/M-reflective-discipline.md).

---

## What is in the substrate

futon1a holds a hypergraph of code-state across ~13 codebases under 14+ labels.
Every edge is typed under `code/v05/*`:

| Type | Meaning |
|------|---------|
| `var` | a defn/defun/def in some namespace (per-repo) |
| `test` | a deftest / test_* form (per-repo) |
| `namespace` | a code namespace / module (per-repo) |
| `calls` | call edge between vars (per-repo) |
| `coverage` | "this test exercises this var" (per-repo) |
| `vocabulary-use` | a term occurs in a doc (per-repo) |
| `term` | a vocabulary term (global) |
| `term-defines` | "this var/doc defines this term" (global) |
| `contains` | namespace ⊇ var/test (per-repo) |
| `doc` | a documentation file (global) |
| `commit` | git commit vertex (global) |
| `author` | git author vertex (global) |
| `authored` | author → commit (global) |
| `precedes` | commit → commit parent edge (global) |
| `edits` | commit ↔ var edited (global) |
| `watcher-event` | a single live ingest cycle (per-repo) |
| `satisficing-signature` | a typed pattern-of-decay finding (per-repo) |

**Per-repo prefix scheme (B-2):** per-repo qnames are namespaced as
`<label>/<qname>` so shared names across codebases don't collide. The raw
qname is preserved in `:hx/props` (e.g. `:var/qname`). Global types are not
prefixed.

---

## Quick health check

```bash
# server up?
curl -s http://localhost:7071/api/alpha/health -H 'X-Penholder: api'

# any vars yet?
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/var&limit=1' \
  -H 'X-Penholder: api'

# is the watcher alive?
ps -p $(cat /tmp/multi-watcher.pid) -o pid,etime,cmd
tail -n 20 /tmp/multi-watcher.log
```

---

## Reading the substrate

All reads go through `/api/alpha/hyperedges?type=…`. Useful filters:

```bash
# all signatures for a label
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/satisficing-signature&label=futon2-d&limit=200' \
  -H 'X-Penholder: api' | jq

# vars in one repo
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/var&label=futon1a-d&limit=500' \
  -H 'X-Penholder: api' | jq '.hyperedges[].hx/props'

# coverage edges (test → var)
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/coverage&label=futon3-d&limit=100' \
  -H 'X-Penholder: api'

# a single commit by SHA
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/commit&endpoint=<sha>&limit=1' \
  -H 'X-Penholder: api'
```

---

## Geometric layer report (T, ∇T, ΔT, drift)

Per-label scalar field over vertices (`T(v) = 1` if no incident `:coverage`,
else `0`), gradient on edges, signed flow ΔT per vertex, and Jaccard drift
across components:

```bash
bb /home/joe/code/futon3/scripts/geometric_layer_phase2.clj \
  --label futon1a-d --out /tmp/geo-futon1a.edn
less /tmp/geo-futon1a.edn
```

Use this to find:
- vertices where ΔT crosses zero (entering/leaving covered region)
- gradient hot edges (uncovered → covered at a call boundary)
- components whose vocab fingerprints have drifted apart

---

## Satisficing signatures (self-awareness)

Phase-5 emits typed findings about decay patterns. Six emitting signatures:

| Signature | Severity | What it flags |
|-----------|----------|---------------|
| `adapter-shim-no-adapt` | medium | adapter ns whose members never cross to another ns |
| `work-around-drift` | medium | comment/term mentions of "workaround" without a defining doc |
| `concept-used-without-definition` | medium | term used in many places but no `:term-defines` exists |
| `completion-rot` | high | "done" label with too-thin var/test/coverage population |
| `coverage-retreat` | medium | recent commits removed coverage edges without adding new ones |
| `concept-introduced-without-attachment` | low | term defined in a doc but used by < 10% of vars |

Run on one label:

```bash
bb /home/joe/code/futon3/scripts/phase_5_signatures.clj --label futon3-d
```

Run across all labels (be aware: O(N²) in the cross-component pass — ran in
~2 min on a 4-label slice; full 14-label run timed out previously, so prefer
single-label or pick a subset):

```bash
bb /home/joe/code/futon3/scripts/phase_5_signatures.clj \
  --label futon1a-d --label futon2-d --label futon3-d --label futon3c-d
```

Then query the substrate's view of itself:

```bash
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/satisficing-signature&label=futon3-d&limit=500' \
  -H 'X-Penholder: api' | jq '.hyperedges[] | {sig: .hx/props.signature, sev: .hx/props.severity, repo: .hx/props.repo}'
```

---

## The live watcher

A poll-based watcher (5 s interval) keeps the substrate current. It hashes
file contents, detects deletions and renames, and dispatches per-language
projectors.

```bash
# show currently-watched roots
ps -p $(cat /tmp/multi-watcher.pid) -ww -o args=

# tail
tail -f /tmp/multi-watcher.log

# stop
kill $(cat /tmp/multi-watcher.pid)

# restart (current invocation, keep cold-scan off; remove --no-cold-scan
# when you want a one-shot full re-ingest after a long downtime)
nohup bb /home/joe/code/futon3/scripts/multi_watcher.clj \
  --root /home/joe/code/futon0=futon0-d \
  --root /home/joe/code/futon1=futon1-d \
  --root /home/joe/code/futon1a=futon1a-d \
  --root /home/joe/code/futon2=futon2-d \
  --root /home/joe/code/futon3=futon3-d \
  --root /home/joe/code/futon3a=futon3a-d \
  --root /home/joe/code/futon3b=futon3b-d \
  --root /home/joe/code/futon3c=futon3c-d \
  --root /home/joe/code/futon4=futon4-elisp-d \
  --root /home/joe/code/futon5=futon5-d2 \
  --root /home/joe/code/futon5a=futon5a-d \
  --root /home/joe/code/futon6=futon6-py-d \
  --root /home/joe/code/futon7=futon7-d \
  --interval-ms 5000 --no-cold-scan \
  > /tmp/multi-watcher.log 2>&1 &
echo $! > /tmp/multi-watcher.pid
```

**Caveat:** the watcher writes to futon1a synchronously through the same
penholder pipeline as everything else. Don't kill the futon1a server while
the watcher is running — restart the watcher first.

---

## Adding a new repo

Three steps: bulk ingest → commit history → enrol in the watcher.

```bash
# 1. structural ingest (vars/tests/calls/coverage/vocab/contains)
bb /home/joe/code/futon3/scripts/ingest_v05_to_futon1a.clj \
  /path/to/repo --label myrepo-d --vocab /path/to/repo/docs

# 2. commit history (commit/author/authored/precedes/edits)
bb /home/joe/code/futon3/scripts/ingest_commits_to_futon1a.clj \
  /path/to/repo --label myrepo-d

# 3. add to the watcher (kill + restart with extra --root)
kill $(cat /tmp/multi-watcher.pid)
# … restart with `--root /path/to/repo=myrepo-d` appended
```

Language coverage in the projectors:
- Clojure (`.clj` `.cljs` `.cljc`) — built in
- Emacs Lisp (`.el`) — `elisp_projection.clj`
- Python (`.py`) — `python_projection.clj` (uses `python_ast_helper.py`,
  stdlib `ast`)
- `.flexiarg` — `flexiarg_projection.clj`

Other file types are inventoried only (no first-class projection).

---

## Caches & on-disk artefacts

| Path | What |
|------|------|
| `/tmp/multi-watcher.pid` | PID file for the live watcher |
| `/tmp/multi-watcher.log` | Watcher rolling log |
| `/tmp/substrate2-byns-<sha>.edn` | P-3 by-namespace cache (60 s TTL) |
| `/tmp/substrate2-watcher-<label>.edn` | Per-label B-3 watcher cache (`{path → {:mtime :hash}}`) |
| `/tmp/geo-<label>.edn` | Hand-saved phase-2 geometric reports |

If a label seems wedged, deleting its watcher cache forces a full re-hash on
next cycle:

```bash
rm /tmp/substrate2-watcher-<label>.edn
```

---

## Useful self-aware queries

```bash
# how many edges per type, in one repo
for t in var test namespace calls coverage vocabulary-use contains; do
  c=$(curl -s "http://localhost:7071/api/alpha/hyperedges?type=code/v05/${t}&label=futon1a-d&limit=1" \
        -H 'X-Penholder: api' | jq '.count')
  echo "${t}: ${c}"
done

# what does the substrate say about itself?
bb /home/joe/code/futon3/scripts/phase_5_signatures.clj --label futon1a-d
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/satisficing-signature&label=futon1a-d&limit=200' \
  -H 'X-Penholder: api' | jq '.hyperedges[] | {sig: .hx/props.signature, sev: .hx/props.severity, details: .hx/props.details}'

# which commits touched a given var?
curl -s 'http://localhost:7071/api/alpha/hyperedges?type=code/v05/edits&endpoint=<repo-label>/<qname>&limit=200' \
  -H 'X-Penholder: api'
```

---

## Cross-references

- Mission: [M-live-geometric-stack](/home/joe/code/futon3/holes/missions/M-live-geometric-stack.md) (CLOSED 2026-04-28)
- Cleanup dispatch table: [CLEANUP-CHECKPOINT-2026-04-27.md](/home/joe/code/futon3/holes/labs/M-live-geometric-stack/CLEANUP-CHECKPOINT-2026-04-27.md)
- Follow-on (PSR/PUR/PAR as tangent vectors): [M-reflective-discipline](/home/joe/code/futon2/holes/missions/M-reflective-discipline.md)
- Sibling read-mostly diagnostic: [M-pattern-application-diagnostic](/home/joe/code/futon3/holes/missions/M-pattern-application-diagnostic.md)
