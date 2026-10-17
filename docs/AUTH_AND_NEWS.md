# Authentication and New Releases reference audit

Source of truth: `hdrezka-original-clean/smali/com/falcofemoralis/hdrezkaapp/`
and its `res/values/strings.xml`, `arrays.xml`.
Readable JADX counterparts are used only as an aid.

## Login

- UserModel: POST `/ajax/login/`.
- Form-encoded fields: `login_name`, `login_password`, `login_not_save=0`.
- BaseModel: Android UA, `X-Hdrezka-Android-App: 1`, `X-Hdrezka-Android-App-Version: 2.2.5`.
- Response JSON: `success`; failure text in `message`.
- UserData: extract `dle_user_id`, `dle_password` (session hash, not plaintext password), `PHPSESSID`.
- Authenticated catalog/search/details send URL-scoped Cookie headers. Details retain
  `allowed_comments=1; _ym_isad=1; _ym_visorc=b; dle_newpm=0` from FilmModel.

New Kotlin behavior: no plaintext password persistence, no body/cookie logging, normal
TLS validation; session token backup encrypted with Android Keystore AES-GCM, bound
to the exact HTTPS origin and stored in noBackupFilesDir. Login does not follow redirects
with credentials. CookieManager gives the network layer the same session on subsequent requests.
WebView cookie storage is excluded from backup/transfer. Mirrors do not receive another
origin's saved credentials automatically. Account validity after expiry still depends on server responses.

Not claimed: migration of existing production legacy token files, premium-account metadata,
registration, password recovery or a successful real login before the user enters credentials.

## News labels (strings.xml + arrays.xml + NewestFilmsModel/Presenter)

| UI label in original | Request | Pagination |
|---|---|---|
| Новинки (`fresh`, sort `new`) | GET `/new/` | `/new/page/N/` |
| Горячие новинки (`new_films`, `newest_slider_content`) | POST `/engine/ajax/get_newest_slider_content.php`, `id=0` | first page only |
| Последние поступления (`last`) | GET `/?filter=last` | `/page/N/?filter=last` |
| Популярные | GET `/?filter=popular` | yes |
| Анонсы | GET `/?filter=soon` | yes |
| Сейчас смотрят | GET `/?filter=watching` | yes |

Previous Kotlin milestone mapped the left menu Новинки to `last`, which was wrong for
that label. The corrected default is `new`; other original modes remain explicit choices.
