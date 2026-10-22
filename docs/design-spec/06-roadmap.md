# 06 - Roadmap

Open items found while reading the source and docs, ordered by how much they affect the player or the maintainer. Nothing here is scheduled. Each item names the code that shows the gap. Items marked **unverified** need a device or mod-side check first.

## Correctness

1. **Port-conflict banner on cold start.** A refused connection counts as silent when no hello has been seen, so the banner can appear with the game closed (`KT/net/HudRepository.kt:689-698`, see [application spec 06](../application-spec/06-testing-limitations-glossary.md#port-conflict-banner-on-cold-start)). Count only accepted-but-silent connections, or check the refused case separately. Add a test that drives the connection loop against a fake socket, which none of the current suites do.
2. **Container open key mismatch.** Android reads `screenOpen` (`KT/net/HudRepository.kt:961`); Linux reads `isScreenOpen` (`linux/thor_mc_hud.py:624`, `692`). Confirm which key the mod sends, then make both clients read it and document it in [protocol reference](../application-spec/04-protocol-reference.md#container). **Unverified** which one is correct.
3. **Send ordering.** Each Android `sendCommand` launches its own coroutine (`KT/net/HudRepository.kt:1306`). Multi-line gestures such as distribute can interleave. Route writes through one channel or a single-threaded dispatcher, as the Linux companion already does by flushing one queue. Effect on the mod is **unverified**.
4. **Linux socket writes on a non-blocking socket.** `sendall` after `setblocking(False)` can raise under back-pressure (`linux/thor_mc_hud.py:372`, `388`). Use a blocking send with a timeout, or handle `BlockingIOError` explicitly.
5. **Linux hello and reconnect.** Linux does not read `hello`, does not back off on reconnect, and its icon cache has no bound. Bring its behaviour in line with the Android client or document why it differs.
6. **Linux maxAir.** Breath bubbles assume a maximum of 300 (`linux/thor_mc_hud.py:1795`) and ignore `maxAir` from the mod, which Android uses.

## Dead code and cleanup

7. Remove or wire `HudRepository.requestAssets` and `ASSETS` (`KT/net/HudRepository.kt:793`); remove or wire `isAssetUnavailable` (`:588`).
8. Remove `GameDirectoryAccess`, its persisted key, and the legacy pack parser in `ResourcePackIconProvider` (`KT/net/ResourcePackIconProvider.kt:267-342`), which `CLAUDE.md` already calls dead. The launcher call in `MainActivity` is commented out (`KT/MainActivity.kt:84`).
9. Remove the Material theme and template colours (`KT/ui/theme/`, `RES/values/colors.xml`), or use the theme in `MainActivity`.
10. Replace the two Android example tests with real tests or delete them (`TEST/ExampleUnitTest.kt`, `ATEST/ExampleInstrumentedTest.kt`).
11. Fix the `InventoryMoves` doc comment that lists `THROW`, `SWAP`, `CLONE`, `PICKUP_ALL` as actions (`KT/ui/InventoryMoves.kt:48-51`) or add them.
12. Remove the debug log line on every missing sprite (`KT/net/ResourcePackIconProvider.kt:242`).

## Gaps in features

13. **Crafter cell toggle has no gesture.** `SLOTSTATE:` is implemented on both sides in principle; the Items tab never sends it (`CLAUDE.md`, Known unverified). Choose a gesture, or remove the sender.
14. **No way to close a container from the second display.** `BIND:` is dropped while a screen is open, and the Linux companion sends `CLOSE_CONTAINER`, which the mod may not handle (**unverified**). Needs a mod-side verb; the client side is already expressible.
15. **Loom pattern previews** are names only (`CLAUDE.md`).
16. **Hotbar background texture** is not bundled, so the hotbar draws bordered boxes (`KT/ui/HotbarRow.kt:134-164`). Bundle a texture or accept the fallback.
17. **Off-hand box and map overlap.** The layout keeps them apart by arithmetic and z-index (`KT/SecondScreenApp.kt:245-252`). Add a test or a layout assertion before the map size changes.
18. **Creative search by translated name.** Only ids are searched (`KT/ui/CreativeScreen.kt:71-77`).

## Tooling and repository hygiene

19. **Simulator bind address.** Bind `hud_sim.py` to `127.0.0.1` (`tools/hud_sim.py:1028`), matching the real mod.
20. **Simulator protocol coverage.** `SLOT:`, `RENAME:` and `TRADE:` change nothing in the simulator (`tools/hud_sim.py:792-842`). Model them or document the limit in `tools/README.md`.
21. **`tools/capture_protocol.py` is missing**, though `CLAUDE.md` depends on it for container checks. Restore it or remove the references.
22. **`capture_hud.ps1` stops every `python` process** (`tools/capture_hud.ps1:84`, `122`). Stop only the simulator it started.
23. **`capture_hud.ps1` display id default** is specific to one device. Make it a required argument, as `deploy.sh` already expects an argument; the default is not reproduced in this spec.
24. **`deploy.sh` default host** is a hardcoded LAN address (`decky-plugin/deploy.sh`, line 3). Make the argument required.
25. **Decky frontend source is absent.** Only the built `dist/index.js` is tracked and `package.json` has no build step. Add the source and a build script, or document the bundle as the source.
26. **Decky license.** `package.json` says MIT; the root `LICENSE` and `README.md` say CC0. Pick one.
27. **Decky status poll** opens a TCP connection every 2 s (`decky-plugin/main.py`, `_is_mc_port_open`). Lengthen the interval or read the mod's state another way. Effect on the mod **unverified**.
28. **Documentation drift.** The handoff doc still lists removed files and letter-code commands (`thor-hud-handoff.md`, sections 2.3, 3, 5). The README says "No permissions" and names a different GitHub namespace. See [application spec 06](../application-spec/06-testing-limitations-glossary.md#documentation-and-code-drift) for the full list.
29. **Linux test runner.** The Linux test file runs as a script (`linux/tests/test_icon_requests.py:66-67`) but no command is documented. Add one to `linux/README.md`.
30. **Linux version.** The jar search lists 1.20.1 (`linux/thor_mc_hud.py:89`) while the rest of the docs say 1.21.11. Confirm which version the Linux path is for.

## Verification still needed on device

These depend on the mod and the Thor. None can be closed from the source.

- Container progress art and extras payloads on live containers (`CLAUDE.md`).
- Port-conflict behaviour on a cold start with the game closed (item 1).
- Which open-container key the mod sends (item 2).
- Whether out-of-order writes affect distribute drags (item 3).
- Icon fidelity from the mod's renderer.
