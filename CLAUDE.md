# CLAUDE.md — ProjectA0IK (IPDA Automation)

Project tracker for Claude sessions. Read this first, and **update it whenever a
decision lands or a phase changes** — this file is the source of truth for project
state; `ipda-automation-discussion.md` holds the full reasoning behind each decision.

## What this project is

An automated trading system built around the ICT **IPDA** model (Interbank Price
Delivery Algorithm — not "Intermarket", which is SMT/correlated-asset analysis).
IPDA is treated as a *heuristic* framing of liquidity-seeking and inefficiency
rebalancing, not a literal documented mechanism.

## Current status

**Milestone 1A landed (26 Jul 2026): Kotlin scaffold + all offline detectors.**
**Milestone 1B landed same day: cTrader Open API connectivity + snapshot fetcher.**
**Milestone 2 landed same day: FeedReplayer + SimBroker + H1-only baseline
backtest harness — 55/55 unit tests green.** `./gradlew backtest --args="--snapshot
snap-xxx"` replays a snapshot through the engine (feed seam — engine can't tell
replay from live), runs the **v0 baseline reference strategy** (see below), and
writes `runs/<runId>/` artifacts stamped with the identity triple
(config hash, snapshot id, code version → deterministic run id).

⚠️ **The v0 baseline strategy is a placeholder, not a settled design decision:**
trade every qualifying MSS displacement in leg direction; stop at the leg's far
extreme; target = 2R from signal close; optional killzone filter (off by
default). It exists purely to make the H1-only baseline measurable. Review and
re-spec before treating its numbers as the strategy's numbers.

**Interim work while Spotware reviews the app (same day, 64/64 tests green):**
- **Pipeline dry-run passed**: `./gradlew synth` writes a seeded SYNTHETIC
  snapshot (source=`synthetic:...`, 43,200 bars) and the real backtest CLI ran
  against it end-to-end; re-running the same identity triple reproduced
  byte-identical trades. Synthetic results mean nothing about markets — never
  mix synthetic and real snapshots in an analysis.
- **Three more PD-array primitives** (offline, unit-tested):
  `LiquidityPoolDetector` (equal highs/lows), `DealingRangeTracker`
  (premium/discount), `OrderBlockDetector`. v1 interpretations below.

The Gradle project lives in `ipda/` (JDK 21, wrapper included; `./gradlew test`).
1A: config schema + SHA-256 config hash, data model, session tagger (DST-tested),
DailyBoundary, fractal swings, FVG, shifted ATR, displacement per §7 with
per-close continuous logging and MSS/into-liquidity classification.
1B: vendored proto2 messages (spotware/openapi-proto-messages @ 3fd8bdd) with
Gradle codegen, TLS frame codec + blocking request/response connection,
CTraderClient (app auth → account-by-token → account auth → symbols → chunked
trendbar fetch with adaptive window halving, throttling, retryAfter handling),
TrendbarMapper (1e5 price units, completed-bars-only rule), SnapshotStore
(SQLite, exact integer prices, content-hash snapshot ids, post-write verify),
FetchMain CLI (`./gradlew fetch --args="--days 730"`).

**Open API app "ipda" registered (26 Jul 2026), ACTIVE 29 Jul 2026.**
Credentials + tokens live in `ipda/secrets.properties` (gitignored; rotate
after dev). Access token ≈30 days — re-mint via the app page Sandbox if
fetches start failing auth. The cloud build sandbox cannot reach
demo.ctraderapi.com; fetch/backtest on real data run on the local Windows
machine (`.\gradlew.bat`); unit tests stay network-free.

**First real data + baseline landed (29 Jul 2026).** Token minted; fetch ran
against the FxPro demo (ctidTraderAccountId 48042139, login 10644317 — the
previously-unverified FxPro↔cTrader-ID link is confirmed working).

- **Snapshot `snap-c8919712b6c5`** (fetched 29 Jul 2026, source
  `ctrader:demo.ctraderapi.com`): EURUSD + GBPUSD, per symbol 12,397 H1 +
  49,588 M15 bars (123,970 total), 2024-07-29 17:00 → 2026-07-29 17:00 UTC.
  Checksum
  `c8919712b6c5454d1217c805703829920cec6f1ab8404bf8fe88c69997ef04fb`,
  post-write verified. **Sanity checks all passed** (29 Jul): 0 duplicate
  opens; all opens UTC-aligned to TF boundaries; gaps = 104 weekends + 4
  holiday gaps (Xmas/New Year 2024 & 2025) only, zero mid-week holes; weekend
  boundaries track the 17:00 New York close/open across DST (Fri last open
  20/21 UTC, Sun first open 21/22 UTC); M15→H1 OHLC aggregation exact for all
  12,396 fully-overlapping hours; 0 OHLC integrity violations; prices in
  plausible ranges.
