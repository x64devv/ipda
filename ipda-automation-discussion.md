# IPDA Automation — Discussion Notes

**Date:** 26 July 2026 (updated same day — displacement settled)
**Status:** Discovery / architecture discussion. Nothing built yet. Displacement definition agreed (§7).

---

## 0. Terminology

The ICT concept is **IPDA — Inter*bank* Price Delivery Algorithm**, not "Intermarket".
"Intermarket" in ICT refers to something separate: correlated-asset analysis, i.e. SMT
divergence between instruments like ES/NQ or EUR/GBP.

---

## 1. IPDA — core premise

Price is not delivered by classical supply/demand but by an algorithm whose job is to
**seek liquidity** and **rebalance inefficiency**. Everything else follows from that:

- **Draw on liquidity** — price is always heading toward a pool (buyside above old highs,
  sellside below old lows) rather than moving randomly.
- **Inefficiency correction** — FVGs, volume imbalances, BISI/SIBI arrays get revisited
  because the algorithm "repairs" one-sided delivery.
- **PD arrays** — order blocks, breakers, mitigation blocks, rejection blocks as the
  discrete reference points delivery works between.
- **Premium / discount** — dealing range midpoint (equilibrium) governs whether the
  algorithm is offering discount or premium.
- **IPDA data ranges** — the 20 / 40 / 60 trading-day lookback used to frame which
  liquidity the algorithm is currently referencing.

### Honest caveat

There is no public evidence of a literal single algorithm operated across the interbank
market. IPDA is best treated as a **heuristic model** — a framing of liquidity and
inefficiency that describes a lot of observable price behaviour, not a documented
mechanism. Traders who do well with it tend to be good at the liquidity-mapping part;
the "algorithm" framing is scaffolding for that.

---

## 2. Platform / broker research

### TradingView — not viable as an execution layer

- Their **Broker REST API** is the pipe *brokerages* implement so their clients can trade
  from TradingView charts. It is a broker-side integration, not something you call as a user.
- TradingView does not route arbitrary live orders. Native trading works only with
  integrated brokers; for everything else you export alerts via webhook and have an
  external service place the orders.
- Webhook alerts require a paid plan (Pro, ~$30/mo).

**Why it's a bad fit for IPDA work:** alerts are bar-close bound, Pine can't hold real
state, and you'd encode structural logic twice — once in Pine, once in the executor.

**Verdict:** keep TradingView as a visual sanity-check only. Not part of the system.

### XM — no public REST API

- The practical integration surface is **MetaTrader**. XM highlights EAs and MQL4/MQL5
  as the automation path, so the terminal is the supported execution boundary.
- That means writing MQL5, or an EA-to-HTTP bridge with a Windows VPS running a
  terminal 24/5.

**Verdict:** workable but architecturally awkward. Skip.

### Brokers that do expose a real API

| Broker | Protocol | Demo | Bracket orders | Notes |
|---|---|---|---|---|
| **OANDA v20** | REST + streaming (HTTP/JSON) | Free practice account | `takeProfitOnFill` / `stopLossOnFill` attached to entry in one call | Personal access token from account portal. Language-agnostic. Easiest start. |
| **cTrader Open API** | Protobuf or JSON over TCP/WebSocket | Yes — demo and live are fully separated endpoints | `relativeStopLoss` / `relativeTakeProfit` (1/100,000 price units) | Free. Platform-level, so works with any cTrader broker (Pepperstone, IC Markets, FXPro, Axi). Official Java SDK. Ports: 5035 protobuf, 5036 JSON. |
| **Deriv** | REST (account setup) + WebSocket (trading) | Demo account issued automatically on signup | N/A — options-shaped contract model | Broadly accessible from Africa; synthetics run 24/7. Contract model distorts what we're trying to validate. Fallback only. |

**Decision:** ~~prototype on **OANDA v20 REST**, behind a thin `BrokerAdapter` interface so
cTrader can slot in later. Don't marry the broker on day one.~~
**SUPERSEDED (26 Jul, second session):** OANDA does not onboard clients from Zimbabwe.
**cTrader Open API promoted to primary** — see §8. The `BrokerAdapter` interface survives
unchanged; OANDA is demoted to a hypothetical second adapter.

Relevant docs:
- OANDA: https://developer.oanda.com/rest-live-v20/introduction/
- cTrader: https://help.ctrader.com/open-api/
- Deriv: https://developers.deriv.com/docs/

