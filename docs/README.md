# Documentation

| Document | What it is |
|----------|------------|
| [`../README.md`](../README.md) | What the app does, screenshots, how to build it |
| [`../CHANGELOG.md`](../CHANGELOG.md) | What changed in each release |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | How to report bugs, propose features, submit a PR |
| [`../SECURITY.md`](../SECURITY.md) | How to report a vulnerability privately |
| [`../DEV_NOTES.md`](../DEV_NOTES.md) | **The engineering write-up** — every bug found and the reasoning behind the fix |
| [`ENGINEERING.md`](ENGINEERING.md) | The project's original engineering README: architecture, conventions, the bug catalogue |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | How the code fits together, for people about to change it |

## Where to start

- **Using the app?** → [`../README.md`](../README.md)
- **Contributing a fix?** → [`../CONTRIBUTING.md`](../CONTRIBUTING.md), then
  [`ARCHITECTURE.md`](ARCHITECTURE.md)
- **Curious why something is the way it is?** → [`../DEV_NOTES.md`](../DEV_NOTES.md). It is 38
  sections explaining what broke and what the fix was, which is the fastest way to learn the
  invariants this codebase depends on.
- **Reviewing a change?** → [`../CONTRIBUTING.md`](../CONTRIBUTING.md#things-that-are-load-bearing)
  lists the six rules that have caused real bugs if undone.

## Screenshots

[`social-preview.png`](social-preview.png) is the GitHub social preview — GitHub looks for a file
called `social-preview.png` in `.github/`, `docs/`, or the repo root. Copy or symlink it to
`.github/social-preview.png` if you would rather not ship it in `docs/`.

[`screenshots/`](screenshots/) holds the images used in the README.

| File | Screen |
|------|--------|
| `01-first-run-tour.png` | The five-page tour shown on first launch |
| `02-deals-feed.png` | The daily deal feed, with the day strip and filters |
| `03-search.png` | Cross-store search results for *elden* |
| `04-detail-page.png` | A detail page: hero, price, "Where to buy", trailers |
| `05-detail-details.png` | Requirements, genres, developers, price history |
| `06-wishlist.png` | The wishlist, showing saved games and their current best price |
| `07-settings.png` | Theme, currency and store settings |
| `08-diagnostics.png` | The Diagnostics card with the log-export button |
| `09-where-to-buy.png` | A cross-store detail page comparing GOG and Steam prices |

To regenerate them, build the release APK, run it on an emulator, and drive it with `adb`:

```bash
adb exec-out screencap -p > out.png     # note: use cmd on Windows, PowerShell corrupts binary
```

## A note on screenshots and store content

These screenshots contain real game titles and artwork returned by the stores' public APIs. They
are here to show what the app does, under the same fair-use reasoning as any review or article
about a game. Game names, logos and cover art remain the property of their respective owners, and
none of this implies any endorsement or affiliation.
