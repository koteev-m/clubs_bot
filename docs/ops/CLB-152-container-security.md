# CLB-152 local container security candidate

Date: 2026-09-30. Local implementation only; no publication or stage authority.

## Identity and evidence

- Fresh remote main/base: `47d4d0529778bd439a1e7ee5c82a0149b93166c6`; tree `4df1a5e359f609dd89024550c8e5baeb6da988c7`.
- Isolated worktree: `/Users/maksimmartynov/.codex/worktrees/clb152-container-security/clubs_bot`; `PROJECT_IDENTITY_OK`. Original dirty worktrees preserved.
- Task-local logs, inventories, image archives, scan JSON, registry metadata, smoke harness and hashes: `/private/tmp/clb152-evidence/`. These local artifacts are not published release attestations.
- Production edit: five added Dockerfile lines. Application sources/dependencies, Java, workflows, signing, provenance verification, SBOM, registry, permissions, deployment and `.trivyignore` unchanged.

## Security and root cause

[Canonical CVE record](https://ubuntu.com/security/CVE-2026-84782) identifies noble's fixed source-package version as `3.0.13-0ubuntu3.16`. [OpenSSL](https://openssl-library.org/news/vulnerabilities/#CVE-2026-84782) rates the DTLS stale-buffer issue High; upstream 3.0 fix is 3.0.23. Ubuntu backports the fix: the Ubuntu revision, not an upstream-version-only comparison, determines remediation here.

Both current base and published final image contain `openssl` and `libssl3t64` at `.12` on amd64 and arm64. Direct disposable `dpkg-query` observations are retained. Published image: `ghcr.io/koteev-m/clubs_bot/app-bot@sha256:4034ccc1a6054bf331a1f80b9b1ca8641ade89056fbb8f78c6e97097041177c0`.

Historical run 36732804120 log shows GHA cache import and both runtime apt steps CACHED: amd64 step #42, arm64 #46. Thus apt did not run in this build. This proves reuse, but neither the original cache creation time nor that cache invalidation would repair the vulnerability.

Fresh `apt-get update` followed by `apt-get -s install --no-install-recommends curl` on each original pinned base selects only curl/libcurl4t64 updates, leaving OpenSSL `.12`. `update` refreshes package indexes; installing curl does not request upgrading every already-satisfied dependency or the independent openssl executable package. Cache invalidation alone is insufficient.

## Choice and reproducibility

Chosen C: retain both existing digest-pinned bases and explicitly install `libssl3t64=3.0.13-0ubuntu3.16 openssl=3.0.13-0ubuntu3.16` in the existing runtime apt transaction; assert both resulting versions before completing the layer. No broad upgrade/dist-upgrade, suppression or gate change.

- A rejected: same `21.0.11_10-jre-noble` official registry index now `sha256:b251c6e60516a48d6511a33b9afc2f8b1a427e53a598b16c1f29390c9bf916bd`, but actual inventory remains `.12` on both platforms.
- B rejected: current compatible `21.0.12.1_1-jre-noble` (also observed through `21-jre-noble`) index `sha256:0c324fbe2e1455c3159184717440f3b52906ef82a39c4a6efc9edb401d31971a` contains `.15` on both platforms. Java reports `21.0.12.1+1-LTS`. Its amd64 manifest is `sha256:a24fdda21f9ab6482cb01302f09d05bc463f265001f335d2e7d446751a40eb62`; arm64/v8 is `sha256:11ef6037c4f2182d04314669f767ba4ba4670bb81900bb4213f602e752ec1945`. It does not solve this CVE; patch migration was not selected or qualified.
- D rejected: adding either repin to the exact package fix has no demonstrated necessity and expands the change surface.

Unchanged builder: `docker.io/library/eclipse-temurin:21.0.11_10-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15`.
Unchanged runtime: `docker.io/library/eclipse-temurin:21.0.11_10-jre-noble@sha256:ca397720325ceefe39ce397f186759fc87d9efafb2dc4ce53315980844c2f4f2`.
Registry observations came from `docker buildx imagetools inspect docker.io/library/eclipse-temurin...`, not search snippets or mirrors.

The reproducibility model is digest-pinned base/JRE plus exact security package versions resolved through the base's authenticated Ubuntu APT sources. Both architectures resolve the same version; binary artifacts differ by architecture. Missing versions cause build failure rather than silently accepting an older/newer package. No insecure APT options or alternate trust roots are introduced. The changed RUN text changes BuildKit's cache key; an old vulnerable apt layer cannot satisfy that key. A subsequent cache-hit build reused the repaired layers and retained identical per-platform manifests and rootfs.

This preserves the existing container contract, not bit-for-bit hermetic whole-image rebuilding: curl and its existing dependency resolution still use live Ubuntu repositories; package retirement can break future uncached builds. A future security revision requires an explicit pin update and rescan. Local index/attestation bytes can differ between builds even when platform image bytes match. Pinning an Ubuntu snapshot/full package closure would be a separate reproducibility expansion, not claimed here.

## Verification

| Image | amd64 openssl / libssl3t64 | arm64 openssl / libssl3t64 |
| --- | --- | --- |
| Original pinned base | 3.0.13-0ubuntu3.12 / same | 3.0.13-0ubuntu3.12 / same |
| Published exact image | 3.0.13-0ubuntu3.12 / same | 3.0.13-0ubuntu3.12 / same |
| Refreshed same-tag base | 3.0.13-0ubuntu3.12 / same | 3.0.13-0ubuntu3.12 / same |
| Latest Java 21 patch base | 3.0.13-0ubuntu3.15 / same | 3.0.13-0ubuntu3.15 / same |
| Final candidate | 3.0.13-0ubuntu3.16 / same | 3.0.13-0ubuntu3.16 / same |

- Production `docker buildx build --platform linux/amd64,linux/arm64 --load -t clubs-bot:clb152-local --progress plain .`: PASS. Both installDist builds and existing miniapp-dist validation passed. No alternate Dockerfile or substitution of published application binaries.
- Exact same repeat build: PASS; repaired apt steps CACHED, unchanged platform manifests/rootfs. Scan results therefore apply to the repeated-build platform bytes.
- Trivy 0.69.3 downloaded from official aquasecurity/trivy release; archive checksum verified: `a2f2179afd4f8bb265ca3c7aefb56a666bc4a9a411663bc0f22c3549fbc643a5` (macOS ARM64 CLI).
- New task-local DB: vulnerability DB v2 updated `2026-09-30T13:11:13.714761705Z`; Java DB v1 updated `2026-09-30T00:59:00.480851969Z`. No inherited stale DB or skip-update option.
- Each final platform image saved via `docker image save --platform linux/<arch>` and scanned with `trivy image --input <archive> --severity HIGH,CRITICAL --ignorefile .trivyignore --exit-code 1 --format json`. Actual exit 0 on both, zero blocking vulnerabilities; CVE-2026-84782 absent. Results include Ubuntu 24.04 and Java analyzers; default secret scanner retained. JSON is a local reporting format, with the same CI security decision policy.
- Positive control: the same fresh-DB invocation against the original published arm64 image exits 1 and reports CVE-2026-84782 HIGH for both openssl and libssl3t64 `.12`, fixed `.16`. Thus the successful candidate result is not explained by a missing DB record.
- Synthetic DEV smoke on both actual candidate platforms: startup, HTTP `/ready`, HTTP `/health`, UID/GID `10001:10001` PASS. Internal network without published ports, disposable PostgreSQL and synthetic credentials, RBAC enabled. Owned containers/volumes/network removed. amd64 execution used Docker Desktop emulation, not a native amd64 host.
- Harness setup failures retained: missing mandatory synthetic HQ/club IDs, then missing RBAC flag; fixed only in task-local harness. The pre-existing logback file appender reports inability to create `/opt/app/logs/app.log`; HTTP/non-root checks pass, container logging remains available. No application fix is bundled.
- `bash scripts/validate-quiesced-deployment.sh`: PASS, including Dockerfile migration-launcher invariants. `git diff --check`: PASS. Workflow/action validators not needed: their files are unchanged. No unrelated JVM suite: Java and application dependencies unchanged, both real container builds and startup/DB smoke exercised the affected boundary.

Platform manifest identities (also Docker Desktop image inspect IDs):
- amd64: `sha256:0a1fd5a0e551039d6ccaab1d0778a2e809b8dac02a583713c219d8d691d9d4d9`; config/Trivy ImageID `sha256:3c86787c3ffb04772a05f7fddffadd5436a5dddd720319b79e01f5d95f16304f`.
- arm64: `sha256:8f2f136b27818f9b007fed935c583f7560408854eac6fbd20c6d07eb3e91ac8f`; config/Trivy ImageID `sha256:2572d604af7c3b537282a357cc599fc3cc71a69b49bf6a8fec92e4d42e992212`.
- Initial local index: `sha256:9301e9d74a8fd2c3f6b0f6a72cbc60ee5aefbf100e4e72225a3eef60c79be846`; repeat index: `sha256:36b4b96db0f54d915aa2c765fb8339e4eed4e0afd8c10e6f1a41560fdf8646c5`. Attestation variation is not a platform-content change.
- Dockerfile SHA-256: `38082ebd8f5ec955c28e0710402a289cc2a4bf175b35bee1a1f3b371017ffd48`.

## Separate stage security reconciliation boundary

Classification: confirmed security-stale `.15` reference/target, separate from the container candidate. No stage files or runtime changed. Existing frozen evidence remains evidence of its historical bytes, not a security-approved `.16` runtime.

Direct contracts that must be reconciled together under a separate local stage task:

1. `scripts/deploy/stage-package-plan-targets.json`: libssl3t64 `.15`, trusted Ubuntu reference digest `sha256:496754492fb28b4d3049432f2ca787449331e23fb14f0dd3fffea86bf5a93eb4`, manifest hash `93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008`, target file hash `29e3592504e33066f2f2a9a208f4f038f052ca33067b8cd6322e46639bd16a14`. A string replacement is not valid trusted-reference regeneration.
2. `stage-package-plan-operation.py`: exact REQUEST and TARGET_SHA256; `stage-package-plan-protocol.py`: TARGET_SHA, COLLECTOR_SHA and validated requested pairs; `stage-package-plan.py`: SOURCE_PINS, fixed 20-count, captured source closure. Any change produces new target/collector/protocol/runner/closure hashes, and a new exact implementation/run identity; old authenticated evidence cannot authorize the new request. The schema version/BASE/confirmation semantics need explicit reconciliation, not automatic renaming.
3. Runtime reference bytes: `scripts/deploy/stage-compose-env-semantic-runtime.json`, `scripts/tests/linux-semantic/amd64-runtime-candidate.json` and native-adapter `ci/reference/accepted-manifest.json` plus `amd64-runtime-candidate.json` contain the `.15` libcrypto hash `6a66c3ba6b3749aacc9497973fff00f6ba61c703ba7015d0d7ab3fd9510974b6`. New `.16` bytes require independently regenerated accepted hashes and checks of dependent loaded-library/generated-state evidence, including ld.so.cache; do not assume every unrelated file changes.
4. Reference inventories/input locks: `scripts/tests/linux-semantic/amd64-packages.txt`, `amd64-inputs.json` and native-adapter `ci/reference/` copies. The input locks explicitly include openssl `.15` DEB URL/hash as well as base/runtime-candidate identities. `packages.txt` and arm64 references are separate architecture fixtures: audit their actual selected OpenSSL inputs if advancing them; never copy amd64 hashes to arm64.
5. Transitive consumers: `stage-runtime-inventory.py`, `stage-runtime-feasibility.py` SOURCE_PINS and `stage-runtime-feasibility-operation.py` REFERENCE_SHA; `run-amd64-ci.py`; native-adapter `ci/reference/SOURCE-PINS.json`, `PROVENANCE.json`, `reference/candidate-runtime.json`, `reference/rootfs-manifest.json`, generated `adapter/generated_contract.h` and rebuilt runtime tar/artifact identity. Regenerate affected current-reference artifacts; preserve historical provenance as historical. Exact reverse-reference paths/line numbers/current file hashes are saved in `stage-references.json`.
6. Required affected tests include `test_stage_package_plan.py` (exact closure/request/count, identity rejection and authenticated producer/consumer), `test_stage_package_plan_collector.py` (resolver/signed-index contract), `test_stage_runtime_inventory.py`, `test_stage_runtime_feasibility.py`, `test_stage_compose_env_semantic.py` (explicit old manifest hash), and native-adapter artifact/runtime checks. Workflow/capability validators must be rerun if their embedded fixed identities change; no workflow change is automatically required by the package version itself.

OpenSSL executable package must be included in the separate security assessment. The current collector's initial names are REQUEST + KNOWN_DEPENDENCIES + EXTRA; none includes `openssl`. It is only observed incidentally if resolver expansion selects it. Updating libssl3t64 does not guarantee an installed openssl package is upgraded. Therefore the old 20-pair result cannot prove openssl remediation or even its complete installed-state observation. Obtain separately authorized exact installed/policy/dependency evidence; if openssl is installed below `.16` (as the task reports), explicitly target fixed openssl too or prove an equivalent deterministic dependency transition. That likely changes the request to 21 pairs and its tests/identities, but this task neither accepts that new frozen request nor reads live stage. No claim of fresh stage package inventory is made.

Proposed stage boundary: separately authorize local security reconciliation of trusted `.16` reference closure and both package targets, reviewed with new manifests/pins/protocol identities and affected tests. Publication, bounded stage collection/Environment approval, any host package transaction and subsequent runtime revalidation each require their own explicit authority. No old dispatch or package-plan permission is reused.

## Completion boundary

Independent review is BLOCKED before substantive review: its identity guard was called with `--scope /private/tmp/clb152-evidence` and returned `STOP_PROJECT_CONTEXT_CONTAMINATION / foreign_instruction_scope`. The guard requires every scope path to be beneath the worktree (`scripts/project-identity-guard.py:118`); this is not evidence of loaded foreign instructions. Local AGENTS prohibits automatic repair after that refusal. A narrow user exception was requested to validate repository scope with the guard and task-owned external evidence separately; no exception is assumed. The same reviewer can continue only after explicit authorization. Security findings are not yet assessed; no PASS or zero-open-findings verdict is claimed. Local technical verification passed, but CLB152_LOCAL_SECURITY_CANDIDATE_READY is not reached.

Unverified: hosted rebuild/Trivy with future DB, published signature/provenance/SBOM of this unpublished candidate, native amd64 runtime and stage security state. A successful local build or pre-existing signature is not a security PASS. No residual HIGH/CRITICAL finding in these scans; future findings require fresh remediation.

External repository/stage mutations: 0. No commit/push/PR/merge/dispatch/rerun/cancel/SSH/deploy, registry push, live package mutation, credentials or global config change.

Next step: hand off this exact local candidate and independent review for a separate publication decision. Stage reconciliation remains a separate scope.