---

## 3. Architecture — deterministic core vs Claude in the loop

**Strong position: make the core deterministic.**

Almost every IPDA primitive is geometric and exactly codable:

- FVG → three-candle gap test
- Swing points → fractal
- Equal highs / lows → tolerance cluster
- Order block → last opposing candle before displacement
- Premium / discount → range midpoint

None of that needs an LLM. Putting one in the hot path costs determinism, latency, money
per bar, and — the killer — **reproducible backtesting**. You cannot walk-forward test a
stochastic judge cheaply.

Where a model *does* earn its place is fuzzy arbitration — the stuff ICT traders do by eye:
which draw on liquidity dominates when there are three candidates, whether displacement is
convincing or noise, ranking competing PD arrays.

### Proposed phasing

**Phase 1 — deterministic engine**
Pure Go / Kotlin. No LLM. Backtestable, reproducible.

**Phase 2 — Claude as offline annotator**
Feed detected structures over historical data to help build a *rules taxonomy*, which then
gets hardened into code. Offline, not in the trade path.

**Phase 3 (optional) — confluence scorer**
Called only at candidate-setup time (a handful of times a day, not per tick). Strict JSON
schema output. Permitted only to **veto or size down — never to originate a trade.**

That last constraint matters: if the model can create trades, performance can't be
attributed to anything.

---

## 4. Language choice

Let the SDK decide.

- **Go** if OANDA — plain REST, goroutines suit the price stream, single binary on a cheap VPS.
- **Kotlin/Java** if cTrader — official Java protobuf SDK exists, and Spring Boot patterns
  carry over.

Either is fine. Not worth over-thinking.

---

## 5. Open questions — to settle before writing code

1. **Instruments and sessions** — ~~IPDA logic is session-anchored.~~ **Settled — see §8.**
2. **Timeframe pairing** — **Settled — see §9.1.** H1 bias → M15 entry refinement;
   engine multi-timeframe-aware from day one; H1-only baseline validated first.
3. **Numerical definition of displacement** — **Settled — see §7.**
4. **Backtester first?** — argued firmly yes. The live loop is the easy part.
5. **Data sources** — **Settled — see §8.** Same-source-for-both dissolves the
   reconciliation problem in v1.

---

## 6. Caveat for later

Demo fills are optimistic. Anything that survives demo still needs a haircut for slippage
on the liquidity sweeps we're specifically trying to trade — which is exactly where fills
are worst.

---

## 7. Displacement — v1 numerical definition (SETTLED, 26 Jul 2026)

Displacement is a property of a **leg**, not a single candle. All conditions are causal
(computable at candle close, no lookahead), so the definition is backtestable as-is.

### Working timeframe

**H1** is the canonical detection timeframe for Phase 1. Rationale: FVGs on H1 are
respected and plentiful enough to matter; ATR is stable; data volume is manageable for
long backtests; and it sits naturally as the future bias timeframe in an H1→M15 pairing.
Caveat acknowledged: killzones are only 2–4 H1 candles wide, so session-anchored logic is
coarse at this resolution — entries will eventually drop to M15, and `T` stays a
parameter throughout the engine so nothing is hard-coded to H1.

### Leg definition (v1)

A maximal run of consecutive same-direction closes on timeframe `T`.
For a bearish leg spanning candles `i..j`: range `R = high(i) − low(j)`. Bullish mirrored.

### Qualifying conditions

| # | Name | Test | Default | Notes |
|---|---|---|---|---|
| A | Energy | `R ≥ k1 × ATR20` | `k1 = 2.0` | ATR computed **as of candle i−1** — the leg must not inflate its own denominator. |
| B | Conviction | `abs(close(j) − open(i)) / R ≥ b` | `b = 0.65` | Aggregate body dominance. Free news-spike filter: both-ways spike candles fail this. |
| C | Imbalance | Leg contains ≥ 1 FVG in leg direction | required | SIBI for bearish, BISI for bullish. Composes two deterministic primitives. |
| D | Speed | Leg length `≤ m` candles | `m = 4` (≈4h on H1) | **Toggleable flag, ON by default** — included from day one so its impact is measurable, and leg length is logged regardless of the toggle so the comparison is a pure query. |

