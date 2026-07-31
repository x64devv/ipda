# ipda on a Linux server — management plane + engine containers

Architecture:

```
GitHub Actions (manual): tests → build engine + manager images → push GHCR → SSH: pull, restart manager
                                                                     │
your server ── docker compose ──► ipda-manager  (web UI, 127.0.0.1:8642)
                                       │  creates/controls via docker socket
                                       ├─► ipda-fx-demo   (engine, /opt/ipda/deployments/fx-demo → /data)
                                       └─► ipda-fx-live   (engine, armed live deployment)
```

- **A deployment** = one account + one instrument set + one rendered config
  (its own run identity) + one engine container. Demo and live run side by
  side as separate deployments.
- **The manager is never in the trading hot path** — it renders configs,
  writes credentials, starts/stops containers, and reads artifacts.
- **Demo→live is double-gated**: the manager passes the engine's `--live`
  flag only for deployments armed via the confirmation page (type the account
  id + two checkboxes, forward-demo stats shown), and the engine itself
  refuses `live.ctraderapi.com` without that flag.

## 1. Server prerequisites

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER        # re-login afterwards
timedatectl set-ntp true             # bar grace/staleness use wall-clock sanity
```

The server must reach `demo.ctraderapi.com:5035` (and `live...` later). A
1 vCPU / 1 GB VPS is plenty.

## 2. SSH key for deploys

```bash
ssh-keygen -t ed25519 -f ipda_deploy -N ""
# append ipda_deploy.pub to ~/.ssh/authorized_keys of the deploy user (docker group)
```

## 3. Server layout

```bash
sudo mkdir -p /opt/ipda/deployments
sudo chown -R $USER /opt/ipda
# copy deploy/docker-compose.yml -> /opt/ipda/docker-compose.yml
#   then edit it: set OWNER (lowercase GitHub username) and MANAGER_PASSWORD
```

Deployments themselves are created **from the manager UI** — each gets
`/opt/ipda/deployments/<name>/{deployment.json, config/, secrets.properties, runs/}`.
Credentials are entered in the UI (stored 0600, never displayed back, never
in git or images); the engine persists refreshed tokens there, which is how
an unattended server survives the ~30-day access-token expiry.

## 4. GitHub secrets

Push the repo to a **PRIVATE** GitHub repository first (verify `git status`
shows no secrets/data/runs). Then Settings → Secrets and variables → Actions:
`DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, optional `DEPLOY_PORT`.

## 5. First deploy + first deployment

1. GitHub → Actions → **deploy-live-loop** → Run workflow
   (tests → images → server pull → manager up).
2. Tunnel in: `ssh -L 8642:127.0.0.1:8642 user@server` → open
   `http://localhost:8642` (user `admin`, your `MANAGER_PASSWORD`).
3. Create deployment `fx-demo`: mode **demo**, EURUSD+GBPUSD, lots 0.10,
   account 48042139, paste credentials → Save → **Start**.

## 6. Going live, later

Create a separate deployment (e.g. `fx-live`), mode **live**, its live
account id and live-account credentials → the arm page shows the forward
demo evidence (trades, cum R, PF) next to the backtest reference → type the
account id, tick both boxes, **Arm** → Start. Any mode change disarms.
The demo deployment keeps running alongside — that comparison stream stays
valuable forever.

## 7. Operations

| Task | How |
|---|---|
| Watch a deployment | manager UI (status, R curve, open positions, spreads, container log, events) |
| Apply a new engine image | run the deploy workflow, then **Restart** per deployment in the UI |
| Stop everything | Stop buttons; positions stay under their server-side bracket |
| Raw artifacts | `/opt/ipda/deployments/<name>/runs/live-*/` — same shape as backtests |
| Manager logs | `cd /opt/ipda && docker compose logs -f manager` |

Restarts/redeploys are safe mid-trade by construction: SIGTERM → open state
logged + summary written; brackets live server-side; the next start adopts
positions via reconcile and the feed back-fills missed candles (staleness
guard keeps replayed signals from trading).

## Notes

- Windows remains the workstation for `fetch`/`backtest`; the server runs
  engines + manager only.
- Deriv: parked per the decision log (contract model distorts validation;
  synthetics contradict the model's premise). A Deriv adapter would be a new
  Feed/BrokerAdapter behind the same seams plus a full re-measurement — a
  deliberate future project, not a config change.
