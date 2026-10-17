# Local episode playlist

Debug and production builds integrate the playlist into normal series playback. The playback
settings checkbox `Список серий в MX Player` defaults to enabled;
MX Player receives the playlist both as an explicit default and when selected
through the system/always-ask chooser. Only the MX ACTION_VIEW targets inside a
chooser receive local playlist URLs; other players retain the direct stream. Settings display the
loopback address, active port or last-used port; each session selects a free port.
The switch affects subsequent launches. Continue also supports this path and loads
the season list when necessary. Movies, downloads and other players retain
single-item playback. There is no separate experimental action.
Subtitles now use episode-specific adjacent loopback URLs (.srt/.ass/.ssa/.vtt).
The initial item gets an explicit local `subs`/`subs.enable` URI; subsequent items
use MX 3.2.1's adjacent HTTP subtitle discovery (C18025p.m38270f0). ActivityScreen
clears subtitle extras on item changes, so a global mutable subtitle endpoint is
not used. Each episode resolves the same selected label or returns 404 if absent.
Video/subtitle probes share the authenticated API response for 60 seconds. No
account headers are sent to MX. MX's subtitle-display setting must remain enabled.
Socket tests verify routing, missing tracks, format probes, caching and unsafe URL
rejection; actual MX subtitle rendering across Next requires device verification.
Release builds include the setting, service and foreground permissions. Only the
test activity remains debug-only.

`EpisodeRelayService` is an unexported component in Bobyl's process, not a separate
application. It starts only for an enabled playlist launch. A foreground notification
keeps it eligible for background execution while MX is visible and allows stopping
the session. It stops on player result, task removal, or after six hours. Process
death ends the session; the user must reopen the playlist.

The local server binds only to 127.0.0.1, on an ephemeral port, with a random session
token. The playlist contains local URLs for the selected season. GET/HEAD resolves
one episode through the authenticated PlaybackRepository and returns HTTP 307 to
the selected quality. Video bytes go directly to MX from the provider. Cookies
never appear in the redirect. Resolution is serialized and reused for 60 seconds
to coalesce player probes; no full-season prefetch occurs. Missing quality or API
failure produces 502, with no retry loop and no account details in the response.

Progress is mapped from MX's returned URI to the local or resolved episode URL.
An unrecognized URI does not overwrite progress with an assumed episode. MX's
preloading must not be interpreted as confirmed playback. Bobyl auto-next is
disabled for relay results to avoid duplicate advancement.

Validation: JVM socket tests cover lazy resolution, HEAD/GET coalescing, result URI
mapping, unknown routes, unsupported methods, and failures/invalid redirect URLs.
The supplied MX Player 3.2.1 APK cannot install on Bobyl_TV_API31 (x86):
INSTALL_FAILED_NO_MATCHING_ABIS. Actual MX playback, Next/Previous, seek, and result
URI behavior were not verified by that emulator test. Subsequent TV testing was
reported successful by the user; this is not a substitute for automated MX tests.

Android API 31 emulator service smoke test passed in noPlayer mode: foreground
service started, each of two requested routes returned 307 with its corresponding
fixture URL, resolver logs showed only requested episodes, and explicit service
stop removed the service. All 49 JVM tests passed; debug and tvRelease APKs built.

A debug-only RelaySmokeActivity is provided for an emulator with compatible MX.
It redirects two local playlist items to fixture video URLs at
http://10.0.2.2:18765/episode1.mp4 and /episode2.mp4. Supply test MP4s with a local
HTTP server, launch this activity, and inspect BobylRelaySmoke logs. It does not
use provider credentials or modify watch history. It is absent from release APKs.