- **First real H1-only baseline: `run-fd4de222050f`** — identity triple =
  (config hash `95231af41c284f73a6b26e58417c61cd8d8d5a8c32485e4b45c7042661f06bb0`,
  snapshot `snap-c8919712b6c5`, code `0.1.0`). 349 trades, 126W/223L
  (winRate 36.1%), avg R +0.060, profitFactor 1.119, cumulative +20.9R, max
  drawdown 17.2R. Exits: 222 stop / 126 target / 1 end-of-data; only 3 trades
  gapped past the stop (worst −1.29R) — conservative fill model behaving.
  Splits (queries over run artifacts, no re-run): symbols symmetric (EURUSD
  PF 1.14, GBPUSD PF 1.11); BUY PF 1.20 vs SELL PF 1.03; by half-year PF
  1.00 / 1.36 / 1.36 / **0.81 (2026H1)** / 1.85 (only 13 trades). Net stays
  positive under +0.5–1.0 pip/round-trip extra slippage haircut.
  **Verdict: marginal positive edge, regime-dependent, unproven** — v0 is a
  measurement placeholder; these are NOT the strategy's numbers (the ⚠️ above
  and standing rule 5 still apply).

**v1 design round 1 executed (29 Jul 2026) — both context layers REJECTED as
specified; measurement protocol worked exactly as designed.** Code 0.2.0
landed (78/78 tests green): `MarketContext` (sweep tracking + dealing range +
draw pools per symbol), config-gated layers B (`requireSweep`, N=24) and C
(`premiumDiscountOnly`), and a `signal_context` record logged on EVERY
displacement signal (sweep ages/levels, rangePos/zone, killzones,
counterfactual sweep stop, draw target + implied R — decisions D2–D4).
Control equivalence verified: gates-off run under 0.2.0 (`run-e0eee159287e`)
reproduces the v0 trade list **byte-identically**. Cloud container reproduces
the Windows run exactly (same run ids, same bytes).

Findings (in-sample = decisions before 2026-01-01; **holdout untouched** —
no design was confirmed, so no look was spent):
- **Layer C fires zero trades — structurally, not empirically.** Every
  bearish MSS closes in DISCOUNT (412/412) and every bullish MSS in PREMIUM
  (456/456); median rangePos at signal ≈ 1.07. Forced by construction: the
  MSS close breaks the most recent confirmed opposite swing, which IS the
  boundary of the immediate dealing range. Premium/discount evaluated at the
  MSS close against the two-swing range is incoherent with the MSS grammar;
  it belongs at the retracement ENTRY (layer D / M15) or needs a larger
  (HTF/multi-swing) range definition.
- **Layer B (sweep within N=24) degrades the edge in-sample**: A control
  n=238, avgR +0.110, PF 1.231 vs B n=227, avgR +0.054, PF 1.073. Sweeps are
  ubiquitous (every MSS signal has an opposing sweep on record, median age
  ~9–10 candles), so the gate mostly removes nothing — and what it interacts
  with, it makes worse (gated-out signals free exposure slots for weaker
  later signals; B is NOT a pure subset of A).
- **Sweep recency gradient is INVERTED vs the textbook story** (pure-filter
  query over control trades): fresh opposing sweep ⇒ worse. N≤3: PF 0.936;
  N≤6: 0.990; N≤12: 1.030; N≤24: 1.201; N≤48: 1.224. Reading: at H1 with
  swing-level sweeps, a just-swept opposing side flags two-sided chop, not
  fuel. (The inverse gate — require sweep age > K — looks strong in-sample
  but is a data-dredged inversion; if wanted, it must be specced as its own
  designed layer next round and survive its own holdout look.)
- **Draw-target counterfactual (D3 log)**: 195/238 signals have an active
  opposing pool; median implied R of the natural draw is ~1.09 vs the fixed
  2R target — relevant when D3 is revisited.

Run registry: control 0.2.0 `run-e0eee159287e` (config `95231af4…`), layer B
`run-0866e7d99412` (config `31c48b75…`, `config/v1-layerB.json`), layer B+C
`run-dab20db8e0cb` (`a4b42590…`), layer C `run-dda13c675e2f` (`fd210a9c…`).
All runs: snapshot `snap-c8919712b6c5`, code `0.2.0`. Run artifacts are
derived data — regenerate exactly via the identity triple.

