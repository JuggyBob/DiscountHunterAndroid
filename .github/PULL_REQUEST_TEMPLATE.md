# Pull request

<!--
Thanks! Please fill this in — it makes review much faster.

If this fixes an issue, put `Closes #123` in the description so the issue closes
automatically on merge.
-->

## What this changes

<!-- One paragraph. What is different after this PR? -->

## Why

<!-- The problem, not just the solution. What was wrong, missing, or slow? -->

Closes #

## How I tested it

<!-- Be specific. "Ran it on a Pixel 6 emulator, API 36" is useful. "It works" is not. -->

- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew lintDebug`
- [ ] Ran it on a device or emulator (version: )
- [ ] Added or updated tests that cover this

## Screenshots

<!-- Required for anything visual. Before and after is ideal. -->

| Before | After |
|--------|-------|
|        |       |

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking change (existing behaviour changes)
- [ ] Refactor or performance (no behaviour change)
- [ ] Documentation or build/CI only

## Checklist

- [ ] My code follows the project's style ([CONTRIBUTING.md](../CONTRIBUTING.md))
- [ ] I documented non-obvious decisions — comments explain *why*
- [ ] New behaviour has a test; a bug fix has a test that failed before it
- [ ] I did **not** break anything load-bearing: lazy-list keys stay stable and unique, cache keys
      stay structured, media stays tied to a verified store identity, parsing stays off the main
      thread, prices are converted rather than relabelled, motion tokens stay in `ui/theme/Motion.kt`
- [ ] I ran the full check suite locally (`./gradlew testDebugUnitTest lintDebug assembleDebug`)
- [ ] Documentation updated if behaviour changed (README, CHANGELOG)
- [ ] My contribution is licensed under Apache 2.0, per [CONTRIBUTING.md](../CONTRIBUTING.md)

## Breaking changes

<!-- If you ticked "breaking change", describe what a user or downstream consumer must do differently,
     and the migration path. Otherwise write "None". -->

None.
