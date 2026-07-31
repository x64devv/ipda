# ipda

Deterministic automated trading engine built on the ICT IPDA model (treated as a
heuristic of liquidity-seeking + inefficiency rebalancing). Kotlin/JVM, targeting
the cTrader Open API. See `../CLAUDE.md` and `../ipda-automation-discussion.md`
for the full design record.

## Ground rules

- Every detection primitive is **causal** (computable at candle close, no lookahead)
  and deterministic.
- Thresholds are binary gates, but continuous values are **always logged** — parameter
  sweeps are queries over the event log, not re-runs.
- All magic numbers live in `config/ipda-config.json`. Run identity =
  (config hash, snapshot id, code version).
- The strategy engine cannot tell live from replay (feed abstraction).
- No LLM in the hot path, ever.

## Layout

```
src/main/kotlin/ipda/
  model/    Candle, Timeframe, Direction, SwingPoint, Fvg
  config/   IpdaConfig + loader (SHA-256 byte hash of the config file)
  feed/     Feed interface + canonical event ordering (HTF-first tie-break)
  detect/   SessionTagger, DailyBoundary, SwingDetector, FvgDetector,
            AtrCalculator, DisplacementDetector (§7 of the design notes)
  broker/   BrokerAdapter seam + SimBroker (conservative fills, stop-first)
  backtest/ BacktestEngine + DecisionPipeline (shared with live), v1 strategy,
            Stats, BacktestMain
  ctrader/  Open API wire layer: FrameCodec, OpenApiTransport seam,
            OpenApiConnection (blocking, fetch) + LiveConnection (event-driven,
            live), CTraderClient (auth, symbols, trendbars, subscriptions,
            reconcile), TrendbarMapper
  live/     LIVE demo loop: LiveSession (feed adapter — assembler, canonical
            sequencer, catch-up/gap-fill, reconnect), LiveBroker (market +
            amend-SLTP bracket, exposure lock, staleness guard),
            SpreadRecorder, LiveEngine, LiveMain, SecretsStore
  data/     SnapshotStore — checksummed, content-addressed SQLite snapshots
  fetch/    FetchMain — history fetcher CLI
  log/      Append-only EventLog (JSONL)
src/main/proto/  cTrader Open API proto2 messages
                 (vendored from spotware/openapi-proto-messages @ 3fd8bdd)
```

## Build & test

Requires JDK 21. Gradle wrapper included:

```
./gradlew test     # unit tests — synthetic candles, no network
./gradlew run      # bootstraps config/ipda-config.json, prints config hash
```

## Fetching history (demo feed)

1. Copy `secrets.properties.example` → `secrets.properties`, fill in the app
   client id/secret from openapi.ctrader.com.
2. On the app's page, open **Playground/Sandbox → Get token** (scope
   `accounts` is enough for data) and paste it as `accessToken`.
3. Run:

```
./gradlew fetch --args="--days 730"
```

Writes completed H1+M15 bars for the config instruments into
`data/snapshots.db` as one immutable snapshot; prints the snapshot id
(content hash). Backtests record that id as part of run identity.

## Backtesting

```
./gradlew backtest --args="--snapshot snap-xxxxxxxxxxxx"
```

Replays the snapshot through the engine (H1-only v0 baseline strategy) and
writes `runs/<runId>/{events.jsonl, trades.jsonl, summary.txt}`. The run id is
deterministic from (config hash, snapshot id, code version).

## Live demo loop (local machine only — needs demo.ctraderapi.com)

```
.\gradlew.bat live                                # demo account from secrets
.\gradlew.bat live --args="--flatten-on-exit"     # close positions on Ctrl-C
.\gradlew.bat live --args="--account 48042139"
```

Runs v1 against the demo account through the SAME engine seams as replay:
LiveSession implements `Feed` (completed bars only, canonical order, warmup/
catch-up through the same stream, reconnect with backoff, in-protocol token
refresh persisted to `secrets.properties`), LiveBroker implements
`BrokerAdapter` (market order with provisional relative bracket → exact
absolute SL/TP amend on fill; one position per symbol; staleness guard so
warmup signals never trade). Artifacts land in
`runs/live-<account>-<stamp>/{events.jsonl, trades.jsonl, summary.txt}` —
same shape as a backtest run, stamped (config hash, LIVE-account, code
version). Bid/ask is recorded at decision, fill, and every execution-TF
close (standing rule 5 feed). Ctrl-C leaves positions open under their
server-side bracket; the next session adopts them via reconcile.

## Status

Milestone 1A (scaffold + offline detectors) — done.
Milestone 1B (Open API connectivity + snapshot fetcher) — done.
Milestone 2 (FeedReplayer + SimBroker + H1-only baseline harness) — done.
Design rounds 1–3 (29 Jul 2026) — done; v1 = v0 by measurement (CLAUDE.md).
Live demo loop (feed + broker adapters, run hygiene, spread recorder) — built;
accumulating forward out-of-sample data on the FxPro demo.