**Round 2 executed (29 Jul 2026): M15 retracement entry REJECTED in-sample —
and the rejection is structural, the most informative result yet.** Design
settled first (FVG-midpoint nearest signal close; 24-M15-candle expiry +
cancel-on-invalidation; pdAtEntry as separate toggle; nearest-FVG choice);
code 0.3.0 landed (93/93 tests): limit orders in SimBroker (fill AT limit
never better, openTime≥decision lookahead guard, expiry, invalidation-at-open,
fill-then-stop-first), `executionTimeframe` config (H1 default =
control-equivalent, verified byte-identical), `DisplacementEvent.legFvgs`,
entry-level counterfactuals + legFvgCount in `signal_context`,
`order_cancelled` events.

Findings (in-sample, decisions < 2026-01-01; **holdout still unspent** —
nothing earned a confirmation look):
- **Run ladder**: A (H1 exec) PF 1.231 / A′ (M15 exec, market) PF 1.217 —
  exit granularity is a wash. E (FVG-midpoint limit) **PF 0.915, avgR −0.052,
  maxDD 27.1R** — worse than not trading. E+PD: n=16, no sample.
- **Adverse selection, fully quantified**: fill rate 42% (433/751 limits
  expired). Signals whose limit expired unfilled averaged **+0.365R** as A′
  market entries (+74.8R total left on the table — winners run without
  retracing). Signals that DID retrace to the midpoint averaged **−0.438R**
  as market entries; the 16-pip entry improvement only lifts them to −0.015R.
  **At H1, retracement to the displacement FVG is a failure signal, not an
  entry opportunity — the MSS displacement edge is momentum-shaped.**
- **Killzone dimension (D4 loop closed)**: pure-filter query looked strong
  (in-KZ PF 1.589 vs outside 1.024, in-sample; LONDON_KZ 1.677) — but the
  real `killzoneOnly=true` run (`run-ba84e619929f`) came out **PF 1.082,
  avgR −0.003**. Decomposition: the 97 KZ trades shared with the control keep
  PF 1.594; the 82 NEW trades enabled by freed exposure slots run PF 0.609
  and erase the benefit.
- **Standing protocol lesson (now seen 3×: layer B, entry E, killzones): with
  one-position-per-symbol, no filter evaluation transfers from a pure-subset
  query to the system — freed slots backfill with staler signals that lose.
  Every filter candidate MUST be judged by a full system run.** Corollary
  hypothesis for a future round (pre-registered here, NOT dredged further):
  the edge concentrates in FIRST-in-move signals; a cooldown /
  first-signal-per-direction rule + killzone filter is the natural next
  design candidate.

Round-2 run registry (all snapshot `snap-c8919712b6c5`, code `0.3.0`):
control `run-e3c39937e20d` (config `95231af4…` — trade-equivalent to v0),
A′ `run-8761f8feb435` (`v2-m15exec.json`), E `run-6de1625b067b`
(`v2-entryE.json`), E+PD `run-e161e6233751` (`v2-entryEPD.json`),
killzoneOnly `run-ba84e619929f` (`v2-killzone.json`).

**Round 3 executed (29 Jul 2026): design space of the brief EXHAUSTED — v0
wins every pre-registered comparison. v1 = v0, no longer a placeholder but
the measured survivor.**
- **Freshness/cooldown hypothesis killed at first checkpoint**: outcome vs
  gap-to-previous-signal is U-shaped (0–8h PF 1.58, 8–24h 1.20, 24–72h 0.94,
  >72h 1.55), no monotone gradient to gate on; same-dir vs opp-dir previous
  signal indistinguishable (PF 1.34 vs 1.33). No layer built — a time-based
  cooldown would fit the U's noise.
- **D2/D3 counterfactuals, exact replay queries** (walker over snapshot
  candles reproduces SimBroker on all 238 in-sample control trades with 0
  mismatches — the numbers below are exact under the documented pessimism,
  per-trade, entry set held fixed):
  fixed target 1.0R PF 1.095 / 1.5R 1.141 / **2.0R 1.253 (current — the
  optimum of the a-priori grid)** / 3.0R 1.204; draw-with-2R-fallback (D3-B)
  1.167; draw≥1.5R-else-skip (D3-C) 1.091 (n=117); sweep-extreme stop (D2-B)
  cumR −7.8 vs +29.7 — all rejected.
- Scorecard across rounds 1–3: sweep gate ✗, PD-at-close ✗ (structural),
  retracement entry ✗ (adverse selection), PD-at-entry ✗ (no sample),
  M15 exit granularity ≈, killzones ✗ (system level), cooldown ✗ (no
  gradient), all alternative targets ✗, alternative stop ✗. **Every
  dimension of `strategy-design-brief.md` is now measured.**
