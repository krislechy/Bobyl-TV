# Film screen and library milestone

Reference contracts were reconstructed from `FilmModel`, `BookmarksModel`,
`FilmFragment` and the original resources.

- Catalogue hero/mini-description blocks were removed from every catalogue section.
- Film selection opens a fullscreen cinematic details surface and requests focus on
  `Смотреть` after the page loads.
- Details include metadata, description, HDRezka rating/votes, 1–10 rating action,
  server bookmark categories, creators and cast with photos.
- Favorites load categories from `/favorites/`, category films from its paged link,
  and toggle membership through `/ajax/favorites/` (`add_post`).
- Rating uses `/engine/ajax/rating.php` with the reference query parameters.
- Settings persist a default external player (system chooser, MX Player, MX Player Pro)
  and 4–7 catalogue cards per row; the default remains six.

The current Room migration moves history, personal scores, playback selections,
public film metadata, persistent DTO cache and file metadata into `bobyl.db`.
Schema, startup gate and legacy handling: [local database](LOCAL_DATABASE.md).
The preferences contract below describes the legacy source imported by that migration;
existing values remain untouched as preserved source data.

Personal scores are local device data, separate from the site's aggregate rating
and vote count. Following the user's account separation request, new entries use
`v2|provider|owner|filmId` in `personal_ratings`. The owner is a stable hash of the
server user ID from valid authentication cookies; password/login changes do not
change that namespace. Guest data has a separate namespace. Incomplete credentials
produce an error rather than falling back to another account's data.

Reads use the account session fence. A save verifies the expected owner before
HTTP, fixes its destination, and writes locally only after server success. A later
cookie change cannot assign the saved score to a different account. Local commit
failure is reported; the in-memory value may already have changed even when disk
persistence fails. No server-side recovery/synchronization of personal scores is
introduced.

Ownerless legacy `provider|filmId` entries remain untouched, without automatic
attribution or a shared fallback. They are preserved but are not displayed as an
account's personal scores. Migration to an identified owner requires a separate
explicit attribution decision. Implementation and verification:
[personal ratings tests](../app/src/test/java/com/bobyl/tv/data/PersonalRatingsTest.kt).

Live emulator verification: main catalogue without hero, automatic Watch-button focus,
rating/bookmark surface, creator/cast rows, and authenticated favorite category/film.
External mutations (rating/bookmark toggle) were not triggered during automated UI QA.
