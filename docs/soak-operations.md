# F4 Soak Operations

This runbook defines the 30-day operational soak for futon1a.

Scope (F4):
- daily health audit
- daily error-shape audit
- daily proof-path audit
- restart-cycle spot checks
- incident ledger

## Window

- Owner: `Joe`
- Start date: `2026-03-02`
- End date: `2026-04-01` (30 days)

## Preconditions

1. futon1a service is running with explicit durable config:
   - `FUTON1A_DATA_DIR`
   - `FUTON1A_ALLOWED_PENHOLDERS`
   - optional `FUTON1A_PROOF_LOG_PATH`
2. audit penholder is allowed (default in scripts: `soak-audit`).

## Daily Checklist

Run once per day and record output in the soak evidence log.

1. Daily health/error-shape/proof-path audit:

```bash
clojure -M -m futon1a.scripts.soak-daily-audit \
  --base-url http://127.0.0.1:7071 \
  --penholder soak-audit \
  --proof-log-path /path/to/proof-path.log.edn \
  --strict-proof-log true
```

Pass criteria:
- `:checks :healthz :ok?` is `true`
- `:checks :health :ok?` is `true` and service status is `:ok`
- layered error-shape checks (`L4`, `L3`, `L2 counter-ratchet`) are `true`
- proof-path check is `true`
- proof-log persistence check is `true` when `--strict-proof-log true`

2. Incident ledger update:
- record any failed check
- record mitigation and verification command
- record MTTR (minutes)

## Restart Spot Check

Run at least twice per week during soak (or after infra changes).

```bash
clojure -M -m futon1a.scripts.soak-restart-spot-check \
  --data-dir /path/to/futon1a-data \
  --penholder soak-audit
```

Pass criteria:
- write succeeds with `:path/id`
- read before restart succeeds (`200`)
- read after restart succeeds (`200`)

## Evidence Storage

- Primary evidence log: `docs/evidence/soak-2026-03-02-to-2026-04-01.md`
- Keep one entry per day with:
  - timestamp
  - command(s) run
  - condensed result map
  - incidents (if any)

## Stop-The-Line Conditions

Treat soak as failed and escalate immediately when:
- health status not `:ok`
- error-shape contract drift (`:error/layer` missing/mismatched)
- proof-path missing on successful writes
- counter-ratchet no longer blocks unexpected protected-class drops
- restart spot check fails durability read-after-restart