- **Holdout integrity note (honest bookkeeping)**: the very first baseline
  run (morning, full sample) predates the 70/30 protocol adoption, so the
  2026H1 weakness (PF 0.81) was already observed once. The holdout is
  partially contaminated for the baseline itself; treat any formal holdout
  read of v1 accordingly.

**Design phase closed. v1 spec (settled by measurement): every qualifying
H1 MSS displacement, market entry next open, stop at leg extreme, fixed 2R
target, no filters, one position per symbol (the exposure constraint is
load-bearing — it implicitly selects first-in-cluster signals).** Economics
in-sample: +26R / 17 months, PF 1.23, maxDD 17.2R — thin and
regime-dependent; not deployment-grade on backtest evidence alone.

**Live demo loop BUILT (29 Jul 2026, code 0.4.0, 113/113 tests green).**
v1 now runs live against the FxPro demo through the SAME seams as replay —
`.\gradlew.bat live` on the local Windows machine (cloud can't reach
demo.ctraderapi.com). Design decisions settled this session: shutdown leaves
positions open under their server-side bracket (time-based exits would
pollute the forward R distribution; `--flatten-on-exit` overrides), volume
0.10 lots/trade (R stats are size-independent; config `live.volumeLots`),
tick-history puller (`ProtoOAGetTickDataReq`) deferred — the live spread
recorder already feeds standing rule 5.

What landed (all live magic numbers in the new `live` config section,
defaults chosen so the control config file's bytes — and hash `95231af4…` —
are unchanged):
- **Transport**: `OpenApiTransport` seam; the proven blocking
  `OpenApiConnection` (fetch) and a new event-driven `LiveConnection`
  (reader thread, clientMsgId-correlated request futures, event dispatch,
  10s heartbeats) both implement it; `CTraderClient` works over either and
  gained reconcile + spot/live-trendbar subscriptions.
- **Live feed** (`LiveSession` implements `Feed`): completed bars only via
  per-series `BarAssembler` (roll detection + wall-clock grace for weekend
  silence); canonical order enforced by `CanonicalSequencer` (close-time
  buckets, settle window ≈2s, late stragglers emitted immediately + logged
  `feed_order_warning`); warmup/catch-up (30d) and per-series gap-fill
  through the same stream so detector state is seamless across restarts and
  reconnects; reconnect with exponential backoff; in-protocol token refresh
  persisted back to `secrets.properties`.
- **Live broker** (`LiveBroker` implements `BrokerAdapter`): market order
  carrying a PROVISIONAL RELATIVE bracket (from the decision price — never
  naked), amended to the EXACT ABSOLUTE leg-extreme stop + 2R target on the
  entry fill; one position per symbol enforced as in SimBroker (the exposure
  lock is load-bearing); STALENESS GUARD (180s) so warmup/catch-up signals
  never trade; open positions adopted via reconcile at startup; every
  ack/execution/error event logged raw (`live_exec`) before interpretation.
- **Decision path extraction**: `DecisionPipeline` now carries the
  per-bias-candle path (detectors → context → signal_context → strategy →
  submit) for BOTH BacktestEngine and LiveEngine — live and replay run the
  same code by construction, differing only behind the broker seam.
  **Equivalence verified, both snapshots**: 0.3.0 vs 0.4.0 — trades.jsonl
  AND events.jsonl byte-identical on the synthetic pipeline
  (snap-12418336fb7c) AND on the real snapshot: the 0.3.0 build reproduced
  registry control `run-e3c39937e20d` exactly, and the 0.4.0 control run on
  snap-c8919712b6c5 is **`run-4d7597f3c1bb`** (config `95231af4…`, 349
  trades, PF 1.119 full-sample) with byte-identical artifacts. Config hash
  unchanged.
- **Spread/fill recorder** (standing rule 5 feed): bid/ask logged at
  DECISION (`live_submit`), FILL (`live_fill`, with signed slippage vs the
  decision price), and every execution-TF close (`spread_sample`);
  aggregates (n/mean/max per symbol) land in the live summary.
- **Artifacts**: `runs/live-<account>-<UTCstamp>/{events.jsonl,
  trades.jsonl, summary.txt}` — same shape as a backtest run, stamped
  (config hash, LIVE-account, code version); summary rewritten after every
  closed trade so a crash leaves current state. Live trades record REAL
  two-sided prices (net == gross, no synthetic spread haircut).

