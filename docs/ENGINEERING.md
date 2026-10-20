# Discount Hunter — Android

A native Android port of the Discount Hunter desktop app (`../WindowsVersion`, C#/.NET 8/WPF).
Kotlin + Jetpack Compose, Material 3, single activity, bottom navigation, no WebView.

The port keeps the behaviour of the PC app and fixes the bugs that were found in it while porting —
most importantly the one where a game's detail page showed **another game's screenshots and
trailers**. It also fixes a handful of bugs that only appear on a real device, listed at the bottom
of this file and detailed in [DEV_NOTES.md](DEV_NOTES.md).

---

## Build and run

Requirements: **Android SDK** with platform `android-36.1` and build-tools `36.0.0`, and a **JDK 17+**
(Android Studio's bundled JBR works).

```bash
# point the build at your SDK (or add sdk.dir to local.properties)
echo "sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk" > local.properties

# debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# release APK -> app/build/outputs/apk/release/app-release.apk  (R8 + resource shrinking)
./gradlew :app:assembleRelease

# unit tests (334 tests, 26 suites)
./gradlew :app:testDebugUnitTest

# lint (configured to fail the build on errors)
./gradlew :app:lintDebug
```

Install and launch:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.discounthunter.android/.MainActivity
```

| | |
|---|---|
| Application id | `com.discounthunter.android` (debug: `…android.debug`) |
| minSdk / targetSdk | 24 (Android 7.0) / 36 |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE` — nothing else |
| Deep links | `https://store.steampowered.com/app/<id>`, `https://www.gog.com/en/game/<slug>`, `https://store.epicgames.com/p/<slug>`, and the app's own `discounthunter://<store>/<id>` |

> `minSdk 24` is honoured by **core library desugaring**. The app uses `java.time` throughout
> (`LocalDate` for the day strip, `Instant`/`ZoneId` for the giveaway schedule) and those types only
> exist from API 26; without desugaring it compiles and then crashes on Android 7. Lint enforces this.

### Release signing

`assembleRelease` works on a clean checkout: when no keystore is configured the build falls back to
the debug signing key so the APK is still installable. To sign properly, add `keystore.properties`
to the project root (**not** committed):

```properties
STORE_FILE=release.keystore
STORE_PASSWORD=…
KEY_ALIAS=upload
KEY_PASSWORD=…
```

`STORE_FILE` / `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` as environment
variables work too and take precedence.

---

## What the app does

Five top-level destinations, reached from the bottom bar:

| Screen | What it does |
|---|---|
| **Deals** | Today's discounts from Steam + GOG + Epic in one feed. A horizontal day strip jumps to the section for a day and marks days with giveaways starting or ending. Filter sheet: stores, free-only, min discount, title filter. Sort: discount depth, lowest price, title A–Z. Pull to refresh. |
| **Search** | Cross-store search with best-deal detection — one row per game, showing the cheapest price and which stores matched. Recent searches are remembered. |
| **Wishlist** | Followed games, sorted with live discounts first; rows show "on sale now" with price, store and percentage. Star/unstar, open details, get deal, clear all. |
| **Settings** | Theme (system/light/dark), currency (USD/EUR/GBP), which stores to include, deals per store, trailer autoplay, prefer store apps, cache size + clear. |
| **Detail** | Full page for one game: hero art, description, screenshots (paged row + full-screen viewer), trailers, minimum/recommended requirements, genres/categories/features, developer/publisher/release date, a best-deal table across stores, and actions to open the store. |

Every list is a Compose lazy container keyed on a **stable identity** — the store-native id — never
on index. All network work happens in `viewModelScope` / `Dispatchers.IO`; nothing touches the main
thread. Every screen has explicit loading, empty and error states with a retry action.

**Screen sizes.** The deal feed renders in a `LazyVerticalGrid` whose column count is derived from
the available width (one column on a phone in portrait, two in landscape, up to three on a large
tablet); the day strip and the sort/filter bar span the full width. Nothing is clipped or stretched
at any size.

**Video and images play inside the page.** Tapping a trailer opens a player *inline* in the detail
page — the page stays readable around it, and the trailer's tile stays tappable so another can be
chosen. Expanding to full screen is an explicit button, and the transition *glides*: the overlay
slides up on a spring while the scrim fades in, and collapsing reverses it. The screenshot viewer
animates in the same way (slide + scale). The same `ExoPlayer` instance is shared by the inline card
and the overlay, so expanding never re-buffers or loses the playhead.

### Data sources (no keys, no tracking)

* **Steam Store API** (`store.steampowered.com/api`) — specials feed, search, `appdetails`.
  When the API leaves a gap (screenshots, requirements, a playable trailer) the game's **Steam store
  page** is parsed as a fallback, age-gate cookies included.
* **GOG** — the catalogue API for discounts and full-text search, and the game's public GOG page for
  the description, gallery, requirements and trailers.
* **Epic Games** — the public giveaways feed (current + upcoming free games).

Media is streamed from the web; only thumbnails and small payload snapshots are cached on disk.

### "Get deal" without a store app

**Get deal** opens the store's own app when one is installed, and the game's public page in the
default browser when one is not. If a store app is installed but refuses the link — disabled, or no
handler for that particular URL — the hand-off falls through to the browser rather than giving up, so
the button is never a dead end.

---

## Architecture

```
app/src/main/java/com/discounthunter/android/
  MainActivity.kt          single activity, bottom bar, NavHost, deep links, snackbars
  DiscountHunterApplication.kt
  core/
    model/                 Deal, GameIdentity, Media, GameDetail, DealFilter, PriceFormat, …
    text/                  TitleMatcher, TitleKey, TextTools, HtmlEntities, Json
  data/
    net/                   HttpClient (OkHttp), Outcome, retry/backoff
    steam/ gog/ epic/      per-store fetch + parse
    source/                SteamSource / GogSource / EpicSource interfaces (the seam for tests)
    repo/                  DealRepository, SearchRepository, GameDetailRepository, WishlistRepository
    cache/                 CacheKey, PayloadCache (TTL), SnapshotStore
    settings/              DataStore-backed settings
    store/                 StoreLinks (deep links / hand-offs)
    image/                 Coil ImageLoader factory
  di/                      hand-written AppContainer + ViewModelFactories
  ui/
    components/ deals/ search/ wishlist/ settings/ detail/ navigation/ theme/
```

Dependencies are wired by hand in `AppContainer` (the graph is small enough that a DI framework
would add more than it removes) and exposed through `ViewModelFactories` using
`viewModelFactory { initializer { … } }`.

State lives in `StateFlow` inside each screen's `ViewModel`. The ViewModel survives configuration
changes; the repository layer is the only thing that touches the network.

Two HTTP clients are derived from one base, sharing the connection pool and cookie jar but not their
dispatchers or timeouts — see "Artwork may not starve the content" below.

---

## Features

* **Animated first-run tour** — five pages over a pager, with `Next`/`Previous`, dots that track the
  real scroll offset, and a staggered entrance. It returns after *Delete all app data*, because
  "seen the intro" is a stored preference like any other.
* **Delete all app data** in Settings → Storage: wishlist, recent searches, cached pages, recorded
  price history and every setting, behind a confirmation that says what it cannot undo.
* **Motion** for windows, sheets, list items and presses, all defined once in `ui/theme/Motion.kt`.
* **Export logs** in its own **Diagnostics** card in Settings: the last few hundred log lines plus the OS version, screen
  size and free memory, shared through a `FileProvider`. An uncaught-exception handler records a crash
  into that buffer *before* the process dies, which is the one moment `adb logcat` would have been
  needed and no longer had.

---

## Tests

`./gradlew :app:testDebugUnitTest` — **334 tests, 26 suites, all passing.**

| Suite | Tests | Covers |
|---|---|---|
| `TitleMatcherTest` | 22 | The reported bug: structural accept/reject, edit distance only for ranking |
| `GameDetailRepositoryMediaTest` | 13 | Media ownership: `elden ring never shows metal eden screenshots`, concurrent loads, source filtering |
| `GameIdentityTest` | 17 | Identity parsing from URLs and routes; refusing to invent an identity |
| `TrailerTest` | 9 | Playable vs hand-off; the query-string case; never advertising a control that cannot work |
| `SteamServiceParsingTest` | 30 | appdetails + store-page parsing, wrong-app rejection, requirements cleanup, HLS trailers, age gate |
| `GogParsingTest` | 24 | Catalogue/search parsing, slugs, price blocks, gallery, requirements |
| `DealRepositoryTest` | 20 | Merge, dedupe by deal id, per-store failure warnings, empty feed |
| `SearchRepositoryTest` | 19 | Cross-store merge, ranking, best-price pick, failure isolation and reporting |
| `HttpClientTest` | 14 | Real sockets via MockWebServer: short bodies, the size cap, retries, 429, HTTP errors |
| `StoreLinksTest` | 15 | Deep-link routing, the browser fallback order, including the no-store case |
| `DealFilterTest` | 21 | Every filter and sort rule, wishlist ordering, edge inputs |
| `CacheIsolationTest` | 19 | Cache keys cannot collide across games/stores; TTL and invalidation |
| `WishlistKeyTest` | 18 | Exact store-id keys, normalised-title keys, the distinctive-title floor |
| `WishlistToggleDecisionTest` | 6 | Starring a second game must not delete the first; cross-store toggling; short titles never match |
| `RegexBraceSafetyTest` | 3 | No regex literal in `src/main` uses an unescaped brace (ICU rejects what the JVM accepts); trailer fields in any order |
| `BestLiveDealTest` | 6 | A wishlist row quotes the store it saved from; another store only when that one is empty |
| `PriceHistoryCatalogTest` | 9 | A database is only offered for a game listed on a store it covers; URL shapes |
| `GameDetailContentTest` | 8 | Telling "we learned something" from "nothing came back", which is what the empty page says |
| `PriceHistorySummaryTest` | 7 | The empty-list case that killed every DLC is a named, tested branch |
| `WishlistTimestampTest` | 4 | Epoch millis must not be read back as an `Int` |
| StableItemKeyTest | 5 | Lazy-list keys are unique, which is what keeps Compose from throwing mid-layout |
| SnapshotRegionTest | 6 | Snapshots cannot cross regions; clear really deletes them |
| PriceCurrencyTest | 9 | A price is converted for the user's currency, and marked as an estimate |
| SavedFirstRankTest | 10 | Saving a search result moves it to the top, cross-store too |
| FxRatesTest | 10 | Rates are cached, fail soft, and cross pairs go through the base |
| BestOfferCurrencyTest | 7 | The cheapest offer is picked after converting, not before |

Run a single suite:

```bash
./gradlew :app:testDebugUnitTest --tests '*GameDetailRepositoryMediaTest*'
```

---

## Bugs fixed

### In the PC app (`../WindowsVersion`)

1. **Fuzzy title matching adopted another game's store id** — the cause of the reported wrong
   screenshots/trailers. `TitleMatcher.Score("Elden Ring", "Metal Eden") = 0.5556` cleared the 0.5
   accept threshold, so the app fetched Metal Eden's Steam data while the header kept saying
   "Elden Ring". Matching is now structural, and edit distance is used only to rank search results.
2. **Media was unowned.** Screenshots and trailers carried no identity, so anything merged into a
   detail page was shown. Media now carries a mandatory `owner: GameIdentity` and the repository
   filters by set membership.
3. **Wishlist used prefix matching** — `final fantasy x` lit up `final fantasy xv`. Now equality
   only, over a normalised title key, with a length floor for the cross-store fallback.
4. **Feed de-duplication used store + title + price**, which merges genuinely different editions.
   Now keyed on the store's own deal id.
5. **Prices were formatted with the device locale** while the symbol was hard-coded `$`, producing
   `$9,99`. Prices now format with `Locale.US` grouping and the configured currency symbol.
6. **Store failures were silent** — a failed store simply vanished from the feed. Failures are now
   surfaced as dismissible warnings, on the feed *and* in search.

### In this Android port, found by running it

7. **Every request failed.** The response size cap used
   `BufferedSource.readUtf8(MAX_RESPONSE_BYTES)`, which demands *exactly* that many bytes and throws
   `EOFException` on every body shorter than the cap. No store page ever loaded.
8. **GOG thumbnails timed out.** GOG publishes no small variant of a deal cover — a 1600×740 PNG of
   0.5–1.7 MB per row — and the store client's 20 s read timeout cut those transfers off mid-flight.
   Artwork now has its own client with image-appropriate timeouts.
9. **Artwork starved the content.** Artwork and store calls shared one OkHttp dispatcher, whose
   four-connections-per-host budget was entirely consumed by one screen of GOG covers — long enough
   that a search typed right after opening the app came back empty. Artwork now has its own
   dispatcher; the connection pool is still shared.
10. **Search reported a crashed store as "nothing found".** Every store call was wrapped in
    `runCatching { }.getOrNull()`, so a thrown exception and an empty result were indistinguishable.
    Failures are now collected and shown.
11. **`GameIdentity.parse("steam")` returned an identity with the id `"steam"`** — a store name with
    no separator parsed as a valid id, producing a detail page for a game that does not exist.
12. **The `discounthunter://` deep link read only the URL host**, so `discounthunter://steam/1245620`
    resolved to the id `"steam"` and rendered an empty shell.
13. **Tapping a trailer's hand-off button crashed the app**: `requireNotNull(store)` on a hand-off
    that legitimately has no store.
14. **Steam's HLS trailers were treated as unplayable.** Steam appends `?t=<epoch>` to every media
    URL, so `endsWith(".m3u8")` said "no" for exactly the streams ExoPlayer plays. The Steam parser
    also ignored `hls_h264` entirely, which the PC app did too.
15. **`java.time` with `minSdk 24` and no desugaring** — compiles clean, crashes on Android 7.
16. **The deal count was printed twice** (app-bar subtitle *and* the pinned bar). The pinned bar now
    reports how the list is arranged, which is information the subtitle cannot carry.
17. **A band of dead space above every page title.** The outer `Scaffold` padded its content by the
    status bar, and each screen's `TopAppBar` then applied the same inset again — 128 px on the test
    device, exactly one status bar of nothing. The outer scaffold no longer provides insets.
18. **"Get deal" refused to do anything without a store app.** The hand-off added
    `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` to *every* intent, which on an ordinary `https://` URL
    excludes the browser from resolution — so the very fallback that was supposed to save it could
    never succeed. The flag now applies only to store deep links, and the web page is always offered
    as a second attempt.
19. **Deep links were ignored while the app was already open.** `launchMode = "singleTask"` delivers
    a second link via `onNewIntent`, which was not handled, so tapping a Steam URL with the app
    running appeared to do nothing.
20. **The app crashed on every GOG detail page** — `ExceptionInInitializerError`, a *static* failure
    from `GogPageParser`'s trailer regex, whose trailing `}` was unescaped. The JVM's `java.util.regex`
    accepts a lone `}`; Android's delegates to ICU, which rejects it. Tests passed and the build was
    clean, because nothing but a device could see it. Steam pages hit it too, since Steam titles sold
    on GOG are enriched through the same parser. `RegexBraceSafetyTest` now scans every regex literal
    in `src/main` for an unescaped brace, and the mega-regex was replaced by per-field patterns that
    also tolerate any key order.
21. **The wishlist would not hold more than one game.** `toggle` chose the entry to delete with
    `matches(it.key, it.titleKey)`, but `matches` asks whether a key is *in the wishlist index*, which
    every saved entry's own key trivially is — so the first saved game was always the one deleted, and
    adding a second removed the first. Selection now uses a separate `representsSame` predicate.
22. **The player's loading spinner stayed up while the video played.** `ExoPlayer.playbackState` is a
    plain `Int`, not Compose state, so `if (player.isBuffering)` captured the flag once at composition
    and never updated. `rememberBuffering` mirrors it into a `mutableStateOf` via a `Player.Listener`.

23. **The filter sheet hid its own text field.** "Filter by title" is the last control in the sheet,
    so the keyboard covered it with no way to scroll back. The sheet body is now scrollable and
    honours the IME inset.
24. **Wishlist rows contradicted themselves.** *Best: FREE · Epic* appeared beside a **GOG** badge
    because the ranking considered every store's offer, free first, while the badge named the store
    the game was saved from. The saved store now wins outright; another store is used only when it
    has nothing live, and then the badge follows. The store name after *Best:* was removed as a
    duplicate of the badge, *on sale now* was capitalised, and the deal button moved into a
    right-hand column under the star to match the Deals tab.
25. **"Where to buy" was indented**, because `StoreActions` padded its column and `SectionHeader`
    padded itself. The same section could also list one store twice: the native branch and the
    cross-store enrichment each add an offer. Offers are now grouped by store, cheapest wins.
26. **The detail page came up empty whenever a store had no record of the game.** The cross-store
    fallbacks were guarded by a set pre-seeded with the identity the user opened, so they never ran
    for the store the page was opened from — exactly when they are needed. Stores that *answered*
    are now tracked separately, and a game no store recognises says so explicitly.27. **Opening a DLC crashed the app on scroll** (`NoSuchElementException: List is empty`). The
    price-history box read `points.last()` *before* its `when` chose a branch. An empty list is the
    normal case — only games that appear in the daily deal snapshots have recorded prices — so every
    DLC and soundtrack died. The branch choice is now a total function over the list, tested.
28. **The search star did not fill until you left the tab.** `SearchUiState` did not depend on the
    wishlist, so tapping a star left the state unchanged and nothing recomposed. `DealsUiState`
    already did this correctly; the two had drifted.
29. **"Added today" never appeared and the wishlist stopped sorting by age.** `addedAt` is epoch
    millis (~1.7e12) but was read back with an `Int`-only helper, so every restored entry silently
    became `0` — and the DataStore collector republished that over the correct in-memory value.
30. **Duplicate list keys could crash a screen.** Compose throws `IllegalArgumentException: Key …
    already used` during layout when two lazy items collide, inside composition and so outside every
    `try`/`catch`. Epic's feed really does return the same product twice; the service now
    de-duplicates by slug, the future-day giveaway path now runs the same `dedupe` as the main feed,
    and search results are de-duplicated on the key the list uses.
31. **Every page was parsed on the main thread.** `HttpClient` moved only the socket read off the main
    thread; all the `Json.parse` and multi-megabyte regex parsing that followed ran on `Main`. Each
    service now dispatches its parsing to `IO`.
32. **Two races.** `WishlistRepository.rebuild` published its list outside the lock that guards its
    index; `DealsViewModel` used `runCatching` around a suspending call, which swallows
    `CancellationException` and defeats the "superseded loads unwind" invariant.
33. **The app could rotate to landscape** despite being a single scrolling column. Now
    `screenOrientation="portrait"`.
34. **Motion was tuned per screen** — three springs for the same beat, two easings for the same fade.
    All of it now comes from one `Motion` object: colour cross-fades, screens rise, anything the
    user touches springs and overshoots.35. **A removed wishlist row stayed on screen forever.** The row was filtered out of the list *and*
    wrapped in the "poof" animation, so it left composition immediately — the hard cut the animation
    existed to prevent — and the animation's completion callback never fired, leaving the entry in
    the store with the count stuck. The row now stays in the list until the poof finishes, and the
    animation is what tells the repository it may go.
36. **Wrapped store badges touched the row above.** `FlowRow` puts no gap between lines, so a store
    badge followed by `ON SALE` on a second line sat flush against it.
37. **Wishlisting re-animated the whole feed on every tap.** Every sort order puts wishlisted deals
    first, so one star tap reorders ~90 grid rows; with `animateItem()` on each, repeatedly
    interrupting those placement animations is the shape of input that turns one into a crash. The
    feed no longer animates placement. The wishlist still does, because there the reorder is the
    point.
38. **Changing currency did not change prices.** The feed never reloaded when the Steam region
    changed, and day snapshots were not region-scoped, so a past day served the previous currency's
    figures. Both fixed; Steam now genuinely re-quotes (`$0.49` becomes `€0.54` for DE).
39. **The app relabelled prices it had not converted.** GOG's catalogue ignores a `currency`
    parameter, so `$0.49` was displayed as `€0.49` — a different price, not a converted one. Prices
    now carry the currency the store actually quoted and are labelled with it (`$0.49 USD`).
40. **"Delete all app data" left the snapshots behind.** `clear()` walked one flat directory level,
    and `File.delete` on a non-empty directory fails silently on Android. `prune` and `sizeBytes`
    were wrong for the same reason and under-reported the cache size.
41. **The detail page showed three prices for one game** — Steam's in the header, the cheapest
    store's in "Where to buy", and that same one again in the wishlist. The headline now leads with
    the offers table, which is the page's single source of truth.
42. **Search results never moved when a game was wishlisted.** Star a DLC at the bottom of twenty
    results and the screen did not change at all — no rise, no feedback. The row already had
    `animateItem()` and had nothing to animate because the order never changed, while the deals
    feed had floated wishlisted entries first all along. Search now leads with saved games,
    newest first. Safe here and not safe on the feed is a scale difference, not a taste one: 25
    rows versus ninety (see bug 37).
    Writing the test caught a second defect: the timestamp index was keyed only on the exact store
    key, so a game saved on GOG matched a Steam row by title, rose to the top, found no timestamp
    under its own key and fell straight back — the cross-store case was the one that did not work.

43. **The currency setting did nothing for a third of the app.** GOG publishes in dollars whatever
    region is asked for - `currency`, `currency_code`, `userSelectedCurrency` and `locale`+`country`
    were each tried and all still answer `"currency":"USD"`. An earlier fix relabelled dollars with a
    euro sign (a false price); the fix after that labelled them `$0.49 USD` (honest, but the setting
    changed nothing). The app now **converts**, using the ECB's daily reference rates via
    `frankfurter.app`, and marks the result with `~` and a note naming the rate's date.
44. **"Best:" picked a different winner on every screen.** `GameDetail.bestOffer` ranked offers by raw
    `price`, which is meaningless when a Steam row is in pounds and a GOG row is in dollars -
    4.99 pounds was being called cheaper than 5.50 dollars. `StoreOffer` had no currency field at all,
    so it could not have been fixed without one. Both sides are now converted before comparing.
45. **The offers table passed the target currency where the source belonged,** so a GOG row printed as
    `£0.49` with no conversion behind it - the original false price, wearing the fix's clothes.
    `DealsUiState.fxRates` and the wishlist factory were also declared-but-unassigned, leaving rates
    null. A nullable injected dependency is a silent failure mode: "not loaded yet" and "never wired
    up" look identical to the type system.
46. **Removing the deals animation was the wrong fix.** It was reasoning, not a reproduction - I never
    reproduced the crash the change was meant to fix. Restored, and the genuinely fragile part
    hardened instead: the star's pop was keyed on `isSaved`, so every tap cancelled a running spring
    and queued another. It is now keyed on a tick that only advances when the pop should play.
    Verified with ~150 rapid toggles, including 60 at 25 ms intervals on one row.
43. **The background check never ran at all.** `WishlistRepository.loadOnce()` awaited a DataStore
    flow with `collect`, which never returns — the flow stays open for the life of the process. The
    worker started, blocked on the wishlist load, and hung with no error and no crash report. It
    now reads the current value with `first()`.
44. **Two scheduling policies, both wrong.** `REPLACE` cancelled the in-flight check, and because
    the alarm and the periodic worker can fire together that became a cancellation storm where no
    check ever completed. `KEEP` fixed that but let a work stuck in a queued state block every
    later request under the same name, so the checks stopped silently. The request is now simply
    enqueued; overlapping runs are harmless because a notification posted under the same tag and id
    replaces itself.
45. **A notification's URI arrived with `data == null`.** The intent that was built was correct and
    the intent that arrived had no data, so tapping a notification opened the app and went
    nowhere. The identity now travels in intent extras, which are copied verbatim.
46. **A free game was announced twice** — as "is free" and as "100% off". A free deal is now
    skipped by the sale path, and its record cleared so a later paid discount is still announced.
47. **A notification's deep link showed the wrong price.** Without a title the detail page looks a
    GOG game up with a catalogue search, which returns unrelated products for anything but a
    distinctive name. The title now travels with the link, and the page shows the same price the
    notification did.
48. **The release build crashed on launch and the debug build did not.** WorkManager keeps its
    schedule in a Room database, and Room instantiates the generated implementation reflectively —
    a call R8 cannot see, so R8 removed it. Every debug run passed; the crash existed only in the
    build that would have been shipped. Fixed with two keep rules, and the release APK is now
    installed and launched on a device before the work is called done.
Full write-up with the reasoning in [DEV_NOTES.md](DEV_NOTES.md).


---

## Project layout

```
build.gradle.kts           root build (plugins only, versions from libs.versions.toml)
settings.gradle.kts
gradle/libs.versions.toml  version catalog
gradle/wrapper/            Gradle 9.3.1
local.properties           sdk.dir (machine-specific, not committed)
app/
  build.gradle.kts
  proguard-rules.pro
  src/main/AndroidManifest.xml
  src/main/java/…          app code (see Architecture)
  src/main/res/            themes, strings, colours, adaptive + legacy launcher icons
  src/test/java/…          unit tests
```


