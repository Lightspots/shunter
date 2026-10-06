# Shunter: notes for AI agents and contributors

Open-source (MIT) mod manager for **Transport Fever 3 on Linux**. TF3 runs **natively** on Linux
(no Proton). Kotlin/JVM 25+, Gradle. Package `ch.lightspots.shunter`, command `shunter`.
See README.md for usage.

## Status and next steps

- Done: `core` (scan, feeds, download, install/update/remove with backups) and `cli`.
- Next: Compose Desktop app (`app` module) on top of `core`. Compose plugin 1.12.1 and Kotlin
  2.4.20 are in the version catalog already; material3's latest stable is 1.9.0.
- Open points:
  - transportfever.net also lists tools (e.g. both Windows mod managers) in category
    "Werkzeuge & Tools" / `mod_type: script`. Installing one downloads it, then fails with
    "no mod.json". `script` also covers real script mods, so it is not a reliable filter.
  - Required dependencies are reported (with the `install` command), not installed automatically.
  - mod.io: `modio login/logout/search/subscribe/unsubscribe`, subscriptions in `list`. Not verified
    yet: that the game picks up subscriptions made outside it on its next start. Next: email
    sign-in (`/oauth/emailrequest` + `/oauth/emailexchange`, needs an API key in the POST body),
    token in the Secret Service instead of a file.
  - modwerkstatt's `/tpfmm/` feed works but only lists `game: "tpf2"` so far; its TF3 mods are
    supporter-only for now.

## Mod folders (native Linux)

| Location | Path | Shunter |
|---|---|---|
| local | `~/.steam/steam/userdata/<steamId>/3493540/local/mods` | the only folder we write to |
| staging | `…/3493540/local/staging_area` | read only (user's own mods) |
| mod.io | `~/mod.io/common/10640/mods` | read only, managed by the in-game Mod Hub; folder name = mod.io id |

Our own state follows XDG: `~/.local/share/shunter` (install records, backups, work dir),
`~/.cache/shunter` (downloads, feeds), `~/.local/state/shunter/logs` (one log per frontend, `cli.log`).
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
- modwerkstatt.com: `https://modwerkstatt.com/tpfmm/`, `mods[].files[]` with `foldername`.
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
- `BuildInfo` is generated from the Gradle project version (`generateBuildInfo` in `core`); do not
  add it to `src`.
- Network calls in `core` are `suspend` and cancellable; suspend functions move blocking work to
  `Dispatchers.IO`. The CLI shares one `HttpDownloader` (closed via Clikt's `registerCloseable`)
  and runs commands in `runBlocking`.
- Logging: `core` uses only the kotlin-logging facade (`private val logger = KotlinLogging.logger {}`).
  Frontends bring Logback and log to a file only, never the console (CLI: `Logging.setup`, rotation by
  size at startup via `LogRotation`). kotlin-logging prints a startup line on stdout unless
  `-Dkotlin-logging.logStartupMessage=false` is set, so keep that JVM argument. Never log secrets (the
  mod.io API key) and keep library loggers (Ktor) at INFO. `SHUNTER_LOG_LEVEL=debug` raises our own.
  URLs end up in logs and error messages, Ktor's timeout exceptions included, so secrets must not be
  part of a URL (no API key as query parameter).

## Working in the ai-sandbox

The host has no global gradle; the wrapper (Gradle 9.8.0, checksum-pinned) is committed.
A built CLI can be run in the container for smoke tests with a fake home:
`JAVA_OPTS=-Duser.home=<fake> XDG_DATA_HOME=… cli/build/install/shunter/bin/shunter --mods-dir <tmp> …`
