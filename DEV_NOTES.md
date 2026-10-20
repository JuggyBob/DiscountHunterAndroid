# Development notes

Why this port looks the way it does. The desktop app in `../WindowsVersion` was read as a
**behavioural specification** — what it should show, from where, in what order — and not as code to
translate. Its structure (WPF `ViewModel`s, desktop deep-link launching, a WebView2 trailer player,
a JSON settings file) has no Android equivalent and was not carried over.

---

## 1. The reported bug: game A showing game B's screenshots

### What the desktop app did

`DealService.FetchDetailAsync` accepted a Steam app id by *fuzzy title match*:

```csharp
// WindowsVersion/src/DiscountHunter/Services/DealService.cs
var best = hits.Select(h => (Hit: h, Score: TitleMatcher.Score(title, h.Name)))
               .OrderByDescending(x => x.Score).First();
return best.Score >= 0.5 ? best.Hit : null;      // <-- the gate
```

`TitleMatcher.Score` is `difflib.SequenceMatcher(...).ratio()` — a length-normalised
**edit distance**. It is a good *ranking* measure and a terrible *identity* measure. Reproduced with
Python's `difflib`, the same algorithm:

```
>>> difflib.SequenceMatcher(None, "eldenring", "metaleden").ratio()
0.5555555555555556
```

`0.5556 >= 0.5`, so for a game whose Steam search failed, the app adopted **Metal Eden's** Steam app
id and displayed its screenshots, its trailers, its requirements and its price — while the page
header kept showing the title the user tapped, because `DetailViewModel` never refreshed it after
the identity changed. That is the whole reported symptom.

`Score` also has an earlier branch that is worse: if one normalised title *contains* the other it
returns `0.65 + 0.3 * ratio`, so `"Portal"` vs `"Portal 2"` scores 0.81 — comfortably "accepted".

### What this port does instead

Three separate mechanisms, so that no single mistake can reproduce the failure:

**a. Matching is structural, not fuzzy.** `core/text/TitleMatcher.kt` decides *identity* with word
structure only:

* identical after normalisation → accept;
* identical *word sets* (`Portal 2` vs `2 Portal`) → accept;
* one title's words are a **strict** subset of the other's **and** the smaller has at least two
  significant tokens → accept (`The Witcher 3` vs `The Witcher 3: Wild Hunt`);
* one is a word-boundary prefix of the other and the extra words look like a subtitle
  (`Dead Space` vs `Dead Space 2`) → accept.

Anything else is rejected. There is deliberately **no** threshold to tune and no edit distance in
the accept path — `similarity()` still exists, but it is used *only* to order search results, where
"roughly as relevant as" is all that is being claimed.

**b. Media is owned, and ownership is enforced structurally.** `Screenshot` and `Trailer` both carry
a mandatory `owner: GameIdentity` — the `(store, id)` pair the storefront itself publishes.
`GameDetailRepository` maintains `acceptedIdentities` for the page it is building, and an identity
enters that set in exactly two ways:

* **natively** — the user opened the game *on that store*, so the store's own id is authoritative and
  no title comparison happens at all; or
* **by proof** — a store was searched by title and a hit was accepted only after
  `TitleMatcher.isSameGame` confirmed it.

Everything is then filtered by `it.owner in accepted`. A source that failed to be proven is not even
consulted. "Game A displays game B's screenshots" is therefore not merely unlikely, it is
unrepresentable: there is no code path that can attach media without an owner.

**c. Store responses are checked against the request.** `SteamService.resolveDataBlock` accepts a
`appdetails` payload only when the response key matches the requested app id, or when the payload
itself reports that `steam_appid`. Steam occasionally answers under a different key; the desktop app
took "the first successful entry", which is another way to attach another game's data to this one.

### Making it testable

`data/source/StoreSources.kt` defines `SteamSource` / `GogSource` / `EpicSource`, and the
repositories depend on those rather than on the concrete services. That exists for one reason: a
fake can be told to return *the wrong game* from Steam's search, which is precisely the situation
that produced the bug and the one that cannot be reproduced on demand against the live store.

`GameDetailRepositoryMediaTest` does exactly that, and includes
`elden ring never shows metal eden screenshots` plus a concurrency test that opens two games at once
and asserts neither page sees the other's media.

---

## 2. Navigation carries identity, not titles

The detail route is `detail/{store}/{id}?title={title}&thumb={thumb}&origin={origin}`. The store and
id round-trip; the title rides along only as an instant placeholder for the first frame.

Two consequences: a rotation or a process death restores the same game, and nothing on the screen is
*keyed* on a user-visible string. The desktop build's failure to refresh its title after an identity
change is a class of bug this makes impossible to write.

`GameIdentity.parse` refuses a value without a `:` separator. It used to accept one — `"steam"`
parsed as store `STEAM` with the id `"steam"`, and the page dutifully loaded a game that does not
exist.

---

## 3. Bugs found in the desktop app

| # | Defect | Consequence | Fix here |
|---|---|---|---|
| 1 | Fuzzy title match accepted at 0.5 edit distance | Wrong game's media, price and requirements | Structural matching; owned media (above) |
| 2 | Media carried no identity | Anything merged into a page was shown | `owner: GameIdentity` + set-membership filter |
| 3 | Wishlist matched on prefix (`TitlesMatch`) | `final fantasy x` lit up `final fantasy xv` | Equality over a normalised title key, with a 12-character floor for the cross-store fallback |
| 4 | Feed dedupe key was `(store, title, price)` | Merged distinct editions; missed real duplicates | Dedupe on the store-native deal id |
| 5 | `price.ToString("N2", CultureInfo.CurrentCulture)` with a hard-coded symbol | `$9,99` on a European device | `DecimalFormatSymbols(Locale.US)` grouping plus the configured symbol |
| 6 | Store failures swallowed | Feed silently looked sparser with no explanation | Per-store warnings on the feed; collected failures in search |
| 7 | `SearchAsync` merged by normalised title | Two different games sharing a title collapsed with mixed prices | Merge only on proven identity |
| 8 | Steam `appdetails` accepted under any key | Another game's payload could be adopted | Key/`steam_appid` must match the request |
| 9 | Store page screenshots accepted from any folder | A gallery folder belonging to another app | The folder name must contain the requested app id |
| 10 | Detail VM never refreshed its title after an identity change | Header said one game, page showed another | The route is keyed on identity; the header follows the loaded detail |

