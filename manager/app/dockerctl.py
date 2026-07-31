"""Engine-container control over the mounted Docker socket.

The manager runs INSIDE a container, so bind-mount sources for the engine
containers must be HOST paths ($HOST_IPDA_ROOT), not the manager's own view
($IPDA_ROOT). Degrades gracefully (status "docker unavailable", actions
disabled) when the socket isn't mounted — e.g. local development.
"""

from __future__ import annotations

import os

try:
    import docker
    from docker.errors import NotFound, DockerException
except Exception:  # pragma: no cover - docker sdk not installed
    docker = None
    NotFound = DockerException = Exception


def engine_image() -> str:
    return os.environ.get("ENGINE_IMAGE", "ghcr.io/OWNER/ipda-live:latest")


def host_ipda_root() -> str:
    return os.environ.get("HOST_IPDA_ROOT", "/opt/ipda")


def _client():
    if docker is None:
        return None
    try:
        c = docker.from_env()
        c.ping()
        return c
    except Exception:
        return None


def available() -> bool:
    return _client() is not None


def container_state(container_name: str) -> str:
    c = _client()
    if c is None:
        return "docker unavailable"
    try:
        return c.containers.get(container_name).status  # running / exited / created…
    except NotFound:
        return "not created"
    except DockerException:
        return "docker error"


def start(deployment_name: str, container_name: str, command: list[str]) -> str:
    """(Re)create and start the engine container for a deployment."""
    c = _client()
    if c is None:
        return "docker unavailable"
    try:
        try:
            old = c.containers.get(container_name)
            old.stop(timeout=30)
            old.remove()
        except NotFound:
            pass
        host_dir = f"{host_ipda_root()}/deployments/{deployment_name}"
        c.containers.run(
            engine_image(),
            command=command,
            name=container_name,
            detach=True,
            working_dir="/data",
            volumes={host_dir: {"bind": "/data", "mode": "rw"}},
            environment={"TZ": "UTC"},
            restart_policy={"Name": "unless-stopped"},
            log_config={"type": "json-file", "config": {"max-size": "20m", "max-file": "5"}},
        )
        return "started"
    except DockerException as e:
        return f"docker error: {e}"


def stop(container_name: str) -> str:
    c = _client()
    if c is None:
        return "docker unavailable"
    try:
        # SIGTERM → engine shutdown hook logs open state + writes summary;
        # positions stay open under their server-side bracket.
        c.containers.get(container_name).stop(timeout=30)
        return "stopped"
    except NotFound:
        return "not created"
    except DockerException as e:
        return f"docker error: {e}"


def logs_tail(container_name: str, lines: int = 60) -> str:
    c = _client()
    if c is None:
        return "(docker unavailable)"
    try:
        return c.containers.get(container_name).logs(tail=lines).decode(errors="replace")
    except NotFound:
        return "(container not created)"
    except DockerException as e:
        return f"(docker error: {e})"
