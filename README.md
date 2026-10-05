# Shunter

An open-source mod manager for **Transport Fever 3 on Linux** (native, no Proton).

It installs and updates mods from [transportfever.net](https://www.transportfever.net) and
[modwerkstatt.com](https://modwerkstatt.com), and shows mods from the in-game Mod Hub (mod.io)
and your own staging area next to them.

> Status: early. There is a command-line tool; a desktop UI (Compose) comes next.

## Usage

```sh
./gradlew :cli:installDist
alias shunter=$PWD/cli/build/install/shunter/bin/shunter

shunter paths                 # which folders are used
shunter list                  # installed mods (local, staging, mod.io)
shunter search [text]         # mods available on the sites
shunter install tfnet:8126    # install from transportfever.net (or just: 8126)
shunter install ./mod.7z      # install a downloaded archive
shunter updates               # newer versions of mods installed with Shunter
shunter update --all
shunter remove <folder>       # moves the mod to the backups
```

Try things out without touching the game with `--mods-dir /some/test/folder`.

What Shunter did, and the details of errors, is logged to `~/.local/state/shunter/logs/cli.log`
(attach it to bug reports). Run with `SHUNTER_LOG_LEVEL=debug` for more detail.

## Where things are

| What                                   | Folder                                                          |
|----------------------------------------|-----------------------------------------------------------------|
| Mods installed by you or Shunter       | `~/.steam/steam/userdata/<steamId>/3493540/local/mods`          |
| Your own mods (development, uploads)   | `…/3493540/local/staging_area` (read only for Shunter)          |
| mod.io / Mod Hub subscriptions         | `~/mod.io/common/10640/mods` (read only, the game manages them) |
| Shunter data: install records, backups | `~/.local/share/shunter`                                        |
| Shunter cache: downloads, mod lists    | `~/.cache/shunter`                                              |
| Shunter logs                           | `~/.local/state/shunter/logs`                                   |

## Safety

Mods are downloaded from the internet and unpacked into your game folders, so Shunter is careful:

- Downloads are checked against the size and SHA-256 checksum the site publishes.
- Archive entries that would land outside the target folder (`../`, absolute paths) or are
  symlinks are refused.
- Installing over an existing mod moves the old version to the backups first (the last two are
  kept); if the install fails, the old version is put back.
- Only `local/mods` is ever written. Nothing of Shunter's own is stored in the game folders.

## Mod sources

| Site               | Feed                          | Notes                                                |
|--------------------|-------------------------------|------------------------------------------------------|
| transportfever.net | `filebase/repos/tpf3-v3.json` | `.zip` and `.7z`, with checksums and dependencies    |
| modwerkstatt.com   | `tpfmm/`                      | lists only TPF2 mods so far                          |
| mod.io             | REST API                      | Planned; subscribed mods are already shown by `list` |

## Development

Kotlin/JVM 21+, Gradle. Modules: `core` (everything except the UI, no UI dependencies), `cli`.

```sh
./gradlew build
./gradlew spotlessApply   # format the code (ktlint)
```

The code is formatted with ktlint (IntelliJ IDEA style, settings in `.editorconfig`), run through
Spotless. `build` fails on unformatted code.

The tests use generated mods and archives only. Real mods are copyrighted by their authors and
must not be committed; keep local samples in `.ref/` (ignored by git).

### Commit messages

Commit subjects follow:

```
<issue> <modifier> <[area]> <subject>
```

where **issue** and **area** are optional — for example
`#2 ✨ [ui] Add a feature`.

- **issue** – `#2`.
- **modifier** – a gitmoji describing the kind of change. Write the emoji
  directly, or use the one-letter shorthand below; the local `commit-msg` hook
  rewrites the shorthand to the emoji on commit:

  | shorthand | emoji | meaning                         |
  |-----------|-------|---------------------------------|
  | `+`       | ✨    | new feature                     |
  | `!`       | 🚑    | bug fix                         |
  | `-`       | 🔥    | remove code                     |
  | `r`       | 🔨    | refactor (no behavior change)   |
  | `c`       | 📖    | documentation only              |
  | `t`       | 🚨    | tests                           |
  | `v`       | ⬆️    | upgrade dependencies / versions |
  | `b`       | 💚    | CI / build                      |
  | `i`       | 🎉    | initial / project setup         |

- **area** – the affected scope in brackets, e.g. `[ui]`, `[service]`,
  `[common]`.

## License

[MIT](LICENSE)
