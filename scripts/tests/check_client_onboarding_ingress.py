#!/usr/bin/env python3
"""Exercise the repository Caddy routes and secret-safe error logs using a disposable local process."""
import argparse
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time


def replace_upstream(value):
    if isinstance(value, dict):
        if value.get("handler") == "reverse_proxy":
            value["upstreams"] = [{"dial": "127.0.0.1:1"}]
        for child in value.values():
            replace_upstream(child)
    elif isinstance(value, list):
        for child in value:
            replace_upstream(child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--caddy", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    env = dict(os.environ, ACME_EMAIL="synthetic@example.invalid", ENABLE_HSTS="false")
    adapted = subprocess.run(
        [str(args.caddy), "adapt", "--adapter", "caddyfile", "--config", str(root / "Caddyfile")],
        env=env, check=True, capture_output=True, text=True,
    )
    config = json.loads(adapted.stdout)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    for server in config["apps"]["http"]["servers"].values():
        server["listen"] = [f"127.0.0.1:{port}"]
        server["automatic_https"] = {"disable": True}
        server.pop("tls_connection_policies", None)
    config["apps"].pop("tls", None)
    config["admin"] = {"disabled": True}
    replace_upstream(config)
    canary = "synthetic_launch_secret_do_not_log"
    with tempfile.TemporaryDirectory(prefix="clubs-onboarding-ingress-") as directory:
        temp = Path(directory)
        env.update(XDG_CONFIG_HOME=str(temp / "config"), XDG_DATA_HOME=str(temp / "data"))
        path = temp / "caddy.json"
        path.write_text(json.dumps(config))
        log = temp / "caddy.log"
        with log.open("w") as output:
            process = subprocess.Popen([str(args.caddy), "run", "--config", str(path)], env=env, stdout=output, stderr=output)
            try:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                            break
                    except OSError:
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError("Disposable ingress did not start")
                        time.sleep(0.05)
                cases = [
                    ("PATCH", "/api/client/applications/1", 502),
                    ("PATCH", "/api/client/clubs/1/profile", 502),
                    ("PATCH", "/api/client/clubs/01/profile", 405),
                    ("PATCH", "/api/client/clubs/1", 405),
                    ("PATCH", "/api/client/clubs/1/profile/extra", 405),
                    ("PATCH", "/api/client/clubs/1/profile/", 405),
                    ("PATCH", "/api/platform/onboarding/clubs/1/profile", 405),
                    ("PUT", "/api/client/clubs/1/profile", 405),
                    ("DELETE", "/api/client/clubs/1/profile", 405),
                    ("PATCH", "/api/client/applications/01", 405),
                    ("PATCH", "/api/platform/onboarding/applications/1", 405),
                    ("PUT", "/api/client/applications/1", 405),
                    ("DELETE", "/api/client/applications/1", 405),
                    ("GET", f"/app?tgWebAppStartParam=ci_{canary}", 502),
                ]
                for method, target, expected in cases:
                    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                    connection.request(method, target, headers={
                        "Host": "nightconcierge.help",
                        "X-Telegram-Init-Data": canary,
                        "Idempotency-Key": canary,
                    })
                    response = connection.getresponse()
                    response.read()
                    assert response.status == expected, (method, expected, response.status)
                    if target.startswith("/app?"):
                        assert response.getheader("Cache-Control") == "no-store"
                        assert response.getheader("Referrer-Policy") == "no-referrer"
                    connection.close()
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        output = log.read_text()
        assert "dial tcp" in output, "Expected a real upstream failure log"
        assert canary not in output, "Secret reached ingress error logs"
    print("CLIENT_ONBOARDING_INGRESS_OK: exact PATCH route, no-store, no-referrer, no query/header secrets")


if __name__ == "__main__":
    main()
