"""Deployment model + config rendering for the ipda management plane.

A DEPLOYMENT is a directory under $IPDA_ROOT/deployments/<name>/ :

    deployment.json        manager-owned metadata (mode, instruments, lots, arming)
    config/ipda-config.json  rendered engine config (hash = run identity)
    secrets.properties     credentials, written from the credentials form (0600)
    runs/                  engine artifacts (events.jsonl / trades.jsonl / summary)

The engine container for a deployment mounts that directory at /data and runs
`bin/ipda-live --config config/ipda-config.json` (+ `--live` ONLY when the
deployment is armed — the engine independently refuses the live host without
that flag, so the confirmation gate is enforced in two places).

Config-hash discipline: a deployment with the DEFAULT instrument set and
default lots renders the bundled control template BYTE-FOR-BYTE (hash
95231af4…), preserving run-identity lineage; anything customized renders a
modified config and honestly gets a new hash.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path

DEMO_HOST = "demo.ctraderapi.com"
LIVE_HOST = "live.ctraderapi.com"

CONTROL_TEMPLATE_PATH = Path(__file__).parent / "control-config.json"
CONTROL_INSTRUMENTS = ["EURUSD", "GBPUSD"]
DEFAULT_LOTS = 0.10

# Per-symbol execution parameters for symbols the project has measured.
# XAUUSD failed its stress test (30 Jul 2026) — selectable, but the UI warns.
KNOWN_SYMBOLS = {
    "EURUSD": {"spreadPips": 0.7, "pipSize": 0.0001},
    "GBPUSD": {"spreadPips": 1.0, "pipSize": 0.0001},
    "XAUUSD": {"spreadPips": 3.5, "pipSize": 0.1},
}
SYMBOL_WARNINGS = {
    "XAUUSD": "Stress test FAILED (30 Jul 2026): avgR −0.031, PF_R 0.954 — no measured edge on gold.",
}

NAME_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,30}$")


def ipda_root() -> Path:
    return Path(os.environ.get("IPDA_ROOT", "/ipda"))


def deployments_root() -> Path:
    return ipda_root() / "deployments"


@dataclass
class Deployment:
    name: str
    mode: str = "demo"                       # "demo" | "live"
    instruments: list[str] = field(default_factory=lambda: list(CONTROL_INSTRUMENTS))
    volume_lots: float = DEFAULT_LOTS
    volume_lots_by_symbol: dict[str, float] = field(default_factory=dict)
    account_id: str = ""
    armed: dict | None = None                # {"armedAt": iso, "typedAccount": str}
    created_at: float = field(default_factory=time.time)
    notes: str = ""

    # ------------------------------------------------------------------
    @property
    def dir(self) -> Path:
        return deployments_root() / self.name

    @property
    def is_live(self) -> bool:
        return self.mode == "live"

    @property
    def is_armed(self) -> bool:
        return self.is_live and self.armed is not None

    @property
    def container_name(self) -> str:
        return f"ipda-{self.name}"

    @property
    def host(self) -> str:
        return LIVE_HOST if self.is_live else DEMO_HOST

    # ------------------------------------------------------------------
    def save(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "runs").mkdir(exist_ok=True)
        (self.dir / "config").mkdir(exist_ok=True)
        (self.dir / "deployment.json").write_text(json.dumps(asdict(self), indent=2))
        self.render_config()

    @classmethod
    def load(cls, name: str) -> "Deployment":
        raw = json.loads((deployments_root() / name / "deployment.json").read_text())
        return cls(**raw)

    @classmethod
    def list_all(cls) -> list["Deployment"]:
        root = deployments_root()
        if not root.exists():
            return []
        out = []
        for d in sorted(root.iterdir()):
            if (d / "deployment.json").exists():
                try:
                    out.append(cls.load(d.name))
                except Exception:
                    pass
        return out

    # ------------------------------------------------------------------
    def is_default_shape(self) -> bool:
        """True when the rendered config should be the control template verbatim."""
        return (
            self.instruments == CONTROL_INSTRUMENTS
            and abs(self.volume_lots - DEFAULT_LOTS) < 1e-12
            and not self.volume_lots_by_symbol
        )

    def render_config(self) -> str:
        """Write config/ipda-config.json; returns its sha256 hex (run identity)."""
        if self.is_default_shape():
            text = CONTROL_TEMPLATE_PATH.read_text()
        else:
            cfg = json.loads(CONTROL_TEMPLATE_PATH.read_text())
            cfg["instruments"] = list(self.instruments)
            cfg["execution"] = {
                "spreadPips": {s: KNOWN_SYMBOLS[s]["spreadPips"] for s in self.instruments},
                "pipSize": {s: KNOWN_SYMBOLS[s]["pipSize"] for s in self.instruments},
                "executionTimeframe": "H1",
            }
            live: dict = {}
            if abs(self.volume_lots - DEFAULT_LOTS) > 1e-12:
                live["volumeLots"] = self.volume_lots
            overrides = {s: v for s, v in self.volume_lots_by_symbol.items() if s in self.instruments}
            if overrides:
                live["volumeLotsBySymbol"] = overrides
            if live:
                cfg["live"] = live
            text = json.dumps(cfg, indent=4) + "\n"
        path = self.dir / "config" / "ipda-config.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return hashlib.sha256(text.encode()).hexdigest()

    def config_hash(self) -> str | None:
        path = self.dir / "config" / "ipda-config.json"
        if not path.exists():
            return None
        return hashlib.sha256(path.read_bytes()).hexdigest()

    # ------------------------------------------------------------------
    def secrets_path(self) -> Path:
        return self.dir / "secrets.properties"

    def secrets_present(self) -> dict[str, bool]:
        """Which credential keys are set — values are never read back into the UI."""
        keys = ["clientId", "clientSecret", "accessToken", "refreshToken", "accountId"]
        present = {k: False for k in keys}
        p = self.secrets_path()
        if p.exists():
            for line in p.read_text().splitlines():
                for k in keys:
                    if line.strip().startswith(f"{k}=") and len(line.strip()) > len(k) + 1:
                        present[k] = True
        return present

    def write_secrets(self, values: dict[str, str]) -> None:
        """Merge non-empty form values into secrets.properties; host follows mode.

        The file stays writable by the engine (it persists refreshed tokens).
        """
        current: dict[str, str] = {}
        p = self.secrets_path()
        if p.exists():
            for line in p.read_text().splitlines():
                if "=" in line and not line.strip().startswith("#"):
                    k, v = line.split("=", 1)
                    current[k.strip()] = v
        for k, v in values.items():
            if v:  # empty form field = keep existing
                current[k] = v.strip()
        current["host"] = self.host
        if self.account_id:
            current["accountId"] = self.account_id
        body = "# managed by ipda-manager — engine persists refreshed tokens here\n"
        body += "".join(f"{k}={v}\n" for k, v in current.items())
        p.write_text(body)
        os.chmod(p, 0o600)

    # ------------------------------------------------------------------
    def arm(self, typed_account: str) -> tuple[bool, str]:
        if not self.is_live:
            return False, "Deployment is not in live mode."
        expected = self.account_id.strip()
        if not expected:
            return False, "Set the live account id on the deployment first."
        if typed_account.strip() != expected:
            return False, "Typed account id does not match the deployment's account id."
        self.armed = {"armedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "typedAccount": typed_account.strip()}
        self.save()
        return True, "Armed."

    def disarm(self) -> None:
        self.armed = None
        self.save()

    def engine_command(self) -> list[str]:
        cmd = ["--config", "config/ipda-config.json"]
        if self.is_armed:
            cmd.append("--live")
        return cmd


def validate_name(name: str) -> bool:
    return bool(NAME_RE.match(name))
