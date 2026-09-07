# Project status checkpoint

## Scope and validity

Repository: `koteev-m/clubs_bot` (`/Users/maksimmartynov/IdeaProjects/clubs_bot`). This is the repository-local operational checkpoint, current as of the CLB-78 local instruction-alignment task on 2026-09-07. It records repository evidence and boundaries; it is not a product specification, external ChatGPT journal, deployment record, or runtime migration checkpoint.

## Task history and current task

- `CLB-76` — `COMPLETE / PR_READY`: PR #495 was made Ready for Review. This is inherited task history, not an action performed by CLB-78.
- `CLB-77` — `COMPLETE / PR_MERGED_CLEAN`: PR #495 was merged; the reported local `main` fast-forward and feature-branch cleanup are inherited history, not actions performed by CLB-78.
- `CLB-78` — instruction and checkpoint alignment. Scope: minimally align repository instructions for ChatGPT/Codex collaboration and record the current repository boundary. No Goal is created for this bounded documentation task.

## Repository state and evidence

- Local inspection for CLB-78 found branch `main`, one worktree at `/Users/maksimmartynov/IdeaProjects/clubs_bot`, and `HEAD` `25c4cb06f46817d51d1773a1338f2afeb5e58f19`.
- Remote `refs/heads/main` of `koteev-m/clubs_bot` was independently read through `git ls-remote` and GitHub API as `25c4cb06f46817d51d1773a1338f2afeb5e58f19` on 2026-09-07. This does not assert the state of local cached `refs/remotes/origin/main`; that cached ref was separately read by `git rev-parse refs/remotes/origin/main` as the same SHA during CLB-78. This is merge PR #495, whose tree is `22822a6fff6cf15b042b780947eb7573f3a3e6bb` and ordered parents are `da9717cef94daacccc9ba998f9af30d0fe27f656`, then `bf2706fefabc9d22b4d4cf92f93d835e65f90a73`.
- Before CLB-78 edits, index and worktree were clean; no untracked files or active merge/rebase/cherry-pick/revert operation were found. CLB-78 intentionally leaves an uncommitted documentation diff, so this checkpoint does not claim a clean worktree after its edits.
- The CLB-77 report recorded the initial post-merge snapshot: 15 `push` runs, with 3 `success` and 12 `in_progress`.
- A later read-only ChatGPT check for exact merge SHA `25c4cb06f46817d51d1773a1338f2afeb5e58f19` found `total_count=15` for `event=push` and `total_count=15` for `event=push&status=success`; the subsequently confirmed result is therefore 15/15 success.
- These historical checks were not run by CLB-78 and do not verify deployment or runtime. Sources: [Clubs Bot — журнал проекта и точка продолжения](https://docs.google.com/document/d/1I4m-kQvDjQ6xTNXh5t6pcdtrpNn9dGSVSgOGuWwwtQ4/edit) and related [PR #495](https://github.com/koteev-m/clubs_bot/pull/495); CLB-78 did not recheck either link.

## Accepted operational boundary

- Collaboration, authority, task-record, model-selection, verification and reporting rules are maintained compactly in root `AGENTS.md`; product truth, accepted decisions and security/release requirements remain authoritative under their existing documents.
- The release selector and CI fixture correction is in `main`; it does not prove deployment or a restored staging runtime. Retained one-off migration-container diagnosis remains historical evidence only.
- Runtime recovery, out-of-band host-key authentication, `SSH_KNOWN_HOSTS` provisioning, Release Status dispatch, resume and recovery require separate authority and are outside CLB-78.

## Operations, blockers and next step

- Completed CLB-78 checks: local Git preflight; applicable instruction and `CONTRIBUTING.md`/product-readme reading; scoped working-memory search; remote-main verification; `git diff --check`; full review of the tracked diff and untracked checkpoint; whitespace and checkpoint-link checks. No project documentation validator applies to these two Markdown files; the available validators target deployment, Mini App, payments or workflow YAML.
- CLB-78 prepared the local instruction/checkpoint edit, received text review, and applied the checkpoint-discipline, remote-reference distinction and CI-provenance clarifications. The diff remains uncommitted and is not final ChatGPT acceptance.
- No deployment, staging-runtime confirmation or release-status workflow execution has been performed. Their evidence is unverified here, and no recovery authority exists in this task.
- Allowed for CLB-78: minimal local edits to applicable instructions and this checkpoint, followed by documentation-diff validation. Prohibited: product/code/test/workflow/deploy/config changes; Git publication operations; external writes; SSH/provider/database access; dispatch, deploy, migration, resume, rollback or recovery.
- Next step: review the current unstaged CLB-78 diff and transmitted texts in ChatGPT before any publication.