A leg qualifies as displacement when it passes A ∧ B ∧ C (∧ D when the toggle is on).

### Classification (separate from detection)

Detection is context-free. Afterwards: leg closes through the most recent swing point →
**MSS displacement**; otherwise → **displacement into liquidity** within the dealing range.
Keeping these separate keeps both independently testable.

### Engineering requirements

- Binary gate, but **log the continuous values on every leg, qualifying or not**:
  `R/ATR` ratio, body ratio, FVG count, leg length. Threshold sweeps in the backtester
  then become a query over logged data, not a re-run of detection.
- All parameters (`k1`, `b`, `m`, D-toggle, `T`) are config, not constants.

### Known failure mode + planned refinements

- **Failure mode:** the strict same-direction-closes leg splits a genuine displacement
  containing one small pullback candle into two legs that individually fail Condition A.
  First planned refinement: tolerate one inside candle mid-run. Expect backtesting to
  surface this early.
- **v2:** session-baselined ATR (rolling ATR of the same hour-of-day across the last N
  days) — plain ATR is session-blind, so an Asian-session leg can clear `2 × ATR` while
  being trivial by London standards. Tuning, not architecture; v1 ships with plain
  shifted ATR.

---

## 8. Instruments, sessions & data sources (SETTLED, 26 Jul 2026, second session)

### 8.1 Broker flip: cTrader is now primary

OANDA is blocked — it does not onboard clients from Zimbabwe. **cTrader Open API is
promoted from planned-second-adapter to the prototype target**, via a demo account with
any cTrader broker (Pepperstone, IC Markets, FxPro, Axi).

Silver lining: the Open API is *platform-level*, not broker-level. One integration works
across every cTrader broker — switching broker is a credentials change, not a code
change. That's actually a stronger position than the OANDA plan, which married us to one
broker's API. The `BrokerAdapter` interface stays anyway (discipline is free); OANDA
becomes the hypothetical second adapter.

**Language consequence (per §4 rule — follow the SDK): Kotlin on the JVM.** The official
Java/protobuf SDK path, with coroutines suiting the streaming connection.

Build-time verifications (documented facts to confirm against current cTrader docs, not
design unknowns): trendbar-request span limits per period; daily bar alignment / server
timezone; how to source bid/ask history for spread modelling (tick-data requests vs
constructed candles).

### 8.2 Instruments

**EURUSD + GBPUSD only for Phase 1.** They're the pairs the ICT session framework was
built around, with the deepest liquidity and tightest spreads (smallest demo-fill
haircut). Limiting to two guards against multi-instrument parameter fishing — sweeping
`k1` across twelve pairs and believing whichever combination worked. The instrument list
is config, so widening later is trivial.

