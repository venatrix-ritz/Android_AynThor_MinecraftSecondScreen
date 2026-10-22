# 03 - Devices and platforms

Hardware and software each deliverable is written for. Device facts that this tree cannot verify are attributed to the repository's own documentation and marked **unverified**.

## AYN Thor (target device)

- Dual-screen Android handheld. The repository calls the second display the "bottom screen" and the presentation category display the target (`KT/MainActivity.kt:36-38`, `KT/MainActivity.kt:170-177`).
- Bottom panel resolution used in code comments and the Linux companion: 1080x1240 (`linux/README.md:8`, `linux/thor_mc_hud.py:6`). Canvas size used by the Linux companion: 1240x1080 (`linux/thor_mc_hud.py:56-57`). **Unverified** which orientation the panel is in at runtime; code treats the wider dimension as width (`tools/capture_hud.ps1`, `Get-TabTapPoint`).
- Game runs on the primary screen. The second screen is never the one Minecraft draws to.
- The device runs Armada OS (`linux/README.md:1`, `linux/systemd/thor-mc-hud.service:10`). **Unverified** in this tree.
- The device-side scripts and documentation that describe the hardware in more detail are outside this repository.

## Android app

| Item | Value | Source |
|---|---|---|
| Application id and namespace | `com.exojosh.minecraftsecondscreen` | `app/build.gradle.kts:7`, `15` |
| minSdk | 24 | `app/build.gradle.kts:16` |
| targetSdk | 36 | `app/build.gradle.kts:17` |
| compileSdk | 36, minor 1 | `app/build.gradle.kts:8-11` |
| Version | 1.0 (code 1) | `app/build.gradle.kts:18-19` |
| Launcher activity | `MainActivity`, exported, `adjustResize` | `app/src/main/AndroidManifest.xml:14-25` |
| Permissions | `android.permission.INTERNET` | `app/src/main/AndroidManifest.xml:4` |
| Backup | `allowBackup="true"`; the backup and data-extraction rules are template files with no active rules | `AndroidManifest.xml:6-8`, `RES/xml/backup_rules.xml`, `RES/xml/data_extraction_rules.xml` |
| Activity theme | Translucent host theme | `RES/values/themes.xml` |
| Services, receivers, providers | None declared | `AndroidManifest.xml` |

`INTERNET` is declared although the app only connects to the loopback address. Nothing in the code opens a remote connection (`KT/net/HudRepository.kt:21-22`, `HOST = "127.0.0.1"`).

The `Presentation` (`KT/SecondScreenPresentation.kt`) sets `FLAG_KEEP_SCREEN_ON` (line 41) and `SOFT_INPUT_ADJUST_RESIZE` (line 48). The host activity has no visible content on the primary screen when a second display exists; without one, it renders the full UI on the primary screen as a development fallback (`KT/MainActivity.kt:88-101`).

Build toolchain (documented here, not executed): Gradle 9.5.0 wrapper (`gradle/wrapper/gradle-wrapper.properties`), JDK toolchain 21 (`gradle/gradle-daemon-jvm.properties`), Android Gradle Plugin 9.3.1, Kotlin 2.2.10, Compose BOM 2026.02.01 (`gradle/libs.versions.toml`).

## Linux companion

`linux/thor_mc_hud.py` is a Python 3 program.

| Requirement | Evidence |
|---|---|
| Python 3 with `gi` (PyGObject), GTK 3, Gdk 3, GdkPixbuf 2 | `linux/thor_mc_hud.py:45-50` |
| Python `cairo` module | `linux/thor_mc_hud.py:50` |
| Python standard library only otherwise (`json`, `socket`, `select`, `zipfile`, `base64`, `codecs`) | `linux/thor_mc_hud.py:30-43` |
| GDK backend X11 | `linux/systemd/thor-mc-hud.service:14` (`GDK_BACKEND=x11`) |
| Launched through the Armada bottom-display wrapper | `linux/systemd/thor-mc-hud.service:10`, `linux/README.md:43` |
| Unbuffered output | `linux/systemd/thor-mc-hud.service:13` |

Install steps are in `linux/README.md`. They copy the script and `assets/lso_overlay.png` to user-local paths and install a user unit. The unit:

- `PartOf=` and `After=` the bottom Gamescope service;
- `Conflicts=armada-bottom-screen.service`;
- `Restart=always`, `RestartSec=2`, `StartLimitIntervalSec=0`;
- `WantedBy=gamescope-session-plus@steam.service default.target` (`linux/systemd/thor-mc-hud.service`).

The script searches for textures in fixed locations (`linux/thor_mc_hud.py:88-102`):

- The Minecraft client jar, for `textures/gui/icons.png`, `widgets.png` and `map_background.png`. The search list names version **1.20.1** (`linux/thor_mc_hud.py:89`) with wildcard fallbacks; the Android app and the mod are documented for 1.21.11 (`thor-hud-handoff.md:5`). **Unverified** which version a working setup uses.
- The LSO overlay, from `assets/lso_overlay.png` or a mod jar.

