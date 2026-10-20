#!/usr/bin/env python3
"""Exercise the real packaged boundary in an explicitly selected LOCAL image."""

import argparse
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    image = parser.parse_args().image
    injections = {
        "JAVA_TOOL_OPTIONS": "-javaagent:/tmp/secret_agent_canary.jar",
        "JDK_JAVA_OPTIONS": "--class-path /tmp/secret_classpath_canary",
        "_JAVA_OPTIONS": "-Dlogback.configurationFile=/tmp/secret_log_canary.xml",
        "JAVA_OPTS": "-javaagent:/tmp/secret_agent_canary.jar",
        "DEFAULT_JVM_OPTS": "-javaagent:/tmp/secret_agent_canary.jar",
        "APP_BOT_OPTS": "-javaagent:/tmp/secret_agent_canary.jar",
        "APP_BOT_VALIDATE_SCHEMA_OPTS": "-javaagent:/tmp/secret_agent_canary.jar",
        "CLASSPATH": "/tmp/secret_classpath_canary",
        "JAVA_HOME": "/tmp/secret_java_canary",
        "PATH": "/tmp/secret_path_canary",
    }
    scenarios = [("invalid-config", {}, [])]
    scenarios.extend((key, {key: value}, []) for key, value in injections.items())
    scenarios.append(("all-jvm-controls", injections, []))
    scenarios.append(("arguments", injections, ["-javaagent:/tmp/secret_agent_canary.jar"]))
    scenarios.append(
        (
            "connection-failure",
            {
                **injections,
                "APP_ENV": "stage",
                "DATABASE_URL": "jdbc:postgresql://127.0.0.1:1/secret_database_canary",
                "DATABASE_USER": "secret_user_canary",
                "DATABASE_PASSWORD": "secret_password_canary",
                "FLYWAY_ENABLED": "true",
                "FLYWAY_MODE": "validate",
                "FLYWAY_BASELINE_ON_MIGRATE": "false",
                "TELEGRAM_BOT_TOKEN": "invalid_secret_telegram_canary",
            },
            [],
        )
    )
    for label, environment, arguments in scenarios:
        command = [
            "docker", "run", "--pull=never", "--rm", "--network=none", "--read-only",
            "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--entrypoint=/opt/app/bin/app-bot-validate-schema",
        ]
        for key, value in environment.items():
            command.extend(("--env", f"{key}={value}"))
        command.extend(("--", image, *arguments))
        result = subprocess.run(command, capture_output=True, text=True, timeout=30, check=False)
        # Report only the fixed case label; never echo rejected process diagnostics.
        if (result.returncode, result.stdout, result.stderr) != (
            1, "schema-validation:v=1 result=rejected\n", "",
        ):
            raise AssertionError(f"packaged schema boundary failed: {label}")
    print(f"bootstrap-launcher-contract: {len(scenarios)}/{len(scenarios)} PASS")


if __name__ == "__main__":
    main()
