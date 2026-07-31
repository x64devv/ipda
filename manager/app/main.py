"""ipda management plane — FastAPI app.

Runs beside the engine containers, never inside the trading hot path. It
renders configs, writes credentials, starts/stops engine containers, and
reads runs/ artifacts. The demo→live gate lives here (and, independently,
in the engine's --live flag check).

Auth: HTTP Basic, password from $MANAGER_PASSWORD. Bind the port to
127.0.0.1 on the server and reach it over an SSH tunnel.
"""

from __future__ import annotations

import os
import secrets as pysecrets

from fastapi import Depends, FastAPI, Form, HTTPException, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.templating import Jinja2Templates
from pathlib import Path

from . import dockerctl
from .core import (
    Deployment,
    KNOWN_SYMBOLS,
    SYMBOL_WARNINGS,
    validate_name,
)
from .runsdata import load_status, recent_events

app = FastAPI(title="ipda manager")
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))
basic = HTTPBasic(auto_error=False)


def auth(credentials: HTTPBasicCredentials | None = Depends(basic)):
    password = os.environ.get("MANAGER_PASSWORD", "")
    if not password:
        return "no-password-set"  # UI shows a warning banner
    if (
        credentials is None
        or credentials.username != "admin"
        or not pysecrets.compare_digest(credentials.password, password)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            headers={"WWW-Authenticate": "Basic"},
        )
    return credentials.username


def _ctx(request: Request, **kw):
    kw.setdefault("no_password", not os.environ.get("MANAGER_PASSWORD"))
    kw.setdefault("docker_ok", dockerctl.available())
    kw["request"] = request
    return kw


@app.get("/", response_class=HTMLResponse)
def dashboard(request: Request, user: str = Depends(auth)):
    cards = []
    for d in Deployment.list_all():
        st = load_status(d.dir)
        cards.append(
            {
                "d": d,
                "state": dockerctl.container_state(d.container_name),
                "st": st,
                "hash": (d.config_hash() or "")[:8],
                "spark": sparkline_svg(st.equity_curve),
            }
        )
    return templates.TemplateResponse(request, "dashboard.html", _ctx(request, cards=cards))


@app.post("/new")
def new_deployment(request: Request, name: str = Form(...), user: str = Depends(auth)):
    name = name.strip().lower()
    if not validate_name(name):
        raise HTTPException(400, "Name must be lowercase letters/digits/hyphens (2-31 chars).")
    if (Deployment(name).dir / "deployment.json").exists():
        raise HTTPException(400, "Deployment already exists.")
    Deployment(name).save()
    return RedirectResponse(f"/d/{name}", status_code=303)


@app.get("/d/{name}", response_class=HTMLResponse)
def edit(request: Request, name: str, user: str = Depends(auth)):
    d = Deployment.load(name)
    st = load_status(d.dir)
    return templates.TemplateResponse(
        request,
        "deployment.html",
        _ctx(
            request,
            d=d,
            st=st,
            state=dockerctl.container_state(d.container_name),
            hash=d.config_hash(),
            known=KNOWN_SYMBOLS,
            warnings=SYMBOL_WARNINGS,
            secrets_present=d.secrets_present(),
            container_logs=dockerctl.logs_tail(d.container_name, 40),
        ),
    )


@app.post("/d/{name}")
async def save(request: Request, name: str, user: str = Depends(auth)):
    form = await request.form()
    d = Deployment.load(name)

    prev_mode = d.mode
    d.mode = "live" if form.get("mode") == "live" else "demo"
    if d.mode != prev_mode:
        d.armed = None  # any mode change disarms — re-confirm deliberately

    d.instruments = [s for s in KNOWN_SYMBOLS if form.get(f"sym_{s}") == "on"]
    if not d.instruments:
        raise HTTPException(400, "Select at least one instrument.")
    d.volume_lots = float(form.get("volume_lots") or 0.10)
    overrides = {}
    for s in d.instruments:
        v = (form.get(f"lots_{s}") or "").strip()
        if v:
            overrides[s] = float(v)
    d.volume_lots_by_symbol = overrides
    d.account_id = (form.get("account_id") or "").strip()
    d.notes = form.get("notes") or ""
    d.save()

    d.write_secrets(
        {
            "clientId": form.get("clientId") or "",
            "clientSecret": form.get("clientSecret") or "",
            "accessToken": form.get("accessToken") or "",
            "refreshToken": form.get("refreshToken") or "",
        }
    )
    return RedirectResponse(f"/d/{name}", status_code=303)


