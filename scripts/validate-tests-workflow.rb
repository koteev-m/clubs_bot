module TestsWorkflow
  PATH = ".github/workflows/tests.yml"
  DIRECT_DISPATCH = "github.event_name == 'workflow_dispatch' && github.workflow_ref == format('koteev-m/clubs_bot/.github/workflows/tests.yml@{0}', github.ref)".freeze
  WORKERS = %w[unit-tests integration-tests].freeze
  GRADLE_OPTS = "-Dorg.gradle.internal.http.socketTimeout=120000 -Dorg.gradle.internal.http.connectionTimeout=60000"
  PG_RUN = <<~'SH'
    started=$(python3 -c 'import time; print(time.time_ns())')
    module=core-data
    check_xml=--verify-junit-xml
    tasks=(:core-data:test --rerun "-PciPostgresShard=$PG_SHARD")
    case "$PG_SHARD" in
      application)
        # Keep the alternate Flyway profile for app-bot and include all other/new JVM modules.
        tasks=(test --rerun -x :core-data:test)
        module=app-bot
        suite=com.example.bot.tools.BootstrapValidatePostgresIT
        cases=('PostgreSQL 16 validation preserves full dump on positive and negative evidence')
        ;;
      data-client)
        check_xml=--verify-junit-case
        suite=com.example.bot.data.client.onboarding.BookingCutoverFencePostgresIT
        cases=('protected seating excludes direct legacy HOLD and createBooked')
        ;;
      data-other)
        suite=com.example.bot.data.migration.FlywayPostgresSmokeTest
        cases=('postgres migrations run with jsonb defaults' 'postgres atomic refund migration preserves legacy actions')
        ;;
      *) echo 'Unknown PostgreSQL test shard' >&2; exit 1 ;;
    esac
    ./gradlew "${tasks[@]}" -PrunIT=true --dependency-verification=strict --no-configuration-cache --console=plain --stacktrace
    python3 scripts/validate-payment-hardening.py "$check_xml" \
      "$module/build/test-results/test/TEST-${suite}.xml" "$suite" "$started" "${cases[@]}"
    python3 -I -S -B scripts/verify-postgres-skips.py "$PG_SHARD" "$started"
  SH
  REQUIRED_RUN = <<~'SH'
    python3 - <<'PYTHON'
    import json
    import os

    expected = {"unit-tests", "integration-tests"}
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
    triggers = workflow["on"] || workflow[true] || workflow["true"]
    policy.validate_exact_value(triggers, {"workflow_call" => nil, "workflow_dispatch" => nil}, "#{PATH}: triggers")
    policy.reject("#{PATH}: custom workflow execution defaults") if workflow.key?("defaults") || workflow.key?("env")
    jobs = workflow.fetch("jobs")
    policy.validate_exact_value(jobs.keys.sort,
      (WORKERS + %w[tests-required amd64-runtime-prototype]).sort, "#{PATH}: exact job inventory")
    workers = jobs.values_at(*WORKERS)
    workers.each do |job|
      keys = %w[env runs-on steps timeout-minutes]
      keys.concat(%w[services strategy]) if job.equal?(jobs["integration-tests"])
      policy.validate_exact_value(job.keys.sort, keys.sort, "#{PATH}: worker controls")
      policy.validate_exact_value(job["env"], {"GRADLE_OPTS" => GRADLE_OPTS}, "#{PATH}: Gradle options")
      policy.validate_exact_value(job["timeout-minutes"], 45, "#{PATH}: worker timeout")
      policy.validate_exact_value(job["runs-on"], "ubuntu-latest", "#{PATH}: runner")
      job.fetch("steps").each do |step|
        controls = step.keys & %w[continue-on-error shell if]
        expected = step["uses"].to_s.start_with?("actions/upload-artifact@") ? {"if" => "always()"} : {}
        policy.validate_exact_value(step.slice(*controls), expected, "#{PATH}: worker step execution")
      end
    end
    setup = jobs["unit-tests"].fetch("steps").take(9)
    policy.validate_exact_value(setup.map { |step| step["name"] }, [
      "Checkout", "Guard: no dynamic dependency versions", "Guard: no dynamic versions in plugin blocks",
      "Guard: no dynamic versions in version catalogs", "Validate Gradle Wrapper", "Set up JDK 21",
      "Gradle cache & setup", "Env versions", "Guard: no project-level repositories",
    ], "#{PATH}: setup and dependency guards")
    policy.validate_exact_value(jobs["integration-tests"]["strategy"], {
      "fail-fast" => false, "matrix" => {"shard" => %w[application data-client data-other]},
    }, "#{PATH}: PostgreSQL shard coverage")
    pg = jobs["integration-tests"].fetch("steps")
    policy.validate_exact_value(pg.take(setup.length), setup, "#{PATH}: PostgreSQL setup")
    h2_env = {"CI" => "true", "TELEGRAM_BOT_TOKEN" => "111111:TEST_BOT_TOKEN",
              "NOTIFICATIONS_ENABLED" => "false", "FLYWAY_LOCATIONS" => "classpath:db/migration/h2"}
    h2_run = "./gradlew build coverageGate :app-bot:checkLogsPolicy ktlintCheck detektGate " \
             "dependencyGuard verifyResolvedProductionDependencyGraph -PktorEnforcedVersion=3.3.1 " \
             "--dependency-verification=strict --no-configuration-cache --console=plain --stacktrace"
    unit = jobs["unit-tests"].fetch("steps")
    policy.validate_exact_value(unit.map { |step| step["name"] }.drop(9),
      ["Build, H2 tests, coverage and logs policy", "Upload H2, coverage and logs policy reports"],
      "#{PATH}: H2 checks")
    policy.validate_exact_value(unit[9],
      {"name" => "Build, H2 tests, coverage and logs policy", "env" => h2_env, "run" => h2_run},
      "#{PATH}: H2 build, coverage, dependency and SEC-02 gates")
    policy.validate_exact_value(pg.map { |step| step["name"] }.drop(setup.length),
      ["Wait for Postgres (5432)", "Run PostgreSQL integration profile", "Upload integration test reports"],
      "#{PATH}: PostgreSQL checks")
    policy.validate_exact_value(pg[-2], {
      "name" => "Run PostgreSQL integration profile", "env" => {"CI" => "true", "PG_SHARD" => "${{ matrix.shard }}"},
      "run" => PG_RUN,
    }, "#{PATH}: PostgreSQL profile")
    policy.validate_exact_value(pg.last.dig("with", "name"), "integration-test-reports-${{ matrix.shard }}",
      "#{PATH}: distinct PostgreSQL shard reports")
    policy.validate_exact_value(jobs["tests-required"], {
      "if" => "always()", "needs" => WORKERS, "runs-on" => "ubuntu-latest", "timeout-minutes" => 5,
      "steps" => [{"name" => "Require H2 and PostgreSQL checks to succeed",
                   "env" => {"TESTS_NEEDS_JSON" => "${{ toJSON(needs) }}"}, "run" => REQUIRED_RUN}],
    }, "#{PATH}: tests-required fail-closed aggregate")
  end
end
