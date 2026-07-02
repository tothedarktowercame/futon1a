# README-census.md — how to access substrate-2 (and read its populations)

Substrate-2 is the **XTDB-backed, bitemporal hypergraph** served by futon1a on
**port 7071**. This note is the practical "how do I find out what's actually in
it" guide — the access surfaces, the recipes, and (most importantly) the
footguns. It wraps up the access discussions from M-populate-substrate-2.

The visual map of *what kinds of things* it holds is
`futon2/holes/substrate-2-explainer.html`; the mission is
`futon3c/holes/missions/M-populate-substrate-2.md`. This doc is the *access* layer.

---

## TL;DR

- **The honest denominator** (real declared types, % populated, globs/dups, off-catalog) → `bb futon3c/scripts/catalog_census.bb` — the reproducible count-pushdown census. The registry is an append-only `run-write!` byproduct that **drifts** (208→216 docs in hours on 2026-06-26), so **re-derive; never cite a frozen number.** As of 2026-06-26: 216 docs → 196 distinct → **186 real declared types** (10 `*`-globs dropped); **101 populated (≈54%)**, 85 empty; ~130 store-side incl. off-catalog.
- **What KINDS** → `GET /api/alpha/types` (the declared type catalog; ~200 kinds).
- **HOW MANY of one kind** → `GET /api/alpha/census?type=<hx-type>` (or
  `?entity-type=<t>`). This is the count endpoint. **Use it for populations.**
- **The actual rows** → `GET /api/alpha/hyperedges?type=<t>&limit=N` (hyperedges)
  or `GET /api/alpha/entities/latest?type=<t>&limit=N` (entities).
- **One specific thing** → `GET /api/alpha/hyperedge/<url-encoded hx-id>`.
- **As of a past commit** (time-travel) → a Drawbridge `db-as-of` query (below).
- **Is it fresh / not frozen?** → `bash futon3c/scripts/substrate2_liveness_probe.sh`.

> **Never trust a `0`** from a high-limit type scan — see footgun #1.
> **Responses are EDN, not JSON** — see footgun #2.

---

## 1. The HTTP API (port 7071)

| Endpoint | Returns |
|---|---|
| `GET /api/alpha/types` | `{:types [...]}` — the declared type **catalog** (registry kinds, ~200). Kinds, not counts. |
| `GET /api/alpha/census?type=<hx-type>` | `{:type :kind :hyperedge :count N}` — **population of one hyperedge type** (count-pushdown; fast, no doc materialisation). |
| `GET /api/alpha/census?entity-type=<t>` | `{:type :kind :entity :count N}` — population of one entity type. |
| `GET /api/alpha/hyperedges?type=<t>&limit=N&repo=<label>&source-file=<p>` | `{:hyperedges [...] :count N}` — rows of a hyperedge type. `:count` is the TRUE total when unfiltered; `repo`/`source-file` filter in-memory. |
| `GET /api/alpha/hyperedge/<url-encoded hx-id>` | the hyperedge doc (EDN), or `{:error "not found"}`. O(1) membership test. |
| `GET /api/alpha/entities/latest?type=<t>&limit=N` | `{:entities [{:id :name :type :external-id :source :props {...}}]}` — entities of a type, props inline. |
| `GET /api/alpha/entity?source=<s>&external-id=<x>` | one entity by durable identity. |
| `GET /ego/<name>` | futon1-style ego view (incoming + outgoing links) for a node. |

hx-ids look like `hx:code/v05/var:futon3c-d/futon3c.agency.logic/add-agent-facts`
— URL-encode them (the `/` become `%2F`).

```bash
enc(){ python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
# population of one type:
curl -s "http://localhost:7071/api/alpha/census?type=$(enc code/v05/var)"
# all 293 sorries (entities, props inline):
curl -s "http://localhost:7071/api/alpha/entities/latest?type=$(enc sorry)&limit=1000"
# is a specific commit in the store?
curl -s "http://localhost:7071/api/alpha/hyperedge/$(enc hx:code/v05/commit:<sha>)"
```

## 2. Drawbridge — direct XTDB (port 6768, admin)

For counts the API doesn't pre-bake, time-travel, or ad-hoc datalog. **Read-only
unless you mean it; never restart the JVM** — reload code with `load-file`.

```bash
EVAL(){ curl -s -H "x-admin-token: $(cat /home/joe/code/futon3c/.admintoken)" \
  -H "Content-Type: text/plain" --data-binary @- "http://127.0.0.1:6768/eval"; }
```

The node is `(:node @futon3c.dev/!f1-sys)`; alias `xtdb.api` (already loaded).

