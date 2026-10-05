# Shunter: notes for AI agents and contributors

Open-source (MIT) mod manager for **Transport Fever 3 on Linux**. TF3 runs **natively** on Linux
(no Proton). Kotlin/JVM 21+, Gradle. Package `ch.lightspots.shunter`, command `shunter`.
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
  - mod.io API not connected yet (game id 10640, `https://g-10640.modapi.io/v1`, needs a user
    API key from mod.io/me/access). Subscribed mods are already shown by `list`.
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

## Working in the ai-sandbox

The host has no global gradle; the wrapper (Gradle 9.8.0, checksum-pinned) is committed.
A built CLI can be run in the container for smoke tests with a fake home:
`JAVA_OPTS=-Duser.home=<fake> XDG_DATA_HOME=… cli/build/install/shunter/bin/shunter --mods-dir <tmp> …`
