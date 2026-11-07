# Shunter: notes for AI agents and contributors

Open-source (MIT) mod manager for **Transport Fever 3 on Linux**. TF3 runs **natively** on Linux
(no Proton). Kotlin/JVM 25+, Gradle. Package `ch.lightspots.shunter`, command `shunter`.
See README.md for usage.

## Status and next steps

- Done: `core` (scan, feeds, download, install/update/remove with backups), `cli`, and the Compose
  Desktop `app` (#16; screens Library, Browse, Updates, Settings, light and dark, as in the design).
- Open points:
  - transportfever.net also lists tools (e.g. both Windows mod managers) in category
    "Werkzeuge & Tools" / `mod_type: script`. Installing one downloads it, then fails with
    "no mod.json". `script` also covers real script mods, so it is not a reliable filter.
  - Required dependencies are reported (with the `install` command), not installed automatically.
  - mod.io: `modio login/logout/search/subscribe/unsubscribe`, subscriptions in `list`. The game
    downloads subscriptions made by shunter on its next start (verified). Next: email
    sign-in (`/oauth/emailrequest` + `/oauth/emailexchange`, needs an API key in the POST body),
    token in the Secret Service instead of a file.
  - modwerkstatt.com: sign-in and installing a mod that is not public yet verified against the real
    server. Signed in, the feed also lists those mods (107 instead of 36 as of 2026-11), some of them
    without a file on the server yet (404). What a membership (API error
    `membership_required`) changes on top is not known.

## Mod folders (native Linux)

| Location | Path | Shunter |
|---|---|---|
| local | `~/.steam/steam/userdata/<steamId>/3493540/local/mods` | the only folder we write to |
| staging | `…/3493540/local/staging_area` | read only (user's own mods) |
| mod.io | `~/mod.io/common/10640/mods` | read only, managed by the in-game Mod Hub; folder name = mod.io id |

Our own state follows XDG: `~/.local/share/shunter` (install records, backups, work dir),
`~/.cache/shunter` (downloads, feeds), `~/.local/state/shunter/logs` (one log per frontend, `cli.log`, `app.log`).
Nothing of ours goes into the game folders.

## TF3 mod format

`<modId>/mod.json` (`modId`, `revision`, `severityAdd`/`severityRemove`, `dependencies` with
`mod.modId`/`revisionMin`/`revisionMax`/`loadBefore`/`optional`, `incompatibilities`; both may be
`null`), `_metadata/modinfo.json` (name, summary, description, `localization.<lang>`, authors,
tags), `_metadata/0.png`, `content/`, `strings.json`. Unlike TPF2, folder names have no `_1`
suffix. mod.io packages have only `mod.json` plus content at the top level (no `_metadata`).

## Feeds

- transportfever.net: `https://www.transportfever.net/filebase/repos/tpf3-v3.json`, format
  `TransportFeverNetRepo` v3. `latest_file` has size + sha256; downloads work anonymously.
  About half the archives are `.7z`. `detected_mods` is always empty, so the mod folder is
  found by extracting. Version is in `custom_fields` with label "Aktuelle Version".
  Dependencies point to other entries (`entry_id`) and carry an `install` block.
  `images.preview` is a thumbnail (PNG, ~140 KB), `images.large` the full picture.
- modwerkstatt.com: `https://modwerkstatt.com/json/mwmanager`, the list the ModWerkstatt ModManager
  (a Godot app) reads. A JSON array: `id`, `modId`, `title`, `download_url` (`.zip`, mod folder at the
  top), `file_size`, `revision` (string, = `mod.json` revision), `updates` (`{"<rev>": text}`, `[]` when
  empty), `required_mods` (`[{id, modId}]`), `image_url` (`.webp`; all of them answered 404 as of 2026-11). No checksum, author or dates. The old `/tpfmm/` lists only
  TPF2. Sign-in at `https://modwerkstatt.com/api`: `POST /login/request {email}` mails a code,
  `POST /login/confirm {email, code, device_name}` returns `{token, expires_at, user{name, membership_until}}`,
  then `Authorization: Bearer` (`GET /me`, `POST /logout`). With the token the feed also lists
  mods that are not public yet, with signed download links (`?exp=<epoch, ~6 h>&sig=<hex>`) that work
  without the token. A bad signature redirects to the home page (HTML); a good one can still 404 when
  the file is missing on the server (seen for unreleased mods). `redactUrl` hides `sig` in logs and errors. Errors are `{"error": "<code>"}`. The token is
  stored in `AppDirs.modwerkstattLoginFile` (mode 600) and sent only to `https://*modwerkstatt.com`.
- mod.io: REST API v1 at `https://g-10640.modapi.io/v1` (`api.mod.io` is retired). Authenticated
  only with an OAuth access token (`Authorization: Bearer`), never the API key, which mod.io only
  takes as `?api_key=` query parameter. The token is a personal access token from mod.io/me/access,
  stored in `AppDirs.modIoLoginFile` (mode 600); `ModIoLogin.toString` hides it. The game's Mod Hub
  downloads subscribed mods, we never write to the mod.io folder. "Sign in with Steam" in the
  Windows tools is the browser plus personal access token flow; `/external/steamauth` needs a Steam
  app ticket of the game itself.

## Rules

- `.ref/` holds third-party reference material. It is git-ignored and must never be committed. 
- Tests use generated mods and archives only (`TestFiles`), never real mods.
- Commit messages follow "Commit messages" in README.md (`<issue> <modifier> <[area]> <subject>`, shorthand like `r`/`+` is fine).
- Keep security properties when changing install code: checksum verification, refusing archive
  entries outside the target and symlinks, backup before replace with rollback.
- Formatting: ktlint via Spotless, `spotlessCheck` is part of `check`. Run `./gradlew spotlessApply`
  before committing. ktlint settings live in `.editorconfig`; after changing them run
  `./gradlew --stop`, the daemon keeps the ktlint settings it read first.
- Keep `core` free of UI dependencies; frontends go through `ModManager`.
- HTTP uses the Ktor client (CIO engine) in `HttpDownloader`, archives Commons Compress + xz,
  CLI Clikt 5 (the root command sets `CliContext` with `findOrSetObject`, subcommands use
  `requireObject`).
- CLI output goes through Clikt's `echo` (Mordant terminal), never `print`, so styles are dropped
  when the output is not a terminal or `NO_COLOR` is set. Use the style helpers in `ManagerCommand`
  (`warning`, `success`, `muted`, `highlight`, `bold`) and `table()`, which pads by visible width.
  Downloads show a Mordant progress bar (`DownloadBar`, via `withDownloadBars`).
- Preview pictures of the sites (`RemoteMod.imageUrl`, `ModIoMod.logoUrl`) go through `ImageCache` into
  `~/.cache/shunter/images` (HTTPS only, max 5 MB, 4 at a time, pruned after 30 days); failures are not
  retried until restart.
- App (`app`): `AppModel` holds the state (Compose state, changed on the UI thread) and calls
  `ModManager`; installs run one at a time behind a mutex. What the Library and Updates screens show
  is derived in `LibraryData` (plain functions, unit tested). Colors are the design's tokens in
  `ui/Theme.kt` (`colors.*`), icons the design's SVG paths in `ui/Icons.kt`. Composables are
  PascalCase (`ktlint_function_naming_ignore_when_annotated_with = Composable`). material3 is
  versioned apart from Compose (`compose-material3` in the catalog).
- `BuildInfo` is generated from the Gradle project version (`generateBuildInfo` in `core`); do not
  add it to `src`.
- Network calls in `core` are `suspend` and cancellable; suspend functions move blocking work to
  `Dispatchers.IO`. The CLI shares one `HttpDownloader` (closed via Clikt's `registerCloseable`)
  and runs commands in `runBlocking`.
- Logging: `core` uses only the kotlin-logging facade (`private val logger = KotlinLogging.logger {}`).
  Frontends bring Logback (through the `logging` module) and log to a file only, never the console
  (`Logging.setup`, rotation by size at startup via `LogRotation`). kotlin-logging prints a startup line on stdout unless
  `-Dkotlin-logging.logStartupMessage=false` is set, so keep that JVM argument. Never log secrets (the
  mod.io API key) and keep library loggers (Ktor) at INFO. `SHUNTER_LOG_LEVEL=debug` raises our own.
  URLs end up in logs and error messages, Ktor's timeout exceptions included, so secrets must not be
  part of a URL (no API key as query parameter).

## Working in the ai-sandbox

The host has no global gradle; the wrapper (Gradle 9.8.0, checksum-pinned) is committed.
A built CLI can be run in the container for smoke tests with a fake home:
`JAVA_OPTS=-Duser.home=<fake> XDG_DATA_HOME=… cli/build/install/shunter/bin/shunter --mods-dir <tmp> …`
