# Contributing to Discount Hunter

Thanks for your interest! This document explains how to report bugs, suggest features, and submit a
pull request. Everything here is a guideline in the helpful sense — if something doesn't fit your
change, say so in the PR and we'll work it out.

- [Reporting a bug](#reporting-a-bug)
- [Suggesting a feature](#suggesting-a-feature)
- [Setting up the project](#setting-up-the-project)
- [Coding standards](#coding-standards)
- [Branch naming](#branch-naming)
- [Commit messages](#commit-messages)
- [Submitting a pull request](#submitting-a-pull-request)
- [Code review](#code-review)

---

## Reporting a bug

Open an issue from the **Issues** tab and pick the **Bug report** template. The single most useful
thing you can attach is a log export — see below.

### Exporting logs from the app

Settings → **Diagnostics** → **Export logs** writes a text file and hands it to your share sheet. It
contains:

- the last few hundred app messages (state changes and caught failures), oldest first
- your Android version, screen size and density, and free memory
- the app version

If the app crashed, the crash is captured into that log *before* the process dies, so the file
usually contains the run-up to the failure and not just the symptom. Nothing personal is recorded.

Attach it to the issue by drag-and-drop, or paste it in a collapsed `<details>` block.

### What a good report contains

1. **What you did** — the exact taps, in order.
2. **What you expected** and **what happened instead**. "Wrong price" is not enough; "the detail
   page showed £9.99 but the wishlist showed ~£10.85 for the same game" is.
3. **A log export** (see above).
4. **Device and Android version** — visible in the same Diagnostics export.
5. **Screenshots**, if a visual glitch is involved.

If something is reproducible every time, say so. If it is intermittent, try to work out what is
different — a store being down, a cache state, a currency.

## Suggesting a feature

Open an issue from the **Feature request** template. A good feature request says:

- **The problem**, not just the solution. "I want to watch prices over time" is a problem;
  "add a sparkline" is a solution that might not be the right one.
- **What you expect to happen**, including in the awkward cases.
- **Why the current app does not do it** — there may be a good reason it doesn't.

Small UI or quality-of-life improvements are very welcome. Large architectural changes: open an
issue *first* so we can agree on the approach before you spend a weekend on it.

---

## Setting up the project

```bash
git clone https://github.com/JuggyBob/DiscountHunterAndroid.git
cd DiscountHunterAndroid

echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew testDebugUnitTest     # should report 334 tests, 0 failures
./gradlew assembleDebug
./gradlew installDebug
```

Debug builds install as `com.discounthunter.android.debug`, so they coexist with a release install.

Before opening a PR, run the same checks CI runs:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

---

## Coding standards

### Kotlin and Compose

- **Follow the existing style.** The codebase uses 4-space indent, trailing commas, and roughly
  100-column lines. Match the file you are editing.
- **Comments explain *why*, not *what*.** The existing code is unusual in how much it documents
  non-obvious decisions and trade-offs. That is deliberate — please keep it up. A comment
  restating the line below it is noise; a comment explaining why an obvious approach was rejected
  saves the next person a day.
- **Prefer a pure function when the logic can be one.** Anything testable without Android
  framework classes should live in `core/` and be a plain function. This is why the app has good
  unit-test coverage without Robolectric.
- **No business logic in Composables.** A `Composable` should read state and call view-model
  methods. If you find yourself computing something meaningful in a composable body, move it out.
- **State flows down, events flow up.** View models expose a single immutable UI state and take
  intents. See any `*ViewModel` in `ui/`.
- **Cancellation is not an error.** In `catch` blocks, rethrow `CancellationException` rather than
  swallowing it — otherwise a superseded load silently appears to succeed. There are several
  comments in the code marking these.

### Things that are load-bearing

These have caused real bugs before. Please do not undo them without understanding why:

- **Lazy-list keys must be stable and unique** — the store-native id, never the list index.
  Duplicate keys crash Compose mid-layout.
- **Cache keys are built from structured parts**, never free-form strings, so a wrong payload cannot
  be served for the right key.
- **Media is only shown for positively verified store identities.** Never relax this to "probably the
  same game"; that is exactly how one game's screenshots end up under another's title.
- **Parsing and network calls run off the main thread**, always via the injected `CoroutineDispatcher`.
- **Prices are converted, never relabelled.** If a store quotes a currency the user did not choose,
  convert it (and mark it `~`) or show the store's own currency — never print a foreign amount under
  a different symbol.
- **Every motion token lives in `ui/theme/Motion.kt`.** Do not hard-code durations or easings in a
  screen.

### Tests

- New behaviour needs a test. Bug fixes need a test that fails before the fix — write it first if
  you can, and say in the PR that you did.
- Name tests as sentences describing the rule, not the method:
  `fun 'a corrupt snapshot is treated as absent'()`, not `fun testCorruptSnapshot()`.
- Put them beside what they cover: `app/src/test/java/.../matching/ThingTest.kt`.
- Tests must not touch the network or the filesystem outside `TemporaryFolder`.

---

## Branch naming

Use a short, descriptive, hyphenated name prefixed with its type:

| Prefix | Use for | Example |
|--------|---------|---------|
| `fix/` | A bug fix | `fix/convert-gog-prices-to-chosen-currency` |
| `feat/` | A new feature | `feat/price-drop-notification` |
| `refactor/` | No behaviour change | `refactor/split-detail-repository` |
| `perf/` | A performance improvement | `perf/smaller-screenshot-requests` |
| `docs/` | Documentation only | `docs/expand-contributing` |
| `test/` | Tests only | `test/wishlist-ordering` |
| `chore/` | Build, CI, tooling | `chore/bump-gradle-to-9-4` |

Always branch from an up-to-date `main`. Do not work on `main` directly.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <imperative summary under ~72 chars>

<optional body: what changed and, more importantly, why>

<optional footer: Closes #123, BREAKING CHANGE: ...>
```

Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`.

Good:

```
fix(price): convert GOG prices instead of relabelling them

GOG's catalogue ignores every currency parameter we send and always
answers in USD, so selecting EUR printed a dollar amount under a euro
sign. The offers table also ranked mixed currencies by raw number, so
the cheapest offer differed per screen.

Prices now carry the currency the store quoted and are converted with
the ECB daily rate, marked with ~ so an estimate is not mistaken for a
quoted price.

Closes #43
```

## Submitting a pull request

1. **Branch** from `main` (see naming above).
2. **Make the change**, with tests.
3. **Run the checks**: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
4. **Commit** with a conventional message.
5. **Push** and open a PR against `main`. The template asks for a summary, a
   linked issue, how you tested, and a checklist.

CI must be green. Required checks are configured as branch protection on `main`; a reviewer approval is
required for merge. If you are a maintainer and need to bypass, say why in the PR.

## Code review

- A maintainer will review within a few days. Reviews are about the code, not you.
- Expect to be asked for changes. That is normal and not a judgement.
- We may ask you to rebase or squash-merge; both are fine.
- **Please don't get discouraged.** Thoughtful contributions are genuinely valued here, including
  first-time ones.

---

## Code of conduct

Be respectful and constructive. Harassment of any kind is not tolerated in issues, PRs, or
discussions. Assume good faith; everyone here is trying to help.

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same license as the project.