**XAUUSD is a stress test, not a validation instrument** — displacement-rich but with the
worst sweep-slippage behaviour (§6's caveat incarnate). Add only once the engine is
trusted. No JPY pairs or synthetics in v1.

### 8.3 Sessions

- All internal timestamps are **UTC**. Session tagging is a **pure, causal function**
  candle → labels (Asia, London KZ, NY KZ, London close, …), composable with everything
  else.
- Killzones are defined in **exchange-local time via the tz database** — London KZ in
  `Europe/London`, NY KZ in `America/New_York` — **never fixed UTC offsets**. DST moves
  killzones against UTC twice a year; a hardcoded UTC table is silently wrong for ~⅓ of
  the year. (Harare is UTC+2 year-round, so the drift is visible from here: London KZ
  floats between 09:00–12:00 and 10:00–13:00 local.)
- The session table itself lives in config, like every other magic number.
- Daily boundaries: the engine derives its own daily open (ICT NY-midnight convention)
  from UTC timestamps rather than trusting platform daily bars, whose alignment is
  broker/server dependent. Identical logic for backtest and live, by construction.

### 8.4 Data sources

- **History and live both come from the cTrader feed.** Same source for both dissolves
  the reconciliation problem by construction; reconciliation returns as a measured
  diff-exercise only if/when a second adapter lands.
- **Pull once, cache locally, version the snapshot.** History lands in local storage
  (SQLite or parquet-style flat files) with a checksum; every backtest run records which
  snapshot it ran against. Reproducibility dies the moment "the data" means "whatever
  the API returned today."
- Only **completed candles** feed detection (causality, same as every primitive).
- Backtesting must be **spread-aware** — the strategy enters at sweep extremes, exactly
  where mid-fill assumptions flatter results most. Source of bid/ask history is a
  build-time verification (8.1).

---

## 9. Timeframe pairing, backtester skeleton, broker path (SETTLED, 26 Jul 2026)

### 9.1 Timeframe pairing + validation sequencing

**H1 bias → M15 entry refinement.** The engine is multi-timeframe-aware from day one
(the data model already treats `T` as a parameter), but validation runs in two stages:

1. **H1-only baseline** — signal *and* entry at H1 close. Establishes whether the
   primitives carry edge at all.
2. **H1 + M15 refinement layered on** — M15 entry logic added, its contribution measured
   as a delta against the baseline.

Same philosophy as the toggleable Condition D: every layer's impact is measured, not
assumed. If M15 refinement doesn't improve expectancy over crude H1 entries, we want to
know.

### 9.2 Backtester skeleton

**Core principle: event-driven bar-replay, and the strategy engine must not be able to
tell whether it is live or in replay.** Everything downstream of the feed consumes one
interface — a time-ordered stream of *completed* candles. Backtest: a `FeedReplayer`
walks a versioned snapshot. Live: the cTrader adapter emits the same events. Lookahead
bugs die by construction, and the live loop becomes a feed swap, not a rewrite.

Components:

- **DataStore** — checksummed, versioned candle snapshots (SQLite or flat files).
- **FeedReplayer** — merges multi-instrument, multi-timeframe candles into one
  deterministic time-ordered event stream.
- **Detectors** — pure state machines per primitive (session tagger, swings, FVG,
  legs/displacement, dealing range). Each logs continuous values on every event.
- **Strategy layer** — consumes detector state, emits order intents.
- **SimBroker** — implements the *same* `BrokerAdapter` interface as the live adapter.
  Brackets, spread, slippage haircut.
- **EventLog** — append-only, queryable. This is what makes sweeps queries, not re-runs.
- **Run identity** — every run is the triple **(config hash, snapshot id, code version)**.
  Any result is reproducible from its triple.

Two correctness rules pinned now (where backtesters quietly lie):

- **Intra-candle ambiguity rule:** if one candle's range touches both stop and target,
  OHLC data cannot order them → **assume the stop was hit first**, always. Pessimistic,
  deterministic, biased against us — the only safe direction.
- **Multi-timeframe tie-break:** at e.g. 13:00 both an M15 and an H1 candle complete.
  Fixed documented order: **higher timeframe processed first** (bias state current
  before M15 entry logic runs). The live adapter must buffer and emit in this same
  canonical order despite real-world event jitter.

Fill model v1 (conservative by policy): evaluate at candle close → enter at next candle
open with spread applied → exits resolve intra-candle under stop-first. Limit-fill
trade-through refinements come later; v1's job is to under-promise.

### 9.3 Broker path — development unblocked without a broker

Key research finding: **cTrader offers demo accounts directly with Spotware as the
"broker"** (app.ctrader.com/accounts/create-demo) — a cTrader ID plus Spotware demo
needs no broker onboarding, no KYC, and gives full Open API access (app registration at
openapi.ctrader.com). **Phase 1 development and demo trading therefore do not depend on
any broker accepting Zimbabwe.** The live-broker decision is deferred until the system
has earned a live deployment.

Zimbabwe broker landscape (for the eventual live account; verify at signup time —
third-party lists go stale):

- **Pepperstone — off the list.** Explicitly bans Zimbabwe residents (which likely
  also explains the IC Markets signup failure — same restriction pattern).
- **FP Markets** — front-runner. Runs a dedicated Zimbabwe site (fpmarkets.com/en-zw),
  offers cTrader, ASIC-regulated.
- **FxPro** — major cTrader broker serving international clients broadly; verify signup.
- **Fusion Markets, BlackBull Markets** — listed by comparison sites as available in
  Zimbabwe and both now carry cTrader; verify.

---

## Next step

Design is complete. Next session: **Kotlin project scaffolding** — repo layout, config
schema (all magic numbers from §7–§9), the candle/event data model, and the first
concrete milestone: **fetch + snapshot EURUSD/GBPUSD H1+M15 history from a Spotware
demo account via the Open API**, then the session tagger and swing/FVG detectors, then
displacement per §7. Backtester before live loop, per §5.
