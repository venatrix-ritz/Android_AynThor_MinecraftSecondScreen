# ThorSecondScreen Application Spec

What this repository ships, what it accepts and sends, and the rules it must obey. Written from a read of the source tree at commit `372351b` (origin `master`) on 2026-10-22. Nothing in this spec was built, run, tested or deployed. Every statement is anchored to `file:line` or marked **unverified**.

The companion [design spec](../design-spec/README.md) explains how each part works.

## Scope

The repository contains four deliverables that talk to one Minecraft mod:

- The Android app (Kotlin, Jetpack Compose) that draws the HUD on the AYN Thor's second display.
- A Linux companion (`linux/thor_mc_hud.py`, PyGObject GTK 3 and Cairo) that draws a similar HUD on the same panel under a Linux session.
- A Decky Loader plugin (`decky-plugin/`) that starts and stops the Linux companion from the Steam Game Mode quick menu.
- Developer tools (`tools/`) that stand in for the mod so the app can be driven without Minecraft.

The mod itself is not in this tree. Its behaviour is described here only as far as this repository's clients observe it, and those claims are marked **unverified** where the mod's side is not in the tree.

## Anchor convention

Paths are relative to the repository root `G:\Projects\Hardware\ThorSecondScreen` unless a prefix is used:

| Prefix | Expands to |
|---|---|
| `KT/` | `app/src/main/java/com/exojosh/minecraftsecondscreen/` |
| `RES/` | `app/src/main/res/` |
| `TEST/` | `app/src/test/java/com/exojosh/minecraftsecondscreen/` |
| `ATEST/` | `app/src/androidTest/java/com/exojosh/minecraftsecondscreen/` |

Line numbers refer to commit `372351b`.

## Files

| # | File | Topic |
|---|---|---|
| 01 | [purpose-users-workflows.md](01-purpose-users-workflows.md) | Purpose, the people involved, the day-to-day workflows |
| 02 | [features.md](02-features.md) | Every screen and control the Android app offers, and the Linux companion's equivalents |
| 03 | [devices-platforms.md](03-devices-platforms.md) | Target hardware and platforms for each deliverable |
| 04 | [protocol-reference.md](04-protocol-reference.md) | The loopback socket: transport, every inbound message, every outbound command |
| 05 | [configuration-requirements.md](05-configuration-requirements.md) | Persisted preferences, build configuration, runtime requirements, requirement list |
| 06 | [testing-limitations-glossary.md](06-testing-limitations-glossary.md) | Test inventory (not run), known limitations, documentation drift, terms |

## Plain-language summary

The app shows the parts of Minecraft's HUD that the player wants on the bottom screen: vital signs, a live minimap, the open inventory or chest, chat, and a grid of buttons that press game key bindings. It gets all of its data and all of its textures from the mod over `127.0.0.1:48291`. Anything the player switches off on the bottom screen is handed back to the game, so the HUD is drawn once across the two screens. The app sends clicks and commands back to the mod and draws whatever the game reports; it does not predict the result of its own taps.
