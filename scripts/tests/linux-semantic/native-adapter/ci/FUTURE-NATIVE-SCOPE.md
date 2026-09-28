# One proposed native experiment — not executed or authorized here

## Environment decision

Use the `amd64-runtime-prototype` job within one manual `Tests` workflow dispatch on a GitHub-hosted `ubuntu-24.04` x64 disposable VM, with the candidate package frozen in the exact dispatched commit. Replace only that prototype job's experiment body; no other Tests jobs or runtime deployment workflow is changed. The runner label is a scheduling request. The actual Linux architecture, absence of emulation, backing filesystem, procfs, namespaces and fixed privileged syscall capability must pass fresh checks. A rejected runner yields a bounded refusal, not a sysctl/AppArmor/filesystem workaround.

The original synthetic directory is created under the disposable test area and bind-mounted at `/opt/clubs-bot-stage` **in an outer private mount namespace only**. It is a dedicated bind mount on an accepted real backing filesystem. The binding is established after that mount. The inner adapter projects that same held original object at the same canonical mount target and verifies real FSTYPE/SOURCE/FSROOT/TARGET, device/inode, numeric UID/GID and post-run identity. No UID user-namespace rewrite and no fabricated procfs/findmnt information are allowed. The extra dedicated-mount prerequisite is prototype-specific; applicability to an existing stage layout is not asserted.

Synthetic principal ownership is numeric UID/GID1000, as pinned by the candidate runtime. The runner's own user need not be UID1000. The fixed root setup may create/chown **its own synthetic fixture only** inside the disposable area; this is an explicitly proposed future permission. The name `prototype` comes from the candidate's pinned private `/etc/passwd`; it is not evidence that the runner's host account database names UID1000 identically. No host account is created and no host `/etc/passwd` is edited.

## Exact preparation and test boundaries

1. Publish/review the small candidate source package and targeted workflow patch at one exact commit. Publication itself is outside current permission.
2. Fetch only the frozen official inputs through `ci/prepare-native-inputs.py`; network preparation is separately logged and stops before the native operation on any mismatch. This reconstructs the original reference image and the exact private runtime; the Mac-local image is neither assumed nor shipped.
3. Compile the candidate standalone adapter on that native runner with the documented static compiler invocation. Record source/header identities, the selected compiler/linker/CRT/static archive identities and binary hash. Headers, GCC built-in specs and the entire provider image are not independently pinned; this completeness limit is recorded by the driver. A failed static link or unexpected PT_INTERP/NEEDED is STOP; there is no dynamic or host-Python fallback. These are candidate experimental identities, not new production pins.
4. Start one fixed native test entrypoint in a disposable outer namespace using separately approved root namespace/setup authority; the adapter takes only `--runtime-root ABS --source-root /opt/clubs-bot-stage`. It has no arbitrary command, input discovery or output-file parameter. Original FDs are acquired before private-view transition. No Docker daemon/socket participates in the tested runtime chain; Docker is used solely for reconstruction of the public exact inputs.
5. Execute the full positive original-object → capture → actual Ruby/Psych/Compose → authenticated result, then fixed negative cases and own cleanup. No stage connection, application data, Environment or secret is available to the job.
6. Upload only the bounded allowlisted JSON evidence, with three-day retention. A native compilation or environment prerequisite failure is reported as NOT_RUN for dependent cases; it must not produce a semantic PASS.

## Proposed permissions and effects requiring one future decision

- One exact-commit manual workflow dispatch and publication of the candidate/test workflow delta.
- The prototype VM job has a75-minute bound. Dispatching existing `Tests` also schedules unconditional `unit-tests` and `integration-tests`,45minutes each: up to165total job-minutes, their existing retry loops, JDK/Gradle/dependency downloads and pinned PostgreSQL test service. Their existing report uploads are additional to the bounded adapter artifact. These effects require explicit future scope; GitHub has no dispatch-one-job API. Usage is subject to the account's existing Actions billing/quota; no claim that it is free. No extra cloud host or persistent runner is requested.
- HTTPS retrieval of locked Ubuntu snapshot records/61 archives and four Compose assets; digest-pinned Ubuntu and cosign OCI pulls. Sizes/bounds and exact identity ledger are in `input-route.json`. No new stage reads.
- Package scripts only during offline construction of a disposable reference image. No package or tool installation on the runner host or stage.
- One fixed root namespace/setup capability in the disposable VM: private mount/PID/network namespaces, readonly bind views, procfs, own fixture ownership and unchanged numeric UID/GID drop. No sudo shell, privileged container, host-global mount, sysctl/AppArmor change or Docker socket in the worker.
- Test-created files, compiler outputs, temporary mounts/processes and anonymous temporary files only in the disposable test area/namespaces. Every negative case reports own cleanup; no wide deletion or process-name killing.
- One bounded synthetic artifact upload. Raw fixtures, private canaries, runtime image/rootfs tar and inherited logs are not uploaded.

The input-preparation stage uses existing independently pinned helpers. If interrupted while an OCI daemon operation is outstanding, its report explicitly says daemon cleanup UNKNOWN and does not start the adapter. This is distinct from the fixed adapter's owned child/namespace cleanup proof; an input-preparation timeout is never a native PASS.

If these fixed capabilities or the actual backing are absent, stop. The single alternative is a separately approved **already provisioned** disposable native amd64 VM with the same input bundle, filesystem and namespace prerequisites. No resources or access are created by this package.

## Existing-workflow side effects must not be omitted

`workflow_dispatch` is a workflow event. The unchanged unit/integration jobs have no event exclusion and run alongside the prototype. The prepared patch intentionally leaves their behavior intact. The1MiB/three-day artifact restriction applies only to the prototype artifact. An approval for only the adapter command does not authorize a full existing Tests dispatch. Also, `concurrency.group=tests-${{ github.ref }}` and `cancel-in-progress=true` can cancel an active same-ref run; require absence of a conflicting run or separate explicit acceptance before a future dispatch. No CI status query or dispatch was performed here. If the broader existing workflow is unsuitable, use the one already-provisioned native VM alternative stated above with exactly the prepared native entrypoint and no new resource creation.

The165-minute figure is the sum of configured job timeouts, not a guaranteed billing or wall-clock cap (jobs may run concurrently). Unchanged jobs also use Temurin21, writable Gradle caches, dependency fetches/retries up to3, the pinned PostgreSQL service and unit/integration/Jacoco report uploads with their own existing policies. The bounded adapter artifact does not bound those outputs.
