"""Read-only views over a deployment's runs/ artifacts.

The engine writes the same shapes live as in backtests (events.jsonl,
trades.jsonl, summary.txt); everything here is parsing, never mutation —
the manager has no write access to engine state by design.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class OpenPosition:
    symbol: str
    side: str
    entry: float | None
    position_id: int | None
    adopted: bool


@dataclass
class DeploymentStatus:
    run_dirs: list[str] = field(default_factory=list)
    newest_run: str | None = None
    last_event_ts: float | None = None          # unix, from file mtime
    trades: list[dict] = field(default_factory=list)   # across all runs, exit order
    open_positions: list[OpenPosition] = field(default_factory=list)
    spread_means: dict[str, float] = field(default_factory=dict)
    summary_text: str | None = None

    # Connection health (added 4 Aug 2026). A deployment that never
    # authenticates still appends a live_disconnect event every backoff
    # interval, so heartbeat_age_s alone reported the dead 31 Jul run as
    # healthy for four days. Liveness is "did it ever connect", not "is the
    # file being written".
    last_connected_at: str | None = None      # ISO, from the live_connected event
    fatal: dict | None = None                 # payload of live_fatal, if any
    last_disconnect: dict | None = None       # payload of the most recent live_disconnect
    disconnects_since_connect: int = 0

    # derived
    @property
    def cum_r(self) -> float:
        return sum(t.get("r") or 0.0 for t in self.trades)

    @property
    def pf_r(self) -> float | None:
        gw = sum((t.get("r") or 0.0) for t in self.trades if (t.get("r") or 0.0) > 0)
        gl = -sum((t.get("r") or 0.0) for t in self.trades if (t.get("r") or 0.0) < 0)
        if gl <= 0:
            return None
        return gw / gl

    @property
    def equity_curve(self) -> list[float]:
        out, cum = [], 0.0
        for t in self.trades:
            cum += t.get("r") or 0.0
            out.append(cum)
        return out

    @property
    def heartbeat_age_s(self) -> float | None:
        if self.last_event_ts is None:
            return None
        return max(0.0, time.time() - self.last_event_ts)

    @property
    def ever_connected(self) -> bool:
        return self.last_connected_at is not None

    @property
    def health(self) -> tuple[str, str]:
        """(level, human message) — level in fatal | never | flapping | idle | ok.

        Deliberately independent of heartbeat freshness: a retry loop keeps
        the heartbeat fresh while trading nothing.
        """
        if self.fatal:
            return "fatal", (self.fatal.get("cause") or "fatal configuration error").split("\n")[0]
        if not self.run_dirs:
            return "idle", "no run artifacts yet — deployment has never been started"
        if not self.ever_connected:
            cause = (self.last_disconnect or {}).get("cause")
            msg = "never authenticated in this run"
            if cause:
                msg += f" — {str(cause).split(chr(10))[0]}"
            return "never", msg
        if self.disconnects_since_connect >= 3:
            return "flapping", (
                f"{self.disconnects_since_connect} disconnects since the last successful "
                f"connect (last connected {self.last_connected_at})"
            )
        return "ok", f"connected {self.last_connected_at}"


def _read_jsonl(path: Path) -> list[dict]:
    out = []
    if not path.exists():
        return out
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass  # torn tail line of a live file
    return out


def load_status(deployment_dir: Path) -> DeploymentStatus:
    st = DeploymentStatus()
    runs = deployment_dir / "runs"
    if not runs.exists():
        return st
    run_dirs = sorted(
        [d for d in runs.iterdir() if d.is_dir() and d.name.startswith("live-")],
        key=lambda d: d.stat().st_mtime,
    )
    st.run_dirs = [d.name for d in run_dirs]
    if not run_dirs:
        return st
    newest = run_dirs[-1]
    st.newest_run = newest.name

    ev_file = newest / "events.jsonl"
    if ev_file.exists():
        st.last_event_ts = ev_file.stat().st_mtime

    # Parse the newest run's event log ONCE — it is the largest artifact
    # (a leg_eval and a spread_sample per execution-TF close, per symbol)
    # and three separate passes over it was already two too many.
    ev_records = _read_jsonl(ev_file)

    # Trades across every run, in exit order (adopted trades included: they
    # carry adopted=true and r=null and simply don't move the R curve).
    for d in run_dirs:
        for rec in _read_jsonl(d / "trades.jsonl"):
            st.trades.append(rec.get("payload", {}))
    st.trades.sort(key=lambda t: t.get("exitTime") or "")

    # Connection health from the newest run: the last successful auth, any
    # fatal stop, and how many disconnects have piled up since that auth.
    for rec in ev_records:
        typ, p = rec.get("type"), rec.get("payload", {})
        if typ == "live_connected":
            st.last_connected_at = p.get("at") or "(no timestamp)"
            st.disconnects_since_connect = 0
            st.last_disconnect = None
        elif typ == "live_disconnect":
            st.last_disconnect = p
            st.disconnects_since_connect += 1
        elif typ == "live_fatal":
            st.fatal = p

    # Open positions from the NEWEST run only (restart adoption re-logs them).
    open_map: dict[int, OpenPosition] = {}
    pending_symbol_fills: dict[str, OpenPosition] = {}
    for rec in ev_records:
        typ, p = rec.get("type"), rec.get("payload", {})
        if typ == "live_fill":
            pos = OpenPosition(
                symbol=p.get("symbol", "?"), side=p.get("side", "?"),
                entry=p.get("entry"), position_id=p.get("positionId"), adopted=False,
            )
            if pos.position_id is not None:
                open_map[pos.position_id] = pos
            else:
                pending_symbol_fills[pos.symbol] = pos
        elif typ == "live_adopt":
            pos = OpenPosition(
                symbol=p.get("symbol", "?"), side=p.get("side", "?"),
                entry=p.get("entry"), position_id=p.get("positionId"), adopted=True,
            )
            if pos.position_id is not None:
                open_map[pos.position_id] = pos
    closed_ids = {
        t.get("positionId")
        for t in (r.get("payload", {}) for r in _read_jsonl(newest / "trades.jsonl"))
        if t.get("positionId") is not None
    }
    st.open_positions = [p for pid, p in sorted(open_map.items()) if pid not in closed_ids]
    st.open_positions += list(pending_symbol_fills.values())

    # Spread means from the newest run's spread_sample events.
    sums: dict[str, list[float]] = {}
    for rec in ev_records:
        if rec.get("type") == "spread_sample":
            p = rec.get("payload", {})
            s = p.get("symbol")
            v = p.get("spread")
            if s and isinstance(v, (int, float)):
                sums.setdefault(s, []).append(float(v))
    st.spread_means = {s: sum(v) / len(v) for s, v in sums.items() if v}

    summary = newest / "summary.txt"
    if summary.exists():
        st.summary_text = summary.read_text()
    return st


def recent_events(deployment_dir: Path, limit: int = 100) -> list[dict]:
    runs = deployment_dir / "runs"
    if not runs.exists():
        return []
    run_dirs = sorted(
        [d for d in runs.iterdir() if d.is_dir() and d.name.startswith("live-")],
        key=lambda d: d.stat().st_mtime,
    )
    if not run_dirs:
        return []
    events = _read_jsonl(run_dirs[-1] / "events.jsonl")
    return events[-limit:]