Next: start the loop on the local machine, let it accumulate forward
out-of-sample trades + spread data; weekly `fetch` snapshots remain the
reconciliation/history path. Tick-history puller and any strategy work stay
parked — new information beats new parameters.

**XAUUSD stress test executed (30 Jul 2026) — v1 does NOT transfer to gold;
gold earns NO live slot.** Snapshot `snap-d7785dcd81ef` (XAUUSD H1+M15, 730d
to 2026-07-30, 58,981 bars; fetched via new `fetch --config` flag; config
`config/v3-xauusd.json`, hash `051b4794…`, spread haircut 0.35/round-trip =
pessimistic FxPro demo gold; pipSize 0.1). Zero re-tuning of detectors (all
ATR-relative by design — that's the point of a stress test). Cloud
reproduced the Windows control run byte-for-run-id (`run-cf1a6e1c95f5`).

- **Control (v1 rules verbatim): 231 trades, WR 32.9%, avgR −0.031,
  cumR −7.3, PF_R 0.954, maxDD 20.5R** — breakeven-to-negative. In-sample
  (pre-2026) is no better (avgR −0.039). Note price-unit PF (1.088) and
  R-based PF disagree on gold because risk sizes vary hugely — R-based is
  the honest metric; the FX numbers agree under both.
- **Structural read: the failure is one-sided.** BUY avgR +0.143 vs SELL
  −0.265 over a 2-year gold bull market — the symmetric MSS grammar bleeds
  on counter-trend shorts. Any gold revival would start from a directional/
  HTF-bias hypothesis, not from parameter tuning.
- **Full variant grid re-run as FULL SYSTEM runs (protocol rule 6)**:
  layerB sweep gate `run-57d7c3ff71a9` (PF_R 0.941, in-sample 0.846 — worse,
  same as FX); killzoneOnly `run-b559618ab0c2` (n=109, PF_R 1.070, in-sample
  1.147 — the only positive full run, but small, in-sample-led, and the same
  pure-filter mirage already exposed on FX); M15 exec `run-08cd3c5a15dd`
  (0.923 — worse); FVG-limit entry E `run-4bdce64f9a8e` (1.010 ≈ breakeven;
  interestingly adverse selection is NOT worse on gold, unlike FX); E+PD
  `run-cc02063a99ed` (n=11 — no sample). Variant configs kept as
  `config/v3-xauusd-*.json`.
- **Counterfactual queries (round-3 walker rebuilt, 0/231 mismatches vs
  SimBroker)**: target grid 1R PF_R 0.892 / 1.5R 0.963 / 2R 0.954 /
  **3R 1.112 (+19.1R)**, in-sample 3R 1.166. A wider target is the only
  thing that helps gold — consistent with bull-trend winners running — but
  it is a post-hoc grid read on one instrument-regime, exactly the dredging
  rule 6/D6 exist to block. Pre-registered hypothesis IF gold is ever
  revisited: HTF-bias-aligned longs + ≥3R target, judged by full system run
  on fresh data. Sweep-extreme stop: PF_R 0.888, maxDD 31.8R — rejected
  again (now 2/2 instruments).
- **Verdict: consistent with the FX scorecard — the v1 edge is
  momentum-shaped and instrument-thin; gold stays out of the live set.**
  Decision table's "XAUUSD as stress test only" stands confirmed.

**Server deployment path added (30 Jul 2026).** The loop can run 24/7 on a
Linux server under Docker: `ipda/Dockerfile` (multi-stage → JRE-21 image
whose entrypoint is the new `bin/ipda-live` launcher, added to the Gradle
distribution), `deploy/docker-compose.yml` (server-side; state volume
`/opt/ipda/data` holds config/, a HAND-CREATED writable secrets.properties —
token refresh persists there — and runs/), and
`.github/workflows/deploy.yml` (manual trigger: 116 unit tests → build+push
image to GHCR → SSH pull+restart; GitHub Actions never RUNS the loop —
runners are ephemeral). Deploys are safe mid-trade by construction: SIGTERM
→ shutdown hook logs open state; positions keep their server-side bracket;
restart adopts via reconcile and catch-up back-fills the gap. Setup guide:
`deploy/SERVER-SETUP.md`. Repo must be PRIVATE; secrets never in git or in
the image (`.dockerignore` enforces the latter).

**Management plane built (30 Jul 2026, engine 117/117 tests + manager smoke
suite).** `manager/` — a Python/FastAPI container beside the engines, NEVER
in the trading hot path. A DEPLOYMENT = one account + one instrument set +
one rendered config (own run identity) + one engine container
(`/opt/ipda/deployments/<name>/`); demo and live run side by side. Features:
per-deployment dashboard (cum-R sparkline, PF_R, open positions from
artifacts, spread means, container state/logs), instrument selection with
per-symbol lot sizes (engine gained `live.volumeLotsBySymbol`; gold selection
shows its failed-stress-test warning), credential entry (0600, never echoed,
host follows mode; token refresh persists), start/stop/restart via docker
socket. **Demo→live is double-gated**: manager arms a live deployment only
after typing the account id + two confirmations (forward-demo stats shown
beside the backtest reference), and the ENGINE independently refuses
live.ctraderapi.com without its new `--live` flag (passed only when armed;
any mode change disarms). Config-hash continuity: a default-shape deployment
renders the control template byte-for-byte (hash `95231af4…`); anything
customized honestly gets a new hash. Compose now runs ONLY the manager
(127.0.0.1:8642, SSH tunnel; basic auth via MANAGER_PASSWORD); deploys pull
new images but engines apply them only via per-deployment Restart. Deriv
request considered and PARKED per the decision table (contract model
distorts validation; synthetics contradict the liquidity premise) — a Deriv
adapter would be a new Feed/BrokerAdapter + full re-measurement.

**DEPLOYED TO PRODUCTION SERVER (31 Jul 2026).** Repo: github.com/x64devv/ipda
(PRIVATE; root = ProjectA0IK; secrets verified absent). Pipeline green
end-to-end: test → build+push (ghcr.io/x64devv/ipda-live + ipda-manager) →
SSH deploy; secrets live in the GitHub environment **`ipda`** (deploy job
must reference `environment: ipda`). Server: existing production box
(srv1282688, shared with other services) — manager at /opt/ipda behind the
box's nginx-proxy + acme-companion (VIRTUAL_HOST/LETSENCRYPT_HOST, TLS +
basic auth; no host port). Setup lessons burned into the repo: repo must be
rooted at ProjectA0IK (not ipda/); `git update-index --chmod=+x ipda/gradlew`
(Windows drops the exec bit; Docker build needs it); ipda/.gitignore
anchors `/data/` and `/runs/` (unanchored `data/` hid the ipda.data source
package); deploy script has `set -e` and NO `docker logout` (the box's other
services share the GHCR login); `.github/workflows/` is UNWRITABLE via
remote tools — user edits it locally. Engine images apply via per-deployment
Restart in the manager UI. Next: create fx-demo deployment in the manager,
accumulate forward demo data; rotate cTrader tokens (they appeared in dev
screenshots).

