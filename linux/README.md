# Thor Minecraft HUD — Native Linux Companion (Armada OS)

A native Linux companion client for the AYN Thor running Armada OS, replacing the Android-only APK on dual-screen setups.

## How it Works

1. **Transport:** Connects to `127.0.0.1:48291` (the TCP loopback socket opened by the `AynThorSecondScreen` Fabric/Forge mod inside Minecraft).
2. **Display:** Runs natively on the bottom 1080×1240 AMOLED display via Armada's secondary Gamescope session (`/tmp/gamescope-lease.sock`), using `/usr/bin/armada-run-bottom`.
3. **Rendering:** Built with PyGObject (GTK 3) + Cairo vector drawing, providing instant touch response and high-contrast HUD graphics (health, armor, hunger, XP, and hotbar slots).
4. **Touch Input:** Tapping any hotbar slot immediately dispatches `"1"`–`"9"` back over the socket to change slots in-game without touching the top screen.
5. **Quick Actions:** The bottom bar has six configurable hotkeys (defaults: `INV [E]`, `DROP [Q]`, `SWAP [F]`, `SORT`, `MAP [M]`, `PAUSE`). Edit them in the ⚙ HOTKEYS panel; they are saved to `~/.config/thor-mc-hud/hotkeys.json`. Each one sends its command to Minecraft's client command dispatcher.

## Installation on Armada OS

Copy `thor_mc_hud.py` to `~/.local/share/thor-mc-hud/bin/thor_mc_hud.py`:

```bash
mkdir -p ~/.local/share/thor-mc-hud/bin
cp linux/thor_mc_hud.py ~/.local/share/thor-mc-hud/bin/thor_mc_hud.py
chmod +x ~/.local/share/thor-mc-hud/bin/thor_mc_hud.py
```

The LSO thirst overlay is loaded from `~/.local/share/thor-mc-hud/assets/lso_overlay.png`, which the systemd unit does not copy. Copy it too:

```bash
mkdir -p ~/.local/share/thor-mc-hud/assets
cp assets/lso_overlay.png ~/.local/share/thor-mc-hud/assets/lso_overlay.png
```

### Systemd User Service Setup

```bash
mkdir -p ~/.config/systemd/user
cp linux/systemd/thor-mc-hud.service ~/.config/systemd/user/
systemctl --user daemon-reload
# To enable auto-start in Game Mode:
systemctl --user enable thor-mc-hud.service
```

### Running Manually

```bash
/usr/bin/armada-run-bottom -- /usr/bin/python3 ~/.local/share/thor-mc-hud/bin/thor_mc_hud.py
```

Or for offline testing with simulated data:

```bash
/usr/bin/armada-run-bottom -- /usr/bin/python3 ~/.local/share/thor-mc-hud/bin/thor_mc_hud.py --demo
```
