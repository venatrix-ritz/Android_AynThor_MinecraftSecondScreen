#!/usr/bin/env python3
"""
Thor Minecraft HUD - Native Linux Second-Screen Companion for AYN Thor
----------------------------------------------------------------------
Connects to the AYN Thor Minecraft Second Screen Fabric/Forge mod over local loopback
TCP (127.0.0.1:48291) and renders the actual Minecraft GUI on the Thor's bottom display (1240x1080).

Features:
1. Roaming Mode (1240x1080 optimized):
   - 480x480 Live Minimap with parchment paper background, player position & yaw orientation
   - Target Inspector (The One Probe / Jade style block & mob HUD)
   - TACZ Gun HUD (Firearm name, ammo count, capacity meter, fire mode, reserve ammo)
   - LSO Survival HUD (Hydration/thirst bar, temperature gauge & status)
   - ParCool Stamina bar
   - Inventory HUD+ style equipped armor durability & active potion effect badges
   - AppleSkin food saturation & exhaustion overlay
   - Centered Vanilla vitals (hearts, absorption, armor/toughness, air bubbles, XP bar)
   - 9-slot touch hotbar with authentic textures, counts, and durability meters
   - 6 customizable touch hotkeys with in-app settings modal

2. Container / Inventory Mode:
   - Activates automatically on opening chests, inventories, furnaces, or mod containers
   - Touch slot interaction (Tap pickup/place and Quick-Move mode toggle)
   - Clickable Sorting Mod elements (Quark Sort, Restock, Deposit, master [⚡ SORT INV] & [⚡ SORT CHEST])
   - Carried cursor item badge + world drop control
   - Live vitals & ammo status bar visible while managing containers
   - Bottom hotkeys bar with [CLOSE [E]] to seamlessly close containers
"""

import codecs
import os
import sys
import glob
import json
import socket
import select
import base64
import io
import math
import time
import zipfile
import threading
import argparse

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Gtk, Gdk, GdkPixbuf, GLib
import cairo

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 48291
RECONNECT_INTERVAL = 1.5

DEFAULT_WIDTH = 1240
DEFAULT_HEIGHT = 1080

CONFIG_DIR = os.path.expanduser("~/.config/thor-mc-hud")
HOTKEYS_FILE = os.path.join(CONFIG_DIR, "hotkeys.json")

DEFAULT_HOTKEYS = [
    {"label": "INV [E]", "cmd": "E"},
    {"label": "DROP [Q]", "cmd": "Q"},
    {"label": "SWAP [F]", "cmd": "F"},
    {"label": "SORT", "cmd": "SORT"},
    {"label": "MAP [M]", "cmd": "M"},
    {"label": "PAUSE", "cmd": "ESCAPE"}
]

HOTKEY_PRESETS = [
    ("INV [E]", "E"),
    ("DROP [Q]", "Q"),
    ("SWAP [F]", "F"),
    ("SORT", "SORT"),
    ("CHAT [T]", "T"),
    ("MAP [M]", "M"),
    ("PAUSE [ESC]", "ESCAPE"),
    ("DEBUG [F3]", "F3"),
    ("CAMERA [F5]", "F5"),
    ("SPRINT", "SPRINT"),
    ("SNEAK", "SNEAK"),
    ("JUMP", "JUMP"),
    ("TAB LIST", "TAB"),
    ("QUICK MOVE", "TOGGLE_SHIFT")
]

CLIENT_JAR_SEARCH_PATHS = [
    "/var/home/armada/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries/com/mojang/minecraft/1.20.1/minecraft-1.20.1-client.jar",
    "/var/home/armada/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries/com/mojang/minecraft/*/minecraft-*-client.jar",
    "/var/home/armada/.local/share/PrismLauncher/libraries/com/mojang/minecraft/*/minecraft-*-client.jar",
    "/var/home/armada/.minecraft/versions/*/*.jar",
]

LSO_JAR_SEARCH_PATHS = [
    os.path.expanduser("~/.local/share/thor-mc-hud/assets/lso_overlay.png"),
    "assets/lso_overlay.png",
    "/var/home/armada/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/DeceasedCraft - Urban Zombie Apocalypse/minecraft/mods/legendarysurvivaloverhaul-*.jar",
    "/var/home/armada/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/*/minecraft/mods/legendarysurvivaloverhaul-*.jar",
    "/var/home/armada/.local/share/PrismLauncher/instances/*/minecraft/mods/legendarysurvivaloverhaul-*.jar",
    "X:/ATLauncher/instances/DeceasedCraftBeta/mods/legendarysurvivaloverhaul-*.jar",
]

def load_hotkeys():
    try:
        if os.path.exists(HOTKEYS_FILE):
            with open(HOTKEYS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list) and len(data) == 6:
                    return data
    except Exception as e:
        print(f"[ThorHud] Error loading hotkeys: {e}")
    return list(DEFAULT_HOTKEYS)

def save_hotkeys(hotkeys):
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(HOTKEYS_FILE, "w", encoding="utf-8") as f:
            json.dump(hotkeys, f, indent=2)
    except Exception as e:
        print(f"[ThorHud] Error saving hotkeys: {e}")

class IconRequests:
    def __init__(self, pending_timeout=5.0, failure_backoff=5.0, clock=time.monotonic):
        self._pending_timeout = pending_timeout
        self._failure_backoff = failure_backoff
        self._clock = clock
        self._pending = {}  # item_id -> time the request was sent
        self._failed = {}   # item_id -> time the mod said it had no icon

    def should_request(self, item_id):
        """True if an ICON request for item_id should be sent now. Marks it pending."""
        now = self._clock()
        failed_at = self._failed.get(item_id)
        if failed_at is not None:
            if now - failed_at < self._failure_backoff:
                return False
            del self._failed[item_id]
        sent_at = self._pending.get(item_id)
        if sent_at is not None and now - sent_at < self._pending_timeout:
            return False
        self._pending[item_id] = now
        return True

    def answered(self, item_id):
        """The mod sent icon data for item_id."""
        self._pending.pop(item_id, None)
        self._failed.pop(item_id, None)

    def failed(self, item_id):
        """The mod replied with no icon data for item_id."""
        self._pending.pop(item_id, None)
        self._failed[item_id] = self._clock()

    def reset(self):
        """Forget everything, e.g. after a reconnect, so requests go out again."""
        self._pending.clear()
        self._failed.clear()

class HotbarSlot:
    def __init__(self, item_id="minecraft:air", count=0, damage=0, max_damage=0, has_glint=False):
        self.item_id = item_id
        self.count = count
        self.damage = damage
        self.max_damage = max_damage
        self.has_glint = has_glint

    @property
    def durability_fraction(self):
        if self.max_damage <= 0:
            return None
        return max(0.0, min(1.0, 1.0 - (self.damage / float(self.max_damage))))

class HudState:
    def __init__(self):
        self.health = 20.0
        self.max_health = 20.0
        self.absorption = 0.0
        self.food = 20
        self.armor = 0
        self.armor_toughness = 0.0
        self.saturation = 5.0
        self.exhaustion = 0.0
        self.air = 300
        self.xp_level = 0
        self.xp_progress = 0.0
        self.selected_slot = 0
        self.hotbar = [HotbarSlot() for _ in range(9)]
        self.offhand = HotbarSlot()
        self.game_mode = "SURVIVAL"
        self.poisoned = False
        self.withered = False
        self.freezing = False
        self.chat_lines = []

        # Mod & Extended Elements
        self.gun = None
        self.survival = None
        self.target = None
        self.effects = []
        self.armor_slots = []

def draw_sprite(cr, surface, sx, sy, sw, sh, dx, dy, scale):
    """Draw a sub-rectangle of a surface with nearest-neighbor pixel scaling."""
    if not surface or sw <= 0 or sh <= 0 or scale <= 0:
        return
    cr.save()
    cr.rectangle(dx, dy, sw * scale, sh * scale)
    cr.clip()
    mat = cairo.Matrix()
    mat.translate(sx, sy)
    mat.scale(1.0 / scale, 1.0 / scale)
    mat.translate(-dx, -dy)
    pat = cairo.SurfacePattern(surface)
    pat.set_matrix(mat)
    pat.set_filter(cairo.FILTER_NEAREST)
    cr.set_source(pat)
    cr.paint()
    cr.restore()