**Concurrent per-instrument live sessions hardened (30 Jul 2026, 116/116
tests).** The "split executors" model: each instrument class runs as its OWN
live process with its OWN config (`gradlew live --args="--config
config/<x>.json"`) — config hash = run identity, symbols independent under
one-position-per-symbol, artifacts stay per-config. Fixes for same-account
concurrency: order errors now clear a pending entry only when the error's
orderId was learned from OUR order's ACCEPTED event (a second session's or a
manual order's error can no longer kill an unrelated pending); a pending
entry stuck >60s (config `live.pendingEntryTimeoutSeconds`) is reaped by
pump-loop housekeeping so a pre-ACCEPTED rejection can't block a symbol's
slot forever.

## Decisions to date

| Decision | Choice | Where argued |
|---|---|---|
| Execution broker (prototype) | **cTrader Open API** (platform-level — Pepperstone / IC Markets / FxPro / Axi demo), behind a thin `BrokerAdapter` interface. **OANDA blocked — doesn't onboard from Zimbabwe**; demoted to hypothetical second adapter | notes §2, §8.1 |
| TradingView | Visual sanity-check only — not part of the system | notes §2 |
| XM / MetaTrader | Rejected (no public REST API; EA bridge too awkward) | notes §2 |
| Deriv | Fallback only (contract model distorts validation) | notes §2 |
| Core architecture | **Deterministic engine, no LLM in the hot path.** Claude allowed only as offline annotator (Phase 2) and optional veto/size-down confluence scorer (Phase 3) — never trade origination | notes §3 |
| Build order | **Backtester before live loop** | notes §5 |
| Language | **Kotlin (JVM)** — follows the cTrader Java/protobuf SDK per the follow-the-SDK rule | notes §4, §8.1 |
| Working timeframe | **H1** canonical for Phase 1; timeframe `T` stays a config parameter; eventual H1-bias → M15-entry pairing anticipated | notes §7 |
| Displacement definition | **Settled** — leg-based, conditions A (energy, `k1×ATR` shifted), B (body ratio ≥ 0.65), C (≥1 directional FVG), D (speed cap, **toggleable, on by default**). Log continuous values on every leg. | notes §7 |
| Instruments (Phase 1) | **EURUSD + GBPUSD only** (config list). XAUUSD later as stress test only; no JPY/synthetics in v1 | notes §8.2 |
| Sessions | UTC timestamps internally; killzones defined in **exchange-local tz** (`Europe/London`, `America/New_York`) via tz database — never fixed UTC offsets. Session table in config. Engine derives its own NY-midnight daily boundaries | notes §8.3 |
| Data sources | **History + live both from the cTrader feed** (no reconciliation problem in v1). Pull once, cache locally, checksum + versioned snapshots; backtests record snapshot id. Completed candles only. Spread-aware backtesting required | notes §8.4 |
| Timeframe pairing | **H1 bias → M15 entry refinement.** Engine multi-TF-aware from day one; validation runs **H1-only baseline first**, M15 layered on as a measured delta | notes §9.1 |
| Backtester skeleton | **Event-driven bar-replay; engine cannot tell live from replay** (feed abstraction). Run identity = (config hash, snapshot id, code version). Stop-first intra-candle ambiguity rule; HTF-first tie-break at simultaneous closes; conservative fill model (next-open entry + spread) | notes §9.2 |
| Demo / dev account | **FxPro account created (26 Jul 2026)** — FxPro is a full cTrader broker, so it slots straight into the Open API path via cTrader ID. Spotware direct demo (app.ctrader.com) remains the zero-dependency fallback. **FxPro is now also the live-broker front-runner** (proven Zimbabwe onboarding); FP Markets is the alternate (Pepperstone/IC Markets restricted) | notes §9.3, HANDOFF.md |
| v1 setup grammar (settled 29 Jul 2026) | ~~Grammar C~~ **REVERSED same day by measurement: grammar A (v0 rules) stays the v1 base.** Layer B degraded the edge in-sample (PF 1.231→1.073); layer C is structurally incoherent at the MSS close (0 trades — the MSS close is always beyond the range edge). Both remain as config toggles (off). Premium/discount moves to the M15 retracement entry (layer D) | status above; brief D1 |
| v1 stop model (settled 29 Jul 2026) | **Leg extreme (= v0)**; sweep-extreme stop logged as a counterfactual on every trade — comparison is a query, never a re-run | brief D2 |
| v1 target model (settled 29 Jul 2026) | **Fixed 2R (= v0)**; opposite-liquidity (draw) target distance + implied R logged on every signal — B/C target models evaluated from the event log before being coded as behaviour | brief D3 |
| Killzones at H1 (settled 29 Jul 2026) | **OFF** for the H1 round (sample size); killzone membership logged per trade; real test deferred to M15 | brief D4 |
| Evaluation protocol (settled 29 Jul 2026) | **70/30 chronological split** of snap-c8919712b6c5: design/argue on ~Aug 2024→early Jan 2026 in-sample; **one look at the holdout per design round**, no iterating against it. Note: the losing 2026H1 regime sits in the holdout by construction. Metrics: trade count, avg R, PF, maxDD(R); nothing believed under ~50 in-sample trades; slippage sweep 0/0.5/1.0 pips must survive the pessimistic end | brief D6 |
| OB vs FVG weighting | Deferred to the M15 entry-model phase (brief Q5 default) | brief D1/Q5 |

