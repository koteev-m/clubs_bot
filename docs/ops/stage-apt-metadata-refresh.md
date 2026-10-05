# CLB-175 local stage APT metadata refresh candidate

Status: LOCAL CANDIDATE ONLY. Not independently reviewed, published, merged,
provisioned or executed on stage. No capability is operational yet. Transaction
readiness remains **NO**.

User-supplied CLB-173 [run 37275966569](https://github.com/koteev-m/clubs_bot/actions/runs/37275966569),
attempt 1 at `ce53c12f798c1a4c5c71d5f4ae5683c292aefca3`, completed
validate/collect/workflow SUCCESS with authenticated `OBSERVED / COLLECTION_COMPLETED`.
Native-stage `outside_mapping.outside_count=0`: locale-archive disappeared with
no replacement. This supersedes the CLB-171 local-only locale verification limit.
Resolver exit 100 still reports unavailable OpenSSL `.16` versions in stale
indexes. CLB-174 found no supported refresh mechanism. Keep all 21 targets,
including `libssl3t64=3.0.13-0ubuntu3.16` and `openssl=3.0.13-0ubuntu3.16`.

## Fixed channel and privilege boundary

The separate [maintenance workflow](../../.github/workflows/stage-apt-metadata-refresh.yml)
is manual-only, main/default-branch-only and attempt-1-only. Its only input is
confirmation `CLB-175:refresh-stage-apt-metadata`. Validation precedes protected
Environment/secrets; execution revalidates before loading the SSH agent.
Permissions are `contents: read`; concurrency is `payments-schema-stage`,
`cancel-in-progress: false`. No package-plan chaining, retry or fallback exists.

The [runner](../../scripts/deploy/stage-apt-metadata-refresh.py) reuses the pinned
package-plan/corrected-stage source snapshot, bounded capture and anonymous
known-host pin primitives without changing their bytes. New runner/workflow/wrapper
must equal checked-out exact Git objects. It uses existing protected NON-ROOT
`SSH_USER`/`SSH_PRIVATE_KEY` at `178.20.209.5:22` and the retained Ed25519 fingerprint
`SHA256:Li2AIDm9/OG8CHWQw16qhDfzbRM7E9uLNjPeKOZ9ST0`. No new secret or root SSH key.
Remote username/non-root checks run before isolated `python3 -I -S -B`, with
remote `LC_ALL=C LANG=C`. One SSH transport only; no mutable remote helper.

The remote bootstrap checks canonical wrapper/parent metadata and SHA-256, then
invokes only `/usr/bin/sudo -n -- /usr/local/sbin/clubs-stage-apt-metadata-refresh`,
with no wrapper arguments. The sudoers digest is an independent binding.
No sudo shell/env/apt command permission exists. Fresh private-stdin HMAC binds
bounded result evidence to this transport; the runner adds exact workflow SHA,
run ID/attempt and source hashes. Raw stderr/credential-bearing APT URLs are not
published. SSH/sudo's normal security audit logging remains an expected transport
side effect; no new host log/config is provisioned by the workflow.

## Root wrapper and APT configuration

The [artifact](../../scripts/deploy/stage-apt-metadata-refresh-wrapper) is a fixed
`/bin/dash` launcher, refuses any argument (exit 64), clears the environment with
absolute `/usr/bin/env -i` and starts absolute isolated/no-site/no-bytecode Python.
Python requires EUID 0. APT receives only fixed PATH, HOME `/`, LANG/LC_ALL `C`,
DEBIAN_FRONTEND and the wrapper-owned APT_CONFIG descriptor. No caller proxy,
APT_CONFIG, Python or shell startup variable is retained.

APT_CONFIG points to a sealed anonymous Linux memfd containing reviewed fixed
configuration. This file is read **before** installed configuration; it sets both
`Dir::Etc::parts` and `Dir::Etc::main` to `/dev/null`, and clears Binary. No persistent
APT config file is edited. This applies to version/config preflight and update.
Sources, auth files and keyrings remain at APT's normal read-only locations;
per-source Signed-By is retained. System apt.conf custom proxies, hooks, methods,
trust overrides and index-target extensions are intentionally not loaded. A host
requiring those customizations is NOT compatible without another reviewed contract.
Do not provision by changing stage APT config to fit the candidate.

APT 2.7.14 or 2.8.3 only; other versions refuse. Before refresh, effective config
is bounded and checked for executable hooks, proxy autodetection and apt-get
binary overrides. `apt-config` itself adds compiled `Binary::apt` presentation
settings after loading config; these cannot apply to apt-get. Its
`CommandLine::AsString "/usr/bin/apt-config dump"` records argv, not a failure.

Authoritative basis: [APT configuration loading and list semantics](https://manpages.debian.org/bookworm/apt/apt.conf.5.en.html),
[APT initialization](https://github.com/Debian/apt/blob/2.7.14/apt-pkg/init.cc),
[binary defaults](https://github.com/Debian/apt/blob/2.7.14/apt-private/private-cmndline.cc),
[update hooks/error/cleanup behavior](https://github.com/Debian/apt/blob/2.7.14/apt-pkg/update.cc),
and [authentication-failure hook](https://github.com/Debian/apt/blob/2.7.14/apt-pkg/acquire-item.cc).
Update-time executable namespaces include APT::Update::Pre-Invoke, Post-Invoke,
Post-Invoke-Success and Auth-Failure. Empty `-o` assignments do not clear lists.
Excluding their configuration before loading avoids all of these, plus configured
proxy auto-detection and binary overrides. The actual-wrapper malicious-hook test
has a positive control that executes a sentinel; the candidate never executes it.

Exactly one fixed `/usr/bin/apt-get update` attempt. `Acquire::Retries=0`,
`APT::Update::Error-Mode=any`; both list-cleanup options are explicitly false.
No obsolete-list sweep, clean/autoclean, package downloads or package transaction.
APT can replace current indexes and remove its own acquired temporary files as
part of normal index acquisition; false cleanup flags do not make lists immutable.
No service operation, sources/keyring/config edit or application operation.
`pkgcache`/`srcpkgcache` writes and terminal/history/planner logs are disabled.
Intended persistent APT writes are only `/var/lib/apt/lists` and its necessary
partial/auxiliary/lock state. Existing dpkg locks are held nonblocking to exclude
concurrent package transactions; missing/busy/unsafe locks refuse. Canonical
state directories must be root-owned and not group/other writable. Lists must
contain regular files/directories, not symlinks; a `file:` source producing list
symlinks is incompatible. No auto-repair or cleanup is performed.

Evidence includes start/end, wrapper/operation hashes, attempt count, APT exit,
bounded diagnostic digest/count/category, before/after list manifests with counts,
bytes and latest mtime, full bounded dpkg-tree manifests (excluding locks), status
SHA-256, APT extended-state and /etc/apt configuration fingerprints. No filenames
or source contents are emitted. Any changed package/config invariant or incomplete
postcondition refuses success. Limits: 40,000 manifest files, 1 GiB aggregate,
512 MiB/file; private subprocess diagnostics capped at 4 MiB. Root preflight/update
budget 270 seconds, update at most 180, postconditions another 30; remote capture
330 and local capture 350 seconds. Cancellation/lost SSH can leave the privileged
operation's outcome unknown until its independent budget expires. Never infer
rollback from cancellation and never retry automatically.

## Out-of-band provisioning contract — NOT performed

Only an already-authorized root/server operator, under separate explicit host
provisioning authorization, may perform these steps after independent review and
merge. Provisioning is distinct from authorizing or executing a refresh.

1. Obtain the reviewed published artifact from the exact merged revision. Verify
   its SHA-256 equals `877715742a47e19bfa596be9c9aebc00de6e18de1a64b8d44346f2070f094c9b` (also pinned in runner and sudoers template).
   Check Ubuntu/Linux, absolute dash/env/python/APT/sudo tools, APT family above
   and sudo >=1.9.15p5 with sudoers digest/no-argument support. Sources/keyrings and
   APT binaries/methods must be administrator-owned and protected against changes
   by the SSH principal; no required site apt.conf proxy/trust/index policy may be
   silently lost. Verify existing canonical state directories/locks; do not run
   APT or change its configuration to satisfy provisioning.
2. Resolve the existing protected stage principal out of band: exact account name
   and UID >0, not root, not a reserved sudoers alias such as ALL. Substitute only
   an ordinary lowercase Unix username matching `[a-z_][a-z0-9_-]{0,31}` in the
   template; stop for any incompatible account. Do not commit the secret username.
   Confirm host identity and that `178.20.209.5` is a local host address matched by
   sudoers. If host matching fails, stop; do not broaden to ALL/wildcard hosts.
3. Canonical install path `/usr/local/sbin/clubs-stage-apt-metadata-refresh`:
   regular non-symlink file, owner root (UID 0), group root (GID 0), mode 0755.
   `/usr`, `/usr/local`, `/usr/local/sbin` must be canonical non-symlink root:root
   0755 directories, protected from principal writes/ACLs. Refuse an unexpected
   existing wrapper/drop-in; do not overwrite another operation's capability.
   Example root-operator installation from the verified local artifact:
   `/usr/bin/install -o root -g root -m 0755 <verified-artifact> /usr/local/sbin/clubs-stage-apt-metadata-refresh`.
4. Verify before granting sudo: `/usr/bin/stat -c '%F %u %g %a' <installed-path>`,
   `/usr/bin/sha256sum <installed-path>`, `/usr/bin/readlink -e <installed-path>`
   and parent/ACL inspection. Expected regular file, 0:0/755, exact hash/canonical
   path and no principal-writable parent. The workflow repeats those checks.
5. Render the [sudoers template](../../scripts/deploy/stage-apt-metadata-refresh.sudoers.in)
   privately, with only the verified principal substitution. Its sole rule is:

   ```sudoers
   @STAGE_PRINCIPAL@ 178.20.209.5 = (root:root) NOPASSWD: NOSETENV: sha256:877715742a47e19bfa596be9c9aebc00de6e18de1a64b8d44346f2070f094c9b /usr/local/sbin/clubs-stage-apt-metadata-refresh ""
   ```

   The final `""` means no command arguments, in addition to the wrapper's own
   argc refusal. This is supported by [upstream sudoers](https://github.com/sudo-project/sudo/blob/main/docs/sudoers.mdoc.in)
   and the [target 1.9.15p5 documentation](https://github.com/sudo-project/sudo/blob/SUDO_1_9_15p5/docs/sudoers.mdoc.in).
   No ALL, wildcard, shell, SETENV or arbitrary apt rule. Verify effective policy
   has no overlapping broad grant; the drop-in cannot revoke unrelated grants.
6. Run `/usr/sbin/visudo -c -f <private-rendered-file>` before installation.
   Install only `/etc/sudoers.d/clubs-stage-apt-metadata-refresh`, regular
   non-symlink root:root 0440, with root-owned protected canonical parent; validate
   `/usr/sbin/visudo -c` afterward. Read-only policy checks as authorized root:
   `/usr/bin/sudo -l -U <principal> -- /usr/local/sbin/clubs-stage-apt-metadata-refresh`
   must allow; corresponding queries with argument `update`, `/bin/sh`, and
   `/usr/bin/apt-get update` must deny. These are policy queries, not execution.
   Do not execute the no-argument wrapper as a provisioning test.
7. Any mismatch, broad existing grant, syntax error, hash/metadata/version issue
   is provisioning failure. Remove only this newly installed drop-in first,
   revalidate sudoers, then remove only this newly installed wrapper, with the
   same separately authorized root operator. Preserve other rules/config/files.
   If it was already present unexpectedly, stop rather than removing it.
   Revocation uses the same capability-specific removal order. No root SSH key
   or GitHub secrets/variables are created/changed. Never use workflow secrets
   to obtain unrestricted root access.

## Failure and operational sequence

Failure returns nonzero with bounded evidence if transport survives. Partial
updated lists may remain; no rollback/cleanup/second update or package-plan run
is automatic. Missing postconditions or transport loss means unknown outcome.
A root operator must assess recovery under separate authorization; restoring
old indexes would restore stale metadata and is not an automatic recovery policy.

After review/merge and separately authorized/verified provisioning:
separately authorize one maintenance run on exact main, dispatch once and approve
normal stage Environment once; inspect terminal evidence. Only then separately
authorize one fresh Stage Package Plan read-only run/Environment approval and
inspect resolver, requested `.16` availability, source/index signatures and other
readiness evidence. Metadata refresh is not transaction approval. Source-signature
trust, candidate script coverage, generated-state and high-impact transition gaps
remain separate. Current candidate grants no execution/provisioning authority.

## Local checks

Synthetic request/transport/framing/workflow/sudoers tests are registered in
selfcheck; Linux root tests are separate and refuse host execution. Disposable
networkless root filesystem, repository bind read-only; no package installation.
The actual wrapper is tested with installed APT 2.8.3, a local copy-method index,
malicious main/parts/Binary hooks, hostile inherited environment, and a public
signed local fixture using existing gpgv. Tests prove meaningful metadata change,
unchanged package DB/config/extended state, one failed attempt/no obsolete-list
cleanup, and fail-closed package-state tampering. Native arm64 syscall audit
requires container-only SYS_PTRACE to decode sandboxed _apt child paths; it checks
no unrelated persistent write and no APT loading of apt.conf files (wrapper may
hash them as data). No host PID namespace or writable host mount.

```sh
python3 -I -S -B scripts/tests/test_stage_apt_metadata_refresh.py
docker run --rm --network none --user 0 --cap-add SYS_PTRACE \
  --mount type=bind,src=<verified-repository>,dst=/repo,readonly \
  -e CLB175_DISPOSABLE=1 --entrypoint /usr/bin/python3 \
  <existing-reviewed-Linux-image> -I -S -B \
  /repo/scripts/tests/test_stage_apt_metadata_refresh_linux.py
```

No stage provisioning, sudoers/secrets modification or live refresh occurred.
One next step: independent read-only security review of this local candidate.

Final focused checks: synthetic 20/20 (including 12 rejected workflow mutations
and local visudo 1.9.17p2 fixture syntax); native Linux arm64 actual-wrapper 9/9;
amd64/Rosetta 8 PASS plus 1 explicitly skipped syscall audit. Package-plan runner
52 tests on Darwin/native Linux (1 archive-fixture skip each); collector Darwin
49 tests (1 Linux-only skip), native Linux 49/49. Shared transport capture/cancellation
14/14; workflow YAML/capability policy 29 workflows, refresh-specific validator,
Python compilation, shell syntax, affected links and diff checks PASS. Existing
release/collector/protocol/target/workflow bytes are unchanged. No shellcheck tool
was available; Linux visudo/effective installed stage sudo policy are unverified.
Actual APT fixtures use 2.8.3; 2.7.14 has authoritative-source review but no runtime
fixture here. These checks do not verify host provisioning or live refresh.
An additional unchanged corrected-release authority suite passed 9 cases in a
disposable non-root fixture; its original release-status producer case remained
blocked by LOCAL_FAILURE before synthetic SSH. Full authority-suite PASS is not
claimed; neither that producer nor its tests were changed.
