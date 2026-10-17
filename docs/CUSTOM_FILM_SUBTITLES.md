# Additional film subtitles

Dev correction: MX 3.2.1 ignores content URIs in `subs` (C18025p.m38280k0).
Dev now snapshots the local tracks and serves them via a tokenized loopback HTTP
server for the external playback session. GET/HEAD return original bytes; no CDN
or API request is made for local tracks. Server tracks/active selection remain
unchanged. Limits: 32 files, 4 MiB per file, 16 MiB total. The existing foreground
service keeps it alive and closes it on player return/stop/expiry. Release retains
the previous implementation; runtime MX verification remains to be performed.

The fixed root is shared storage `Documents/Bobyl/Subtitles`; no folder picker is
used. Populate `<root>/films/<film ID>/` with SRT, ASS, SSA or VTT
files, e.g. `films/88449/Українська.srt`. The feature reads existing files; it does
not download, replace or relocate server subtitles, and does not process series.

On movie launch the directory is queried on IO. Missing directories mean no
additional tracks. Inaccessible roots produce a notice without preventing playback.
Local URIs are appended to `subs`, `subs.name`, `subs.filename` after player-specific
configuration, including each explicit chooser target. Existing server tracks and
`subs.enable` are preserved. File read grants use ClipData on the target and chooser;
FileProvider exposes only the films subtree. The player must support the subtitle
extras (MX does); other players may ignore them.

On activity resume, an IO task checks/creates the root and `films` directory without
deleting existing files. Android 11+ requires user-granted MANAGE_EXTERNAL_STORAGE
for direct access to non-media subtitle files in this shared directory. Settings
explain the broad OS permission and open its system screen; no grant is automatic.
Android 6–10 uses legacy storage permission. Missing access is reported rather
than treated as an empty subtitle list. File selection inside MX is not returned
to Bobyl for history persistence. Device verification of permission UI and MX
loading is still required. The previous customSubtitleTree preference is unused.
