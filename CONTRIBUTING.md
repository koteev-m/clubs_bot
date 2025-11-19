# Contributing

## Repository policy

Our Gradle builds pull dependencies from the repositories configured in [`settings.gradle.kts`](./settings.gradle.kts). Do not add `repositories { }` blocks to any module-level `build.gradle` / `build.gradle.kts` files:

- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` is enabled, so Gradle will reject extra repositories even if they compile locally.
- The CI workflow contains a guard step that scans tracked build files and fails fast with a clear error message when `repositories { }` is detected outside `settings.gradle.kts`.

If you need to add another repository (for example, to introduce a vendor-specific artifact), update the centralized lists under `pluginManagement { repositories { ... } }` and `dependencyResolutionManagement { repositories { ... } }` in `settings.gradle.kts`. Apply the same content filters that existing entries use so the Google Maven mirror remains scoped to the expected groups only.

## Dependency verification

Gradle dependency verification is enabled via [`gradle/verification-metadata.xml`](./gradle/verification-metadata.xml). If a dependency is upgraded or a new module is added, refresh the metadata locally and commit the updated file together with your change:

```bash
./gradlew --write-verification-metadata sha256 --refresh-dependencies help
```

The command re-resolves the dependency graph, recomputes checksums, and ensures CI can validate the artifacts downloaded from Maven Central and the Google mirror.

## Dependency versions

Avoid dynamic versions such as `1.2.+`, `latest.release`, `[1.2, 1.3)`, or `1.0-SNAPSHOT`. CI guard steps scan tracked `build.gradle*`, `settings.gradle*`, and `gradle/libs.versions.toml` files for these patterns (including plugin DSL blocks), so any of the following will fail before Gradle runs:

- dependency coordinates with a `+` suffix (`org.example:foo:+` or `version = "1.2.+"`),
- plugin declarations with `+`, `latest.release` / `latest.integration`, ranges, or `-SNAPSHOT`,
- version-catalog entries that rely on dynamic, snapshot, or ranged coordinates.

Pin explicit releases instead so dependency verification metadata stays deterministic and reviewable.

## Container supply chain

Images published to GHCR are built via [`docker-publish.yml`](.github/workflows/docker-publish.yml), signed with [cosign](https://github.com/sigstore/cosign) in keyless mode, and include SLSA provenance. To validate a released digest locally:

```bash
COSIGN_EXPERIMENTAL=1 cosign verify ghcr.io/nightconcierge/clubs_bot/app-bot@sha256:<digest>
COSIGN_EXPERIMENTAL=1 cosign verify-attestation \
  --type slsaprovenance \
  ghcr.io/nightconcierge/clubs_bot/app-bot@sha256:<digest>
```

Verification succeeds only if the signature matches the GitHub Actions OIDC identity for this repository.
