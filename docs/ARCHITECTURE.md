# Architecture

A map of the codebase for people about to change it. For *why* things are the way they are, read
[`../DEV_NOTES.md`](../DEV_NOTES.md) — this document only covers what goes where.

## Layers

```
        ┌──────────────────────────────────────────────┐
        │  ui/            Compose screens + view models │
        └───────────────┬──────────────────────────────┘
                        │  repositories return immutable models
        ┌───────────────▼──────────────────────────────┐
        │  data/repo/    merge stores into GameDetail,   │
        │                Deal, SearchResult             │
        └───────┬───────────────────────┬──────────────┘
                │                       │
   ┌────────────▼──────────┐   ┌────────▼─────────────────┐
   │ data/{steam,gog,epic} │   │ data/cache/               │
   │ one service per store │   │ payload cache, snapshots  │
   └────────────┬──────────┘   └──────────────────────────┘
                │
        ┌───────▼─────────────────────────────────────┐
        │  data/net/HttpClient   OkHttp, retries,      │
        │                        timeouts, size caps   │
        └─────────────────────────────────────────────┘

        ┌──────────────────────────────────────────────┐
        │  core/       pure Kotlin, no Android imports │
        └──────────────────────────────────────────────┘
```

The rule that shapes everything: **`core/` has no Android dependencies**. Models, title matching,
price formatting and currency conversion all live there as plain functions, which is why 334 unit
tests need no emulator and no Robolectric.

## Packages

### `core/` — pure Kotlin

| Path | Contents |
|------|----------|
| `core/model/` | `Deal`, `GameDetail`, `StoreOffer`, `SearchResult`, `WishlistEntry`, `GameIdentity`, `PriceFormat`, `FxRates`, `summarizePriceHistory` |
| `core/text/` | `TitleKey` (normalised titles), `TextTools` (HTML stripping, requirements cleanup) |

`FxRates` is the only class here that touches the network. It is deliberately standalone: a rate
fetch that fails must never take prices with it, so it is impossible for one to break anything else.

### `data/` — everything that knows about the outside world

| Package | Responsibility |
|---------|----------------|
| `data/steam/` | Steam Store API: featured deals, search, app details, store page scraping |
| `data/gog/` | GOG catalogue and game pages |
| `data/epic/` | Epic's GraphQL free-games feed, including the giveaway schedule |
| `data/source/` | Adapters wrapping each service behind a small interface |
| `data/net/` | `HttpClient`: timeouts, bounded retries, rate-limit backoff, response size caps |
| `data/cache/` | `PayloadCache` (parsed responses) and `SnapshotStore` (daily price history) |
| `data/repo/` | `DealRepository`, `GameDetailRepository`, `SearchRepository` — the merge layer |
| `data/wishlist/` | `WishlistRepository` — thread-safe, identity-based matching |
| `data/settings/` | `SettingsRepository` over DataStore |
| `data/image/` | Coil loader factory — one disk cache, one connection pool |

### `ui/` — Compose

One package per screen (`ui/deals/`, `ui/search/`, `ui/wishlist/`, `ui/detail/`, `ui/settings/`,
`ui/intro/`), plus `ui/components/` for shared pieces and `ui/theme/` for colour, type and
`Motion.kt`.

Each screen has a `*ViewModel` exposing one immutable `UiState` and taking intents. Screens render
state and call methods; they do not compute.

### `di/` and `diagnostics/`

- `di/AppContainer` — a hand-rolled container. No DI framework, because the graph is small and
  explicit lifetimes are easier to reason about than annotations.
- `diagnostics/AppLog` — the bounded in-app log behind the export button, plus the uncaught-exception
  handler that records a crash before the process dies.

## Data flow, concretely

Opening a game:

```
DealsScreen
  → DetailViewModel.load(GameReference)
      → GameDetailRepository.load(reference, country)
          → SteamService.fetchAppDetails(id, cc=GB)      ┐  in parallel,
          → GogService.search(title) + TitleMatcher      │  under supervisorScope
          → EpicService.freeGames(country)               ┘
          → merge into GameDetail, recording which stores
            *positively verified* an identity (acceptedIdentities)
      → DetailUiState.detail
  → DetailScreen renders
```

`acceptedIdentities` is the whole point of that step. Screenshots and trailers are only ever rendered
for identities in it, so a game can never display another game's media — the desktop app's original
critical bug.

## Invariants worth knowing before you change anything

These are documented in full in [`../CONTRIBUTING.md`](../CONTRIBUTING.md#things-that-are-load-bearing),
but briefly:

1. **Lazy-list keys are store-native ids, never list positions.** Duplicate keys crash Compose
   mid-layout; a positional key makes rows swap content when the list reorders.
2. **Cache keys are structured, never free-form strings**, so the wrong payload cannot be served for
   the right key.
3. **Media is shown only for positively verified identities.** "Probably the same game" is how one
   game's screenshots end up under another's title.
4. **Prices are converted, never relabelled.** A foreign amount under a different symbol is a
   different price.
5. **Parsing runs off the main thread**, always, via an injected `CoroutineDispatcher`.
6. **Motion tokens live in `ui/theme/Motion.kt`.** No hard-coded durations in screens.

## Testing seams

The seams are chosen so that most logic is reachable without a device:

- Stores implement a source interface (`data/source/`), so repositories are tested with fakes.
- `HttpClient` returns an `Outcome`, so failure paths are values rather than exceptions.
- View models take repositories, not Android classes.
- `AppLog` is an `object`, so tests can read it.

```bash
./gradlew testDebugUnitTest
```

## Adding a store

1. `data/<store>/` — a service, its models, and its parsers, each taking a `CoroutineDispatcher`.
2. `data/source/` — an adapter behind the source interface.
3. `core/model/StoreId` — add the enum value and its display name.
4. `data/repo/` — query it in parallel and add its offers to the merge, recording verified
   identities.
5. `core/model/PriceHistoryCatalog` — add its price-history deep links.
6. Tests, including: parsing a malformed response, the store being down, and the store returning the
   same product twice.