```clojure
;; count-pushdown for ONE type (what /census does under the hood) — fast, bound type:
(ffirst (xtdb.api/q (xtdb.api/db (:node @futon3c.dev/!f1-sys))
                    (quote {:find [(count e)] :where [[e :hx/type :code/v05/var]]})))

;; TIME-TRAVEL: code structure as of a past commit (D3 bitemporal).
;; (var/edits/contains/calls/coverage/test are valid-time'd at the commit ts.)
(let [node (:node @futon3c.dev/!f1-sys)
      n (fn [db] (count (xtdb.api/q db (quote {:find [e] :in [pfx]
                  :where [[e :hx/type :code/v05/var] [e :hx/endpoints ep]
                          [(clojure.string/starts-with? ep pfx)]]}) "futon3c-d/")))]
  {:mar (n (xtdb.api/db node #inst "2026-03-01"))
   :now (n (xtdb.api/db node))})
```

> Caveat on the `(quote {...})` form: write `(require (quote [xtdb.api :as xt]))`
> and quote whole query maps; some heredoc/`'`-reader-macro combinations choke —
> prefer fully-qualified `xtdb.api/q` + `(quote …)`.

## 3. Footguns (read these — they cost real hours)

1. **A `0` from a type scan is usually a lie (timeout).** A high-`limit`
   `?type=X` scan over a large type (var ~125k, edits ~184k) can exceed the
   query timeout and return empty. **For counts use `/api/alpha/census`**
   (count-pushdown). A *full* all-types census is a ~470k-doc scan that times
   out as one call — there is no cheap "all counts at once"; it's per-type by
   design. Never assert "empty" without a census or by-id check.
2. **Responses are EDN, not JSON.** `/hyperedge/:id` and the type queries return
   EDN (`{:hx/id "…" :hx/type :code/v05/…}`). `json/parse` will throw on the
   leading `:`. (This exact bug broke replay `:resume?`.)
3. **Type keywords:** `code/v05/var` reads as `:code/v05/var` (ns `code`, name
   `v05/var`). The endpoints accept the string without the leading `:`.
4. **Dual population / endpoint prefixes.** The April batch wrote bare endpoints
   (`ns/var`, `256ca/var`, label `…-d`/`phase-1`); the live watcher + D3 replay
   write **repo-label-prefixed** (`futon3c-d/ns/var`). For time-travel query the
   *prefixed* population. substrate-2 is a palimpsest of several ingest campaigns
   under different labels — the declared type catalog (216 docs → 196 distinct →
   **186 real** after dropping 10 globs; re-derive with `catalog_census.bb`) ≠
   the populated types (~130 store-side incl. **off-catalog** heavies like
   `code/v05/edits` 185k / `code/v05/var` 125k; of the 186 declared, **101 are
   populated ≈54%**; an earlier "~35" was a timeout-sniff undercount — footgun #1).
5. **Merges aren't ingested.** commit-ingest skips merge commits, so a merge
   HEAD is never in the store. To ask "is this repo current?", check the
   **last non-merge** sha: `git -C <repo> rev-list --no-merges -1 HEAD`.
6. **`file→mission` is NOT versioned** (still a bare path at HEAD); everything
   else in the code graph time-travels. Don't expect `db-as-of` to fix a stale
   file→mission link (that's a named follow-on — the "Nelson v1/v10" link).
7. **Liveness is not automatic-forever.** The code/history layer froze silently
   for 5 weeks once (commit-ingest default-off). Guards now exist:
   `substrate2_liveness_probe.sh` (manual), the `substrate-2-commit-freshness`
   probe family + the watcher's `freshness/check+notify!` (D7a). If counts look
   stale, run the probe before assuming the data is right.
8. **Never restart the serving JVM** (it hosts 7071/7070/6768/3100). Reload via
   Drawbridge `load-file`. See `futon3c/README-drawbridge.md`.
9. **RELATIONS are invisible to the census/type endpoints.** `census?type=` and
   `hyperedges?type=` count hyperedge/entity docs — a relation written via
   `POST /relation` shows `:count 0` on both even when present. Relation docs
   store their type KEYWORDIZED (`:relation/type :outcome-ref`, not
   `"outcome-ref"`). Count/join them via Drawbridge datalog:
   `{:find [(count r)] :where [[r :relation/type :outcome-ref]]}` — found the
   hard way during R19-PROOF-JOIN (2026-07-02, claude-3 diagnosis: the 252
   freshly-written relations "didn't exist" by HTTP scan; they were all there).

## 4. Freshness / liveness check

```bash
bash /home/joe/code/futon3c/scripts/substrate2_liveness_probe.sh
```
Reports the watcher's `commit-ingest?` flag and, per repo, whether the current
HEAD (last non-merge) is in the store. A `MISSING`/`STALE` row means substrate-2
has fallen behind that repo's git HEAD.

## 5. See also

- `futon2/holes/substrate-2-explainer.html` — the live 2-column map (what-it-is /
  what-it-should-be), with per-box mission-deliverable tags.
- `futon3c/holes/missions/M-populate-substrate-2.md` — the mission + checkpoint log
  (D0 liveness, the reader fix, D2.1 edits, D3 bitemporal, D7a freshness alarm, D0.2).
- `futon3c/README-drawbridge.md` — the Drawbridge reload/eval surface (the `.admintoken` lives in futon3c too).
- `README-conventions.md` — id/endpoint conventions.
