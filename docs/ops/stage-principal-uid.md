# CLB-194 principal UID evidence — LOCAL-ONLY candidate

The user accepted a separate bounded UID-only evidence contract on 2026-10-08.
This candidate is not published, dispatched or operationally authorized. Existing
feasibility, capture, Runtime and deployment contracts remain unchanged.

## Meaning of the evidence

The [workflow](../../.github/workflows/stage-principal-uid.yml) selects the existing
protected `stage` account. The [runner](../../scripts/deploy/stage-principal-uid.py)
compares that protected account name privately with the remote UID's OS account
lookup. It accepts only Linux, equal real/effective UID, canonical UID in
`1..4294967294`, and an exact account match. It publishes no account name, name
hash, host/account path, host policy, environment dump or secret-derived identity.

Three trust levels remain separate:

| Level | What a successful result establishes | Limit |
| --- | --- | --- |
| A — protected selection | The fixed `stage` job supplied the configured SSH account | Repository wiring does not independently verify live DEC-037 policy or secret scope |
| B — authenticated self-report | A response on the pinned SSH connection reports account equality and the numeric non-root UID | Initial SSH shell/startup, account lookup, OS/kernel and indirect principal rights are not independently attested |
| C — independent host account binding | Always `UNKNOWN` in this capability | Requires separately authorized independent administrative evidence |

`result=PASS` is only a successful A/B diagnostic, never `STAGE_TRUST=PASS`.
`startup_trust` and `independent_host_binding` are always `UNKNOWN`, including on
success. Absolute interpreter paths and an empty child environment reduce PATH
ambiguity; they cannot validate the SSH server's earlier login/startup chain.
Nonce/HMAC authenticates framing and invocation association, not the host: the
remote process receives the nonce. Existing administrative root-owned-path
observations are conditional ordinary-write evidence, not principal-rights proof.

## Fixed authority and bounds

Manual `workflow_dispatch` only; exact repository/main/default-main/workflow SHA,
revision, run ID and attempt 1 are validated before credentials and again before
execution. Confirmation `CLB-194:principal-uid` is not human authorization.
The protected job uses existing Environment `stage`, `contents: read`, canonical
checkout/SSH action pins, `payments-schema-stage`, and no concurrency cancellation.
No new credentials or privileges are introduced; public-key action logging is off.
DEC-037 live protection and secret-scope checks remain separate execution gates.

DEC-038 validation requires its exact endpoint and a single matching Ed25519 key
and retained fingerprint before the generic pinned-host primitive. Host changes,
wildcards, certificate entries and alternative keys refuse. SSH disables password
fallback, default identity files/certificates, proxy, forwarding, multiplexing,
DNS/global/command host trust and host-key updates. It makes one connection only,
with no retry: connect deadline 15 seconds, remote alarm 20 seconds, transport
deadline 30 seconds, public/frame bound 4096 bytes, request bound 1024 bytes.
Each workflow job has a five-minute deadline.

The runner independently pins the existing feasibility module before its first
dependency execution and reuses its bounded capture/cleanup and pinned whole-source
loader. All required sources, including this workflow/runner, must match exact
dispatched Git blobs before credentials or SSH. Sources and HEAD are rechecked
after transport cleanup; this detects drift, not an atomic snapshot. Local candidate
bytes are uncommitted: a real `--validate` against the base must refuse them until
separate publication. Tests use synthetic Git responses and account answers.

Remote code performs only current UID/account lookup and bounded protocol I/O.
It creates no remote files and reads no application data, Docker metadata, private
dotenv, startup content or host policy. No Docker API, sudo, package operation,
deployment or principal impersonation is used. SSH/startup/NSS/audit and atime
effects require consideration in any future execution approval; startup safety is
not established by this candidate.

## Public result and masking

One canonical `principal-uid:v=1` JSON record has exactly `version`, `result`,
`reason`, `selection`, `account_match`, `non_root`, `uid`, `startup_trust`,
`independent_host_binding`, `revision`, `run_id`, `attempt`. Only success contains
a numeric UID. Refusals use fixed categories and `uid=null`; no raw stdout/stderr,
nonce, secret hash, evidence artifact or arbitrary remote value is published.

Before output, significant fields are checked for masking collisions with supplied
protected inputs. A collision returns unbound `UNKNOWN/publication`, with UID and
provenance removed. It never splits, hashes or encodes values to evade masking.
GitHub can mask persisted logs after runner publication. The strict `parse_public`
reader treats any bounded record containing `***` as `UNKNOWN/publication`; no
masked value is reconstructed. Other malformed/extra/oversized/wrong-context
records refuse. An unmasked success must be matched independently to the actual
GitHub run/attempt/revision and exact producer sources; a copied record is not
authenticated run evidence. No API token permission is added for this task.

## Local verification and next boundary

[Synthetic tests](../../scripts/tests/test_stage_principal_uid.py) cover UID/type
bounds, root/setuid/account/platform refusals, authentication/tampering, source and
HEAD drift, endpoint/pin constraints, cancellation/timeout/ambiguous transport,
masking, disclosure, credential ordering, workflow mutations and no retry.
The exact [capability validator](../../scripts/validate-stage-principal-uid-workflow.rb)
and existing generic validators enforce the sole new capability; selfcheck runs
the suite once. No broad JVM/native/Docker suite is needed for these unchanged
surfaces. Final checks and focused independent review belong in the CLB-194 handoff.

Publication needs separate approval. Dispatch, Environment approval and stage
access need their own authorization after publication, fresh policy/secret/pin
checks and review of startup risk. Nothing here authorizes them.
`STAGE_TRUST=UNVERIFIED`; `TRANSACTION_READINESS=NO`.
