import json
import os
from pathlib import Path
import subprocess
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class TelegramRuntimeScriptsTest(unittest.TestCase):
    def compose_environment(self, mini_app_url: str | None) -> dict[str, str]:
        environment = {
            "PATH": os.environ["PATH"],
            "HOME": os.environ["HOME"],
            "COMPOSE_DISABLE_ENV_FILE": "1",
            "TELEGRAM_BOT_TOKEN": "dummy-bot-token",
            "BOT_TOKEN": "dummy-bot-token",
            "TELEGRAM_WEBHOOK_SECRET": "dummy-webhook-secret",
            "OWNER_TELEGRAM_ID": "1",
            "QR_SECRET": "dummy-qr-secret",
            "QR_OLD_SECRET": "dummy-old-qr-secret",
            "BASE_URL": "https://base-fallback.invalid",
            "WEBAPP_ORIGIN": "https://origin-fallback.invalid",
            "CORS_ALLOWED_ORIGINS": "https://cors.invalid",
            "RL_IP_ENABLED": "true",
            "RL_SUBJECT_ENABLED": "true",
            "RL_SUBJECT_RPS": "1",
            "RL_SUBJECT_BURST": "1",
            "RL_SUBJECT_TTL_SECONDS": "60",
            "RL_RETRY_AFTER_SECONDS": "1",
            "RL_SUBJECT_PATH_PREFIXES": "/",
            "ACME_EMAIL": "compose-test@example.invalid",
        }
        if mini_app_url is not None:
            environment["MINI_APP_URL"] = mini_app_url
        return environment

    def render_compose(self, mini_app_url: str | None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["docker", "compose", "-f", "docker-compose.yml", "config", "--format", "json"],
            cwd=REPOSITORY_ROOT,
            env=self.compose_environment(mini_app_url),
            capture_output=True,
            text=True,
            check=False,
        )

    def test_compose_passes_exact_mini_app_url_to_app(self) -> None:
        mini_app_url = "https://mini-app.example.invalid/app"

        result = self.render_compose(mini_app_url)

        self.assertEqual(0, result.returncode)
        rendered = json.loads(result.stdout)
        self.assertEqual(
            mini_app_url,
            rendered["services"]["app"]["environment"]["MINI_APP_URL"],
        )

    def test_compose_rejects_missing_mini_app_url_without_legacy_fallback(self) -> None:
        result = self.render_compose(None)

        self.assertNotEqual(0, result.returncode)
        output = result.stdout + result.stderr
        self.assertFalse("https://base-fallback.invalid" in output)
        self.assertFalse("https://origin-fallback.invalid" in output)

    def test_update_ui_uses_only_canonical_mini_app_url(self) -> None:
        script = (REPOSITORY_ROOT / "scripts/update-telegram-ui.sh").read_text()

        self.assertIn(': "${MINI_APP_URL:?set MINI_APP_URL}"', script)
        self.assertEqual(2, script.count('${MINI_APP_URL}'))
        self.assertNotIn("PUBLIC_URL", script)
        self.assertNotIn("/ui/checkin", script)
        self.assertNotIn("/ui/waitlist", script)
        self.assertNotIn("/ui/guest-list", script)

    def test_local_smoke_defaults_to_dev_with_safe_mini_app_url(self) -> None:
        script = (REPOSITORY_ROOT / "scripts/smoke.sh").read_text()

        self.assertIn('APP_PROFILE="${APP_PROFILE:-DEV}"', script)
        self.assertNotIn('APP_PROFILE="${APP_PROFILE:-PROD}"', script)
        self.assertIn(
            'MINI_APP_URL="${MINI_APP_URL:-http://127.0.0.1:${APP_PORT_HOST}/app}"',
            script,
        )
        self.assertIn('-e MINI_APP_URL="${MINI_APP_URL}"', script)


if __name__ == "__main__":
    unittest.main()
