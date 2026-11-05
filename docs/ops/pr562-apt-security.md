# PR #562 targeted APT security verification

2026-11-05, task `CLB-owner-setup-ux`, branch `codex/owner-setup-ux` in the
registered `owner-setup-ux` worktree. Verified local/remote PR parent:
`2ae70a29331803c9708512e1fa0943fdf93922c7`. Identity guard:
`PROJECT_IDENTITY_OK`; active foreign instructions: 0.

## Cause and minimal fix

The digest-pinned Noble runtime base contains `apt` and `libapt-pkg6.0t64`
`2.8.3`. Installing curl and the existing explicit OpenSSL/Brotli fixes does
not request upgrading those independent APT packages. Refreshing indexes alone
does not upgrade installed packages. The old PR image-security failures reported
CVE-2026-109518 HIGH for both APT packages on both architectures.

[Ubuntu's advisory](https://ubuntu.com/blog/apt-vulnerability-fixes-available)
and [USN-8996-1](https://ubuntu.com/security/notices/USN-8996-1) identify the Noble
fix as `2.8.4ubuntu0.1`. Disposable containers from the exact pinned base resolved
that version using its existing signed Ubuntu sources: archive/security.ubuntu.com
for amd64 and ports.ubuntu.com for arm64. Simulating the targeted APT install on
each architecture selected two upgrades, no additions/removals, 63 other packages
left unchanged. No alternate mirror, trust override or broad upgrade was used.

- `Dockerfile`: add exact APT/libapt pins and post-install assertions to the
  existing runtime package transaction. Set `Acquire::Retries=0` for update and
  install, following Ubuntu's mitigation while the vulnerable client updates
  itself. The changed RUN text invalidates the old package layer's cache key.
- `.github/workflows/docker-image.yml`: assert and record those same installed
  versions in the existing native amd64/arm64 runtime probes.
- Digest-pinned builder/runtime bases, Java 21, curl installation, OpenSSL/Brotli
  pins/assertions, UID/GID, launchers and healthcheck remain unchanged. Trivy
  0.69.3, HIGH/CRITICAL, exit-code 1, default scanners, `.trivyignore`, native
  matrix runners, no-push behavior and aggregate gate remain unchanged.

## Actual runtime observations

Both production Dockerfile builds completed `:app-bot:installDist` and miniapp
distribution validation. No replacement Dockerfile, dummy application or
published application payload was used. Builds were sequential by architecture.

| Package / probe | linux/amd64 | linux/arm64 |
| --- | --- | --- |
| apt | 2.8.4ubuntu0.1 | 2.8.4ubuntu0.1 |
| libapt-pkg6.0t64 | 2.8.4ubuntu0.1 | 2.8.4ubuntu0.1 |
| openssl / libssl3t64 | 3.0.13-0ubuntu3.16 | 3.0.13-0ubuntu3.16 |
| libbrotli1 | 1.1.0-2ubuntu0.1 | 1.1.0-2ubuntu0.1 |
| Java / UID:GID / four launchers | 21.0.11 / 10001:10001 / PASS | 21.0.11 / 10001:10001 / PASS |
| Trivy HIGH/CRITICAL / exit | 0 / 0 | 0 / 0 |
| HTTP /ready and /health | 200 / 200 | 200 / 200 |

The updated workflow's exact shell runtime probe passed on both actual images
with no network, read-only filesystem, all capabilities dropped and no-new-privileges.
Synthetic DEV smoke used RBAC, a disposable PostgreSQL 16 fixture pinned by the
existing smoke workflow, an internal network and no published ports. Both apps
ran non-root. Owned containers/network were removed. No stage/prod operation.
Docker Desktop host is arm64; local amd64 runtime uses emulation. Native amd64
hosted verification remains a CI requirement.

Trivy 0.69.3 used the existing official scanner image at
`docker.io/aquasec/trivy@sha256:bcc376de8d77cfe086a917230e818dc9f8528e3c852f7b1aff648949b6258d1c`.
Fresh task-local vulnerability and Java DBs were downloaded from official GHCR
repositories after the default mirror's TLS failure. Vulnerability DB updated
`2026-11-05T13:09:41.435406326Z`; Java DB updated
`2026-11-05T01:08:50.735678238Z`. No skip-update or scanner-disable option.
Each scan used an exported platform image archive (no Docker socket mount),
`--severity HIGH,CRITICAL --ignorefile .trivyignore --exit-code 1 --timeout 10m`
with JSON/list-all-pkgs reporting. Each report includes Ubuntu 24.04 (136 packages)
and Java (127 packages), with zero blocking vulnerabilities or secrets. Actual
scanner exit 0 on both. A positive control on the original arm64 base exits 1
and reports this exact HIGH CVE for both APT packages `2.8.3`, fixed
`2.8.4ubuntu0.1`; successful scans are not due to an absent vulnerability record.

## Reproducible evidence and qualifications

Task-local full logs, build metadata and reports remain in `/private/tmp/pr562-*`;
they are local observations, not published release attestations. Builds preceded
this documentation-only update. Tested Dockerfile/workflow bytes are frozen:

| File | SHA-256 |
| --- | --- |
| Dockerfile | 956fbc7b51add71464296628548ef9291dbcf88d819bc615b6e82e7f40a0b7b1 |
| .github/workflows/docker-image.yml | 3e6501598e0e47e2a0b835c3bf40695fad68453930efd4aea254d346eb2db9fd |
| unchanged .trivyignore | 9df28a63db365f8fee379642be488d2f0ee3415fa5a70cc98bfdf2024991887b |
| amd64-trivy.json | 1ad70e4655606cc485d62afdf3c8fc1886f51d24432f3d5516b1f55492251d67 |
| arm64-trivy.json | 1a9076ab04e18e9a62b4cf7148a3adbce7205fb2653d512b87a0242978c73189 |
| base-arm64-trivy.json | 7c423c22fd0029ec7eed47fc03d5c47af7358fffcc55b930afea8f841c2de194 |

Reports reside in `/private/tmp/pr562-security-reports/`. Local images carry
revision label `pr562-apt-uncommitted`; they are not labeled as the future commit.

| Platform | Platform manifest | Config / Trivy ImageID |
| --- | --- | --- |
| amd64 | sha256:70f1e5c935bb215c16c27c371c1c66a8c765667b4dfe2ef41f3ec266ade6becf | sha256:7d06aa594988563504129476c7f77043480f04cb6f5c37f6b2e072063a401210 |
| arm64 | sha256:a1ec0c1f8255e6291e9ad7db2ac83ec9833b2a961358cc1c56228ee2c179fbb7 | sha256:5184d343597946c89eb182cfb32c07198a624ba1911e0c8ef09c5cbd203c644a |

Additional checks: workflow YAML and capability validators (32 workflows),
`test_pr_image_workflow.rb` (15 cases / 30 assertions), quiesced-deployment
contract and whitespace validation PASS. The unchanged quiesced validator needs
Bash coproc; macOS Bash 3.2 cannot parse it. Its successful run used the existing
repository-owned Linux Bash fixture with repository mounted read-only and
network disabled. Focused review found no open blocker or weakened gate.

Initial full multiarch build failed at the Gradle distribution's network timeout;
a retry encountered the shared Gradle cache journal lock while both architectures
built concurrently. Sequential full production builds then passed. No Gradle,
wrapper/cache settings or workflow network workaround was introduced. The old
hosted Gradle/cache outages remain infrastructure evidence, not proven source
failures. New hosted results must be tied to the additional commit's exact SHA.

All UX, application, Gradle dependency and migration files are byte-identical to
the verified parent; prior [UX evidence](owner-setup-ux-pr.md) remains historical
evidence for those bytes. No data, grants, booking runtime, V067–V073, public
guest/staff/payment behavior, actual images or staging state changed. The
Main/Welcome incident remains **EVIDENCE_REQUIRED** with mandatory manual retest
after a separately authorized staging deployment.

The user authorizes one additional commit/non-force push into existing Draft
[PR #562](https://github.com/koteev-m/clubs_bot/pull/562), and one bounded CI
snapshot for the new SHA. No merge, deploy, workflow dispatch or registry push.
Final publication SHA/status belong to the response and GitHub record. Local
success does not guarantee native hosted builds or future Trivy DB results;
retired Ubuntu package pins intentionally fail closed. Next: assess the exact
new SHA's hosted checks before a separate merge decision.
