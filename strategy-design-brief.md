# v1 Strategy Design Brief — replacing the v0 baseline placeholder

**Date:** 26 July 2026 · **Status:** open for decision — nothing here is settled
**Context:** the engine, detectors, and backtest harness are built. The v0
baseline (trade every MSS displacement, stop at leg extreme, 2R target) is a
measurement placeholder. This brief lays out the decision points for the v1
strategy so each can be argued, settled, and recorded in CLAUDE.md like every
other decision. All primitives referenced below already exist in code.

The IPDA premise gives the skeleton: price seeks liquidity, then rebalances
inefficiency. A displacement is only *meaningful* in context — what liquidity
was just taken, and where is price drawing to next? The v0 baseline ignores
context entirely; v1 is about adding it in measurable layers.

---

## Decision 1 — Setup grammar: what sequence constitutes a trade idea?

The classic IPDA/ICT sequence is:

> sweep resting liquidity → displace away (MSS) → retrace into the
> displacement's inefficiency (FVG) or order block → continue toward the
> opposite pool.

Candidate grammars, in increasing strictness:

- **A. Displacement-only** (= v0). No context. Already implemented; serves as
  the control.
- **B. Sweep-then-displacement:** require that a liquidity pool (equal
  highs/lows) or a prior swing extreme was swept within the last N candles
  (config) *against* the eventual trade direction — i.e. buyside taken, then
  bearish MSS ⇒ short. Uses `LiquidityPoolDetector` sweeps. One new config
  number (N).
- **C. B + premium/discount filter:** shorts only from premium, longs only
  from discount (`DealingRangeTracker` at signal close). Zero new numbers.
- **D. C + retracement entry:** don't enter at next open; place a limit at the
  displacement FVG midpoint / order block edge, cancel if unfilled within M
  candles. This is the first *entry model* change — it belongs to the M15
  refinement phase per §9.1 and can wait.

**Recommendation:** implement B and C as config-gated layers on the H1
baseline (each measurable as a delta, same philosophy as Condition D). Defer D
to the M15 phase. The harness can then answer: does context actually pay?

## Decision 2 — Stop placement

- **A. Leg extreme** (= v0): stop at the far end of the displacement leg.
- **B. Sweep extreme:** stop just beyond the swept pool's level (the "real"
  invalidation under the sweep narrative — if price trades back through the
  swept level, the story is wrong). Slightly tighter than A in most cases.
- Buffer: fixed pips vs ATR fraction (one config number either way).

**Recommendation:** A for v1 (already built, fewer numbers), B logged as a
counterfactual on every trade (both stops evaluated in the event log → the
comparison is a query, standing rule 2).

## Decision 3 — Target model

- **A. Fixed R multiple** (= v0, 2R).
- **B. Opposite liquidity:** target the nearest unswept opposing pool /
  dealing-range extreme (draw on liquidity — the IPDA-native answer). Falls
  back to fixed R when no pool exists.
- **C. Hybrid:** take B but require its implied R ≥ a floor (e.g. 1.5R), else
  skip the trade.

**Recommendation:** start with A (control), log the B target's distance on
every signal so B/C can be evaluated from the event log before being coded as
behaviour.

## Decision 4 — Session anchoring

Killzone filter exists (config, off by default). Decide: is the v1 claim
"displacements work" or "displacements work *in killzones*"? IPDA doctrine
says the latter, but H1 granularity makes killzones only 2–4 candles wide —
the filter will cut trade count hard.

**Recommendation:** keep OFF for the H1 baseline (sample size), evaluate as a
logged dimension, revisit at M15 where killzones are 8–16 candles.

## Decision 5 — Risk / trade management (v1 scope only)

Fixed one-position-per-symbol, no pyramiding, no break-even moves, no partial
exits — all deferred. Sizing stays notional (results in R multiples). The only
v1 question: skip signals within X candles of a still-open position's entry?
Current behaviour: broker rejects while exposed (equivalent to X = position
lifetime). **Recommendation:** keep as is.

## Decision 6 — Evaluation protocol (how we'll judge any of this)

- Baseline control = v0 on the same snapshot; every layer (B, C, killzones)
  reported as a delta against it, not in isolation.
- Split the fetched history: parameter choices argued on the first ~70%
  (in-sample), confirmed once on the held-out remainder. One look at the
  holdout per design round — no iterating against it.
- Metrics: trade count, avg R, profit factor, max drawdown in R; nothing
  believed under ~50 trades in-sample.
- Slippage haircut (standing rule 5) applied as a per-trade pips penalty
  sweep (0 / 0.5 / 1.0 pips) — edge must survive the pessimistic end.

---

## What I need from you

1. Which setup grammar for v1 — B, C, or straight to something stricter?
2. Stop model: agree with A-plus-logged-B?
3. Target model: agree with A-plus-logged-B?
4. Killzones off for the H1 baseline round?
5. Anything in the classic sequence you want treated differently from the
   textbook ICT reading (e.g. how much weight order blocks get vs FVGs in the
   eventual entry model)?

Answer in any order; each answer gets recorded as a settled decision in
CLAUDE.md and implemented as a config-gated, individually measurable layer.
