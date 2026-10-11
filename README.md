# Discount Hunter

**Every game discount from Steam, GOG and Epic in one Android feed — with an honest price, a wishlist
that follows you around, and a detail page for every game.**

<p align="center">
  <img src="docs/screenshots/02-deals-feed.png" width="30%" alt="The deals feed" />
  <img src="docs/screenshots/09-where-to-buy.png" width="30%" alt="A game detail page comparing stores" />
  <img src="docs/screenshots/06-wishlist.png" width="30%" alt="The wishlist" />
</p>

---

## What it does

Discount Hunter is a native Android app (Kotlin + Jetpack Compose) that pulls live discounts from
**Steam, GOG and the Epic Games Store**, merges them into a single daily feed, and lets you save
games to watch their prices.

| | |
|---|---|
| 🛒 **Daily deal feed** | Every discount across all three stores, sorted, filterable, with a seven-day strip for browsing past and upcoming days |
| 🔎 **Cross-store search** | Search all three stores at once and get one deduplicated result per game, not one per store |
| 💶 **An honest price** | Prices are quoted in the currency the store actually used, and genuinely converted when they differ — never relabelled |
| ⭐ **Wishlist** | Save any game from the feed, search results or a detail page. Saved games rise to the top everywhere they appear |
| 🖼️ **Rich detail pages** | Hero art, screenshots, trailers, system requirements, genres, "Where to buy" comparing every store that has the game |
| 📈 **Price history** | A chart of prices **this device** recorded, with deep links to external databases |
| 🎨 **Material You** | Follows your system light/dark theme, edge-to-edge, with hand-tuned motion throughout |
| 🔒 **Private by design** | No account, no tracking, no analytics, no API keys. Everything is cached on-device |

<p align="center">
  <img src="docs/screenshots/01-first-run-tour.png" width="32%" alt="First-run tour" />
  <img src="docs/screenshots/03-search.png" width="32%" alt="Cross-store search" />
  <img src="docs/screenshots/08-diagnostics.png" width="32%" alt="Settings, including log export" />
</p>

## Download

Pre-built release APKs live in [`apk/`](apk/). The app is unsigned-by-CI, so Android will ask you to
allow installation from your file manager the first time.

- [`app-release.apk`](apk/release/app-release.apk) — the current release

Or build it yourself:

```bash
git clone https://github.com/JuggyBob/DiscountHunterAndroid.git
cd DiscountHunterAndroid
./gradlew assembleRelease
```

## Build from source

Discount Hunter is a standard Gradle project — no special tooling beyond the Android SDK.

**Requirements**

- JDK 17+ (tested on JDK 25)
- Android SDK with **API 36** installed
- The Gradle wrapper handles Gradle itself — nothing to install

**Quick start**

```bash
# Point the build at your SDK (or set ANDROID_HOME / ANDROID_SDK_ROOT)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# Build a debug APK
./gradlew assembleDebug

# Run the unit tests
./gradlew testDebugUnitTest

# Build the optimised release APK
./gradlew assembleRelease
```

The debug variant installs as `com.discounthunter.android.debug` so it sits alongside a release
install without one overwriting the other.

**Toolchain**

| | |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (BOM 2024.09.00), Material 3 |
| Gradle | 9.3.1 (via wrapper) |
| Android Gradle Plugin | 9.1.1 |
| Min / target SDK | 24 (Android 7.0) / 36 |
| JVM target | 11 |

## How it is built

```
app/src/main/java/com/discounthunter/android/
├── core/          # Pure Kotlin: models, text matching, price formatting, FX
├── data/          # Networking, caching and parsing — one package per store
│   ├── steam/     # Steam Store API
│   ├── gog/       # GOG catalogue
│   ├── epic/      # Epic free-games feed (GraphQL)
│   ├── cache/     # Region-scoped snapshots, payload cache
│   └── repo/      # Repositories merging the stores together
├── diagnostics/   # In-app log + crash capture used by the export button
├── di/            # AppContainer — the hand-rolled dependency graph
└── ui/            # Compose screens, one package per screen, plus shared components
```

There is no dependency-injection framework and no database — DataStore for preferences, files for
the price-history snapshots, and a small hand-wired container for object lifetimes. The whole app is
~70 Kotlin source files.

Some decisions worth knowing about, all explained at length in
[`docs/ENGINEERING.md`](docs/ENGINEERING.md) and [`DEV_NOTES.md`](DEV_NOTES.md):

- **Store-native ids, never titles.** A game is identified by its store id (`s1:1245620`) wherever
  one exists, and by a normalised title only as a fallback. Titles are never used as cache keys.
- **Prices are converted, not relabelled.** Steam and Epic re-quote per region; GOG publishes in
  dollars regardless. The app converts using the ECB's daily reference rates and marks the result
  with `~`, so a converted price is never mistaken for a quoted one.
- **Media belongs to a verified identity.** A game's screenshots and trailers are only ever shown
  when the store positively confirmed the id, so one game can never display another's media.

## Testing

```bash
./gradlew testDebugUnitTest      # 334 unit tests across 26 suites
```

The suite covers the things that are easy to get quietly wrong: cross-store identity and media
ownership, currency conversion and price ranking, cache invalidation and region scoping, filtering
and sorting, network failure and rate limiting, and JSON/HTML parsing edge cases.

## Reporting bugs

Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) — it explains how to file a useful report, and the
app has a built-in **Diagnostics → Export logs** button that attaches everything a maintainer needs
(the last few hundred log lines plus your device and memory info) to a bug report.

## Contributing

Pull requests are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the coding standards, branch
naming conventions, and how to submit a change. By participating you agree to license your work
under the [Apache 2.0](LICENSE) license.

## License

[Apache License 2.0](LICENSE) — use it, change it, ship it.

This project is not affiliated with, endorsed by, or connected to Valve, GOG.com, or Epic Games.
Game titles, cover art and store data belong to their respective owners; the app displays them only
as returned by each store's public API.
