# stsloop

An audio-first personal context hub: a bidirectional speech-to-speech turn loop
over an attention queue.

## Agent skills

### Issue tracker

Issues live in GitHub Issues for `walnutgeek/stsloop`, managed via the `gh` CLI.
See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary; each label string equals its role name.
See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` plus `docs/adr/` at the repo root.
See `docs/agents/domain.md`.

## Working on tickets

- **One background agent per ticket**, each in its own git worktree, running
  the `/implement` flow:
  - TDD at the `:core` seam;
  - `mise exec -- ./gradlew build`, plus device tests when the ticket touches
    `:audio`;
  - `/code-review` against `origin/main`, with the ticket as the spec;
  - a PR in the `/pr` template, with `Closes #N`.
- **Run tickets in parallel** when they touch different code. A ticket waits
  for the tickets that block it.
- **Note low-confidence choices and standouts on the ticket**, as a comment
  with short bullets that each give the reason. That comment is where the owner
  reviews decisions.
- **Merge when CI passes and GitHub reports no conflicts**
  (`mergeStateStatus` is `CLEAN`). A PR that is `DIRTY` goes back to its agent
  to rebase onto `origin/main`. Tell agents still running when `main` moves
  under them.
- **Delete the branch only after confirming the PR is `MERGED`.** Never chain
  the merge and the branch delete with `;`, because a failed merge then still
  deletes the branch and closes the PR.
- **Keep going until blocked by human-only work**, such as a drive, listening
  to Recordings, or a check on the phone.

## The dev phone

The Pixel 10 Pro on USB is the owner's own phone, and it holds the real Corpus.

- **Lock every adb call:** run it as
  `flock /tmp/stsloop-phone.lock mise exec -- adb ...`, because agents share
  the phone. Keep each locked section short.
- **Leave the owner's data alone:** never modify or delete the owner's Corpus
  Turns (tests use their own directories). Leave the `files/*.json` configs as
  you found them.
- **Leave the phone itself alone:** never lock the phone, and ask before
  changing its settings.