If nothing is found it uses procedural shapes (`linux/thor_mc_hud.py:302`).

### Linux companion versus Android app

| Topic | Android app | Linux companion |
|---|---|---|
| Container-open key read from the mod | `screenOpen` (`KT/net/HudRepository.kt:961`) | `isScreenOpen` (`linux/thor_mc_hud.py:624`, `692`) |
| Reconnect delay | `ReconnectPolicy`: 1.5 s x5, then doubling to 30 s | Fixed 1.5 s (`linux/thor_mc_hud.py:54`, `422`) |
| Hello line | Required to reset counters and clear port conflict | Not read; falls through to the snapshot parser, which sets nothing |
| Icon request timeout / failure back-off | 3 s / 60 s | 5 s / 5 s (`linux/thor_mc_hud.py:124`) |
| Icon cache | LRU bound 200 | Unbounded dict (`linux/thor_mc_hud.py:236`) |
| Messages handled | hello, noplayer, asset, bindings, chat, container, creative, map, icon, snapshot | noplayer, container, map, icon, snapshot (and any other type as snapshot) |
| Snapshot fields used | health, maxHealth, absorption, food, armor, air, xpLevel, xpProgress, selectedSlot, hotbar, offhand, gameMode, heartType, hardcore, maxAir, plus armor/food/saturation extras | health, maxHealth, absorption, food, armor, armorToughness, saturation, exhaustion, air (max assumed 300), xpLevel, xpProgress, selectedSlot, poisoned, withered, hotbar, offhand, gun, survival, target, effects, armorSlots |
| Game mode, creative tab | Yes | No |
| Textures | Served by the mod | Read from the client jar on disk |
| Hotkey bar | Not present (uses a fixed Items/Input UI) | Six configurable buttons |

Which field names the mod actually sends (`screenOpen` or `isScreenOpen`, `armorToughness` and similar) is **unverified** in this tree.

## Decky plugin

Steam Game Mode quick-menu panel, built on Decky Loader.

- `decky-plugin/plugin.json`: name "Minecraft HUD", author Ven, flags `["root"]`, `api_version` 1.
- `decky-plugin/package.json`: name `thor-mc-hud`, version 1.0.0, `"type": "module"`, license **MIT**. The root `LICENSE` file is CC0 1.0 (see [06](06-testing-limitations-glossary.md#documentation-and-code-drift)).
- `decky-plugin/main.py` (Python backend) provides `get_status`, `load_hud`, `unload_hud` and `restart_hud`. It runs `systemctl` as the device user, first with `--machine=armada@.host --user`, then without, with `XDG_RUNTIME_DIR=/run/user/1000` set (`main.py`, `_run_systemctl`).
  - `load_hud` stops `touch-master.service` and `armada-bottom-screen.service`, then starts `thor-mc-hud.service`.
  - `unload_hud` stops `thor-mc-hud.service`, then starts `touch-master.service`, falling back to `armada-bottom-screen.service`.
  - `get_status` reports `active`, `connected` (a TCP probe of `127.0.0.1:48291`, half-second timeout) and a description.
- `decky-plugin/dist/index.js` is the built frontend. It polls `get_status` every 2000 ms while mounted and shows the Load/Unload and Restart buttons (`index.js`, `Content`). **There is no frontend source in the repository**: the bundle is the only copy, and no build script exists in `package.json`.
- `decky-plugin/deploy.sh` copies the four plugin files to a device over SSH and restarts `plugin_loader.service` with `sudo`. It takes a host argument, defaulting to a hardcoded LAN address. The address is intentionally not reproduced here. The script was read but not run.

Every 2 s status poll opens and closes a TCP connection to the mod's port. **Unverified** whether the mod or the Android app treats that as a silent connection (see [06](06-testing-limitations-glossary.md#port-conflict-banner-on-cold-start)).

## Developer tools

| Tool | Platform | Requirements |
|---|---|---|
| `tools/hud_sim.py` | Python 3 standard library | A client jar path as argument 1; scenario name as argument 2 |
| `tools/capture_hud.ps1` | Windows PowerShell with `python` and `adb` on PATH | Client jar; `adb reverse` set up; display id for screencap |

`hud_sim.py` binds `0.0.0.0:48291` (`tools/hud_sim.py:1028`), not loopback, so any host that can reach the machine can connect while it runs. Its README and `tools/README.md` do not mention this. `capture_hud.ps1` stops every running `python` process before and after each scenario (`Get-Process python | Stop-Process -Force`).

`tools/capture_hud.ps1` carries a default SurfaceFlinger display id specific to one device. The value is not reproduced here; `tools/README.md` explains how to find it.

## Mod (out of tree)

The mod is a Fabric mod for Minecraft 1.21.11 per `thor-hud-handoff.md:5` and `CLAUDE.md`. Its source, build and version are not in this repository. Every field and command the mod handles is described in [04](04-protocol-reference.md) only from the clients' side.