@app.post("/d/{name}/action")
def action(request: Request, name: str, act: str = Form(...), user: str = Depends(auth)):
    d = Deployment.load(name)
    if act == "start":
        if d.is_live and not d.is_armed:
            raise HTTPException(400, "Live deployment is not armed — confirm on the arm page first.")
        result = dockerctl.start(d.name, d.container_name, d.engine_command())
    elif act == "stop":
        result = dockerctl.stop(d.container_name)
    elif act == "restart":
        result = dockerctl.stop(d.container_name)
        if d.is_live and not d.is_armed:
            raise HTTPException(400, "Live deployment is not armed.")
        result = dockerctl.start(d.name, d.container_name, d.engine_command())
    else:
        raise HTTPException(400, "Unknown action.")
    if result.startswith("docker error"):
        raise HTTPException(500, result)
    return RedirectResponse(f"/d/{name}", status_code=303)


@app.get("/d/{name}/arm", response_class=HTMLResponse)
def arm_page(request: Request, name: str, user: str = Depends(auth)):
    d = Deployment.load(name)
    # Forward-demo evidence across ALL demo deployments — the numbers that
    # should drive this decision are shown next to the button that takes it.
    demo_stats = []
    for other in Deployment.list_all():
        if other.is_live:
            continue
        st = load_status(other.dir)
        if st.trades:
            demo_stats.append(
                {
                    "name": other.name,
                    "n": len(st.trades),
                    "cum_r": st.cum_r,
                    "pf_r": st.pf_r,
                }
            )
    return templates.TemplateResponse(
        request, "arm.html", _ctx(request, d=d, demo_stats=demo_stats)
    )


@app.post("/d/{name}/arm")
def do_arm(
    request: Request,
    name: str,
    typed_account: str = Form(""),
    confirm_risk: str = Form(""),
    confirm_data: str = Form(""),
    user: str = Depends(auth),
):
    d = Deployment.load(name)
    if confirm_risk != "on" or confirm_data != "on":
        raise HTTPException(400, "Both confirmation boxes are required.")
    ok, msg = d.arm(typed_account)
    if not ok:
        raise HTTPException(400, msg)
    return RedirectResponse(f"/d/{name}", status_code=303)


@app.post("/d/{name}/disarm")
def do_disarm(request: Request, name: str, user: str = Depends(auth)):
    d = Deployment.load(name)
    d.disarm()
    return RedirectResponse(f"/d/{name}", status_code=303)


@app.get("/d/{name}/events", response_class=HTMLResponse)
def events(request: Request, name: str, user: str = Depends(auth)):
    d = Deployment.load(name)
    return templates.TemplateResponse(
        request, "events.html", _ctx(request, d=d, events=recent_events(d.dir, 150))
    )


# ----------------------------------------------------------------------
def sparkline_svg(curve: list[float], width: int = 220, height: int = 44) -> str:
    """Single-series cumulative-R sparkline (inline SVG, role-based colors)."""
    if len(curve) < 2:
        return ""
    lo, hi = min(curve + [0.0]), max(curve + [0.0])
    span = (hi - lo) or 1.0
    pad = 3
    n = len(curve)
    pts = []
    for i, v in enumerate(curve):
        x = pad + i * (width - 2 * pad) / (n - 1)
        y = pad + (hi - v) * (height - 2 * pad) / span
        pts.append(f"{x:.1f},{y:.1f}")
    zero_y = pad + (hi - 0.0) * (height - 2 * pad) / span
    return (
        f'<svg class="spark" viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
        f'role="img" aria-label="cumulative R, {curve[-1]:+.1f} after {n} trades">'
        f'<line x1="{pad}" y1="{zero_y:.1f}" x2="{width-pad}" y2="{zero_y:.1f}" class="spark-zero"/>'
        f'<polyline points="{" ".join(pts)}" class="spark-line"/>'
        f"</svg>"
    )
