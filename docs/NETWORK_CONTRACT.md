# Reference mapping: catalog milestone

Reference sources: `hdrezka-src/sources/com/falcofemoralis/hdrezkaapp/models/`
(`BaseModel`, `NewestFilmsModel`, `FilmsListModel`, `SearchModel`, `FilmModel`).
The matching smali classes remain the source of truth when JADX output is ambiguous.

| Original | Kotlin replacement | Preserved behavior |
|---|---|---|
| BaseModel.getJsoup | HtmlTransport | Android UA, X-Hdrezka-Android-App=1, protocol version 2.2.5, URL-scoped cookies |
| NewestFilmsModel.getNewestFilms | CatalogUrls.page | /page/N/, filter=last/popular, genre=2; /new/page/N/ |
| SearchModel.getFilmsFromSearchPage | CatalogUrls.page(query) | /search/?do=search&subaction=search&q=...&page=N |
| FilmsListModel.getFilmsFromPage | CatalogParser | card ID, absolute URL, poster, title, metadata, badge, kind, rating |
| FilmModel basic description selectors | DetailsParser | title, original title, cover, info table, description |

Intentional safety/robustness differences:

- Normal platform TLS validation; no trust-all certificate or hostname verifier.
- Missing metadata/category/cover no longer causes null/index crashes.
- Requests run on IO, are cancellable, have a 20-second timeout and bounded bodies.
- No cookie values, credentials or response bodies in application logs.
- User-selected provider must be an HTTPS origin, without credentials/query/path.
- A response without recognizable catalog markup is an explicit error, not a forever spinner.

Not yet migrated: authenticated lists, login UI, full settings schema, stream extraction,
language/quality/subtitle flow, external-player intents, remaining screens.
The detail modal is a read-only milestone, not a claim of full FilmModel parity.

## Verification checkpoint

- `:app:testDebugUnitTest :app:assembleDebug`: successful; 8 tests, 0 failures.
- API 31 Android TV emulator: application starts; catalog/posters load; six cards per row.
- Search for `Matrix`: real results including «Матрица» shown.
- Details: spinner transitions to a recoverable HTTP 403 error; Back/Retry available.
- Reconstructed original headers/cookie flags also received HTTP 403 in control requests.
  This is not proof of runtime parity with the original APK, nor proof that login resolves it.
- Only `emulator-5554` was modified during this milestone; physical TV was not touched.
