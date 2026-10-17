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

Live emulator verification: main catalogue without hero, automatic Watch-button focus,
rating/bookmark surface, creator/cast rows, and authenticated favorite category/film.
External mutations (rating/bookmark toggle) were not triggered during automated UI QA.