## v1 implementation interpretations (fixed during build, 26 Jul 2026)

Small ambiguities in the spec that had to be pinned to code. Each is documented in
the source and changeable in one place; none contradicts a settled decision.

- **Candle direction** = close vs open (body direction). An exact doji
  (close == open) terminates the current leg and belongs to no leg.
- **Leg evaluation cadence**: the growing leg i..j is evaluated (and its continuous
  values logged) at **every** close, not only at leg completion. The displacement
  event fires at the **first** qualifying close, once per leg. A leg that keeps
  qualifying afterwards logs but does not re-fire.
- **Condition C attribution (v1)**: an FVG belongs to the leg iff its **middle
  candle is inside the leg** and its third candle closed within the leg; the first
  candle of the triple **may be the candle immediately before the leg** — so a
  2-candle burst can satisfy C via the pre-leg candle.
- **ATR method**: config choice `SMA` (simple mean of true ranges, v1 default) or
  `WILDER`. ATR warmup ⇒ Condition A fails (leg can't qualify), values still logged.
- **Range is spec-literal**: bearish `R = high(i) − low(j)` (bullish mirrored) —
  first/last candle extremes, not max/min over the leg.
- **Swings are strict fractals**: equal extremes do NOT form a swing (equal
  highs/lows are a liquidity primitive, not structure). Wing = 2 default, in config.
- **MSS classification**: uses the most recent **confirmed** opposite swing as of
  the qualifying close, and the swing must sit **before the leg started**; close
  beyond its price ⇒ MSS, else into-liquidity.
- **Session tagging**: a candle is tagged by its **open time**; windows are
  [start, end) on the local wall clock of the session's own tz; start > end means
  the window crosses local midnight (Asia 20:00→00:00 America/New_York).
- **Canonical feed order** (contract in `ipda.feed`): close time, then HTF first,
  then symbol ascending.
- **SimBroker fill conventions (v1)**: candle prices treated as one-sided (bid);
  full configured spread charged once per round trip in net results. Entry at
  next candle open as-is (gaps taken at open). Stop gapped through → exit at the
  worse open; target gapped through → exit AT the target level, never better.
  Stop-first applies from the entry candle itself. One position/pending per
  symbol. Sizing fixed; results in price units + R multiples.
- **Engine per-candle order**: broker first (fills at open, exits intra-candle),
  then detectors at close, then strategy at close (fills next open). Non-bias-TF
  candles flow through the stream untouched in v1.
- **Liquidity pools (equal highs/lows)**: ≥2 confirmed same-type swings within
  tolerance (config, pips) cluster into a pool; pool level = cluster EXTREME
  (max of equal highs / min of equal lows — where stops rest). Swept when a
  later candle trades strictly through the level; touch exactly at it is not a
  sweep. Swept pools stay in the record but stop accepting members.
- **Dealing range**: spanned by the most recent confirmed swing high + swing
  low (min/max normalized). Equilibrium = midpoint; above = premium, below =
  discount; continuous 0..1 range position logged for sweeps-as-queries.
- **Order blocks**: last opposing candle before a qualifying displacement leg,
  located when the event fires; backward scan skips dojis, capped by config
  lookback (default 10, no block if none found). Zone = full candle range
  (config body-only option). First-touch mitigation tracked.

## Build-time verifications (resolved 26 Jul 2026, against help.ctrader.com)

- **Endpoints:** demo `demo.ctraderapi.com:5035`, live `live.ctraderapi.com:5035`
  (protobuf/TLS; port 5036 = JSON/WebSocket). Demo and live fully isolated.
- **OAuth:** auth URL `id.ctrader.com/.../grantingaccess`, token exchange at
  `openapi.ctrader.com/apps/token`; access token ≈30 days, refresh token
  non-expiring (`ProtoOARefreshTokenReq` also works in-protocol). **Playground/
  Sandbox on the app page mints tokens with no redirect server** — the dev path.
- **Trendbar span limits:** per-period caps exist but current docs don't publish
  numbers → fetcher is limit-agnostic (conservative windows ≈1500 bars, halve-
  and-retry on boundary errors and on `hasMore`, dedupe by open time).
- **Trendbar wire format:** prices int64 in 1/100000 units; `low` absolute +
  `deltaOpen/High/Close`; `utcTimestampInMinutes` = UTC open minute.
- **Spread modelling source:** `ProtoOAGetTickDataReq` (BID/ASK tick history,
  ≤1 week per request, `hasMore` paging) — implement when spread model lands.
- **Daily bar alignment:** moot for v1 — engine derives its own NY-midnight
  boundaries and only fetches H1/M15.

## Open questions

1. None blocking. (Trendbar span caps unpublished — handled adaptively; revisit
   only if fetch performance matters.)

## Conventions / standing rules

- Every detection primitive must be **causal** (computable at candle close, no lookahead)
  and deterministic — reproducible backtesting is non-negotiable.
- Thresholds are binary gates but underlying continuous values are always logged, so
  parameter sweeps are queries, not re-runs.
- All magic numbers (`k1`, body ratio, `m`, `T`, toggles) live in config.
- Any LLM component may **veto or size down, never originate** a trade.
- Demo-fill results get a slippage haircut before being believed — the strategy trades
  liquidity sweeps, exactly where fills are worst.

## Files

- `ipda/` — the Kotlin/Gradle project (see `ipda/README.md`). `./gradlew test` runs
  the offline suite; `./gradlew run` bootstraps `config/ipda-config.json` and prints
  the config hash; `.\gradlew.bat live` runs the live demo loop (local machine only).
- `ipda-automation-discussion.md` — running discussion notes; full reasoning and specs.
- `HANDOFF.md` — build→testing handoff brief, now executed (29 Jul 2026) and
  superseded by the status above; keep for reference or archive.
- `CLAUDE.md` — this file; state summary. Keep in sync with the others.
