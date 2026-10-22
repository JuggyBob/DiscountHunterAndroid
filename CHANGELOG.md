# Changelog

All notable changes to Discount Hunter are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet. Open an issue or PR to propose something.

## [1.1.1] - 2026-10-22

The app can now find and install its own updates, and its Settings screen has an About section that
shows what changed in the latest release.

### Added

- **Check for updates.** A new *Updates* card in Settings. It reports whether a newer version has
  been published, and downloads and starts the installation of it without the user having to find
  the release page first. Nothing is fetched until the button is pressed, and nothing is downloaded
  until the download is asked for.
- **About / What's New.** A dialog showing the version installed on the device, the latest
  published version, when it was published, and the release notes for it. The notes come from the
  official GitHub release, so they are the same notes the release page shows. A copy is kept on the
  device, so the dialog still says something useful when there is no network.
- **The update flow respects the platform's safeguards.** The downloaded package is handed to
  Android's own installer, which asks the user to confirm. On Android 8 and above the "install
  unknown apps" permission is granted separately, in system settings, and the app says so and
  takes the user there.

### Fixed

- **A release signed with the wrong key installed once and then refused to update.** The release
  workflow now restores the keystore from a repository secret, verifies the signature before
  publishing, and refuses to publish at all if the secret is missing.

### Security

- The release check uses GitHub's public API **without a token**. A token embedded in an
  application can be extracted from every user's device; the endpoint is public, and the result is
  cached so it is not hammered.
- Release notes are rendered from a deliberately small Markdown subset. HTML is stripped, and a
  link in somebody else's release notes is shown as text rather than made tappable.

## [1.1.0] - 2026-10-20

Deal alerts: the app now tells you when a game you saved goes on sale, and when a game becomes
free to keep — with the name, the new price and the discount — and it does so **with the app
closed**.

### Added

- **Background deal alerts.** A wishlisted game that starts being discounted is announced, with its
  name, its new price and the discount rate. Free games are announced too, whether or not they are
  saved. Two notification channels, so "Sales on your wishlist" and "Free games" can be tuned
  separately.
- **A Deal alerts switch in Settings**, in its own card, explaining what is checked and how often.
  When notifications are blocked at the system level, the screen says so and links straight to the
  system notification settings rather than leaving the switch quietly doing nothing.
- **Tapping a notification opens that game**, showing the same price the notification did.
- **Prices in notifications are converted** the same way the app converts them, and marked `~` so a
  converted figure is never mistaken for a quoted one.

### Fixed

- **The background check never ran at all.** `WishlistRepository.loadOnce()` awaited a DataStore
  flow with `collect`, which never returns — the flow stays open for the life of the process. The
  worker started, blocked on the wishlist load, and hung with no error, no crash report and no
  notification. It now reads the current value with `first()`.
- **Two scheduling policies, both wrong.** `REPLACE` cancelled the in-flight check, and because the
  alarm and the periodic worker can fire together that became a cancellation storm in which no check
  ever completed. `KEEP` fixed that but let a work stuck in a queued state block every later request
  under the same name, so the checks stopped silently. The request is now simply enqueued;
  overlapping runs are harmless, because a notification posted under the same tag and id replaces
  itself rather than adding to the shade.
- **The release build crashed on launch, and the debug build did not.** WorkManager keeps its
  schedule in a Room database, and Room instantiates the generated implementation reflectively — a
  call R8 cannot see, so R8 removed it. Every debug run passed; the crash existed only in the build
  that would have been shipped. Fixed with two keep rules, and the release APK is now installed and
  launched on a device before the work is called done.
- **A notification's URI arrived with `data == null`.** The intent that was built was correct and
  the intent that arrived had no data, so tapping a notification opened the app and went nowhere.
  The identity now travels in intent extras, which are copied verbatim and cannot be lost that way.
- **A notification's deep link showed the wrong price.** Without a title, the detail page looks a
  GOG game up with a catalogue search, and that returns unrelated products for anything but a
  distinctive name. The title now travels with the link, and the page shows the same price the
  notification did.
- **A free game was announced twice** — as "is free" and as "100% off". A free deal is now skipped
  by the sale path, and its record cleared so that a later paid discount is still announced.

### Changed

- The check runs on **two** mechanisms rather than one: an `AlarmManager` alarm, which is punctual
  and Doze-aware, and a WorkManager periodic job, which is guaranteed and survives a reboot. Both
  end up running the same idempotent check, so overlapping triggers cannot double-notify.
- The exact-alarm permission is asked for rather than assumed. If it is refused, the scheduler falls
  back to an inexact alarm, which still works inside Doze — a later notification beats none.
- The background check fetches a larger feed than the app shows, so a wishlisted game at a shallow
  discount is not missed below the cut-off.

## [1.0.0] - 2026-10-11

The first public release: a native Android port of the Discount Hunter desktop app, plus the fixes
found by running it on a real device.

### Added

**Core app**

- **Daily deal feed** merging Steam, GOG and Epic discounts into one list, with a seven-day strip
  for browsing past days (from the on-device snapshot) and upcoming days (Epic giveaways).
- **Filtering and sorting** by discount depth, price, title, store and "saved only", with the choice
  remembered per session.
- **Cross-store search** returning one deduplicated result per game rather than one per store.
- **Wishlist**, savable from the feed, from search results and from a detail page, shown newest-first
  with the current best price and which store it is at.
- **Game detail pages** with hero art, screenshots, trailers, description, system requirements,
  genres, release date, developers, publishers, and a "Where to buy" table comparing every store
  that has the game.
