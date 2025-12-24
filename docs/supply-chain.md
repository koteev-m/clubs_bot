# Supply-chain hardening

## Goal
Strengthen CI/CD and delivery security so that critical build surfaces are owned, artifacts are traceable (SBOM + provenance), and supply-chain checks block unsafe releases.

## CODEOWNERS
- Location: `.github/CODEOWNERS`.
- Coverage highlights:
  - Docker and CI entrypoints (`Dockerfile`, `Caddyfile`, `.github/workflows/*`) → `@org/infra-team`.
  - Gradle/build metadata (`settings.gradle.kts`, `gradle/verification-metadata.xml`, `gradle/libs.versions.toml`, `build.gradle.kts`) → `@org/backend-leads`.
  - Security tooling and this document (`.trivyignore`, `.github/workflows/security-scan.yml`, `.github/CODEOWNERS`, `docs/supply-chain.md`) → `@org/infra-team`.
- Any edits to these files should be reviewed by the listed owners.

## SBOM
- Tool: Syft via `anchore/sbom-action` generating CycloneDX JSON (`sbom.cdx.json`).
- Scope: Built container image `ghcr.io/${{ github.repository }}/app-bot` using the pushed digest.
- Workflow: `.github/workflows/docker-publish.yml` (runs on non-PR push/tag).
- Artifact: `sbom` (download from the workflow run → **Artifacts** → `sbom`).
- Local inspection examples:
  - `syft ghcr.io/<org>/<image>@<digest> -o cyclonedx-json > local-sbom.cdx.json`
  - `jq '.components[] | {name, version}' sbom.cdx.json | head`

## Trivy vulnerability scanning
- **FS scan (branches/PRs):** `.github/workflows/security-scan.yml` runs Trivy against the repository filesystem on pushes/PRs to main/master and tags.
- **Image scan (release gate):** `.github/workflows/docker-publish.yml` scans the pushed image `${{ github.repository }}/app-bot@<digest>` and fails the release if new HIGH/CRITICAL CVEs appear.
- Config/allowlist: `.trivyignore` in repo root; each CVE entry must include a comment explaining why it is ignored.
- Policy: both scans fail on `HIGH` or `CRITICAL` findings; SARIF reports are uploaded to code scanning and stored as artifacts (`trivy-report`, `trivy-image-report`).
- To update allowlist: verify exploitability, create a tracking issue for remediation, add the CVE with justification, request review from `@org/infra-team`, and remove once upstream/base image is fixed.

## cosign signing & SLSA provenance
- Registry target: `ghcr.io/<repo>/app-bot`.
- Signing: keyless cosign using GitHub OIDC inside `.github/workflows/docker-publish.yml` after image push.
- Verification job: `verify-and-provenance` installs cosign, verifies signatures, downloads SLSA provenance, re-verifies it, and uploads `slsa-provenance` artifact.
- Manual verification:
  ```bash
  COSIGN_EXPERIMENTAL=1 cosign verify \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    --certificate-identity-regexp "https://github.com/<owner>/<repo>/.+" \
    ghcr.io/<owner>/<repo>/app-bot@<digest>

  COSIGN_EXPERIMENTAL=1 cosign verify-attestation \
    --type slsaprovenance \
    ghcr.io/<owner>/<repo>/app-bot@<digest>
  ```
- Provenance source: Docker Buildx `provenance: true`; the attestation is downloaded and attached to workflow artifacts.

## Pinned GitHub Actions
- All actions are pinned to commit SHAs with a comment showing the tag (e.g., `actions/checkout@<SHA> # v4.1.7`).
- Guard: `.github/workflows/ci-guards.yml` enforces that no workflow uses `@vX`, `@main`, `@master`, `@HEAD`, and that every `uses: ...@...` reference is a full 40-character hex commit SHA.
  - The guard skips commented lines and local actions (`uses: ./...`) but raises clear errors for version tags (`@vX`) and branch heads (`@main`, `@master`, `@HEAD`) while requiring a pinned commit SHA with the tag noted only in a comment.
  - Both quoted and unquoted `uses:` values are supported (e.g., `uses: actions/checkout@<SHA>` or `uses: "actions/checkout@<SHA>"`), but the reference must still be a commit SHA.
- Updating guidance:
  1. Find the desired release tag on GitHub.
  2. Resolve its commit SHA (`git ls-remote https://github.com/<owner>/<repo>.git <tag>`).
  3. Replace the `uses:` reference with that SHA, keep the tag only as a trailing comment for readability.
- Format: 40-character hexadecimal SHA (upper or lower case) without refs/tags prefixes; guard ignores commented lines (`# ...`) and local actions (`uses: ./...`) but validates all other external `uses:` statements.
- Prod/stage миграции базы выполняются через pinned workflow `.github/workflows/db-migrate.yml` (`workflow_dispatch` или релизный тег) с `FLYWAY_MODE=migrate-and-validate` и тем же набором действий `checkout`/`setup-java`/`setup-gradle`.
- Миграционный workflow (`.github/workflows/db-migrate.yml`) входит в supply-chain: он единственный источник истины для prod/stage миграций, использует pinned actions и управляется секьюрными секретами БД; политика и доп. ограничения описаны в `docs/dr.md`.

## Dependency drift report
- Workflow: `.github/workflows/dependency-drift.yml` (scheduled weekdays at 04:00 UTC and manual via `workflow_dispatch`).
- Output: `dependency-drift-report` artifact with Gradle Versions Plugin output from `build/dependencyUpdates`.
- Scope: read-only reporting; no automatic upgrades.

## Conventional commits & releases
- Commit format: enforced by `.github/workflows/commitlint.yml` using `commitlint.config.cjs` (extends `@commitlint/config-conventional`).
- Release notes: `.github/workflows/release.yml` now builds `CHANGELOG.md` via `conventional-changelog` using the conventional commits in the current tag range.

## Runbook
- Trivy fails on new CVE: confirm it is legitimate, patch dependency or base image; if unavoidable, add to `.trivyignore` with rationale and get `@org/infra-team` approval.
- cosign verification fails: ensure the image digest matches the pushed artifact, confirm `id-token: write` permissions, and re-run the workflow to refresh the Fulcio certificate.
- SBOM step fails: rerun to rule out transient registry issues; if reproducible, run Syft locally with the same image digest to inspect errors and open an issue with infra owners.
- Unsure who to contact: check `.github/CODEOWNERS` for the relevant path and tag the listed owners.
