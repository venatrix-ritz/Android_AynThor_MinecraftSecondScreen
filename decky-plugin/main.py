"""
Minecraft HUD Decky Plugin Backend
----------------------------------
Quick loader and unloader for the AYN Thor Minecraft Second Screen companion.
Controls thor-mc-hud.service and restores the bottom screen (trackpad/Ratatoskr) when unloaded.
"""
from __future__ import annotations

import asyncio
import os
import socket
import subprocess
from pathlib import Path

USER_ENV = {
    "XDG_RUNTIME_DIR": "/run/user/1000",
    "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/user/1000/bus",
}

def _run_systemctl(action: str, unit: str | None = None) -> tuple[int, str]:
    tail = [action] + ([unit] if unit else [])
    cmds = [
        ["systemctl", "--machine=armada@.host", "--user", *tail],
        ["systemctl", "--user", *tail],
    ]
    env = {**os.environ, **USER_ENV}
    last_rc, last_out = 1, ""
    for cmd in cmds:
        try:
            res = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=5.0)
            if res.returncode == 0:
                return 0, res.stdout
            last_rc, last_out = res.returncode, (res.stdout + res.stderr)
        except Exception as e:
            last_out = str(e)
    return last_rc, last_out

def _is_service_active(unit: str) -> bool:
    rc, out = _run_systemctl("is-active", unit)
    return rc == 0 and "active" in out

def _is_mc_port_open(port: int = 48291) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except (ConnectionRefusedError, TimeoutError, OSError):
        return False

class Plugin:
    async def get_status(self) -> dict:
        def _check():
            hud_active = _is_service_active("thor-mc-hud.service")
            touch_active = _is_service_active("touch-master.service") or _is_service_active("armada-bottom-screen.service")
            mc_open = _is_mc_port_open(48291)
            
            if hud_active:
                status_desc = "Connected to Minecraft" if mc_open else "Waiting for Minecraft (port 48291)"
            else:
                status_desc = "Bottom screen restored (Trackpad active)" if touch_active else "Stopped"

            return {
                "active": hud_active,
                "connected": mc_open,
                "description": status_desc,
            }
        return await asyncio.to_thread(_check)

    async def load_hud(self) -> dict:
        def _load():
            _run_systemctl("stop", "touch-master.service")
            _run_systemctl("stop", "armada-bottom-screen.service")
            _run_systemctl("start", "thor-mc-hud.service")
            return {"ok": True}
        await asyncio.to_thread(_load)
        return await self.get_status()

    async def unload_hud(self) -> dict:
        def _unload():
            _run_systemctl("stop", "thor-mc-hud.service")
            rc, _ = _run_systemctl("start", "touch-master.service")
            if rc != 0:
                _run_systemctl("start", "armada-bottom-screen.service")
            return {"ok": True}
        await asyncio.to_thread(_unload)
        return await self.get_status()

    async def restart_hud(self) -> dict:
        def _restart():
            _run_systemctl("restart", "thor-mc-hud.service")
            return {"ok": True}
        await asyncio.to_thread(_restart)
        return await self.get_status()
