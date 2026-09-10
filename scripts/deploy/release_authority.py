"""CLB-82 authority: authenticated prior status and a persistent one-use claim."""
import sys
if __name__ == "__main__" and not (sys.flags.isolated and sys.flags.no_site):
    raise SystemExit("release-status-evidence:v=3 unavailable")

import base64
import hashlib
import json
import os
from pathlib import Path
import re
from types import MappingProxyType

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "koteev-m/clubs_bot"
WORKFLOW = ".github/workflows/release-status.yml"
STEP = "Read exact retained release status once"
PREFIX = "release-status-evidence:v=3 "
PRODUCER_PATHS = (WORKFLOW, "scripts/deploy/read-only-release-status.sh",
                  "scripts/deploy/release_private_root.py", "scripts/deploy/release-status.pattern",
                  "scripts/deploy/release_authority.py")
NUMBER = r"[1-9][0-9]{0,19}"
REFERENCE = rf"({NUMBER}):({NUMBER}):([0-9a-f]{{40}})"
INCIDENT_HELPER_SHA256 = "8d8321d325d6ca25f48bcfdd7d9fb0eeb6f80af9c26f136ea06953cf1c2b914e"
# Single source for the producer, verifier and corrected executor. This module
# is part of the authenticated producer source chain, never caller input.
INCIDENT = MappingProxyType({
    "APP_ENV": "stage",
    "INCIDENT_TAG": "deploy-stage-44497dc",
    "RELEASE_OWNER": "33468965282-1",
    "EXPECTED_REVISION": "44497dcd28139cef865c3f98ac3f2c4a5afac636",
    "IMAGE_DIGEST": "ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe",
})
ORIGINAL_OPERATION = "start"


class AuthorityError(Exception):
    """Fixed safe category; never include API, filesystem or transport detail."""


def check(condition, category="PRIOR_STATUS_INVALID"):
    if not condition:
        raise AuthorityError(category)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        check(key not in result)
        result[key] = value
    return result


def canonical_status(line):
    pattern = (ROOT / "scripts/deploy/release-status.pattern").read_text().strip()
    check(isinstance(line, str) and len(line) <= 1024 and re.fullmatch(pattern, line))
    value = dict(field.split("=", 1) for field in line.split(" ")[1:])
    check(all(value[k] == "yes" for k in ("status_available", "owner_match", "revision_match", "digest_match"))
          and value["failure_category"] == "none")
    return value


def produce(env, line):
    # Called only after the existing channel has validated the whole SSH stream.
    # Metadata comes from the fixed protected job, never a caller-supplied JSON.
    canonical_status(line)
    check(env.get("GITHUB_REPOSITORY") == REPOSITORY and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
          and env.get("GITHUB_REF") == "refs/heads/main" and env.get("GITHUB_REF_TYPE") == "branch"
          and env.get("GITHUB_JOB") == "status"
          and env.get("GITHUB_WORKFLOW_REF") == f"{REPOSITORY}/{WORKFLOW}@refs/heads/main")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        check(re.fullmatch(NUMBER, env.get(key, "")))
    patterns = {"GITHUB_SHA": r"[0-9a-f]{40}", "APP_ENV": r"stage|prod",
                "INCIDENT_TAG": r"deploy-(?:stage|prod)-[0-9a-f]{7,40}", "RELEASE_OWNER": r"[0-9]{1,20}-[0-9]{1,20}",
                "EXPECTED_REVISION": r"[0-9a-f]{40}", "EXPECTED_HELPER_SHA256": r"[0-9a-f]{64}",
                "IMAGE_DIGEST": r"ghcr\.io/koteev-m/clubs_bot/app-bot@sha256:[0-9a-f]{64}",
                "REQUESTED_OPERATION": r"[a-z]+(?:-[a-z]+)?", "SSH_USER": r"[a-zA-Z0-9_][a-zA-Z0-9._-]*"}
    for key, pattern in patterns.items():
        check(re.fullmatch(pattern, env.get(key, "")))
    check(env["SSH_USER"] not in {"root", "hookah-staging"})
    value = {key: env[key] for key in ("GITHUB_REPOSITORY", "GITHUB_EVENT_NAME", "GITHUB_REF", "GITHUB_JOB",
             "GITHUB_WORKFLOW_REF", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA", "APP_ENV", "INCIDENT_TAG",
             "RELEASE_OWNER", "EXPECTED_REVISION", "REQUESTED_OPERATION", "EXPECTED_HELPER_SHA256")}
    # GitHub masks persisted logs, including substrings of a public digest.
    # Attest the semantic tuple here; neither a raw digest nor its hash is log
    # authority. A generic trusted status can legitimately be ineligible.
    exact_incident = (all(env.get(k) == v for k, v in INCIDENT.items())
                      and env["REQUESTED_OPERATION"] == ORIGINAL_OPERATION
                      and env["EXPECTED_HELPER_SHA256"] == INCIDENT_HELPER_SHA256)
    value.update(exact_incident=exact_incident,
                 principal_sha256=hashlib.sha256(env["SSH_USER"].encode()).hexdigest(),
                 compose_path_sha256=hashlib.sha256(env["COMPOSE_PATH"].encode()).hexdigest(), status=line)
    serialized = json.dumps(value, sort_keys=True, separators=(",", ":"))
    check(len(serialized) <= 4096)
    print(PREFIX + serialized, flush=True)


