# Playback migration

Reference: original-clean `models/FilmModel.smali` methods `getStreamsByTranslationId`,
`getSeasons`, `getStreamsByEpisodeId`, `parseSteams`, `decodeUrl`, `parseSubtitles`;
external player contract: `views/fragments/FilmFragment.smali` (`video/*`, `subs.name`).
Readable JADX is a navigation aid, not compiled into the Kotlin project.

Implemented:

- Page translator IDs and ads/camrip/director/premium flags; movie/series initializer ID.
- No-translator-list fallback with inline streams and subtitles.
- POST `/ajax/get_cdn_series/?t=<millis>` with reference actions `get_movie`,
  `get_episodes`, `get_stream`, using the shared authenticated transport.
- Movie and episode requests retain the original additional detail-cookie flags.
- Ordered season/episode parsing, quality and subtitle parsing; `#h` decoding,
  removal of reference `//_//` 21-character markers, second `or` URL selection.
- Cinematic voice / season / episode / quality / subtitle dialogs with cancellable
  loading, readable errors and retry. Back cancels the current request.
- Explicit external playback through ACTION_VIEW, `video/*`, `title`, selected
  `subs` URI array and `subs.name` / `subs.filename`. No account cookies are forwarded.

Intentional migration differences / incomplete parity:

- Only HTTP(S) media URLs accepted; malformed entries are rejected without logging signed URLs.
- Explicit final launch action even when no subtitles are offered.
- Selected subtitle URLs are passed directly. The legacy optional public Downloads
  subtitle copy / `subtitles_location` behavior is not migrated yet.
- No built-in player, thumbnail timeline, viewing-progress sync, auto-next episode,
  remembered playback choices or completed external-player compatibility matrix yet.
- A premium-labelled voice is not unlocked or filtered locally; availability remains
  controlled by the authenticated server response.
- Saved login was confirmed by the user; authenticated movie details opened in emulator.
  Emulator live checks passed for movie inline quality choices and series translator ->
  season -> episode -> network quality -> subtitle choices. Loader was observed during
  season request. 24 JVM tests passed and the final debug APK was installed with `-r`.
  Actual playback remains unverified: emulator has no external player. User-supplied
  MX Player 3.1.4 (2001003524) APK has ARM64/ARMv7 native code only; x86 emulator rejects
  it with INSTALL_FAILED_NO_MATCHING_ABIS. No physical TV actions were performed.

Unit coverage: request fields, endpoint, encoded/plain quality URLs, alternatives,
subtitles, unsafe schemes, page voices/fallback, season ordering and server errors.
