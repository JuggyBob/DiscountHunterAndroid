# Branch protection rules

GitHub does not enforce branch protection from files in the repository — it is a repository setting.
This file is the **documented, reviewable source of truth** for what the protection should be, plus
an optional ruleset that GitHub *can* apply automatically.

## Apply it with one command

From a clone of this repository, with `gh` authenticated and admin rights:

```bash
gh api -X PUT repos/:owner/:repo/rulesets \
  --input .github/branch-protection.json
```

Verify with:

```bash
gh api repos/:owner/:repo/rulesets
```

Or do it by hand: **Settings → Rules → Rulesets → New ruleset → Import** and paste
`.github/branch-protection.json`.

Review the file like any other change — it is the difference between "we require tests" and "we do
not require tests", and it should get the same scrutiny.

---

## What is enforced

| Rule | `main` | Reason |
|------|--------|--------|
| Pull request required | ✅ | No direct pushes; every change is reviewed |
| Approvals required | 1 | One maintainer's judgement is enough. Two is theatre for a project this size. |
| Dismiss stale approvals | ✅ | Approving a PR, then the author pushing more commits, must re-open review |
| Require review from Code Owners | ✅ | `CODEOWNERS` decides who must review what |
| Up-to-date branch required | ✅ | Merge `main` first, so CI runs against the real target |
| Conversation resolved | ✅ | No merging with unresolved threads |
| **CI / Build and test** | ✅ required | The gate. Unit tests + lint + assemble, all of them |
| **CodeQL** | ✅ required | Static analysis must not be skipped to land a change |
| Force-push | ❌ | Rewriting published history loses context |
| Deletion | ❌ | `main` is not deletable |
| Signed commits | ❌ | Not enforced for open contributions; signing adds friction and this is not a supply-chain-critical package |
| Linear history | ❌ | Merge commits are fine and keep the history readable |

## Why these settings

**Approvals are dismissed when new commits land.** Without this, a reviewer approves a PR, the
author pushes three more commits, and it merges with nobody having seen them. It is the most common
way branch protection is quietly defeated.

**The branch must be up to date before merge.** Without this, GitHub happily tests a PR against an
old `main` and merges it into a newer one that was never tested against it.

**CI is a required check rather than advisory.** `testDebugUnitTest` and `lintDebug` run in the same
`build` job as `assembleDebug`, so a red compile, a failing test, a lint error and a broken build all
show up as the single `CI / Build and test` status. One required check, one thing to wait for.

**Force-push is blocked on `main`.** Rewriting published history loses context and can silently
detach tags. Contributors should force-push freely on their *own* branches, and this rule does not
touch those.

## Release branches

`release/*` branches inherit the same rules. `develop`, if you use it, is a copy of this with the
approval count raised to 2.

## Changing these rules

Treat a change to `.github/branch-protection.json` like any other change to the project's rules: open
a PR, get a second opinion, and let CI pass. Relaxing protection is a decision worth writing down.