def verify_prior(env, capture):
    # Independent of validate_request(): direct verifier callers cannot select
    # another incident and reuse a positive attestation for the bounded one.
    check(all(env.get(k) == v for k, v in INCIDENT.items()))
    match = re.fullmatch(REFERENCE, env.get("PRIOR_STATUS", ""))
    check(match, "PRIOR_STATUS_REFERENCE_REQUIRED")
    run_id, attempt, revision = match.groups()
    check(env.get("GITHUB_TOKEN"), "PRIOR_STATUS_UNAVAILABLE")
    child_env = {"PATH": env.get("PATH", os.defpath), "GH_TOKEN": env["GITHUB_TOKEN"],
                 "GH_PROMPT_DISABLED": "1", "GH_NO_UPDATE_NOTIFIER": "1", "GH_TELEMETRY": "0", "LC_ALL": "C"}

    def api(path, *, raw=False, limit=262144):
        code, data = capture(["gh", "api", "--hostname", "github.com", "--method", "GET",
                              "-H", "X-GitHub-Api-Version: 2026-03-10", f"repos/{REPOSITORY}/{path}"],
                             timeout=30, limit=limit, env=child_env)
        check(code == 0, "PRIOR_STATUS_UNAVAILABLE")
        if raw:
            return data
        return json.loads(data, object_pairs_hook=unique_object)

    workflow = api("actions/workflows/release-status.yml")
    check(workflow.get("path") == WORKFLOW and workflow.get("name") == "Release Status (read-only)"
          and type(workflow.get("id")) is int)
    run = api(f"actions/runs/{run_id}/attempts/{attempt}")
    expected = {"id": int(run_id), "run_attempt": int(attempt), "head_sha": revision, "head_branch": "main",
                "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
                "workflow_id": workflow["id"]}
    check(all(type(run.get(k)) is type(v) and run[k] == v for k, v in expected.items()))
    check(run.get("path") in (WORKFLOW, WORKFLOW + "@main", WORKFLOW + "@refs/heads/main")
          and run.get("repository", {}).get("full_name") == REPOSITORY
          and run.get("head_repository", {}).get("full_name") == REPOSITORY)
    # Authenticate the complete non-stdlib producer chain at that run's SHA.
    # The approved workflow checks out github.sha and launches -I -S; its
    # bootstrap compiles the one own dependency directly, ignoring .pyc.
    # Extra checkout modules are neither dependencies nor import candidates.
    # Old vulnerable producer bytes and v1/v2 evidence are not accepted.
    # Authenticate the exact producer code at that run's revision. An arbitrary
    # successful workflow or copied log line cannot stand in for this producer.
    for path in PRODUCER_PATHS:
        source = api(f"contents/{path}?ref={revision}")
        check(source.get("type") == "file" and source.get("path") == path and source.get("encoding") == "base64")
        data = base64.b64decode(source["content"].replace("\n", ""), validate=True)
        check(len(data) == source.get("size") and data == (ROOT / path).read_bytes()
              and source.get("sha") == hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest())
    jobs = api(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100")
    check(jobs.get("total_count") == 2 and len(jobs.get("jobs", [])) == 2)
    check({job.get("name") for job in jobs["jobs"]} == {"validate-status-request", "deployment-principal-status"})
    for job in jobs["jobs"]:
        check(job.get("run_id") == int(run_id) and job.get("head_sha") == revision
              and job.get("head_branch") == "main" and job.get("status") == "completed"
              and job.get("conclusion") == "success" and type(job.get("id")) is int)
    job = next(job for job in jobs["jobs"] if job["name"] == "deployment-principal-status")
    steps = [step for step in job.get("steps", []) if step.get("name") == STEP]
    check(len(steps) == 1 and steps[0].get("status") == "completed" and steps[0].get("conclusion") == "success")
    # Job IDs come exclusively from the attempt-specific list; never list latest
    # jobs or combine a prior attempt's producer with a new attempt's conclusion.
    log = api(f"actions/jobs/{job['id']}/logs", raw=True, limit=1048576)
    lines = []
    for line in log.decode("utf-8").splitlines():
        line = re.sub(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z ", "", line)
        if line.startswith("release-status-evidence:"):
            check(line.startswith(PREFIX))
            lines.append(line[len(PREFIX):])
    check(len(lines) == 1 and len(lines[0]) <= 4096)
    value = json.loads(lines[0], object_pairs_hook=unique_object)
    expected = {"GITHUB_REPOSITORY": REPOSITORY, "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/main", "GITHUB_JOB": "status",
                "GITHUB_WORKFLOW_REF": f"{REPOSITORY}/{WORKFLOW}@refs/heads/main", "GITHUB_RUN_ID": run_id,
                "GITHUB_RUN_ATTEMPT": attempt, "GITHUB_SHA": revision, "REQUESTED_OPERATION": ORIGINAL_OPERATION,
                "EXPECTED_HELPER_SHA256": INCIDENT_HELPER_SHA256,
                **{k: v for k, v in INCIDENT.items() if k != "IMAGE_DIGEST"}}
    check(isinstance(value, dict)
          and set(value) == set(expected) | {"exact_incident", "principal_sha256", "compose_path_sha256", "status"}
          and value["exact_incident"] is True
          and all(value[k] == v for k, v in expected.items()))
    check(re.fullmatch(r"[0-9a-f]{64}", value["principal_sha256"])
          and value["compose_path_sha256"] == hashlib.sha256(b"/opt/clubs-bot-stage").hexdigest())
    status = canonical_status(value["status"])
    check(status["checkpoint"] == "migration_completed" and status["migration_evidence"] == "present"
          and status["operation_result"] == "remote_failure")
    # resume_permitted=no from the retained selector is expected and accepted.
    if "SSH_USER" in env:
        check(value["principal_sha256"] == hashlib.sha256(env["SSH_USER"].encode()).hexdigest())
    return {"reference": env["PRIOR_STATUS"], "job_id": job["id"],
            "evidence_sha256": hashlib.sha256(lines[0].encode()).hexdigest()}


# Authorization consumption belongs only to the bound implementation helper.
# No standalone pathname-based claim executor is exposed here.

if __name__ == "__main__":
    try:
        check(len(sys.argv) == 3 and sys.argv[1] == "produce")
        produce(os.environ, sys.argv[2])
    except BaseException:
        print("release-status-evidence:v=3 unavailable")
        sys.exit(1)
