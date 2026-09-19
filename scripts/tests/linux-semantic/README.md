# CLB-91 Linux semantic fixtures

This is a local test image, not a stage dependency or installation procedure.
Use the existing Docker Desktop runtime. Dependency preparation needs network;
test execution has none, no Docker socket, no credentials, a read-only root and
RAM-only owned fixture directories. No daemon/Engine calls are made by Compose.

The image uses the official Ubuntu 24.04 arm64 digest in `Dockerfile`. APT uses
the image's Ubuntu signing keys; exact direct versions and the full resulting
package inventory (`packages.txt`) are checked. Changed dependency resolution
fails the build instead of silently changing the tested closure.

Copy this directory into a disposable build directory outside the checkout.
Download only the official asset there:

```sh
curl --fail --location --proto '=https' --tlsv1.2 \
  https://github.com/docker/compose/releases/download/v5.1.1/docker-compose-linux-aarch64 \
  -o docker-compose-linux-aarch64
echo '4b5c42952b7dd81f508d01a771df2a9e5dbffe9b8c5c7d983e738504ad38f056  docker-compose-linux-aarch64' | shasum -a 256 -c -
docker build --pull=false -t clb91-semantic-linux:5.1.1-arm64 .
```

Checksum source: [official Compose 5.1.1 checksums](https://github.com/docker/compose/releases/download/v5.1.1/checksums.txt).
The runtime manifest pins actual Linux executable, Python source/cache,
Ruby/Psych and shared-library bytes. These are Linux identities, not Darwin
hashes or a version-string approval. The reference closure is arm64 Python
3.12.3, Ruby 3.2.3, bundled Psych 5.0.1 (`--disable-gems`), Compose 5.1.1.
The installed standalone Psych gem is not used by the private adapter.

Export the exact candidate with `git archive` to another disposable directory.
Mount that export at `/source:ro` (never mount the checkout, home or socket).
Run with these options; the image has the required fixture mount points:

```sh
docker run --rm --read-only --network=none --cap-drop=ALL \
  --security-opt=no-new-privileges \
  --tmpfs /work/runner-temp:uid=1000,gid=1000,mode=0700,exec,nosuid,nodev \
  --tmpfs /run/user/1000:uid=1000,gid=1000,mode=0700 \
  --tmpfs /opt/clubs-bot-stage:uid=1000,gid=1000,mode=0755 \
  --mount type=bind,src=ABSOLUTE_EXPORTED_CANDIDATE,dst=/source,readonly \
  clb91-semantic-linux:5.1.1-arm64 /bin/sh -c '
    cp -R /source /work/runner-temp/repo &&
    git -c init.templateDir= init -q /work/runner-temp/repo &&
    git -C /work/runner-temp/repo add -- . &&
    python3 -B /work/runner-temp/repo/scripts/tests/test_stage_compose_env_file_plan.py &&
    python3 -B /work/runner-temp/repo/scripts/tests/test_stage_compose_env_file_context.py &&
    python3 -B /work/runner-temp/repo/scripts/tests/test_stage_compose_env_semantic.py'
```

The capture integration fixture substitutes only the mount fingerprint because
Docker overlay/tmpfs are deliberately outside the production backing allowlist.
FD metadata, no-follow opens, reads, locks, sealed memfd, real config subprocess,
HMAC and the consumer execute normally. This is fault injection, not proof of
stage backing identity. The context suite additionally uses actual `strace` file
syscalls to audit absence of original-content reads after capture. A writable
overlay root changed root ctime during testing and correctly failed the existing
guard; the immutable-root fixture avoids that environmental side effect without
relaxing the production predicate.

The owned `/work/runner-temp` test mount explicitly permits the synthetic SSH
test executable; it remains `nosuid,nodev`, nonroot and networkless. The fixture
checks this before invoking the runner so `execvp` cannot fall through a noexec
test double to the system SSH client. Private runtime/target mounts need no exec.

On other toolchains run only the portable request/source/protocol selectors:

```sh
python3 -B scripts/tests/test_stage_compose_env_semantic.py ProtocolTest SourceTest
```

These selectors are not Linux semantic proof. Missing/unsupported runtime, or
any failed Linux test, cannot be replaced by mocks or reported as semantic PASS.
