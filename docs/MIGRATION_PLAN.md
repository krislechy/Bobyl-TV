# Migration plan

The original APK is treated as an executable specification. No smali is compiled into
this project.

| Stage | Scope | Acceptance gate |
|---|---|---|
| 0 | Gradle/Kotlin/Compose for TV shell | Debug APK assembles; TV launcher starts |
| 1 | Manifest and resources | Package, permissions, deep link, icons and TV banner diffed |
| 2 | Network/API | Recorded requests and responses match reference behavior |
| 3 | Models/data | Parsing fixtures and repository tests pass |
| 4 | Authentication/settings | Existing session schema and settings behavior are compatible |
| 5 | Catalog | D-pad navigation, six-card rows and focus restoration verified |
| 6 | Movie/series details | Cinematic details and actions match reference capabilities |
| 7 | Stream URL | Quality/language/subtitle flow matches without changing semantics |
| 8 | External player | Intent contract and fallback handling verified |
| 9 | Remaining screens | Feature parity checklist complete |

## Non-negotiable compatibility

- Production application ID: `com.bobyl.tv`.
- Minimum/target SDK from reference: 23/31.
- Deep link: `https://hdrzk.org/*`.
- Launcher and Leanback launcher entry points.
- Reference target SDK 31 is documented, but the maintained app targets API 35 and
  handles resulting permission/behavior differences explicitly.
- Existing production data is never cleared during testing.
- TV UI only; no mobile/TV selector.
