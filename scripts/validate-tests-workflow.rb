# The Logs Policy task keeps its test prerequisite; only its CI budget moves.
module TestsWorkflow
  PATH = ".github/workflows/tests.yml"
  WORKERS = %w[unit-tests integration-tests logs-policy].freeze
  GRADLE_OPTS = "-Dorg.gradle.internal.http.socketTimeout=120000 -Dorg.gradle.internal.http.connectionTimeout=60000"
  LOGS_RUN = <<~'SH'
    set -e
    for i in 1 2; do
      extra=""
      if [ "$i" -gt 1 ]; then extra="--refresh-dependencies"; fi
      echo "Attempt $i: ./gradlew :app-bot:checkLogsPolicy --no-configuration-cache $extra"
      if ./gradlew :app-bot:checkLogsPolicy --no-configuration-cache $extra --no-daemon --stacktrace; then
        exit 0
      fi
      ./gradlew --stop || true
      echo "Retrying in 20s..."
      sleep 20
    done
    exit 1
  SH
  REQUIRED_RUN = <<~'SH'
    python3 - <<'PYTHON'
    import json
    import os

    expected = {"unit-tests", "integration-tests", "logs-policy"}
    needs = json.loads(os.environ["TESTS_NEEDS_JSON"])
    if not isinstance(needs, dict) or set(needs) != expected:
        raise SystemExit("tests gate: missing or unexpected worker")
    failed = [name for name in sorted(expected) if not isinstance(needs[name], dict) or needs[name].get("result") != "success"]
    if failed:
        raise SystemExit("tests gate: workers did not succeed: " + ", ".join(failed))
    print("tests gate: every mandatory worker succeeded")
    PYTHON
  SH

  def self.validate(policy, workflow)
    jobs = workflow.fetch("jobs")
    policy.validate_exact_value(jobs.keys.sort,
      (WORKERS + %w[tests-required amd64-runtime-prototype]).sort, "#{PATH}: exact job inventory")
    # These are the same anchored setup/strict dependency guards as unit-tests.
    # The selfcheck separately pins their scripts and order in unit-tests.
    setup = jobs.fetch("unit-tests").fetch("steps").take(9)
    policy.validate_exact_value(setup.map { |step| step["name"] }, [
      "Checkout", "Guard: no dynamic dependency versions", "Guard: no dynamic versions in plugin blocks",
      "Guard: no dynamic versions in version catalogs", "Validate Gradle Wrapper", "Set up JDK 21",
      "Gradle cache & setup", "Env versions", "Guard: no project-level repositories",
    ], "#{PATH}: shared setup inventory")
    policy.validate_exact_value(jobs["logs-policy"], {
      "runs-on" => "ubuntu-latest", "timeout-minutes" => 45, "env" => {"GRADLE_OPTS" => GRADLE_OPTS},
      "steps" => setup + [
        {"name" => "Logs policy scan (SEC-02) (retry x2)",
         "env" => {"TELEGRAM_BOT_TOKEN" => "111111:TEST_BOT_TOKEN", "NOTIFICATIONS_ENABLED" => "false",
                   "FLYWAY_LOCATIONS" => "classpath:db/migration/h2"}, "run" => LOGS_RUN},
        {"name" => "Upload logs policy and prerequisite test reports", "if" => "always()",
         "uses" => "actions/upload-artifact@b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882",
         "with" => {"name" => "logs-policy-reports", "path" => "app-bot/build/reports/logs-policy/**\napp-bot/build/reports/tests/**\n",
                    "if-no-files-found" => "ignore"}},
      ],
    }, "#{PATH}: logs-policy bounded prerequisite and scan contract")
    policy.validate_exact_value(jobs["tests-required"], {
      "if" => "always()", "needs" => WORKERS, "runs-on" => "ubuntu-latest", "timeout-minutes" => 5,
      "steps" => [{"name" => "Require every test and logs policy worker to succeed",
                   "env" => {"TESTS_NEEDS_JSON" => "${{ toJSON(needs) }}"}, "run" => REQUIRED_RUN}],
    }, "#{PATH}: tests-required fail-closed aggregate")
  end
end