- **Price history** charted from prices this device recorded, with per-store deep links to external
  databases (SteamDB, GG.deals, PSprices and others).
- **Settings**: theme (system/light/dark), currency, included stores, deals per store, trailer
  autoplay, and preference for store apps over a browser.
- **First-run tour** — five animated pages shown on first launch.
- **Export logs** under Settings → Diagnostics: the last few hundred log lines plus device and
  memory info, shared through a `FileProvider`. An uncaught-exception handler records crashes into
  that buffer before the process dies.
- **Delete all app data**, with a confirmation, which also brings the first-run tour back.

**Platform**

- Jetpack Compose throughout, Material 3, Material You dynamic colour, edge-to-edge, **portrait
  only**.
- Coil for image loading, sharing one disk cache, memory cache and connection pool app-wide.
- Media3/ExoPlayer for trailers, with no fake play controls — a thumbnail only looks playable when
  there is something real to play.
- DataStore for preferences, files for price-history snapshots, and a hand-rolled `AppContainer`
  for object lifetimes. No DI framework, no database.

**Porting**

- Full port of the WPF desktop app: deals, search, filtering, sorting, prices, stores, detail pages,
  screenshots, trailers, links, wishlist, metadata, categories and settings.
- The desktop app's core identity bug — matching games by title — replaced with **store-native ids**
  throughout (see *Fixed* below).

### Fixed

Each of these was found by running the port on a device. `DEV_NOTES.md` explains the reasoning and
failure mode behind every one.

**Identity and media ownership**

- A game could show **another game's screenshots and trailers** — the original critical bug. Media is
  now only shown for store identities positively verified by the store's own response.
- Cross-store fallback never ran for the opened store, because the accepted-identity set was
  pre-seeded with the identity the user happened to open.
- Wishlist matching collapsed to a single entry, because a predicate always evaluated true.
- A game saved on one store was not recognised on another.

**Prices**

- The detail page showed **three different prices for one game** — Steam's in the header, the
  cheapest store's in "Where to buy", and that same one again in the wishlist. The headline now
  leads with the offers table, the page's single source of truth.
- `bestOffer` ranked offers by raw number, which is meaningless across currencies: a £4.99 Steam row
  was being called cheaper than a $5.50 GOG row. Both sides are now converted before comparing.
- The app **relabelled prices it had not converted**. GOG publishes in dollars whatever region is
  asked for, so `$0.49` was displayed as `€0.49` — a different price, not a converted one. Prices now
  carry the currency the store actually quoted and are **converted** with the ECB's daily reference
  rates, marked `~` so an estimate is never mistaken for a quoted price.
- Changing currency did not change prices. The feed never reloaded on a Steam-region change, and day
  snapshots were not region-scoped, so a past day served the previous currency's figures.
- The offers table passed the target currency where the source belonged, so a converted row printed
  without its `~` marker.

**Crash fixes**

- **GOG parsing crashed** on certain titles with a `PatternSyntaxException`: ICU rejects regular
  expressions that the JVM accepts.
- **DLC and other titles with no recorded price history crashed** the detail page with
  `NoSuchElementException`.
- **Duplicate lazy-list keys crashed screens** when a store returned the same product twice.
- All JSON and HTML parsing ran **on the main thread**; every service now takes a dispatcher and
  parses off it.
- Two coroutine races: the wishlist index and list were rebuilt in two critical sections, and a
  `runCatching` rethrew `CancellationException` as a failure.
- The trailer player **spun forever** because `ExoPlayer.playbackState` is not Compose state.
- The filter sheet clipped its own title field.
- Star-pop animations were keyed on the saved flag, so rapid tapping queued dozens of springs
  against one `Animatable`.
- "Delete all app data" left price-history snapshots on disk, because `File.delete` on a non-empty
  directory fails silently on Android.

**User interface**

- The wishlist poof left exiting rows on screen forever — the completion callback never fired.
- Wishlisting from search did nothing visible; saved results now rise to the top.
- Wrapped store badges sat flush against the line above.
- "Where to buy" was over-indented and showed duplicate offer boxes.
- Search stars did not fill in until the user left the tab and came back.
- "Added today" never appeared and the wishlist did not sort by age, because an epoch-milliseconds
  `Long` was written but read back with an `Int` parser.
- Price history appeared before the chart could handle an empty list.

### Performance and robustness

- Network requests have timeouts, bounded retries, and rate-limit handling; nothing blocks the main
  thread.
- Feed pages load progressively as you scroll.
- Images are requested in appropriately small variants with correct cache keys, so one game's
  artwork is never served for another.
- Parsed store payloads are cached and reused, keyed by store, kind, id and region.

### Known limitations

- **GOG cannot re-quote by region.** Its catalogue ignores every currency parameter the app sends and
  always answers in USD, so GOG prices are *converted* rather than quoted. Converted prices carry a
  `~` and a note naming the rate's date; the store's own price may differ by a little.
- **Epic giveaways have no media.** Epic's product pages block automated access, so Epic entries
  appear in the feed and calendar without screenshots or trailers.
- **Price history is local.** The chart shows prices *this device* recorded. External databases are
  reachable through deep links rather than being scraped, because most of them block programmatic
  access.
[Unreleased]: https://github.com/JuggyBob/DiscountHunterAndroid/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/JuggyBob/DiscountHunterAndroid/releases/tag/v1.1.1
[1.1.0]: https://github.com/JuggyBob/DiscountHunterAndroid/releases/tag/v1.1.0
[1.0.0]: https://github.com/JuggyBob/DiscountHunterAndroid/releases/tag/v1.0.0