class ThorMcHud(Gtk.Window):
    def __init__(self, host=DEFAULT_HOST, port=DEFAULT_PORT, demo_mode=False):
        super().__init__(type=Gtk.WindowType.TOPLEVEL)
        self.set_title("Thor Minecraft Second Screen")
        self.set_default_size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        self.set_decorated(False)
        self.set_resizable(True)

        # Connection and state
        self.host = host
        self.port = port
        self.demo_mode = demo_mode
        self.connected = False
        self.sock = None
        self.state = HudState()
        self.icon_cache = {}  # item_id -> cairo.ImageSurface
        self.icon_requests = IconRequests()
        self.lock = threading.Lock()
        self.send_queue = []

        # Container & Minimap State
        self.current_container = None
        self.map_tile = None
        self.shift_click_mode = False

        # Hotkeys configuration
        self.hotkeys = load_hotkeys()
        self.settings_open = False
        self.selected_hotkey_slot = 0

        # Hit-testing rects
        self.slot_rects = []
        self.action_btns = []
        self.container_slot_rects = []
        self.container_action_btns = []
        self.settings_action_btns = []

        # Texture surfaces
        self.icons_surface = None
        self.widgets_surface = None
        self.map_bg_surface = None
        self.lso_overlay_surface = None
        self.load_minecraft_textures()

        # GTK Drawing Area
        self.darea = Gtk.DrawingArea()
        self.darea.connect("draw", self.on_draw)
        self.darea.set_events(
            Gdk.EventMask.BUTTON_PRESS_MASK
            | Gdk.EventMask.BUTTON_RELEASE_MASK
            | Gdk.EventMask.TOUCH_MASK
        )
        self.darea.connect("button-press-event", self.on_button_press)
        self.darea.connect("touch-event", self.on_touch_event)

        self.add(self.darea)
        self.connect("destroy", self.on_destroy)

        # Background Network Thread
        self.running = True
        if self.demo_mode:
            self.init_demo_state()
            GLib.timeout_add(100, self.update_demo)
        else:
            self.net_thread = threading.Thread(target=self.network_loop, daemon=True)
            self.net_thread.start()

        # 60fps refresh timer
        GLib.timeout_add(33, self.tick_redraw)

    def load_minecraft_textures(self):
        """Locates the Minecraft client jar and extracts icons.png, widgets.png, map_background.png."""
        jar_path = None
        for pattern in CLIENT_JAR_SEARCH_PATHS:
            matches = glob.glob(pattern)
            if matches:
                jar_path = sorted(matches)[-1]
                break

        if not jar_path or not os.path.isfile(jar_path):
            # Keep going: the LSO overlay below does not depend on the client jar.
            print(f"[ThorHud] Client jar not found. Using procedural fallback textures.")
        else:
            try:
                with zipfile.ZipFile(jar_path, 'r') as z:
                    for name in z.namelist():
                        if name.endswith("textures/gui/icons.png"):
                            data = z.read(name)
                            self.icons_surface = cairo.ImageSurface.create_from_png(io.BytesIO(data))
                        elif name.endswith("textures/gui/widgets.png"):
                            data = z.read(name)
                            self.widgets_surface = cairo.ImageSurface.create_from_png(io.BytesIO(data))
                        elif name.endswith("textures/map/map_background.png"):
                            data = z.read(name)
                            self.map_bg_surface = cairo.ImageSurface.create_from_png(io.BytesIO(data))

                print(f"[ThorHud] Loaded textures from: {jar_path}")
            except Exception as e:
                print(f"[ThorHud] Error loading textures from jar: {e}")

        # Locate LSO mod jar or pre-extracted overlay.png for thirst droplets
        for p in LSO_JAR_SEARCH_PATHS:
            expanded = os.path.expanduser(p)
            if os.path.isfile(expanded) and expanded.endswith(".png"):
                try:
                    self.lso_overlay_surface = cairo.ImageSurface.create_from_png(expanded)
                    print(f"[ThorHud] Loaded LSO overlay texture from: {expanded}")
                    break
                except Exception as e:
                    print(f"[ThorHud] Error loading LSO overlay image {expanded}: {e}")
            else:
                matches = glob.glob(expanded)
                if matches:
                    lso_path = sorted(matches)[-1]
                    if os.path.isfile(lso_path):
                        if lso_path.endswith(".png"):
                            try:
                                self.lso_overlay_surface = cairo.ImageSurface.create_from_png(lso_path)
                                print(f"[ThorHud] Loaded LSO overlay texture from: {lso_path}")
                                break
                            except Exception as e:
                                print(f"[ThorHud] Error loading LSO overlay image {lso_path}: {e}")
                        else:
                            try:
                                with zipfile.ZipFile(lso_path, 'r') as z:
                                    for name in z.namelist():
                                        if name.endswith("textures/gui/overlay.png"):
                                            data = z.read(name)
                                            self.lso_overlay_surface = cairo.ImageSurface.create_from_png(io.BytesIO(data))
                                            print(f"[ThorHud] Loaded LSO overlay texture from: {lso_path}")
                                            break
                                if self.lso_overlay_surface:
                                    break
                            except Exception as e:
                                print(f"[ThorHud] Error extracting LSO overlay from {lso_path}: {e}")

    def tick_redraw(self):
        self.darea.queue_draw()
        return self.running

    def send_command(self, cmd):
        with self.lock:
            self.send_queue.append(cmd)

    def network_loop(self):
        while self.running:
            s = None
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(2.0)
                s.connect((self.host, self.port))
                s.setblocking(False)
                self.sock = s
                self.connected = True
                with self.lock:
                    self.icon_requests.reset()
                print(f"[ThorHud] Connected to {self.host}:{self.port}")

                # An incremental decoder keeps a multi-byte UTF-8 character that straddles two recv() chunks.
                decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                recv_buf = ""
                while self.running:
                    # Flush outgoing queue
                    with self.lock:
                        if self.send_queue:
                            out = "\n".join(self.send_queue) + "\n"
                            self.send_queue.clear()
                            s.sendall(out.encode("utf-8"))

                    # Read incoming data
                    r, _, _ = select.select([s], [], [], 0.05)
                    if r:
                        chunk = s.recv(16384)
                        if not chunk:
                            break
                        recv_buf += decoder.decode(chunk)
                        # Scan by index: splitting off the head each time copies the rest of the buffer per line.
                        start = 0
                        while True:
                            nl = recv_buf.find("\n", start)
                            if nl < 0:
                                break
                            line = recv_buf[start:nl].strip()
                            start = nl + 1
                            if line:
                                self.parse_incoming_line(line)
                        recv_buf = recv_buf[start:]

            except Exception:
                pass
            finally:
                # Every exit path (error, server close, shutdown) drops the socket, so the next loop starts clean.
                with self.lock:
                    self.connected = False
                    self.sock = None
                    self.icon_requests.reset()
                if s is not None:
                    s.close()
            # After cleanup, so the HUD does not report "connected" through the back-off. Also covers a clean server close,
            # which used to reconnect in a tight loop.
            if self.running:
                time.sleep(RECONNECT_INTERVAL)

    def request_icon(self, item_id):
        """Queue an ICON: request for item_id unless it is cached or already in flight. Call with self.lock held."""
        if item_id == "minecraft:air" or item_id in self.icon_cache:
            return
        if self.icon_requests.should_request(item_id):
            self.send_queue.append(f"ICON:{item_id}")

    def parse_incoming_line(self, line):
        try:
            msg = json.loads(line)
        except Exception:
            return

        msg_type = msg.get("type")

        # 1. Minimap tile update
        if msg_type == "map":
            data_b64 = msg.get("data")
            if data_b64:
                try:
                    png_bytes = base64.b64decode(data_b64)
                    surface = cairo.ImageSurface.create_from_png(io.BytesIO(png_bytes))
                    with self.lock:
                        self.map_tile = {
                            "surface": surface,
                            "originX": msg.get("originX", 0),
                            "originZ": msg.get("originZ", 0),
                            "playerX": msg.get("playerX", 0.0),
                            "playerZ": msg.get("playerZ", 0.0),
                            "yaw": msg.get("yaw", 0.0),
                            "size": msg.get("size", 128)
                        }
                except Exception as ex:
                    print(f"Error decoding map tile: {ex}")
            return

        # 2. Container menu update
        if msg_type == "container":
            with self.lock:
                self.current_container = msg
                # Prefetch item icons
                for s in msg.get("slots", []):
                    st = s.get("stack", {})
                    iid = st.get("itemId", "minecraft:air")
                    self.request_icon(iid)
                cur = msg.get("cursor", {})
                cur_id = cur.get("itemId", "minecraft:air")
                self.request_icon(cur_id)
            return

        # 3. Player left world
        if msg_type == "noplayer":
            # Drop the last player snapshot too, or the HUD keeps drawing a frozen game.
            with self.lock:
                self.current_container = None
                self.map_tile = None
                self.state = HudState()
            return

        # 4. Item icon response
        if msg_type == "icon":
            item_id = msg.get("itemId")
            data_b64 = msg.get("data")
            if item_id and data_b64:
                try:
                    png_bytes = base64.b64decode(data_b64)
                    surface = cairo.ImageSurface.create_from_png(io.BytesIO(png_bytes))
                except Exception as ex:
                    print(f"Error decoding icon for {item_id}: {ex}")
                    with self.lock:
                        self.icon_requests.failed(item_id)
                    return
                with self.lock:
                    self.icon_cache[item_id] = surface
                    self.icon_requests.answered(item_id)
            elif item_id:
                # The mod has no icon for this item (IconFailure): back off, don't retry every snapshot.
                with self.lock:
                    self.icon_requests.failed(item_id)
            return

        # 5. Standard & Mod HudState snapshot
        with self.lock:
            if "health" in msg:
                self.state.health = float(msg.get("health", 20.0))
            if "maxHealth" in msg:
                self.state.max_health = float(msg.get("maxHealth", 20.0))
            if "absorption" in msg:
                self.state.absorption = float(msg.get("absorption", 0.0))
            if "food" in msg:
                self.state.food = int(msg.get("food", 20))
            if "armor" in msg:
                self.state.armor = int(msg.get("armor", 0))
            if "armorToughness" in msg:
                self.state.armor_toughness = float(msg.get("armorToughness", 0.0))
            if "saturation" in msg:
                self.state.saturation = float(msg.get("saturation", 0.0))
            if "exhaustion" in msg:
                self.state.exhaustion = float(msg.get("exhaustion", 0.0))
            if "air" in msg:
                self.state.air = int(msg.get("air", 300))
            if "xpLevel" in msg:
                self.state.xp_level = int(msg.get("xpLevel", 0))
            if "xpProgress" in msg:
                self.state.xp_progress = float(msg.get("xpProgress", 0.0))
            if "selectedSlot" in msg:
                self.state.selected_slot = int(msg.get("selectedSlot", 0))
            if "poisoned" in msg:
                self.state.poisoned = bool(msg.get("poisoned", False))
            if "withered" in msg:
                self.state.withered = bool(msg.get("withered", False))

            # Mod HUD elements
            if "gun" in msg:
                self.state.gun = msg.get("gun")
            if "survival" in msg:
                self.state.survival = msg.get("survival")
            if "target" in msg:
                self.state.target = msg.get("target")
            if "effects" in msg:
                self.state.effects = msg.get("effects", [])
            if "armorSlots" in msg:
                self.state.armor_slots = msg.get("armorSlots", [])

            if "hotbar" in msg and isinstance(msg["hotbar"], list):
                new_hotbar = []
                for item in msg["hotbar"]:
                    slot = HotbarSlot(
                        item_id=item.get("itemId", "minecraft:air"),
                        count=int(item.get("count", 0)),
                        damage=int(item.get("damage", 0)),
                        max_damage=int(item.get("maxDamage", 0)),
                        has_glint=bool(item.get("hasGlint", False)),
                    )
                    new_hotbar.append(slot)
                    self.request_icon(slot.item_id)
                self.state.hotbar = new_hotbar

            if "offhand" in msg and isinstance(msg["offhand"], dict):
                item = msg["offhand"]
                self.state.offhand = HotbarSlot(
                    item_id=item.get("itemId", "minecraft:air"),
                    count=int(item.get("count", 0)),
                    damage=int(item.get("damage", 0)),
                    max_damage=int(item.get("maxDamage", 0)),
                    has_glint=bool(item.get("hasGlint", False)),
                )
                self.request_icon(self.state.offhand.item_id)

    def on_touch_event(self, widget, event):
        if event.type == Gdk.EventType.TOUCH_BEGIN:
            self.handle_pointer_interaction(event.x, event.y, button=1)
        return True

    def on_button_press(self, widget, event):
        self.handle_pointer_interaction(event.x, event.y, button=event.button)
        return True

    def handle_pointer_interaction(self, x, y, button=1):
        # Debounce duplicate touch / synthesized button events
        now = time.time()
        if hasattr(self, "_last_interaction_time") and hasattr(self, "_last_interaction_pos"):
            lx, ly = self._last_interaction_pos
            if (now - self._last_interaction_time < 0.12) and abs(x - lx) < 10 and abs(y - ly) < 10:
                return
        self._last_interaction_time = now
        self._last_interaction_pos = (x, y)

        # 0. Hotkey Settings Modal Interaction (Intercepts all clicks when open)
        if self.settings_open:
            for bx, by, bw, bh, cmd, val in self.settings_action_btns:
                if bx <= x <= bx + bw and by <= y <= by + bh:
                    if cmd == "CLOSE_SETTINGS":
                        self.settings_open = False
                        save_hotkeys(self.hotkeys)
                    elif cmd == "RESET_SETTINGS":
                        self.hotkeys = list(DEFAULT_HOTKEYS)
                        save_hotkeys(self.hotkeys)
                    elif cmd == "SELECT_SLOT":
                        self.selected_hotkey_slot = int(val)
                    elif cmd == "ASSIGN_PRESET":
                        preset_label, preset_cmd = val
                        self.hotkeys[self.selected_hotkey_slot] = {
                            "label": preset_label,
                            "cmd": preset_cmd
                        }
                        save_hotkeys(self.hotkeys)
                    return
            # Tap outside settings box closes it
            return

        # Check Hotkeys Settings Button in Header (present in both Roaming and Container)
        for bx, by, bw, bh, label, cmd in self.action_btns:
            if cmd == "OPEN_SETTINGS" and bx <= x <= bx + bw and by <= y <= by + bh:
                self.settings_open = True
                return

        # Container Mode Interactions
        is_container_open = False
        with self.lock:
            if self.current_container and self.current_container.get("isScreenOpen"):
                is_container_open = True

        if is_container_open:
            # Check container action buttons & mod widgets
            for bx, by, bw, bh, cmd in self.container_action_btns:
                if bx <= x <= bx + bw and by <= y <= by + bh:
                    if cmd == "OPEN_SETTINGS":
                        self.settings_open = True
                    elif cmd == "TOGGLE_SHIFT":
                        self.shift_click_mode = not self.shift_click_mode
                    elif cmd == "CLOSE" or cmd == "CLOSE_CONTAINER" or cmd == "E":
                        self.send_command("CLOSE_CONTAINER")
                    elif cmd == "DROP_HELD":
                        sync_id = self.current_container.get("syncId", 0)
                        self.send_command(f"SLOT:{sync_id},-999,0,PICKUP")
                    elif cmd.startswith("WIDGET:") or cmd == "SORT":
                        self.send_command(cmd)
                    return

            # Check bottom hotkeys while in container mode (allows closing or sorting from bottom bar)
            for bx, by, bw, bh, label, cmd in self.action_btns:
                if bx <= x <= bx + bw and by <= y <= by + bh:
                    if cmd == "E" or cmd == "ESCAPE" or cmd == "CLOSE":
                        self.send_command("CLOSE_CONTAINER")
                    elif cmd == "TOGGLE_SHIFT":
                        self.shift_click_mode = not self.shift_click_mode
                    else:
                        self.send_command(cmd)
                    return

            # Check container slot clicks
            for rx, ry, rw, rh, slot_idx in self.container_slot_rects:
                if rx <= x <= rx + rw and ry <= y <= ry + rh:
                    sync_id = self.current_container.get("syncId", 0)
                    action = "QUICK_MOVE" if self.shift_click_mode else "PICKUP"
                    btn_val = 0 if button == 1 else 1
                    self.send_command(f"SLOT:{sync_id},{slot_idx},{btn_val},{action}")
                    return
            return

        # Roaming Mode Interactions
        for rx, ry, rw, rh, idx in self.slot_rects:
            if rx <= x <= rx + rw and ry <= y <= ry + rh:
                self.state.selected_slot = idx
                self.send_command(str(idx + 1))
                return

        for bx, by, bw, bh, label, cmd in self.action_btns:
            if bx <= x <= bx + bw and by <= y <= by + bh:
                if cmd == "TOGGLE_SHIFT":
                    self.shift_click_mode = not self.shift_click_mode
                else:
                    self.send_command(cmd)
                return

    def on_draw(self, widget, cr):
        W = widget.get_allocated_width()
        H = widget.get_allocated_height()
        if W <= 1 or H <= 1:
            W, H = DEFAULT_WIDTH, DEFAULT_HEIGHT

        # Dark Minecraft UI canvas
        cr.set_source_rgb(0.08, 0.09, 0.11)
        cr.paint()

        is_container_open = False
        with self.lock:
            if self.current_container and self.current_container.get("isScreenOpen"):
                is_container_open = True

        if is_container_open:
            self.draw_container_screen(cr, W, H)
        else:
            self.draw_roaming_screen(cr, W, H)

        # Draw Settings Modal Overlay if open
        if self.settings_open:
            self.draw_settings_modal(cr, W, H)

    # -------------------------------------------------------------------------
    # CONTAINER SCREEN RENDERING
    # -------------------------------------------------------------------------
    def draw_container_screen(self, cr, W, H):
        self.container_slot_rects = []
        self.container_action_btns = []
        self.action_btns = []

        container = self.current_container or {}
        sync_id = container.get("syncId", 0)
        title = container.get("title") or "INVENTORY"
        slots = container.get("slots", [])
        cursor = container.get("cursor", {})
        cursor_item = cursor.get("itemId", "minecraft:air")
        cursor_count = cursor.get("count", 0)
        widgets = container.get("widgets", [])

        # 1. Top Header & Control Bar
        header_y = 16
        header_h = 54
        margin_x = 36
        header_w = W - 2 * margin_x

        cr.set_source_rgba(0.12, 0.14, 0.18, 0.95)
        cr.rectangle(margin_x, header_y, header_w, header_h)
        cr.fill()
        cr.set_source_rgba(0.28, 0.32, 0.42, 1.0)
        cr.set_line_width(2.0)
        cr.rectangle(margin_x, header_y, header_w, header_h)
        cr.stroke()

        # Title
        cr.set_source_rgb(1.0, 0.85, 0.2)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(20)
        cr.move_to(margin_x + 20, header_y + 35)
        cr.show_text(title.upper())

        # Settings button [⚙ HOTKEYS] in header
        sett_btn_w = 120
        sett_btn_h = 40
        sett_btn_x = margin_x + header_w - 230
        sett_btn_y = header_y + 7
        self.container_action_btns.append((sett_btn_x, sett_btn_y, sett_btn_w, sett_btn_h, "OPEN_SETTINGS"))
        cr.set_source_rgb(0.22, 0.25, 0.32)
        cr.rectangle(sett_btn_x, sett_btn_y, sett_btn_w, sett_btn_h)
        cr.fill()
        cr.set_source_rgb(0.9, 0.92, 0.96)
        cr.set_font_size(13)
        ext = cr.text_extents("⚙ HOTKEYS")
        cr.move_to(sett_btn_x + (sett_btn_w - ext.width) / 2.0, sett_btn_y + 25)
        cr.show_text("⚙ HOTKEYS")

        # Close [X] Button (top right)
        close_btn_w = 90
        close_btn_h = 40
        close_btn_x = margin_x + header_w - close_btn_w - 8
        close_btn_y = header_y + 7
        self.container_action_btns.append((close_btn_x, close_btn_y, close_btn_w, close_btn_h, "CLOSE"))
        cr.set_source_rgb(0.70, 0.18, 0.18)
        cr.rectangle(close_btn_x, close_btn_y, close_btn_w, close_btn_h)
        cr.fill()
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(15)
        ext = cr.text_extents("CLOSE [X]")
        cr.move_to(close_btn_x + (close_btn_w - ext.width) / 2.0, close_btn_y + 25)
        cr.show_text("CLOSE [X]")

        # 2. Sorting & Action Toolbar (below header)
        toolbar_y = 78
        toolbar_h = 48
        cr.set_source_rgba(0.10, 0.12, 0.16, 0.9)
        cr.rectangle(margin_x, toolbar_y, header_w, toolbar_h)
        cr.fill()
        cr.set_source_rgba(0.22, 0.26, 0.34, 0.8)
        cr.set_line_width(1.5)
        cr.rectangle(margin_x, toolbar_y, header_w, toolbar_h)
        cr.stroke()

        tb_cur_x = margin_x + 12

        # Touch Mode Button (Normal vs Shift Quick-Move)
        mode_btn_w = 180
        mode_btn_h = 36
        mode_btn_y = toolbar_y + 6
        self.container_action_btns.append((tb_cur_x, mode_btn_y, mode_btn_w, mode_btn_h, "TOGGLE_SHIFT"))
        if self.shift_click_mode:
            cr.set_source_rgb(0.2, 0.65, 0.3)
        else:
            cr.set_source_rgb(0.24, 0.26, 0.32)
        cr.rectangle(tb_cur_x, mode_btn_y, mode_btn_w, mode_btn_h)
        cr.fill()
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.set_font_size(12)
        mode_lbl = "⚡ QUICK-MOVE: ON" if self.shift_click_mode else "TAP: NORMAL MOVE"
        ext = cr.text_extents(mode_lbl)
        cr.move_to(tb_cur_x + (mode_btn_w - ext.width) / 2.0, mode_btn_y + 23)
        cr.show_text(mode_lbl)
        tb_cur_x += mode_btn_w + 14

        # Master Sort Inventory Button
        sort_btn_w = 120
        self.container_action_btns.append((tb_cur_x, mode_btn_y, sort_btn_w, mode_btn_h, "SORT"))
        cr.set_source_rgb(0.28, 0.42, 0.65)
        cr.rectangle(tb_cur_x, mode_btn_y, sort_btn_w, mode_btn_h)
        cr.fill()
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.set_font_size(12)
        ext = cr.text_extents("⚡ SORT INV")
        cr.move_to(tb_cur_x + (sort_btn_w - ext.width) / 2.0, mode_btn_y + 23)
        cr.show_text("⚡ SORT INV")
        tb_cur_x += sort_btn_w + 14

        # Detected Mod Widgets (Quark / Sophisticated Backpacks / Sorting buttons)
        for w in widgets:
            w_id = w.get("id")
            w_lbl = w.get("label") or "WIDGET"
            if not w_lbl or w_lbl.strip() == "":
                w_lbl = "SORT"
            w_btn_w = 100
            self.container_action_btns.append((tb_cur_x, mode_btn_y, w_btn_w, mode_btn_h, f"WIDGET:{w_id}"))
            cr.set_source_rgb(0.32, 0.36, 0.48)
            cr.rectangle(tb_cur_x, mode_btn_y, w_btn_w, mode_btn_h)
            cr.fill()
            cr.set_source_rgb(0.9, 0.95, 1.0)
            cr.set_font_size(12)
            lbl_text = f"📦 {w_lbl.upper()[:8]}"
            ext = cr.text_extents(lbl_text)
            cr.move_to(tb_cur_x + (w_btn_w - ext.width) / 2.0, mode_btn_y + 23)
            cr.show_text(lbl_text)
            tb_cur_x += w_btn_w + 10

        # Carried Cursor Item Badge
        if cursor_item != "minecraft:air":
            held_w = 240
            held_x = margin_x + header_w - held_w - 95
            cr.set_source_rgba(0.22, 0.16, 0.05, 0.9)
            cr.rectangle(held_x, mode_btn_y, held_w, mode_btn_h)
            cr.fill()
            cr.set_source_rgb(1.0, 0.7, 0.1)
            cr.rectangle(held_x, mode_btn_y, held_w, mode_btn_h)
            cr.stroke()

            cur_short = cursor_item.split(":")[-1].replace("_", " ")[:10]
            cr.set_source_rgb(1.0, 1.0, 1.0)
            cr.set_font_size(12)
            cr.move_to(held_x + 10, mode_btn_y + 23)
            cr.show_text(f"Holding: {cur_short} x{cursor_count}")

            # Drop button
            drop_btn_w = 75
            drop_btn_x = held_x + held_w + 8
            self.container_action_btns.append((drop_btn_x, mode_btn_y, drop_btn_w, mode_btn_h, "DROP_HELD"))
            cr.set_source_rgb(0.55, 0.16, 0.16)
            cr.rectangle(drop_btn_x, mode_btn_y, drop_btn_w, mode_btn_h)
            cr.fill()
            cr.set_source_rgb(1.0, 1.0, 1.0)
            cr.move_to(drop_btn_x + 16, mode_btn_y + 23)
            cr.show_text("DROP")

        # 3. Container Slots Layout (Scaled for touch & balanced vertically)
        player_start = container.get("playerStart", 9)
        hotbar_start = container.get("hotbarStart", 36)
        total_slots = len(slots)

        cell_size = 68
        cell_pitch = 76
        player_w = 9 * cell_pitch
        player_x = (W - player_w) / 2.0

        if player_start > 0:
            c_slots = slots[:player_start]
            c_cols = min(9, len(c_slots))
            c_rows = int(math.ceil(len(c_slots) / float(c_cols))) if c_cols > 0 else 0
            c_w = c_cols * cell_pitch
            c_x = (W - c_w) / 2.0
            c_y = 138

            cr.set_source_rgba(0.12, 0.13, 0.17, 0.85)
            cr.rectangle(c_x - 12, c_y - 10, c_w + 24, (c_rows * cell_pitch) + 20)
            cr.fill()

            for i, slot in enumerate(c_slots):
                col = i % c_cols
                row = i // c_cols
                sx = c_x + col * cell_pitch
                sy = c_y + row * cell_pitch
                self.draw_container_slot(cr, sx, sy, cell_size, slot, i)

            player_y = c_y + (c_rows * cell_pitch) + 30
        else:
            player_y = 410
            self.draw_player_equipment_block(cr, player_x, 138, cell_size, cell_pitch, slots)

        # Player 3x9 Main Inventory
        cr.set_source_rgba(0.12, 0.13, 0.17, 0.85)
        cr.rectangle(player_x - 12, player_y - 10, player_w + 24, (4 * cell_pitch) + 32)
        cr.fill()

        cr.set_source_rgb(0.7, 0.75, 0.85)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(14)
        cr.move_to(player_x, player_y - 14)
        cr.show_text("INVENTORY")

        main_count = hotbar_start - player_start
        for i in range(main_count):
            slot_idx = player_start + i
            if slot_idx >= total_slots:
                break
            slot = slots[slot_idx]
            col = i % 9
            row = i // 9
            sx = player_x + col * cell_pitch
            sy = player_y + row * cell_pitch
            self.draw_container_slot(cr, sx, sy, cell_size, slot, slot_idx)

        # 1x9 Hotbar
        hotbar_y = player_y + (3 * cell_pitch) + 14
        cr.set_source_rgb(0.7, 0.75, 0.85)
        cr.set_font_size(14)
        cr.move_to(player_x, hotbar_y - 14)
        cr.show_text("HOTBAR")

        hotbar_count = min(9, total_slots - hotbar_start)
        for i in range(hotbar_count):
            slot_idx = hotbar_start + i
            slot = slots[slot_idx]
            sx = player_x + i * cell_pitch
            sy = hotbar_y
            self.draw_container_slot(cr, sx, sy, cell_size, slot, slot_idx)

        # 4. Status & Vitals bar below container (lets player see health while looting)
        vitals_bar_y = hotbar_y + cell_pitch + 18
        self.draw_mini_vitals_bar(cr, player_x, vitals_bar_y, player_w)

        # 5. Bottom Hotkeys Bar (with CLOSE [E] ready to exit container)
        self.draw_quick_actions(cr, W, H, in_container=True)

    def draw_mini_vitals_bar(self, cr, vx, vy, vw):
        cr.set_source_rgba(0.10, 0.12, 0.15, 0.9)
        cr.rectangle(vx - 12, vy, vw + 24, 46)
        cr.fill()
        cr.set_source_rgba(0.24, 0.28, 0.36, 0.8)
        cr.set_line_width(1.5)
        cr.rectangle(vx - 12, vy, vw + 24, 46)
        cr.stroke()

        # Health
        cr.set_source_rgb(0.9, 0.2, 0.25)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(14)
        cr.move_to(vx, vy + 28)
        cr.show_text(f"❤ HP: {self.state.health:.0f}/{self.state.max_health:.0f}")

        # Food & Saturation & Thirst
        cr.set_source_rgb(0.85, 0.55, 0.2)
        cr.move_to(vx + 165, vy + 28)
        th_str = ""
        if self.state.survival and self.state.survival.get("hasThirst"):
            th = self.state.survival.get("thirst", 20.0)
            th_str = f"  💧 {th:.0f}/20"
        cr.show_text(f"🍗 FOOD: {self.state.food}/20  (SAT: {self.state.saturation:.1f}){th_str}")

        # Armor & Toughness
        cr.set_source_rgb(0.3, 0.7, 0.9)
        cr.move_to(vx + 395, vy + 28)
        cr.show_text(f"🛡 ARMOR: {self.state.armor}  (TOUGH: {self.state.armor_toughness:.0f})")

        # Gun Ammo or XP
        if self.state.gun and self.state.gun.get("active"):
            g = self.state.gun
            cr.set_source_rgb(1.0, 0.8, 0.2)
            cr.move_to(vx + 550, vy + 28)
            cr.show_text(f"🔫 {g.get('gunName', 'GUN')}: {g.get('ammo')}/{g.get('maxAmmo')}")
        else:
            cr.set_source_rgb(0.4, 0.9, 0.3)
            cr.move_to(vx + 550, vy + 28)
            cr.show_text(f"⭐ LEVEL: {self.state.xp_level}")

    def draw_player_equipment_block(self, cr, player_x, eq_y, cell_size, cell_pitch, slots):
        cr.set_source_rgba(0.12, 0.13, 0.17, 0.85)
        cr.rectangle(player_x - 12, eq_y - 10, (9 * cell_pitch) + 24, (3 * cell_pitch) + 30)
        cr.fill()

        cr.set_source_rgb(0.7, 0.75, 0.85)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(14)
        cr.move_to(player_x, eq_y - 14)
        cr.show_text("ARMOR & CRAFTING")

        armor_indices = [5, 6, 7, 8]  # Helmet, Chest, Legs, Boots
        for i, a_idx in enumerate(armor_indices):
            if a_idx < len(slots):
                sx = player_x + 20 + i * cell_pitch
                sy = eq_y + 10
                self.draw_container_slot(cr, sx, sy, cell_size, slots[a_idx], a_idx)

        # Offhand slot (slot 45)
        if len(slots) > 45:
            off_x = player_x + 20 + 4 * cell_pitch + 10
            off_y = eq_y + 10
            self.draw_container_slot(cr, off_x, off_y, cell_size, slots[45], 45)
            cr.set_source_rgb(0.6, 0.65, 0.75)
            cr.set_font_size(11)
            cr.move_to(off_x, off_y - 4)
            cr.show_text("OFFHAND")

        # 2x2 Crafting Grid (slots 1, 2, 3, 4) & Result (slot 0)
        craft_x = player_x + 20 + 6 * cell_pitch
        craft_y = eq_y + 10
        craft_indices = [[1, 2], [3, 4]]
        for r in range(2):
            for c in range(2):
                c_idx = craft_indices[r][c]
                if c_idx < len(slots):
                    sx = craft_x + c * (cell_pitch - 14)
                    sy = craft_y + r * (cell_pitch - 14)
                    self.draw_container_slot(cr, sx, sy, cell_size - 12, slots[c_idx], c_idx)

        # Arrow & Result (slot 0)
        cr.set_source_rgb(0.6, 0.65, 0.75)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(18)
        cr.move_to(craft_x + 2 * (cell_pitch - 14) + 8, craft_y + 36)
        cr.show_text("->")

        if len(slots) > 0:
            res_x = craft_x + 2 * (cell_pitch - 14) + 40
            res_y = craft_y + 15
            self.draw_container_slot(cr, res_x, res_y, cell_size, slots[0], 0)

    def draw_container_slot(self, cr, sx, sy, size, slot_data, slot_idx):
        self.container_slot_rects.append((sx, sy, size, size, slot_idx))

        # Minecraft slot bevel box
        cr.set_source_rgb(0.22, 0.22, 0.22)
        cr.rectangle(sx, sy, size, size)
        cr.fill()

        # Top/Left shadow
        cr.set_source_rgb(0.14, 0.14, 0.14)
        cr.rectangle(sx, sy, size, 3)
        cr.fill()
        cr.rectangle(sx, sy, 3, size)
        cr.fill()

        # Bottom/Right highlight
        cr.set_source_rgb(0.35, 0.35, 0.35)
        cr.rectangle(sx, sy + size - 3, size, 3)
        cr.fill()
        cr.rectangle(sx + size - 3, sy, 3, size)
        cr.fill()

        # Draw Item if present
        stack = slot_data.get("stack", {})
        item_id = stack.get("itemId", "minecraft:air")
        count = stack.get("count", 0)
        damage = stack.get("damage", 0)
        max_damage = stack.get("maxDamage", 0)

        if item_id != "minecraft:air":
            inner_pad = 6
            item_draw_size = size - (inner_pad * 2)
            ix = sx + inner_pad
            iy = sy + inner_pad

            if item_id in self.icon_cache:
                surf = self.icon_cache[item_id]
                draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                            ix, iy, float(item_draw_size) / surf.get_width())
            else:
                short_name = item_id.split(":")[-1].replace("_", " ")[:6]
                cr.set_source_rgb(0.85, 0.85, 0.85)
                cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
                cr.set_font_size(11)
                cr.move_to(ix + 2, iy + item_draw_size / 2)
                cr.show_text(short_name)

            # Stack count numeral
            if count > 1:
                count_str = str(count)
                cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
                cr.set_font_size(16)
                ext = cr.text_extents(count_str)
                tx = sx + size - ext.width - 4
                ty = sy + size - 4
                cr.set_source_rgb(0.0, 0.0, 0.0)
                cr.move_to(tx + 2, ty + 2)
                cr.show_text(count_str)
                cr.set_source_rgb(1.0, 1.0, 1.0)
                cr.move_to(tx, ty)
                cr.show_text(count_str)

            # Durability bar
            if max_damage > 0:
                dur = max(0.0, min(1.0, 1.0 - (damage / float(max_damage))))
                bar_w = size - 14
                bar_h = 4
                bx = sx + 7
                by = sy + size - 8
                cr.set_source_rgb(0.0, 0.0, 0.0)
                cr.rectangle(bx, by, bar_w, bar_h)
                cr.fill()
                cr.set_source_rgb(1.0 - dur, dur, 0.1)
                cr.rectangle(bx, by, bar_w * dur, bar_h)
                cr.fill()

    # -------------------------------------------------------------------------
    # ROAMING SCREEN RENDERING (Proportionally filled 1240x1080)
    # -------------------------------------------------------------------------
    def draw_roaming_screen(self, cr, W, H):
        self.action_btns = []
        self.slot_rects = []

        # 1. Header with Status & Settings
        self.draw_header(cr, W, H)

        # 2. Target Inspector Banner (TOP / WAILA)
        self.draw_target_inspector(cr, W, H)

        # 3. Live 480x480 Minimap Display
        self.draw_minimap(cr, W, H)

        # 4. Left & Right Mod Sidebars
        self.draw_left_sidebar_mods(cr, W, H)
        self.draw_right_sidebar_mods(cr, W, H)

        # 5. Centered Vitals, XP & Hotbar
        scale = 4.3
        hotbar_w = 182 * scale
        center_x = (W - hotbar_w) / 2.0

        hotbar_y = 860
        xp_y = hotbar_y - (5 * scale) - 16
        vitals_y = xp_y - (9 * scale) - 22

        self.draw_vitals(cr, center_x, vitals_y, xp_y, scale)
        self.draw_hotbar(cr, center_x, hotbar_y, scale)

        # 6. Customizable Hotkeys Bar at Bottom
        self.draw_quick_actions(cr, W, H, in_container=False)

    def draw_header(self, cr, W, H):
        header_h = 54
        margin_x = 36
        header_w = W - 2 * margin_x

        cr.set_source_rgba(0.12, 0.14, 0.18, 0.95)
        cr.rectangle(margin_x, 16, header_w, header_h)
        cr.fill()

        cr.set_source_rgba(0.24, 0.28, 0.36, 1.0)
        cr.set_line_width(2.0)
        cr.rectangle(margin_x, 16, header_w, header_h)
        cr.stroke()

        cr.set_source_rgb(0.92, 0.94, 0.98)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(19)
        cr.move_to(margin_x + 20, 50)
        cr.show_text("THOR MINECRAFT COMPANION")

        status_text = "CONNECTED" if self.connected else ("DEMO MODE" if self.demo_mode else "WAITING FOR GAME")
        dot_color = (0.2, 0.9, 0.3) if self.connected else ((0.3, 0.7, 1.0) if self.demo_mode else (1.0, 0.4, 0.2))

        # Status indicator
        dot_x = margin_x + header_w - 380
        cr.set_source_rgb(*dot_color)
        cr.arc(dot_x, 43, 6, 0, 2 * math.pi)
        cr.fill()

        cr.set_source_rgb(0.75, 0.82, 0.9)
        cr.set_font_size(14)
        cr.move_to(dot_x + 16, 48)
        cr.show_text(status_text)

        # Settings [⚙ HOTKEYS] button
        sett_btn_w = 130
        sett_btn_h = 38
        sett_btn_x = margin_x + header_w - sett_btn_w - 10
        sett_btn_y = 24
        self.action_btns.append((sett_btn_x, sett_btn_y, sett_btn_w, sett_btn_h, "⚙ HOTKEYS", "OPEN_SETTINGS"))
        cr.set_source_rgb(0.22, 0.25, 0.32)
        cr.rectangle(sett_btn_x, sett_btn_y, sett_btn_w, sett_btn_h)
        cr.fill()
        cr.set_source_rgb(0.9, 0.92, 0.96)
        cr.set_font_size(13)
        ext = cr.text_extents("⚙ HOTKEYS")
        cr.move_to(sett_btn_x + (sett_btn_w - ext.width) / 2.0, sett_btn_y + 24)
        cr.show_text("⚙ HOTKEYS")

    def draw_target_inspector(self, cr, W, H):
        tgt = self.state.target
        if not tgt or tgt.get("type") == "NONE" or not tgt.get("name"):
            return

        tgt_w = 480
        tgt_h = 34
        tgt_x = (W - tgt_w) / 2.0
        tgt_y = 80

        cr.set_source_rgba(0.10, 0.12, 0.15, 0.9)
        cr.rectangle(tgt_x, tgt_y, tgt_w, tgt_h)
        cr.fill()
        cr.set_source_rgba(0.25, 0.30, 0.40, 0.8)
        cr.set_line_width(1.5)
        cr.rectangle(tgt_x, tgt_y, tgt_w, tgt_h)
        cr.stroke()

        name = tgt.get("name", "")
        mod = tgt.get("mod", "")
        hp = tgt.get("health", 0.0)
        max_hp = tgt.get("maxHealth", 0.0)

        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(13)
        if tgt.get("type") == "ENTITY":
            cr.set_source_rgb(1.0, 0.35, 0.35)
            cr.move_to(tgt_x + 12, tgt_y + 22)
            cr.show_text(f"⚔ {name}")
            if max_hp > 0:
                cr.set_source_rgb(0.9, 0.9, 0.9)
                hp_str = f"❤ {hp:.0f} / {max_hp:.0f}"
                ext = cr.text_extents(hp_str)
                cr.move_to(tgt_x + tgt_w - ext.width - 12, tgt_y + 22)
                cr.show_text(hp_str)
        else:
            cr.set_source_rgb(0.3, 0.85, 1.0)
            cr.move_to(tgt_x + 12, tgt_y + 22)
            cr.show_text(f"🧱 {name}")
            if mod:
                cr.set_source_rgb(0.6, 0.65, 0.75)
                ext = cr.text_extents(f"({mod})")
                cr.move_to(tgt_x + tgt_w - ext.width - 12, tgt_y + 22)
                cr.show_text(f"({mod})")

    def draw_minimap(self, cr, W, H):
        map_tile = self.map_tile
        sheet_size = 480
        sheet_x = (W - sheet_size) / 2.0
        sheet_y = 124
        border = 20
        tile_draw_size = sheet_size - 2 * border

        # Paper Parchment Background
        if self.map_bg_surface:
            surf = self.map_bg_surface
            draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                        sheet_x, sheet_y, float(sheet_size) / surf.get_width())
        else:
            cr.set_source_rgb(0.84, 0.75, 0.59)
            cr.rectangle(sheet_x, sheet_y, sheet_size, sheet_size)
            cr.fill()
            cr.set_source_rgb(0.45, 0.38, 0.28)
            cr.set_line_width(4.0)
            cr.rectangle(sheet_x, sheet_y, sheet_size, sheet_size)
            cr.stroke()

        if not map_tile or not map_tile.get("surface"):
            cr.set_source_rgb(0.35, 0.32, 0.25)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(18)
            msg = "JOIN WORLD TO ACTIVATE MINIMAP" if self.connected else "MINIMAP WAITING FOR GAME"
            ext = cr.text_extents(msg)
            cr.move_to(sheet_x + (sheet_size - ext.width) / 2.0, sheet_y + sheet_size / 2.0)
            cr.show_text(msg)
            return

        # Draw 128x128 map tile with nearest-neighbor scaling
        tile_surf = map_tile["surface"]
        tile_scale = float(tile_draw_size) / map_tile["size"]
        draw_sprite(cr, tile_surf, 0, 0, map_tile["size"], map_tile["size"],
                    sheet_x + border, sheet_y + border, tile_scale)

        # Player Marker
        origin_x = map_tile.get("originX", 0)
        origin_z = map_tile.get("originZ", 0)
        player_x = map_tile.get("playerX", 0.0)
        player_z = map_tile.get("playerZ", 0.0)
        yaw = map_tile.get("yaw", 0.0)

        marker_px = (player_x - origin_x) * tile_scale
        marker_pz = (player_z - origin_z) * tile_scale
        mx = sheet_x + border + marker_px
        my = sheet_y + border + marker_pz

        cr.save()
        cr.translate(mx, my)
        cr.rotate(math.radians(yaw + 180.0))

        r = 13.0
        cr.move_to(0, -r)
        cr.line_to(r * 0.65, r * 0.75)
        cr.line_to(0, r * 0.35)
        cr.line_to(-r * 0.65, r * 0.75)
        cr.close_path()

        cr.set_source_rgb(0.0, 0.0, 0.0)
        cr.set_line_width(4.0)
        cr.stroke_preserve()
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.fill()
        cr.restore()

        # Coordinates label
        coord_str = f"X: {player_x:.1f}   Z: {player_z:.1f}"
        cr.set_source_rgb(0.9, 0.95, 1.0)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(15)
        ext = cr.text_extents(coord_str)
        cr.move_to(sheet_x + (sheet_size - ext.width) / 2.0, sheet_y + sheet_size + 24)
        cr.show_text(coord_str)

    def draw_left_sidebar_mods(self, cr, W, H):
        """Draws Equipped Armor Durability & Active Potion Effects on the left side of the minimap."""
        box_x = 36
        box_w = 320
        start_y = 124

        # 1. Equipped Armor Status (Inventory HUD+ style)
        if self.state.armor_slots:
            cr.set_source_rgba(0.10, 0.12, 0.16, 0.85)
            cr.rectangle(box_x, start_y, box_w, 175)
            cr.fill()
            cr.set_source_rgba(0.24, 0.28, 0.36, 0.8)
            cr.set_line_width(1.5)
            cr.rectangle(box_x, start_y, box_w, 175)
            cr.stroke()

            cr.set_source_rgb(0.7, 0.75, 0.85)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(13)
            cr.move_to(box_x + 12, start_y + 22)
            cr.show_text("EQUIPPED ARMOR")

            # 4 armor pieces (HEAD, CHEST, LEGS, FEET)
            for i, a in enumerate(reversed(self.state.armor_slots)):
                row_y = start_y + 36 + i * 32
                slot_name = a.get("slot", "")
                item_id = a.get("itemId", "minecraft:air")
                dmg = a.get("damage", 0)
                max_d = a.get("maxDamage", 0)

                cr.set_source_rgb(0.6, 0.65, 0.75)
                cr.set_font_size(11)
                cr.move_to(box_x + 12, row_y + 16)
                cr.show_text(f"{slot_name[:4]}:")

                if item_id != "minecraft:air":
                    short_id = item_id.split(":")[-1].replace("_", " ")[:14]
                    cr.set_source_rgb(0.9, 0.92, 0.96)
                    cr.move_to(box_x + 60, row_y + 16)
                    cr.show_text(short_id)

                    if max_d > 0:
                        dur = max(0.0, min(1.0, 1.0 - (dmg / float(max_d))))
                        bar_w = 100
                        bar_h = 8
                        bar_x = box_x + box_w - bar_w - 12
                        bar_y = row_y + 7
                        cr.set_source_rgb(0.05, 0.05, 0.05)
                        cr.rectangle(bar_x, bar_y, bar_w, bar_h)
                        cr.fill()
                        cr.set_source_rgb(1.0 - dur, dur, 0.1)
                        cr.rectangle(bar_x, bar_y, bar_w * dur, bar_h)
                        cr.fill()
                else:
                    cr.set_source_rgb(0.4, 0.42, 0.48)
                    cr.move_to(box_x + 60, row_y + 16)
                    cr.show_text("EMPTY")

        # 2. Active Potion Effects (below armor status)
        eff_y = start_y + 195
        if self.state.effects:
            eff_h = min(280, 30 + len(self.state.effects) * 30)
            cr.set_source_rgba(0.10, 0.12, 0.16, 0.85)
            cr.rectangle(box_x, eff_y, box_w, eff_h)
            cr.fill()
            cr.set_source_rgba(0.24, 0.28, 0.36, 0.8)
            cr.set_line_width(1.5)
            cr.rectangle(box_x, eff_y, box_w, eff_h)
            cr.stroke()

            cr.set_source_rgb(0.7, 0.75, 0.85)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(13)
            cr.move_to(box_x + 12, eff_y + 22)
            cr.show_text("ACTIVE EFFECTS")

            for i, eff in enumerate(self.state.effects[:8]):
                row_y = eff_y + 36 + i * 28
                name = eff.get("name", "")
                dur_ticks = eff.get("durationTicks", 0)
                amp = eff.get("amplifier", 0)
                sec = dur_ticks // 20
                time_str = f"{sec // 60:02d}:{sec % 60:02d}"
                amp_str = f" {amp + 1}" if amp > 0 else ""

                cr.set_source_rgb(0.2, 0.8, 0.9) if eff.get("beneficial", True) else cr.set_source_rgb(0.9, 0.3, 0.3)
                cr.set_font_size(12)
                cr.move_to(box_x + 12, row_y + 14)
                cr.show_text(f"{name}{amp_str}")

                cr.set_source_rgb(0.7, 0.75, 0.85)
                ext = cr.text_extents(time_str)
                cr.move_to(box_x + box_w - ext.width - 12, row_y + 14)
                cr.show_text(time_str)

    def draw_right_sidebar_mods(self, cr, W, H):
        """Draws TACZ Gun Tactical HUD & LSO Survival / ParCool Stamina on the right side of the minimap."""
        box_w = 320
        box_x = W - box_w - 36
        start_y = 124

        # 1. TACZ Gun Tactical HUD
        if self.state.gun and self.state.gun.get("active"):
            g = self.state.gun
            g_h = 160
            cr.set_source_rgba(0.12, 0.14, 0.18, 0.95)
            cr.rectangle(box_x, start_y, box_w, g_h)
            cr.fill()
            cr.set_source_rgba(0.85, 0.65, 0.15, 0.9)
            cr.set_line_width(2.0)
            cr.rectangle(box_x, start_y, box_w, g_h)
            cr.stroke()

            # Gun Name
            cr.set_source_rgb(1.0, 0.85, 0.2)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(15)
            cr.move_to(box_x + 14, start_y + 26)
            cr.show_text(f"🔫 {g.get('gunName', 'FIREARM').upper()}")

            # Fire Mode
            f_mode = g.get("fireMode", "SEMI")
            cr.set_source_rgb(0.3, 0.8, 1.0)
            cr.set_font_size(12)
            ext = cr.text_extents(f_mode)
            cr.move_to(box_x + box_w - ext.width - 14, start_y + 26)
            cr.show_text(f_mode)

            # Ammo Count (Big Numeral)
            ammo = g.get("ammo", 0)
            max_ammo = g.get("maxAmmo", max(1, ammo))
            cr.set_source_rgb(1.0, 1.0, 1.0)
            cr.set_font_size(28)
            cr.move_to(box_x + 14, start_y + 70)
            cr.show_text(f"{ammo} / {max_ammo}")

            # Reserve Ammo
            res = g.get("reserveAmmo", 0)
            cr.set_source_rgb(0.65, 0.72, 0.85)
            cr.set_font_size(13)
            cr.move_to(box_x + 14, start_y + 96)
            cr.show_text(f"RESERVE: {res} ROUNDS")

            # Ammo Fill Gauge
            bar_w = box_w - 28
            bar_h = 14
            bar_x = box_x + 14
            bar_y = start_y + 118
            cr.set_source_rgb(0.06, 0.08, 0.10)
            cr.rectangle(bar_x, bar_y, bar_w, bar_h)
            cr.fill()
            fill_pct = max(0.0, min(1.0, ammo / float(max_ammo))) if max_ammo > 0 else 0
            if fill_pct > 0.3:
                cr.set_source_rgb(0.2, 0.85, 0.3)
            elif fill_pct > 0.15:
                cr.set_source_rgb(0.9, 0.7, 0.1)
            else:
                cr.set_source_rgb(0.9, 0.2, 0.1)
            cr.rectangle(bar_x, bar_y, bar_w * fill_pct, bar_h)
            cr.fill()

            start_y += g_h + 16

        # 2. LSO Survival & ParCool Stamina Box
        surv = self.state.survival
        if surv and (surv.get("hasThirst") or surv.get("hasTemp") or surv.get("hasStamina")):
            s_h = 175
            cr.set_source_rgba(0.10, 0.12, 0.16, 0.85)
            cr.rectangle(box_x, start_y, box_w, s_h)
            cr.fill()
            cr.set_source_rgba(0.24, 0.28, 0.36, 0.8)
            cr.set_line_width(1.5)
            cr.rectangle(box_x, start_y, box_w, s_h)
            cr.stroke()

            cr.set_source_rgb(0.7, 0.75, 0.85)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(13)
            cr.move_to(box_x + 14, start_y + 22)
            cr.show_text("SURVIVAL VITALS")

            row_y = start_y + 36

            # Hydration / Thirst
            if surv.get("hasThirst"):
                th = surv.get("thirst", 20.0)
                cr.set_source_rgb(0.2, 0.65, 1.0)
                cr.set_font_size(12)
                cr.move_to(box_x + 14, row_y + 14)
                cr.show_text(f"💧 THIRST: {th:.1f} / 20.0")

                bar_w = 120
                bar_h = 8
                bar_x = box_x + box_w - bar_w - 14
                bar_y = row_y + 4
                cr.set_source_rgb(0.06, 0.08, 0.10)
                cr.rectangle(bar_x, bar_y, bar_w, bar_h)
                cr.fill()
                cr.set_source_rgb(0.2, 0.65, 1.0)
                cr.rectangle(bar_x, bar_y, bar_w * (th / 20.0), bar_h)
                cr.fill()
                row_y += 34

            # Temperature Gauge
            if surv.get("hasTemp"):
                t_val = surv.get("temperature", 0.0)
                t_status = surv.get("tempStatus", "NORMAL")
                cr.set_source_rgb(1.0, 0.5, 0.2) if "HOT" in t_status else cr.set_source_rgb(0.4, 0.8, 1.0)
                cr.set_font_size(12)
                cr.move_to(box_x + 14, row_y + 14)
                cr.show_text(f"🌡 TEMP: {t_status}")

                badge_w = 90
                badge_h = 20
                badge_x = box_x + box_w - badge_w - 14
                badge_y = row_y - 2
                cr.set_source_rgb(0.18, 0.20, 0.26)
                cr.rectangle(badge_x, badge_y, badge_w, badge_h)
                cr.fill()
                cr.set_source_rgb(0.9, 0.95, 1.0)
                cr.set_font_size(11)
                ext = cr.text_extents(f"{t_val:.1f}°")
                cr.move_to(badge_x + (badge_w - ext.width) / 2.0, badge_y + 14)
                cr.show_text(f"{t_val:.1f}°")
                row_y += 34

            # ParCool Stamina
            if surv.get("hasStamina"):
                st = surv.get("stamina", 0)
                max_st = surv.get("maxStamina", max(1, st))
                cr.set_source_rgb(0.3, 0.9, 0.4)
                cr.set_font_size(12)
                cr.move_to(box_x + 14, row_y + 14)
                cr.show_text(f"⚡ STAMINA: {st}")

                bar_w = 120
                bar_h = 8
                bar_x = box_x + box_w - bar_w - 14
                bar_y = row_y + 4
                cr.set_source_rgb(0.06, 0.08, 0.10)
                cr.rectangle(bar_x, bar_y, bar_w, bar_h)
                cr.fill()
                cr.set_source_rgb(0.3, 0.9, 0.4)
                pct = max(0.0, min(1.0, st / float(max_st))) if max_st > 0 else 0
                cr.rectangle(bar_x, bar_y, bar_w * pct, bar_h)
                cr.fill()

    def draw_procedural_waterdrop(self, cr, tx, ty, scale, val, has_sat):
        """Draws a vector water droplet when LSO overlay texture is not loaded."""
        w = 9 * scale
        h = 9 * scale
        cx = tx + w / 2.0
        cy = ty + h * 0.62
        r = w * 0.38

        def droplet_path():
            cr.new_path()
            cr.move_to(cx, ty + 1.0 * scale)
            cr.curve_to(cx + r * 1.05, ty + h * 0.40, cx + r, cy + r * 0.90, cx, cy + r * 0.90)
            cr.curve_to(cx - r, cy + r * 0.90, cx - r * 1.05, ty + h * 0.40, cx, ty + 1.0 * scale)
            cr.close_path()

        # 1. Background / container outline
        cr.set_source_rgb(0.04, 0.18, 0.28)
        droplet_path()
        cr.fill()

        # 2. Interior fill
        if val >= 2:
            cr.set_source_rgb(0.18, 0.65, 0.95)
            cr.save()
            droplet_path()
            cr.clip()
            cr.paint()
            # Glint
            cr.set_source_rgba(1.0, 1.0, 1.0, 0.6)
            cr.arc(cx - r * 0.3, cy - r * 0.2, 1.2 * scale, 0, 2 * math.pi)
            cr.fill()
            cr.restore()
        elif val == 1:
            cr.save()
            droplet_path()
            cr.clip()
            cr.rectangle(cx, ty, w / 2.0, h)
            cr.set_source_rgb(0.18, 0.65, 0.95)
            cr.fill()
            cr.restore()

        # 3. Saturation border highlight
        if has_sat:
            cr.set_source_rgba(0.2, 0.9, 1.0, 0.8)
            cr.set_line_width(1.5)
            droplet_path()
            cr.stroke()

    def draw_vitals(self, cr, hotbar_x, vitals_y, xp_y, scale):
        icons = self.icons_surface
        hotbar_w = 182 * scale

        # --- Experience Bar ---
        xp_h = 5 * scale
        if icons:
            draw_sprite(cr, icons, 0, 64, 182, 5, hotbar_x, xp_y, scale)
            prog = max(0.0, min(1.0, self.state.xp_progress))
            prog_w = int(182 * prog)
            if prog_w > 0:
                draw_sprite(cr, icons, 0, 69, prog_w, 5, hotbar_x, xp_y, scale)
        else:
            cr.set_source_rgb(0.1, 0.15, 0.1)
            cr.rectangle(hotbar_x, xp_y, hotbar_w, xp_h)
            cr.fill()
            prog = max(0.0, min(1.0, self.state.xp_progress))
            cr.set_source_rgb(0.3, 0.9, 0.2)
            cr.rectangle(hotbar_x, xp_y, hotbar_w * prog, xp_h)
            cr.fill()

        # XP Level Number
        if self.state.xp_level > 0:
            num_str = str(self.state.xp_level)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(24)
            ext = cr.text_extents(num_str)
            tx = hotbar_x + (hotbar_w - ext.width) / 2.0
            ty = xp_y - 8
            cr.set_source_rgb(0.0, 0.0, 0.0)
            for ox, oy in [(-2, 0), (2, 0), (0, -2), (0, 2), (2, 2)]:
                cr.move_to(tx + ox, ty + oy)
                cr.show_text(num_str)
            cr.set_source_rgb(0.5, 1.0, 0.15)
            cr.move_to(tx, ty)
            cr.show_text(num_str)

        # --- Hearts Row ---
        hearts_x = hotbar_x
        health = self.state.health
        step = 8 * scale

        for i in range(10):
            hx = hearts_x + i * step
            hy = vitals_y
            val = health - (i * 2)

            if icons:
                draw_sprite(cr, icons, 16, 0, 9, 9, hx, hy, scale)
                if self.state.withered:
                    sx = 124 if val >= 2 else 133
                elif self.state.poisoned:
                    sx = 88 if val >= 2 else 97
                else:
                    sx = 52 if val >= 2 else 61

                if val >= 1:
                    draw_sprite(cr, icons, sx, 0, 9, 9, hx, hy, scale)
            else:
                color = (0.9, 0.15, 0.2)
                cr.set_source_rgb(0.2, 0.05, 0.05)
                cr.arc(hx + 4 * scale, hy + 4 * scale, 4 * scale, 0, 2 * math.pi)
                cr.fill()
                if val >= 1:
                    cr.set_source_rgb(*color)
                    cr.arc(hx + 4 * scale, hy + 4 * scale, 3 * scale, 0, 2 * math.pi)
                    cr.fill()

        # Absorption Hearts
        if self.state.absorption > 0:
            for i in range(10):
                hx = hearts_x + i * step
                hy = vitals_y - (10 * scale)
                val = self.state.absorption - (i * 2)
                if val >= 1 and icons:
                    draw_sprite(cr, icons, 16, 0, 9, 9, hx, hy, scale)
                    abs_sx = 160 if val >= 2 else 169
                    draw_sprite(cr, icons, abs_sx, 0, 9, 9, hx, hy, scale)

        # Armor & Toughness Row
        armor_pts = self.state.armor
        if armor_pts > 0:
            armor_y = vitals_y - (10 * scale) if self.state.absorption <= 0 else vitals_y - (20 * scale)
            for i in range(10):
                ax = hearts_x + i * step
                val = armor_pts - (i * 2)
                if icons:
                    if val >= 2:
                        draw_sprite(cr, icons, 34, 9, 9, 9, ax, armor_y, scale)
                    elif val == 1:
                        draw_sprite(cr, icons, 25, 9, 9, 9, ax, armor_y, scale)
                    elif armor_pts > 0:
                        draw_sprite(cr, icons, 16, 9, 9, 9, ax, armor_y, scale)

        # --- Hunger / Food Row (with AppleSkin saturation outline) ---
        food_right = hotbar_x + hotbar_w
        food_pts = self.state.food

        for i in range(10):
            fx = food_right - (9 * scale) - i * step
            fy = vitals_y
            val = food_pts - (i * 2)

            if icons:
                draw_sprite(cr, icons, 16, 27, 9, 9, fx, fy, scale)
                if val >= 2:
                    draw_sprite(cr, icons, 52, 27, 9, 9, fx, fy, scale)
                elif val == 1:
                    draw_sprite(cr, icons, 61, 27, 9, 9, fx, fy, scale)

                # AppleSkin saturation glow
                if self.state.saturation > (i * 2):
                    cr.set_source_rgba(1.0, 0.8, 0.2, 0.4)
                    cr.rectangle(fx - 1, fy - 1, 9 * scale + 2, 9 * scale + 2)
                    cr.stroke()
            else:
                cr.set_source_rgb(0.25, 0.15, 0.05)
                cr.arc(fx + 4 * scale, fy + 4 * scale, 4 * scale, 0, 2 * math.pi)
                cr.fill()
                if val >= 1:
                    cr.set_source_rgb(0.85, 0.45, 0.15)
                    cr.arc(fx + 4 * scale, fy + 4 * scale, 3 * scale, 0, 2 * math.pi)
                    cr.fill()

        # --- Legendary Survival Overhaul (LSO) Hydration / Thirst Row ---
        # 10 water droplets appear directly above hunger meter at vitals_y - 10 * scale
        surv = self.state.survival
        has_thirst = bool(surv and surv.get("hasThirst"))
        if has_thirst:
            thirst_pts = float(surv.get("thirst", 20.0))
            thirst_sat = float(surv.get("thirstSat", surv.get("thirstSaturation", 0.0)))
            thirst_y = vitals_y - (10 * scale)

            # Active effects determine droplet variant
            has_thirst_eff = any("thirst" in e.get("id", "").lower() and "heat" not in e.get("id", "").lower() for e in self.state.effects)
            has_heat_thirst = any("heat_thirst" in e.get("id", "").lower() for e in self.state.effects)

            if has_thirst_eff and has_heat_thirst:
                x_eff_base = 27
                y_eff_base = 9
                sat_eff_base = 126
            elif has_thirst_eff:
                x_eff_base = 27
                y_eff_base = 0
                sat_eff_base = 72
            elif has_heat_thirst:
                x_eff_base = 0
                y_eff_base = 9
                sat_eff_base = 72
            else:
                x_eff_base = 0
                y_eff_base = 0
                sat_eff_base = 54

            lso_overlay = self.lso_overlay_surface

            for i in range(10):
                tx = food_right - (9 * scale) - i * step
                ty = thirst_y
                val = thirst_pts - (i * 2)

                if lso_overlay:
                    # 1. Background droplet container
                    draw_sprite(cr, lso_overlay, x_eff_base, y_eff_base, 9, 9, tx, ty, scale)
                    # 2. Filled droplet
                    if val >= 2:
                        draw_sprite(cr, lso_overlay, x_eff_base + 9, y_eff_base, 9, 9, tx, ty, scale)
                    elif val == 1:
                        draw_sprite(cr, lso_overlay, x_eff_base + 18, y_eff_base, 9, 9, tx, ty, scale)

                    # 3. Saturation border (AppleSkin highlight for hydration)
                    if thirst_sat > (i * 2):
                        sat_val = thirst_sat - (i * 2)
                        sat_x = sat_eff_base if sat_val >= 2 else (sat_eff_base + 9)
                        draw_sprite(cr, lso_overlay, sat_x, 0, 9, 9, tx, ty, scale)
                else:
                    self.draw_procedural_waterdrop(cr, tx, ty, scale, val, thirst_sat > (i * 2))

        # Air Bubbles (stack above thirst droplets if thirst is active)
        if self.state.air < 300 and icons:
            air_y = vitals_y - (20 * scale) if has_thirst else vitals_y - (10 * scale)
            num_bubbles = max(0, min(10, int(math.ceil(self.state.air / 30.0))))
            for i in range(num_bubbles):
                bx = food_right - (9 * scale) - i * step
                draw_sprite(cr, icons, 16, 18, 9, 9, bx, air_y, scale)

    def draw_hotbar(self, cr, hotbar_x, hotbar_y, scale):
        widgets = self.widgets_surface

        # Hotbar Background Strip
        if widgets:
            draw_sprite(cr, widgets, 0, 0, 182, 22, hotbar_x, hotbar_y, scale)
        else:
            cr.set_source_rgb(0.15, 0.17, 0.22)
            cr.rectangle(hotbar_x, hotbar_y, 182 * scale, 22 * scale)
            cr.fill()

        # Selected Slot Indicator
        sel_idx = max(0, min(8, self.state.selected_slot))
        sel_x = hotbar_x + (sel_idx * 20 - 1) * scale
        sel_y = hotbar_y - 1 * scale
        if widgets:
            draw_sprite(cr, widgets, 0, 22, 24, 24, sel_x, sel_y, scale)
        else:
            cr.set_source_rgb(0.2, 0.85, 1.0)
            cr.set_line_width(3.0)
            cr.rectangle(sel_x, sel_y, 24 * scale, 24 * scale)
            cr.stroke()

        # Offhand slot
        offhand_x = hotbar_x - (29 * scale) - 20
        offhand_y = hotbar_y
        if widgets:
            draw_sprite(cr, widgets, 24, 22, 29, 24, offhand_x, offhand_y, scale)
            if self.state.offhand.item_id != "minecraft:air" and self.state.offhand.item_id in self.icon_cache:
                surf = self.icon_cache[self.state.offhand.item_id]
                draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                            offhand_x + 9 * scale, offhand_y + 4 * scale, (16 * scale) / surf.get_width())

        # Item Slots
        self.slot_rects = []
        for i in range(9):
            slot = self.state.hotbar[i] if i < len(self.state.hotbar) else HotbarSlot()
            slot_hit_x = hotbar_x + (i * 20) * scale
            slot_hit_y = hotbar_y
            slot_hit_w = 20 * scale
            slot_hit_h = 22 * scale
            self.slot_rects.append((slot_hit_x, slot_hit_y, slot_hit_w, slot_hit_h, i))

            item_x = hotbar_x + (3 + i * 20) * scale
            item_y = hotbar_y + 3 * scale
            item_size = 16 * scale

            if slot.item_id != "minecraft:air":
                if slot.item_id in self.icon_cache:
                    surf = self.icon_cache[slot.item_id]
                    draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                                item_x, item_y, item_size / float(surf.get_width()))
                else:
                    short_name = slot.item_id.split(":")[-1].replace("_", " ")[:5]
                    cr.set_source_rgb(0.9, 0.9, 0.9)
                    cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
                    cr.set_font_size(12)
                    cr.move_to(item_x + 4, item_y + item_size / 2)
                    cr.show_text(short_name)

                if slot.count > 1:
                    count_str = str(slot.count)
                    cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
                    cr.set_font_size(18)
                    ext = cr.text_extents(count_str)
                    tx = item_x + item_size - ext.width - 2
                    ty = item_y + item_size - 2
                    cr.set_source_rgb(0.0, 0.0, 0.0)
                    cr.move_to(tx + 2, ty + 2)
                    cr.show_text(count_str)
                    cr.set_source_rgb(1.0, 1.0, 1.0)
                    cr.move_to(tx, ty)
                    cr.show_text(count_str)

                dur = slot.durability_fraction
                if dur is not None:
                    bar_w = 13 * scale
                    bar_h = 2 * scale
                    bar_x = item_x + 1.5 * scale
                    bar_y = item_y + item_size - bar_h
                    cr.set_source_rgb(0.0, 0.0, 0.0)
                    cr.rectangle(bar_x, bar_y, bar_w, bar_h)
                    cr.fill()
                    r = (1.0 - dur) * 1.0
                    g = dur * 1.0
                    cr.set_source_rgb(r, g, 0.1)
                    cr.rectangle(bar_x, bar_y, bar_w * dur, bar_h)
                    cr.fill()

    def draw_quick_actions(self, cr, W, H, in_container=False):
        btn_w = 175
        btn_h = 70
        btn_gap = 18

        # Build list of 6 hotkeys from user configuration
        btns = []
        for item in self.hotkeys:
            label = item.get("label", "ACTION")
            cmd = item.get("cmd", "")
            # Adapt label in container mode if it's the inventory hotkey
            if in_container and cmd == "E":
                label = "CLOSE [E]"
            btns.append((label, cmd))

        total_w = len(btns) * btn_w + (len(btns) - 1) * btn_gap
        btn_x0 = (W - total_w) / 2.0
        btn_y0 = H - btn_h - 10

        for i, (label, cmd) in enumerate(btns):
            bx = btn_x0 + i * (btn_w + btn_gap)
            by = btn_y0
            self.action_btns.append((bx, by, btn_w, btn_h, label, cmd))

            # Button Background
            if in_container and cmd in ("E", "ESCAPE", "CLOSE"):
                cr.set_source_rgb(0.45, 0.18, 0.18)
            else:
                cr.set_source_rgb(0.20, 0.22, 0.28)
            cr.rectangle(bx, by, btn_w, btn_h)
            cr.fill()

            # Highlights
            cr.set_source_rgb(0.38, 0.42, 0.52)
            cr.rectangle(bx, by, btn_w, 3)
            cr.fill()
            cr.rectangle(bx, by, 3, btn_h)
            cr.fill()

            # Shadows
            cr.set_source_rgb(0.10, 0.11, 0.14)
            cr.rectangle(bx, by + btn_h - 3, btn_w, 3)
            cr.fill()
            cr.rectangle(bx + btn_w - 3, by, 3, btn_h)
            cr.fill()

            cr.set_source_rgb(0.92, 0.94, 0.98)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(17)
            ext = cr.text_extents(label)
            cr.move_to(bx + (btn_w - ext.width) / 2.0, by + (btn_h + ext.height) / 2.0 - 2)
            cr.show_text(label)

    # -------------------------------------------------------------------------
    # HOTKEY CONFIGURATION MODAL DIALOG
    # -------------------------------------------------------------------------
    def draw_settings_modal(self, cr, W, H):
        self.settings_action_btns = []

        # Semi-transparent backdrop
        cr.set_source_rgba(0.0, 0.0, 0.0, 0.78)
        cr.paint()

        # Modal Box (centered)
        box_w = 980
        box_h = 720
        box_x = (W - box_w) / 2.0
        box_y = (H - box_h) / 2.0

        cr.set_source_rgba(0.12, 0.14, 0.18, 0.98)
        cr.rectangle(box_x, box_y, box_w, box_h)
        cr.fill()

        cr.set_source_rgba(0.30, 0.36, 0.48, 1.0)
        cr.set_line_width(2.5)
        cr.rectangle(box_x, box_y, box_w, box_h)
        cr.stroke()

        # Modal Header
        cr.set_source_rgb(1.0, 0.85, 0.2)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(22)
        cr.move_to(box_x + 30, box_y + 45)
        cr.show_text("CONFIGURE HOTKEYS")

        # Done [✕ DONE] Button
        done_w = 110
        done_h = 42
        done_x = box_x + box_w - done_w - 24
        done_y = box_y + 20
        self.settings_action_btns.append((done_x, done_y, done_w, done_h, "CLOSE_SETTINGS", None))
        cr.set_source_rgb(0.25, 0.65, 0.35)
        cr.rectangle(done_x, done_y, done_w, done_h)
        cr.fill()
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.set_font_size(15)
        ext = cr.text_extents("✕ DONE")
        cr.move_to(done_x + (done_w - ext.width) / 2.0, done_y + 26)
        cr.show_text("✕ DONE")

        # 6 Hotkey Slot Selection Buttons
        slot_w = 140
        slot_h = 80
        slot_gap = 16
        slots_x0 = box_x + (box_w - (6 * slot_w + 5 * slot_gap)) / 2.0
        slots_y0 = box_y + 85

        for i in range(6):
            sx = slots_x0 + i * (slot_w + slot_gap)
            sy = slots_y0
            self.settings_action_btns.append((sx, sy, slot_w, slot_h, "SELECT_SLOT", i))

            is_selected = (self.selected_hotkey_slot == i)
            if is_selected:
                cr.set_source_rgb(0.24, 0.38, 0.58)
            else:
                cr.set_source_rgb(0.18, 0.20, 0.25)
            cr.rectangle(sx, sy, slot_w, slot_h)
            cr.fill()

            if is_selected:
                cr.set_source_rgb(0.4, 0.85, 1.0)
                cr.set_line_width(3.0)
                cr.rectangle(sx, sy, slot_w, slot_h)
                cr.stroke()

            # Slot title
            cr.set_source_rgb(0.7, 0.75, 0.85)
            cr.set_font_size(12)
            cr.move_to(sx + 10, sy + 22)
            cr.show_text(f"SLOT {i + 1}")

            # Current assigned label
            cur_label = self.hotkeys[i].get("label", "NONE") if i < len(self.hotkeys) else "NONE"
            cr.set_source_rgb(1.0, 1.0, 1.0)
            cr.set_font_size(14)
            ext = cr.text_extents(cur_label)
            cr.move_to(sx + (slot_w - ext.width) / 2.0, sy + 54)
            cr.show_text(cur_label)

        # Action Preset Picker
        cr.set_source_rgb(0.9, 0.92, 0.96)
        cr.set_font_size(16)
        cr.move_to(box_x + 30, box_y + 205)
        cr.show_text(f"SELECT ACTION FOR SLOT {self.selected_hotkey_slot + 1}:")

        cols = 4
        preset_w = 210
        preset_h = 52
        preset_gap_x = 24
        preset_gap_y = 16
        preset_x0 = box_x + (box_w - (cols * preset_w + (cols - 1) * preset_gap_x)) / 2.0
        preset_y0 = box_y + 230

        for idx, (p_lbl, p_cmd) in enumerate(HOTKEY_PRESETS):
            col = idx % cols
            row = idx // cols
            px = preset_x0 + col * (preset_w + preset_gap_x)
            py = preset_y0 + row * (preset_h + preset_gap_y)
            self.settings_action_btns.append((px, py, preset_w, preset_h, "ASSIGN_PRESET", (p_lbl, p_cmd)))

            cur_assigned = (self.hotkeys[self.selected_hotkey_slot].get("cmd") == p_cmd)
            if cur_assigned:
                cr.set_source_rgb(0.2, 0.55, 0.3)
            else:
                cr.set_source_rgb(0.20, 0.22, 0.28)
            cr.rectangle(px, py, preset_w, preset_h)
            cr.fill()

            cr.set_source_rgb(0.35, 0.38, 0.48)
            cr.rectangle(px, py, preset_w, 2)
            cr.fill()

            cr.set_source_rgb(0.95, 0.95, 1.0)
            cr.set_font_size(14)
            ext = cr.text_extents(p_lbl)
            cr.move_to(px + (preset_w - ext.width) / 2.0, py + 32)
            cr.show_text(p_lbl)

        # Reset Defaults Button
        rst_w = 170
        rst_h = 42
        rst_x = box_x + 30
        rst_y = box_y + box_h - rst_h - 24
        self.settings_action_btns.append((rst_x, rst_y, rst_w, rst_h, "RESET_SETTINGS", None))
        cr.set_source_rgb(0.32, 0.22, 0.22)
        cr.rectangle(rst_x, rst_y, rst_w, rst_h)
        cr.fill()
        cr.set_source_rgb(0.9, 0.7, 0.7)
        cr.set_font_size(13)
        ext = cr.text_extents("RESET DEFAULTS")
        cr.move_to(rst_x + (rst_w - ext.width) / 2.0, rst_y + 26)
        cr.show_text("RESET DEFAULTS")

    def init_demo_state(self):
        self.state.health = 18.0
        self.state.armor = 14
        self.state.armor_toughness = 6.0
        self.state.food = 19
        self.state.saturation = 8.5
        self.state.xp_level = 42
        self.state.xp_progress = 0.68
        self.state.selected_slot = 1
        self.state.gun = {
            "active": True,
            "gunName": "AK-47 Tactical",
            "ammo": 30,
            "maxAmmo": 30,
            "fireMode": "AUTO",
            "reserveAmmo": 180
        }
        self.state.survival = {
            "hasThirst": True,
            "thirst": 17.5,
            "thirstSat": 4.0,
            "thirstSaturation": 4.0,
            "hasTemp": True,
            "temperature": 37.0,
            "tempStatus": "NORMAL",
            "hasStamina": True,
            "stamina": 850,
            "maxStamina": 1000
        }
        self.state.target = {
            "type": "ENTITY",
            "name": "Infected Walker",
            "mod": "theundead",
            "health": 18.0,
            "maxHealth": 20.0
        }
        self.state.effects = [
            {"id": "speed", "name": "Speed", "durationTicks": 1800, "amplifier": 1, "beneficial": True},
            {"id": "regeneration", "name": "Regeneration", "durationTicks": 400, "amplifier": 0, "beneficial": True}
        ]
        self.state.armor_slots = [
            {"slot": "FEET", "itemId": "minecraft:diamond_boots", "count": 1, "damage": 50, "maxDamage": 429},
            {"slot": "LEGS", "itemId": "minecraft:diamond_leggings", "count": 1, "damage": 80, "maxDamage": 495},
            {"slot": "CHEST", "itemId": "minecraft:diamond_chestplate", "count": 1, "damage": 120, "maxDamage": 528},
            {"slot": "HEAD", "itemId": "minecraft:diamond_helmet", "count": 1, "damage": 30, "maxDamage": 363}
        ]
        self.state.hotbar = [
            HotbarSlot("minecraft:netherite_sword", count=1, damage=240, max_damage=2031, has_glint=True),
            HotbarSlot("minecraft:bow", count=1, damage=85, max_damage=384, has_glint=True),
            HotbarSlot("minecraft:diamond_pickaxe", count=1, damage=410, max_damage=1561),
            HotbarSlot("minecraft:golden_apple", count=12),
            HotbarSlot("minecraft:cooked_beef", count=64),
            HotbarSlot("minecraft:torch", count=48),
            HotbarSlot("minecraft:water_bucket", count=1),
            HotbarSlot("minecraft:cobblestone", count=64),
            HotbarSlot("minecraft:ender_pearl", count=16),
        ]

    def update_demo(self):
        self.state.xp_progress = (self.state.xp_progress + 0.005) % 1.0
        return True

    def on_destroy(self, widget):
        self.running = False
        Gtk.main_quit()

def main():
    parser = argparse.ArgumentParser(description="Thor Minecraft Second-Screen Linux Companion")
    parser.add_argument("--host", default=DEFAULT_HOST, help="Minecraft host IP (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Minecraft port (default: 48291)")
    parser.add_argument("--demo", action="store_true", help="Run with simulated demo HUD data")
    args = parser.parse_args()

    app = ThorMcHud(host=args.host, port=args.port, demo_mode=args.demo)
    app.show_all()
    Gtk.main()

if __name__ == "__main__":
    main()