The wishlist fix (#3) is the same *family* of bug as #1: a forgiving string comparison standing in
for an identity. Worth naming because the symptom ("my wishlist lights up for the wrong game") is
easy to write off as a data problem.

---

## 4. Bugs found by running this port on a device

Unit tests cannot see these. Each was found by installing the APK and using it, and each is now
covered by a test.

### 4.1 Every request failed — `readUtf8(n)`

`HttpClient.readResult` capped responses with okio:

```kotlin
source.request(MAX_RESPONSE_BYTES + 1)
val text = source.readUtf8(MAX_RESPONSE_BYTES)     // demands EXACTLY n bytes
```

`BufferedSource.readUtf8(byteCount)` is `require`-based: it throws `EOFException` on any body
shorter than the cap. With a 6 MB cap, that is *every* response. The feed, the search and every detail
page reported a network error and nothing loaded.

```kotlin
// Now: the size is checked from Content-Length, and the body is read with the cap as a bound
// rather than a demand.
val declared = response.body?.contentLength() ?: -1L
if (declared > MAX_RESPONSE_BYTES) return Outcome.Failure(MALFORMED, "…too large…")
val text = response.peekBody(MAX_RESPONSE_BYTES + 1).string()
```

`HttpClientTest` runs these against a real socket (`MockWebServer`) because that is the only way to
reproduce the class of mistake.

### 4.2 Artwork may not starve the content

Two separate symptoms with one root cause — artwork and store traffic sharing one `OkHttpClient`.

* **Broken tiles.** GOG publishes no small variant of a deal's cover. `coverHorizontal` is a
  1600×740 PNG of 0.5–1.7 MB per row (verified against the live catalogue), and the store client's
  20 s read timeout cut those transfers off mid-flight, so perfectly valid URLs rendered as broken
  tiles. (`coverVertical` is only 60–110 KB but is 342×482 portrait, which crops badly in a wide
  row, so it is not used for the list.)
* **Empty searches.** OkHttp's default budget is four concurrent requests per host. Almost every GOG
  thumbnail comes from `images.gog-statics.com`, so one screen of deals filled the budget with
  multi-megabyte downloads and the *store* requests queued behind them — long enough that a search
  typed immediately after opening the app came back with nothing.

`AppContainer` now builds a second client from the same base: same connection pool, same cookie jar,
but its own dispatcher and image-appropriate timeouts.

```kotlin
val imageClient: OkHttpClient = httpClient.newBuilder()
    .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 8 })
    .connectTimeout(15, SECONDS).readTimeout(60, SECONDS).callTimeout(Duration.ZERO)
    .build()
```

### 4.3 Search reported a crashed store as "nothing found"

```kotlin
val steamDeferred = async { runCatching { steam.search(term, country) }.getOrNull() }
```

`runCatching` catches `Throwable`, so a store that threw and a store with no results were
indistinguishable — and the user was told to check their spelling. `SearchRepository.search` now
returns `SearchResponse(results, failures)`, rethrows `CancellationException`, and the view model
shows *why* the page is short.

### 4.4 A hand-off with no store crashed the app

```kotlin
val deepLink = if (preferStoreApp) deepLinkFor(requireNotNull(store), url) else null
```

A trailer's "open externally" control hands over a URL with **no** store, so tapping it threw
`IllegalArgumentException`. The decision is now a pure function (`StoreLinks.resolveHandOff`) that
takes the store as nullable and is unit-tested.

### 4.5 Steam's HLS trailers were treated as unplayable

Steam has stopped publishing progressive MP4 for many titles and ships an HLS master playlist under
`hls_h264` instead. Two separate defects:

* the parser only read `mp4`, so those trailers became "watch on the store page" links;
* even once parsed, `canPlayInApp` used `endsWith(".m3u8")`, and Steam appends a cache-busting
  `?t=<epoch>` to every media URL — so the URLs ExoPlayer plays perfectly well were all judged
  unplayable.

`TrailerKind.ADAPTIVE_STREAM` had existed in the model since the start; nothing produced it. Now the
API parser emits it, and the extension test ignores the query and fragment. Playback is confirmed
working on-device via ExoPlayer/Media3.

### 4.6 The custom deep link read the wrong half of the URL

`discounthunter://steam/1245620` has host `steam` and path `/1245620`. The handler read the host, so
it resolved to the id `"steam"` and rendered an empty shell.

### 4.7 `java.time` against `minSdk 24`

The app uses `java.time` throughout and declares `minSdk = 24`. Those types only exist from API 26.
Core library desugaring is now enabled; without it the app compiles clean and crashes on Android 7.
Lint is configured with `abortOnError = true` and fails the build on this, which is how it was
caught.

### 4.8 The deal count was printed twice

The app-bar subtitle and the pinned bar both rendered `92 deals · free games on top`. The pinned bar
now reports how the list is *arranged* (`Sorted by biggest discount`, plus the active filter), which
is information the subtitle cannot carry — and the app-bar line is the one that scrolls away.

### 4.9 A band of dead space above every title

Measured on the Pixel 6 emulator (1080×2400, 420 dpi), the status bar inset is **128 px**:

```
dumpsys window | grep type=statusBars
  InsetsSource id=62f20000 type=statusBars frame=[0,0][1080,128]
```

The app's `Discount Hunter` title sat at y = 293 px. With a 64 dp (168 px) app bar whose two-line
title block is centred, the correct position is y ≈ 128 + 10 dp = 154 px. The extra ~128 px was the
status bar counted **twice**: the outer `Scaffold` in `MainActivity` padded its content by
`contentWindowInsets` (default `systemBars`), and then each screen's own `TopAppBar` applied the same
inset to itself.

```kotlin
// The outer scaffold exists only to host the bottom bar and the snackbar; the screens inside
// apply their own insets.
contentWindowInsets = WindowInsets(0, 0, 0, 0),
```

The bottom bar keeps its own handling, because `NavigationBar` applies the navigation-bar inset
itself. After the fix the title measures y = 165 px — the 128 px of dead space is gone.

### 4.10 "Get deal" refused to do anything without a store app

Every hand-off was launched with:

```kotlin
addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
```

That flag is meant to stop a browser swallowing a `steam://` deep link. Applied to the **`https://`
fallback** it does the opposite of what is wanted: it *excludes* browsers from resolution, so the
hand-off threw `ActivityNotFoundException` and reported a failure — on precisely the devices with no
store app, which is where the fallback exists to help. The emulator has no Steam or Epic launcher, so
this was the common case, not an edge case.

Two changes:

* the flag is now passed per attempt and set only for store-app deep links;
* `resolveHandOff` became `handOffAttempts`, returning an ordered *list*. A store app that refuses the
  link no longer ends the attempt — it hands over to the browser.

```kotlin
for (attempt in attempts) {
    if (launch(attempt.url.toUri(), requireNonBrowser = attempt.viaStoreApp)) {
        return LinkResult.OpenedIn(attempt.viaStoreApp, attempt.storeName)
    }
}
```

Verified on-device: tapping **Get it free** on an Epic title now launches the browser.

### 4.11 Deep links were ignored while the app was already open

`launchMode = "singleTask"` means a second link is delivered to `onNewIntent`, not `onCreate`. Only
`onCreate` read `intent?.data`, so tapping a Steam URL with the app already running did nothing.

The link now lives in a `MutableStateFlow` that `onNewIntent` writes to, and the navigation effect is
keyed on the link's **value** — keying it on the `StateFlow` instance was my first attempt and was
subtly wrong, because `LaunchedEffect` compares keys with `equals` and a `StateFlow` equals itself
forever, so the effect ran exactly once and dropped every link after the first.

### 4.12 The player took over the screen

Tapping a trailer replaced the whole page with a black full-screen `ExoPlayer` view, which hides a
page the user is still reading and discards their scroll position. Playback now starts **inline**, in
a card in the page; full screen is an explicit button, and the overlay *glides* up on a spring rather
than cutting.

Two details worth recording, because both were visible only on the device:

* the inline card must **stay composed** while the overlay animates in. Hiding it the instant
  `isFullScreen` flips left a frame with neither visible — a flash on every expand. It now stays, and
  the overlay simply covers it.
* one `ExoPlayer` is created by `rememberTrailerPlayer` and shared by the card and the overlay, so
  expanding never re-buffers or loses the playhead.

The screenshot viewer was moved off `Dialog` for the same reason: a `Dialog` animates its own window
and ignores the content's enter transition, so the slide-and-scale had to become a plain overlay
(with `BackHandler` for dismissal).

### 4.13 The app crashed the moment a GOG detail page opened

`ExceptionInInitializerError` from `GogPageParser`'s class initialiser, i.e. a *static* failure: once
the class failed to initialise the whole process died, and it did so on the **first** GOG page.

The offending literal was the trailer-entry regex:

```kotlin
private val VIDEO_ENTRY = Regex("""\{\s*"provider"\s*:\s*"(.*?)"\s*,\s*"url"…""")
```

Its final `}` was unescaped. `java.util.regex` on the JVM accepts a lone `}`, so **every test passed**
and the app still compiled; Android's `java.util.regex` delegates to ICU, which rejects it with
`PatternSyntaxException: Syntax error in regexp pattern near index 90`.

Two things made the report read as "sometimes Steam crashes too":

* `GameDetailRepository` calls `gog.fetchGameDetails(...)` to enrich a **Steam** game with GOG media
  when the title matches, so any Steam title sold on GOG touched `GogPageParser` as well.
* the crash only happened on a device. No unit test can see it unless it asserts on the *pattern*.

Fixed three ways, because the original regex was wrong in a second, quieter way:

1. `RegexBraceSafetyTest` scans every regex literal under `src/main/java` for an unescaped brace,
   skipping character classes (where `[^{}]` is a legal class of two literal braces) and balanced
   quantifiers. This is the guard that would have caught the bug before the first release.
2. the mega-regex is gone. `provider` / `url` / `imageUrl` are read by three independent regexes, so a
   trailer whose keys are in a different order — or with extra keys between them — is still found.
   `VIDEO_ENTRY` now only has to find the `{…}` object, and it is *string-aware*:
   `\{(?:[^{}"]|"(?:[^"\\]|\\.)*")*\}`. The naive `[^{}]` version cannot work here, because GOG puts
   its `{formatter}` CDN placeholder inside a JSON string.
3. `youtubeId` is extracted from the URL **before** `substringBefore('?')` strips the query, otherwise
   every `watch?v=<id>` trailer lost its id. That was a real second bug the new tests exposed.

### 4.14 The wishlist would not hold more than one game

`WishlistRepository.toggle` picked the entry to delete with

```kotlin
val existing = current.firstOrNull { matches(it.key, it.titleKey) }
```

`matches` answers *"is this game saved?"* — and it answers that by looking the keys up in an index
built from the saved entries. An entry's own key is **by definition** in that index, so the predicate
was true for the first saved entry every time. Adding a second game therefore *removed the first*,
and the wishlist never grew past one. `remove` had the same shape.

The two questions are different and now have different functions: `matches` still answers "is it
saved?" for the UI, while `representsSame` answers "is *this* entry the same game as these keys?" and
drives the selection. The decision is pure, so it is unit-tested without a DataStore-backed
`SettingsRepository`.

### 4.16 The filter sheet put its own text field under the keyboard

"Filter by title" is the last control in the `ModalBottomSheet`, so once the keyboard appeared the
field sat behind it and the sheet offered no way to scroll back. The body is now
`verticalScroll`-able and carries `imePadding()`, so the field and **Show results** both stay above
the keyboard.

### 4.17 The wishlist contradicted itself about price and store

Two separate mistakes, one visible symptom.

`bestLiveDeal` ranked **every** offer for the game, free first. A game saved on GOG at `$0.49` that
happened to be free on Epic that week therefore rendered as *Best: FREE · Epic* — while the badge
beside it still said **GOG**. The row named one store and quoted another, and it disagreed with the
Deals tab, which correctly showed `$0.49`.

Two rules replace it:

* the store the game was **saved from** wins outright, even when another store is cheaper or free,
  so the badge and the price always describe the same shop;
* another store is used only when the saved store has nothing live — the genuinely useful discovery
  case (saved on GOG, free on Epic), and then the badge follows the price.

The store name after *Best:* is gone entirely: the badge already carries it. *On sale now* was
capitalised, and the deal button moved out of the text column into a right-hand column under the
star, so it lines up with the game's data the way it does on the Deals tab.

### 4.18 "Where to buy" was indented, and sometimes listed a store twice

`StoreActions` padded its `Column` by 16dp *and* `SectionHeader` applied its own 16dp, so that one
heading sat 16dp further right than every other section on the page. `OfferRow` now owns the padding.

The duplicates had a real cause. A store can legitimately be added twice: the native branch adds an
offer even when its title probe failed, and cross-store enrichment adds another when the title
matches. A GOG title also picked up its Steam offer *plus* a GOG offer from inside the Steam block.
Offers are now grouped by store and the cheapest row wins, so a shop appears exactly once.

### 4.19 The detail page was blank whenever a store had no record of the game

`accepted` was seeded with the identity the user opened, and the cross-store fallbacks were guarded
by `accepted.none { it.store == … }`. Those two facts together meant the fallback that re-queries a
store by title **never ran for the store the user opened from** — precisely the games whose id no
longer resolves, which is exactly when it is needed.

*Which store answered* and *which identity was opened* are now separate: a `verified` set records
stores that returned content, and the fallbacks follow `verified`. When nothing at all is found the
page says so explicitly instead of rendering a title over an empty body.

### 4.21 Opening any DLC crashed the app when you scrolled

`NoSuchElementException: List is empty`, from `PriceHistorySection`.

The price-history box read its points like this:

```kotlin
val days = (points.last().epochDay - points.first().epochDay).toInt()   // ← runs first
when {
    points.isEmpty() -> Text("No price history recorded yet…")
    points.size == 1 -> Text("One price recorded: …")
    else -> PriceChart(points)
}
```

`points.last()` was evaluated **before** the `when` chose a branch, so an empty list threw. An empty
list is the *normal* case, not an edge case: the box renders whenever the game has a store, and only
games that have ever appeared in the daily deal snapshots have recorded prices. Every DLC,
soundtrack and obscure title therefore had an empty list — which is why this looked like "searching
certain games crashes", and why it only fired once you scrolled far enough for the lazy column to
compose the box.

Fixed by making the branch choice a total function over the list, outside the composable:

```kotlin
fun summarizePriceHistory(points: List<PricePoint>, today: Long): PriceHistorySummary = when {
    points.isEmpty() -> PriceHistorySummary.Empty
    points.size == 1 -> PriceHistorySummary.Single(points.first().price, today - points.first().epochDay)
    else -> PriceHistorySummary.Chart
}
```

An empty case that cannot throw is a named, tested branch rather than an ordering mistake inside a
`when`. `PriceHistorySummaryTest` covers the empty list, one point, two points, same-day points and
a fifty-point list.

### 4.22 The search star did not fill until you left the tab

`SearchViewModel.uiState` was built from the query and the settings. Nothing in it depended on the
wishlist, so tapping a star left the state *equal to what it was before*, and Compose had no reason
to recompose. The filled star only appeared once the user navigated away and back.

`DealsViewModel` already included `wishlistRepository.entries` in its `combine`, which is why the same
gesture worked there — the two screens had drifted apart. The wishlist entries are now part of the
search state too. The star's state is still *asked of* `WishlistRepository.matches` during
composition, so the matching rules stay in one place; the list's only job is to make the state
change identity when the wishlist does.

### 4.23 "Added today" never appeared, and the wishlist stopped sorting by age

`SettingsRepository` wrote `addedAt` as epoch **milliseconds** — about 1.7e12 — and read it back
through a helper that only tried `Int` parsing. `intOrNull` returned null, the elvis substituted `0`,
and every restored entry became `addedAt = 0`.

Two consequences: `WishlistRow.addedLabel` returns `""` for `addedAt <= 0`, so the age label was
permanently blank; and `rebuild` sorts by `addedAt` descending, which degenerated to a no-op. Worse,
the DataStore collector then republished the zeroed entries over the correct in-memory ones, so the
label was wrong even for a game starred seconds earlier. Added a `long()` reader
(`longOrNull` → `toLongOrNull` → `toIntOrNull`, so builds that stored seconds still load).

### 4.24 Duplicate list keys — a crash waiting for a repeated store row

Compose's `LazyColumn`/`LazyVerticalGrid` throw `IllegalArgumentException: Key "…" was already used`
during layout when two items share a key. That happens **inside composition**, so it escapes every
`try`/`catch` in the data layer and kills the process.

Epic's feed genuinely returns the same product more than once — several elements share one
`productSlug` across catalog namespaces. `EpicService` now de-duplicates by slug at the source, and
`DealRepository.giveawaysForDate` was found *not* calling `dedupe` the way `refreshFeed` did, which
left future calendar days exposed. `SearchRepository.merge` gained a `distinctBy` on the same key the
search list uses, since Steam's search can repeat an app id and two giveaways can share a title.

### 4.25 Every page was parsed on the main thread

`HttpClient.execute` moves only the socket read into `withContext(Dispatchers.IO)`; once it returns,
the continuation resumes on the **caller's** dispatcher, which for a ViewModel is `Main`. Every
`Json.parse` of a cached payload and every `GogPageParser.parse` / `SteamPageParser.parse` — several
megabytes of HTML through a dozen regex passes each — therefore ran on the UI thread.

Each service now takes a `CoroutineDispatcher` (defaulting to `Dispatchers.IO`) and wraps its parsing
in `withContext(io)`. The network calls were never the problem; it was everything that happened after
them.

### 4.26 Two smaller races

* `WishlistRepository.rebuild` cleared and refilled the lookup sets under `synchronized(this)` but
  published `_entries.value` **outside** it. A DataStore emission and a `toggle` could interleave
  between the two sections and leave the star disagreeing with the stored list. Both are now inside
  one critical section.
* `DealsViewModel` wrapped the calendar-marker refresh in `runCatching`, which swallows
  `CancellationException` along with real failures — so a superseded load kept running instead of
  unwinding, breaking the invariant that class documents. It now rethrows cancellation and catches
  only genuine errors.

### 4.27 Portrait only

`android:screenOrientation="portrait"` on the single activity. The layout was already a scrolling
column that works at any width, so landscape bought nothing but a cramped bottom bar.

### 4.28 Wishlist removals left the row on screen forever

Worth recording because the bug was self-inflicted by the animation meant to fix the jump. The row
being removed was filtered out of the list **and** wrapped in `PoofOut`:

```kotlin
val rows = state.rows.filterNot { rowKey(it) == exiting }   // takes it out of composition
items(rows) { PoofOut(visible = exiting != rowKey(row)) { … } }   // …so this never runs
```

Both cannot be true. The row was removed from composition immediately — the exact hard cut the
animation existed to avoid — and `PoofOut`'s `onFinished` never fired, so the entry stayed in the
repository forever and the count never changed.

The row now **stays** in the list while `exiting` is set; `PoofOut` plays the poof and its
`onFinished` is what calls the repository. The screen owns *when*, the repository owns *what*. An
earlier attempt also launched the removal from `onRemove`, which reintroduced the jump for the same
reason: the animation had nothing left to animate.

### 4.30 Wishlisting re-animated the whole feed on every tap

Worth recording because it is a bug this port introduced while making things *prettier*. Every sort
order in `DealFilter` puts wishlisted deals first, so a single star tap reorders the entire grid. That
is fine. Adding `Modifier.animateItem()` to those items is not: it asks Compose to play a placement
animation for every one of roughly ninety rows, on every tap, and repeatedly interrupting and
restarting those animations is exactly the shape of input that turns a placement animation into a
crash.

The feed no longer animates item placement. The *wishlist* still does, because there reordering is
the point the animation exists to communicate: a newly starred game rising to the top instead of
appearing there.

### 4.31 Changing currency did not change prices

Two independent causes, both needed.

**The feed never reloaded.** `DealsViewModel` observed the settings flow but only adopted the store
selection from it; nothing noticed that `steamCountry` had changed, so the list kept the deals
fetched for the previous region until the ViewModel was recreated.

**Snapshots were not region-scoped.** Even after reloading, a *past* day is served from
`SnapshotStore`, and those files were named after the day alone — so the US figures captured earlier
in the session came back under a euro sign. Snapshots now live in one folder per region.

### 4.32 The app relabelled prices it had not converted

Worth being precise about, because the first fix made it look fixed when it was not.

With the reload in place, Steam prices genuinely re-quote: `$0.49` becomes `€0.54` for the German
region, which is correct and is the behaviour the setting always implied. GOG does not. Its catalogue
endpoint ignores a `currency` parameter — verified directly, `?currency=EUR` still answers
`"currency":"USD"` — so the same `$0.49` was being printed as `€0.49`. That is not a converted price,
it is a *different* price.

So the rule is now: relabel only where the store re-quoted, and otherwise print the currency the
store actually said, with its code. `Deal.currencyCode` carries it, read from GOG's own
`finalMoney.currency`, and `PriceFormat.priceForCurrency` decides between the two. A GOG deal under a
euro setting reads `$0.49 USD`, which is honest, where before it read `€0.49`, which was not.
Settings says as much rather than implying the whole app converts.

### 4.33 "Delete all app data" left the snapshots behind

`SnapshotStore.clear()` walked one flat directory level and called `File.delete` on each entry.
`File.delete` on a non-empty directory fails silently on Android, so as soon as region folders
existed — i.e. immediately after §4.31 — "delete everything" reported success while a month of
price history stayed on disk. `prune` and `sizeBytes` were wrong for the same reason: neither
counted or visited the new subdirectories, so the Settings cache size under-reported too.
`SnapshotRegionTest` pins all three.
### 4.34 Search results never moved when a game was wishlisted

Star a DLC sitting at the bottom of twenty search results and the screen did not move at all — no
rise, no confirmation, nothing. The row already had `animateItem()` on it and had nothing to
animate, because the order never changed.

The deals feed has floated wishlisted entries to the top in every sort order since it was written,
so the same action visibly did something there. That inconsistency is what made the search page read
as the app having forgotten the tap.

Search results now lead with saved games, newest first, so the row just starred rises to the top.
Unsaved results keep the stores' relevance order — among those, the store's own ranking is still the
best answer available.

`SavedFirstRank` is a pure function with the match rule injected, so it is tested directly rather
than through a repository. Writing that test immediately paid for itself twice over:

  * **A cross-store save did not work.** The timestamp index was keyed only on `entry.key`, but a
    game saved on GOG matches a Steam row by *normalised title*. Such a row was correctly identified
    as saved and correctly hoisted to the top — and then found no timestamp under its own key and
    fell straight back to the bottom. The index is now keyed on both.
  * **A hand-written key in the test matched nothing.** The first draft of these tests asserted
    against literal key strings like `"s1:1"`, which no production key has that shape, so the tests
    passed for reasons unrelated to the code. Every key in them is now produced by the real
    `SearchResult.wishlistKey` / `TitleKey.normalize`.

The scale difference from §4.30 is deliberate and is the reason this is safe here and was not safe
there: search caps at 25 rows and the user is looking at a handful, so the placement animations
involved are finite. The feed reorders ninety rows on every tap, and interrupting *those* repeatedly
is a crash, not a nicety.

### 4.35 The currency setting did nothing for a third of the app

Reported twice, and the first fix made it worse before it made it better.

Round one: selecting EUR re-rendered every price with a euro sign and left the number alone. For
GOG that was not a missing conversion — it was a **false** one. GOG publishes in US dollars
regardless of region. Verified directly: `currency`, `currency_code`, `userSelectedCurrency` and
`locale`+`country` were each tried against `catalog.gog.com` and all four still answered
`"currency":"USD"`; `api.gog.com/products/{id}` carries no price at all. So `$0.49` was being
displayed as `€0.49`.

Round two: fix that by labelling honestly, and prices read `$0.49 USD` everywhere. Honest, and
useless — the user changed a setting and nothing changed, which is arguably a worse bug than the
first. `PriceFormat.priceForCurrency` was deleted for it.

What ships now is conversion. `FxRates` fetches the European Central Bank's daily reference rates
from `frankfurter.app` — a real published source, no key, no account — and prices quoted in a
currency the user did not choose are converted and marked with `~`. Three rules, all tested:

  * Same currency, or the store did not say → print as quoted. Steam and Epic re-quote per region,
    so their numbers already *are* in the user's currency.
  * Different currency, rate available → **convert**, prefixed `~`.
  * Different currency, no rate → the store's own currency and code. A labelled foreign price, never
    an invented one.

A converted price is an estimate and says so, on the price line and in a note under it naming the
date the rate was published. GOG may round its own GBP price differently from `USD x rate`.

### 4.36 "Best:" picked a different winner on every screen

The real cause of three prices for one game, and it was not in any of the three screens.

`GameDetail.bestOffer` ranked offers by their raw `price`. That is only meaningful when every store
quotes the same currency — and one Steam row requested for `cc=GB` arrives in pounds while a GOG row
is always in dollars. So `minBy(price)` compared 4.99 pounds against 5.50 dollars and called the
pounds cheaper. The feed, the wishlist and the detail page each ran this comparison over
overlapping-but-different data, so each named a different "cheapest". `StoreOffer` had no currency
field at all, so the comparison could not even have been fixed without one.

`bestOffer` now takes a target currency and converts both sides before comparing.
`BestOfferCurrencyTest` pins it, including a test asserting the *old* naive comparison gets the
same case wrong, so the reason cannot rot.

### 4.37 Passing the target currency where the source belonged

Worth recording because it looked correct and compiled cleanly. The offers table called

    PriceLine(currencyCode = currencyCode, chosenCode = currencyCode, ...)

— the user's chosen code in both slots. Since they matched, conversion was skipped and a GOG dollar
row was printed as `£0.49` with no `~`: the original false price, back again, wearing the fix's
clothes. Only visible on a real device; the header above it was correct the whole time, which is
what made it look like a rendering bug rather than a wiring one.

Same class of error twice more in the same change: `DealsUiState.fxRates` and
`ViewModelFactories.wishlist` were both declared-but-unassigned, so the feed and the wishlist
silently kept `null` rates and fell through to the labelled fallback. All three were found by
running the app, not by the compiler — `FxRates?` being nullable means "no rates yet" and "rates
never wired up" look identical to the type system. A nullable injected dependency is a silent
failure mode; that is the lesson.

### 4.38 Removing the deals animation was the wrong fix

Last round removed `animateItem()` from the deals grid on the theory that reordering ~90 rows per tap
was a crash risk. That was reasoning, not a reproduction — I never reproduced a crash, and the
theory was never tested. It cost the feature and did not demonstrably fix anything.

Restored, and the actual fragile part hardened instead: `WishlistToggle`'s pop was keyed on
`isSaved`, so every tap cancelled a running spring and started a new one from a fresh `snapTo`.
Rapid tapping queued dozens of springs against one `Animatable`. It is now keyed on a tick that only
advances when the pop should actually play, so repeated taps on an already-correct state do nothing,
and the animation body is wrapped so a cancellation cannot escape as a crash.

Verified with ~150 rapid toggles including 60 at 25 ms intervals on a single row, with and without
scrolling: no crash, and the reorder animation is visibly back.
### 4.39 The background check never ran at all

The feature was built, the schedule armed, the alarm registered with the system — and the check
never completed. The worker started, produced no log line, no notification, and no error.

`WishlistRepository.loadOnce()` looked like this:

```kotlin
suspend fun loadOnce() {
    val snapshot = settings.settings.map { it.wishlist }.distinctUntilChanged()
    var first: List<WishlistEntry>? = null
    snapshot.collect { value -> first = value.map { it.toEntry() } }
    rebuild(first.orEmpty())
}
```

`collect` on a DataStore flow **never returns** — the flow stays open for as long as the process
lives. So a caller that awaited `loadOnce()` waited forever. The background worker did exactly
that: it started, blocked on the wishlist load, and hung. There was no exception and no crash
report, because nothing was wrong: the code was waiting for something that was never going to
happen.

It now reads the current value with `first()`. The function was only ever *used* by tests, where
the flow completed and the bug could not show.

### 4.40 Two scheduling policies, both wrong

The one-shot check is enqueued under a unique name so that overlapping triggers cannot pile up.
Both obvious policies are wrong, and both were wrong in practice:

  * **`REPLACE`** cancels whatever is already running. The alarm and the periodic worker can fire
    within seconds of each other, and that became a cancellation storm: each new request killed the
    one that had just started, so no check ever completed. The log showed
    `Work [...] was cancelled` immediately followed by `Starting work for DealAlertWorker`.
  * **`KEEP`** never cancels, but a work that is stuck in a queued state — waiting for a network
    that is not coming — blocks every later request under the same name. The checks then stop
    happening, silently.

The request is now simply enqueued, with no unique name. Overlapping runs are harmless: the
notification for a game is posted under that game's own tag and id, so a second post *replaces* the
first rather than adding to the shade. The user sees one notification either way. A process-wide
mutex inside the worker serialises the runs so they do not fetch the same feed twice.

### 4.41 A notification's URI arrived with `data == null`

Tapping a notification opened the app and went nowhere.

The intent was built with the game's URI, and the log confirmed the intent that was *built* was
correct:

```
Alerts: tap target for 'Dance of Cards Soundtrack' is
  discounthunter://gog/dance_of_cards_soundtrack?title=Dance%20of%20Cards%20Soundtrack
```

But the intent that *arrived* had no data at all:

```
Main: onNewIntent action=android.intent.action.VIEW extras=[] data=null
```

Making the intent explicit, and dropping `FLAG_ACTIVITY_CLEAR_TOP`, changed nothing — the data was
still null. The fix was to stop using a URI: the identity now travels in **intent extras**, which
are copied verbatim and cannot be dropped the way a URI data can be.

The extras also carry the game's **title**, and that matters for a different reason. Without a
title the detail page has to look the game up by name, and for GOG that means a catalogue *search* —
which returns unrelated products for anything but a distinctive name, so the page showed the wrong
offer, or none. With the title the page resolves correctly and the price on it matches the
notification exactly: `$0.25 (was $4.99, -95%)` in both places.

### 4.42 A free game was announced twice

A wishlisted game that is free was announced by the free-game path *and* as a sale, so it produced
two notifications: "Agent A is free" and "Agent A is 100% off". The second is the less useful of
the two, and the duplicate is the kind of thing that makes people turn a feature off.

A free deal is now skipped by the sale path, and its record is cleared so that if the game later
becomes a *paid* discount, that is announced as a sale.
### 4.43 The release build crashed on launch, and the debug build did not

The most expensive bug in this round, and it was invisible until the *release* APK was installed.

```
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider
Caused by: java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
```

WorkManager keeps its schedule in a Room database, and Room instantiates the generated
implementation reflectively — `WorkDatabase_Impl`, by name, with a no-argument constructor. R8
cannot see that call, so it removed the class.

The debug build was fine, because R8 only runs for release. Every debug test, every emulator run
and every unit test passed; the crash only ever appeared in the one build that would have been
shipped. That is the shape of bug that reaches users and never reaches the person developing it,
and the only defence is to install the release APK before believing anything.

The fix is two keep rules in `proguard-rules.pro`:

```
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }
```
---

## 5. Design decisions worth explaining

**Search results lead with the games you saved.** The deals feed has floated wishlisted entries to
the top in every sort order since it was written, so a saved game already followed the user around
the feed. Search did not, and the asymmetry is what made it read as a bug: star something on the
search page and literally nothing happens, while the same tap on the deals page visibly does.

Saved results lead, newest first, so the row you just starred is the one that moves. Unsaved
results keep the stores' relevance order — among those, the store's own ranking is still the best
answer available. The same rule is deliberately *not* applied to the feed (§4.30): search caps at
25 rows and the user is looking at a handful, so the placement animations are bounded, whereas the
feed reorders ninety grid rows on every tap.
**The background check runs on two mechanisms, because one is never enough.** The single most
common reason an app's notifications "do not always work" is that it relies on one mechanism, and
that mechanism has a hole. `WorkManager` is the officially supported way to do background work and it
survives reboots, but a *periodic* worker is a request rather than a promise: the system batches
and defers it, Doze pushes it out, and several manufacturers' battery managers delay it further. An
alarm is punctual and `setAndAllowWhileIdle` is delivered inside a Doze window, but alarms do not
survive a reboot on their own and an app that is force-stopped loses them. So both are used: the
alarm is the punctual path and the periodic worker is the guaranteed backstop, and both end up
running the same idempotent check.

The exact-alarm permission is asked for, not assumed. `canScheduleExactAlarms()` is checked and the
scheduler falls back to `setAndAllowWhileIdle`, which needs no permission and still works inside
Doze. A less punctual notification is much better than none.

**A notification carries a game's identity in extras, not in a URI.** A URI in a notification's
intent was measured arriving with `data == null` — the intent the activity received was not the one
that had been built — so the notification opened the app without going anywhere. Extras are copied
verbatim and cannot be lost that way. They also carry the title, which the detail page needs: for
GOG, looking a game up by name means a catalogue search, and that returns unrelated products for
anything but a distinctive title, so the page showed the wrong offer or none.

**Only changes are announced, and absence is not evidence.** A check runs on a timer, so the same
deals are seen over and over. What is worth telling someone about is a *change*: a wishlisted game
that has started being discounted, and a game that has become free. A game that is still discounted
next hour is not news. When a wishlisted game is missing from the feed its record is left alone —
the feed holds only the top few dozen discounts per store, so dropping out of it says nothing about
whether the game is still discounted. The record is only cleared when the game is *seen* and is not
discounted, which is the one observation that proves the sale ended.
**The app keeps its own log.** `AppLog` is a bounded ring buffer of the last few hundred state
transitions and caught failures, written to a shareable file from Settings. `adb logcat` is not an
answer for a user without a computer, and by the time someone thinks to report a crash the buffer
that held it has usually rotated away — so the *run-up* to the failure is what a report needs.
Nothing user-identifying goes in, and the log never leaves the device unless the user exports it.
An uncaught-exception handler records the crash into that buffer before the process dies, which is
the one moment `adb logcat` would otherwise be needed and would no longer have.

**The detail page's headline price comes from its own offers table.** It used to lead with Steam's
`price_overview` instead, which made one page show three different prices for one game: Steam's in
the header, the cheapest store's in "Where to buy", and that same cheapest store's again in the
wishlist. The offers table is the page's single source of truth — it is the same data the feed and the
wishlist read — so the headline now leads with that.

**Deleting all app data is a stored preference away from the first run.** "Seen the intro" is just
another key in the same `DataStore` as the wishlist and the settings, so `reset()` takes it too. That
is what makes the Settings confirmation honest: deleting everything really does start from a clean
slate, and the tour slides back up rather than the app appearing out of nowhere.
**All motion comes from one object.** `Motion` (see `ui/theme/Motion.kt`) names the handful of
animations the app actually uses and defines each once: a fade for colour-only changes, a short rise
for screen transitions, a bouncy spring for anything the user touched. Motion tuned per screen drifts
— three springs for the same beat, two easings for the same fade — and a new screen then invents its
own. The rules it encodes are worth stating because they are what make the set feel like one app:
*position and size animate, colour cross-fades*; *travel scales with distance*; *nothing blocks
input*. Two consequences are visible: the four bottom-nav tabs cross-fade rather than slide, because
they are peers and sliding implies a hierarchy that does not exist, while opening a game slides
because that genuinely *is* deeper.
**No DI framework.** The graph is small, entirely singleton-scoped, and every dependency is a
constructor parameter — which is also what makes the repositories testable with hand-written fakes.
Hilt would have added an annotation processor and a layer of indirection without removing any real
complexity.

**`SteamSource` / `GogSource` / `EpicSource` are a seam, not an abstraction for its own sake.** They
exist so the media-ownership guarantee can be tested against a fake that returns the wrong game. The
implementations are one-line delegations.

**Store failures degrade, they do not abort.** Each store is fetched in its own coroutine inside a
`supervisorScope`, so an outage produces a warning and a shorter feed rather than an empty screen.
The desktop app did the same thing but *silently*, which is why its outages were invisible.

**Search is debounced and generation-guarded.** Each keystroke cancels the previous search and bumps
a generation counter; a response may only publish while its generation is current. A slow request for
`elden` can never land under the heading for `elden ring`.

**Item identity is the store-native id, never the list index.** `key = { it.id }` on every list. The
same rule drives Coil's cache keys: a recycled row can only ever show the bitmap belonging to its own
URL.

**Two `OkHttpClient`s, one connection pool.** See §4.2.

**Prices format with `Locale.US` grouping.** The currency *symbol* comes from the user's setting; the
separators do not follow the device locale, because `$1,234.56` is what a shopper expects to read.

---

## 6. What is deliberately not here

* **No store-app deep linking for GOG.** GOG Galaxy publishes no stable URL scheme, so GOG offers
  open in the browser. Stated in Settings rather than hidden.* **No claiming games programmatically.** No store offers a public API for it. For a free title the
  app opens the game's page inside the store app where the user is already signed in, and says so.
* **No MPEG-DASH trailers.** Steam publishes `dash_av1`/`dash_h264`, which need
  `media3-exoplayer-dash`. Rather than ship a play control that fails on some titles, DASH trailers
  are shown as store-page hand-offs. MP4 and HLS cover the common cases.
* **No scraped price history.** The brief was to draw price history from steamdb.info, egdata.app,
  gogdb.org, with isthereanydeal, gg.deals, cheapshark, dekudeals and psprices as fallbacks.
  Measured from a plain HTTP client, and again with a browser user-agent:

  | Database | Answer | Why |
  | --- | --- | --- |
  | steamdb.info | `403` | Cloudflare interstitial |
  | gg.deals | `403` | Cloudflare interstitial |
  | psprices.com | `403` | Cloudflare interstitial |
  | isthereanydeal.com | `403` | needs a paid API key |
  | cheapshark.com/api | `400` | rejects non-browser clients |
  | gogdb.org | `200`, but no price markup | the price table is fetched client-side |
  | egdata.app | root `200`, no documented public history endpoint | |

  Not one of them will serve a history to an app, so a scraper here would show an empty chart on
  every device and break the first time a template changed — while also violating their terms.

  What ships instead is honest about the same point:

  * the chart is drawn from prices **this device actually recorded** — the per-day snapshots the app
    already writes for the calendar. It needs no network, works offline, and gets richer the more
    days you browse;
  * the dropdown beside it offers the databases, **filtered to the stores the game is actually
    listed on** (an Epic-only game is never sent to SteamDB), and each opens that site's own page for
    the game, through the browser hand-off that already works.

  Adding real history later means adding an API key to the build and treating it as a server-side
  feature, not a client-side scrape.* **No offline mode beyond caches.** Snapshots of past days are browsable offline; the feed itself is
  not persisted as a first-class feature, matching the desktop app.

---

## 7. Verification performed

Debug and release APKs built from a clean checkout, installed on an Android 16 / API 36 emulator
(Pixel 6 profile), and driven through:

* feed load with all three stores live (92 deals), Epic giveaways leading, giveaway days marked on
  the day strip;
* future day with nothing scheduled → empty state; store outage → error state with retry;
* scroll through the whole feed with artwork loading under a saturated dispatcher;
* search `elden`, `elden ring` → correct results, per-store prices, Steam app ids;
* **Elden Ring (`steam:1245620`) detail page** — hero, description, 10 screenshots, 8 trailers,
  requirements, genres, categories, publishers — all belonging to Elden Ring;
* inline trailer playback, expand to full screen (spring slide), collapse, back-press to close;
* screenshot viewer: slide-and-scale in, swipe between shots, back to close;
* **Get it free** on an Epic title with no Epic launcher installed → the browser opens;
* wishlist star → list → badge; Settings theme switch; light and dark; portrait and landscape;
* **three different games starred in one pass** and the count read "3 saved · 3 are on sale", then
  survived a force-stop and relaunch (this is the §4.14 regression);
* **GOG detail page** — *The Black Within*, -95% — hero, cross-store "Where to buy" (GOG + Steam),
  price history, genres, publisher, one trailer and 12 screenshots, no crash (this is the §4.13
  regression);
* inline Steam trailer playback (Elden Ring, HLS) with a decoded frame and **no spinner** over it
  (this is the §4.15 regression);
* deep links cold **and warm** (app already running) — both `https://store.steampowered.com/app/1245620/`
  and `discounthunter://steam/1245620`;
* release APK (R8 + resource shrinking) installed and launched, verified signed, `minSdk 24`,
  `targetSdk 36`.

* **filter sheet with the keyboard open** — the title field and *Show results* both stay reachable,
  which was the §4.16 failure;
* wishlist rows re-checked for self-consistency: a GOG game reads **Best: $0.49** with a GOG badge
  and a *Get deal* button (it used to read *Best: FREE* with a GOG badge), an Epic giveaway reads
  **Best: FREE** with an Epic badge and *Claim free*, and no store name is printed twice;
* detail page for *The Black Within*: *Where to buy* flush with every other section heading, exactly
  one row per store, and a price-history box at the foot whose dropdown listed SteamDB, GOG Database,
  PSPrices, CheapShark, IsThereAnyDeal, GG.deals and DeKuDeals — and **not** EGData, because the
  game is not on Epic;
* an Epic-game page offered EGData alongside the Steam databases, matching its own store set.

Every fix in §4 was reproduced on the device before it was changed, and re-verified after.

### Redundant controls removed while polishing

Three places stated the same fact twice, or offered the same action twice:

| Removed | Why |
| --- | --- |
| "Filters" icon in the feed's app bar | A labelled **Filters** chip already sits next to the sort order it belongs with, and lights up when a filter is active. One control, not two. |
| Price line on a free deal card | The card said it was free three times — `FREE` badge, `Free` price, `Get it free` button. Discounted cards keep both prices. |
| Store name after "Best: …" on a wishlist row | The store is already the badge above it. The suffix is now printed **only** when the cheapest deal came from a *different* store, which is the case that carries information. |

Two labels were misleading rather than duplicated, and were reworded: the search empty state repeated
the search field's own placeholder ("Search every store") as its heading, and the settings section
titled **Default stores** actually controlled which stores are queried, with a per-row subtitle
("Opens in your browser") that read like a second setting. It is now **Included stores**, and the
subtitle explains what the switch does.

**Known environment limitation:** Chrome on this emulator image cannot complete its first-run wizard
(it cannot reach Google's servers), so the *page* Chrome would load cannot be shown. The hand-off
itself is verified — the browser activity comes to the foreground, which was impossible before the
flag fix — and the URL is covered by `StoreLinksTest`.
