# Poster status and external episodes

Comparison with the original implementation (2026-09-11):

- `FilmsListModel.SUB_INFO` reads `div.b-content__inline_item-cover a span.info`
  from HTML into `Film.subInfo`; this is not a separate JSON API boolean.
- `FilmModel.AWAITING_TEXT` is `В ожидании`; the original adapter displays
  subInfo and additionally darkens posters with that text.
- Bobyl already parsed this text into `CatalogFilm.badge` but did not display
  it in `PosterCard`. The shared poster now displays the server text in a dark
  overlay, including waiting/completion notices when supplied. No completion
  is inferred from the year, last available episode, or an absent stream.
- An independent series-completed flag was not found in the inspected original
  catalog parser. Completion display therefore depends on the server badge;
  a fixture containing completion text tests preservation, not live availability.
- Original external playback (`FilmFragment.openStream`) sends the current URI,
  title, and optional subtitles. Its internal TV player owns a series playlist.
- Bobyl's external intent contains one URI in `video_list`. With auto-next enabled,
  a successful player result with `end_by=playback_completion` triggers fetching
  and launching the next episode in Bobyl. This depends on player result support;
  it is not a next-episode playlist inside the external player.

Reference APK: `reference/original-apk/hdrezka_app_2_2_5_original.apk`.
