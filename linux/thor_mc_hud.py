#!/usr/bin/env python3
"""
Thor Minecraft HUD - Native Linux Second-Screen Companion for AYN Thor
----------------------------------------------------------------------
Connects to the AYN Thor Minecraft Second Screen Fabric/Forge mod over local loopback
TCP (127.0.0.1:48291) and renders the actual Minecraft GUI on the Thor's bottom display (1240x1080).

Modes:
1. Roaming Mode:
   - Live 128x128 Minimap with paper parchment background, player position marker and yaw rotation
   - Player coordinates and compass orientation
   - Vitals: Vanilla health hearts (normal, absorption, poison, wither), armor, hunger, air bubbles, XP bar
   - Hotbar with authentic vanilla textures, items, counts, durability bars, offhand slot
   - Quick action buttons (INV, DROP, SWAP, CHAT, ESC)

2. Container / Inventory Mode:
   - Automatically activates when player opens inventory, chest, furnace, crafting table, or mod container
   - Top screen suppresses container rendering for clean 3D gameplay view
   - Bottom screen renders full interactive container slot grid + player inventory + hotbar
   - Touch slot interaction: Normal pickup/place and Shift-click quick move toggle
   - Carried cursor item badge and drop-to-world control
   - Close button to return to Roaming mode
"""

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

CLIENT_JAR_SEARCH_PATHS = [
    "/var/home/armada/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries/com/mojang/minecraft/1.20.1/minecraft-1.20.1-client.jar",
    "/var/home/armada/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries/com/mojang/minecraft/*/minecraft-*-client.jar",
    "/var/home/armada/.local/share/PrismLauncher/libraries/com/mojang/minecraft/*/minecraft-*-client.jar",
    "/var/home/armada/.minecraft/versions/*/*.jar",
]

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
        self.requested_icons = set()
        self.lock = threading.Lock()
        self.send_queue = []

        # Container & Minimap State
        self.current_container = None
        self.map_tile = None
        self.shift_click_mode = False

        # Hit-testing rects
        self.slot_rects = []
        self.action_btns = []
        self.container_slot_rects = []
        self.container_action_btns = []

        # Texture surfaces
        self.icons_surface = None
        self.widgets_surface = None
        self.map_bg_surface = None
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
        GLib.timeout_add(50, self.tick_redraw)

    def load_minecraft_textures(self):
        """Locates the Minecraft client jar and extracts icons.png, widgets.png, map_background.png."""
        jar_path = None
        for pattern in CLIENT_JAR_SEARCH_PATHS:
            matches = glob.glob(pattern)
            if matches:
                jar_path = sorted(matches)[-1]
                break

        if not jar_path or not os.path.isfile(jar_path):
            print(f"[ThorHud] Client jar not found. Using procedural fallback textures.")
            return

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

    def tick_redraw(self):
        self.darea.queue_draw()
        return self.running

    def send_command(self, cmd):
        with self.lock:
            self.send_queue.append(cmd)

    def network_loop(self):
        while self.running:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(2.0)
                s.connect((self.host, self.port))
                s.setblocking(False)
                self.sock = s
                self.connected = True
                print(f"[ThorHud] Connected to {self.host}:{self.port}")

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
                        recv_buf += chunk.decode("utf-8", errors="replace")
                        while "\n" in recv_buf:
                            line, recv_buf = recv_buf.split("\n", 1)
                            line = line.strip()
                            if line:
                                self.parse_incoming_line(line)

            except Exception as e:
                with self.lock:
                    self.connected = False
                    self.sock = None
                time.sleep(RECONNECT_INTERVAL)

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
                    if iid != "minecraft:air" and iid not in self.icon_cache and iid not in self.requested_icons:
                        self.requested_icons.add(iid)
                        self.send_queue.append(f"ICON:{iid}")
                cur = msg.get("cursor", {})
                cur_id = cur.get("itemId", "minecraft:air")
                if cur_id != "minecraft:air" and cur_id not in self.icon_cache and cur_id not in self.requested_icons:
                    self.requested_icons.add(cur_id)
                    self.send_queue.append(f"ICON:{cur_id}")
            return

        # 3. Player left world
        if msg_type == "noplayer":
            with self.lock:
                self.current_container = None
                self.map_tile = None
            return

        # 4. Item icon response
        if msg_type == "icon":
            item_id = msg.get("itemId")
            data_b64 = msg.get("data")
            if item_id and data_b64:
                try:
                    png_bytes = base64.b64decode(data_b64)
                    surface = cairo.ImageSurface.create_from_png(io.BytesIO(png_bytes))
                    with self.lock:
                        self.icon_cache[item_id] = surface
                except Exception as ex:
                    print(f"Error decoding icon for {item_id}: {ex}")
            return

        # 5. Standard HudState snapshot
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
                    if slot.item_id != "minecraft:air" and slot.item_id not in self.icon_cache and slot.item_id not in self.requested_icons:
                        self.requested_icons.add(slot.item_id)
                        self.send_queue.append(f"ICON:{slot.item_id}")
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
                if self.state.offhand.item_id != "minecraft:air" and self.state.offhand.item_id not in self.icon_cache and self.state.offhand.item_id not in self.requested_icons:
                    self.requested_icons.add(self.state.offhand.item_id)
                    self.send_queue.append(f"ICON:{self.state.offhand.item_id}")

    def on_touch_event(self, widget, event):
        if event.type == Gdk.EventType.TOUCH_BEGIN:
            self.handle_pointer_interaction(event.x, event.y, button=1)
        return True

    def on_button_press(self, widget, event):
        self.handle_pointer_interaction(event.x, event.y, button=event.button)
        return True

    def handle_pointer_interaction(self, x, y, button=1):
        # Container Mode Interactions
        is_container_open = False
        with self.lock:
            if self.current_container and self.current_container.get("isScreenOpen"):
                is_container_open = True

        if is_container_open:
            # Check container action buttons
            for bx, by, bw, bh, cmd in self.container_action_btns:
                if bx <= x <= bx + bw and by <= y <= by + bh:
                    if cmd == "TOGGLE_SHIFT":
                        self.shift_click_mode = not self.shift_click_mode
                    elif cmd == "CLOSE":
                        self.send_command("CLOSE_CONTAINER")
                    elif cmd == "DROP_HELD":
                        sync_id = self.current_container.get("syncId", 0)
                        self.send_command(f"SLOT:{sync_id},-999,0,PICKUP")
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

    # -------------------------------------------------------------------------
    # CONTAINER SCREEN RENDERING
    # -------------------------------------------------------------------------
    def draw_container_screen(self, cr, W, H):
        self.container_slot_rects = []
        self.container_action_btns = []

        container = self.current_container or {}
        sync_id = container.get("syncId", 0)
        title = container.get("title") or "INVENTORY"
        slots = container.get("slots", [])
        cursor = container.get("cursor", {})
        cursor_item = cursor.get("itemId", "minecraft:air")
        cursor_count = cursor.get("count", 0)

        # 1. Top Header & Control Bar
        header_y = 20
        header_h = 58
        margin_x = 40
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
        cr.move_to(margin_x + 24, header_y + 36)
        cr.show_text(title.upper())

        # Touch Mode Button (Normal vs Shift Quick-Move)
        mode_btn_w = 200
        mode_btn_h = 42
        mode_btn_x = margin_x + 300
        mode_btn_y = header_y + 8
        self.container_action_btns.append((mode_btn_x, mode_btn_y, mode_btn_w, mode_btn_h, "TOGGLE_SHIFT"))

        if self.shift_click_mode:
            cr.set_source_rgb(0.2, 0.6, 0.3)
        else:
            cr.set_source_rgb(0.22, 0.24, 0.30)
        cr.rectangle(mode_btn_x, mode_btn_y, mode_btn_w, mode_btn_h)
        cr.fill()
        cr.set_source_rgb(0.9, 0.95, 1.0)
        cr.set_font_size(14)
        mode_lbl = "MODE: QUICK-MOVE [ON]" if self.shift_click_mode else "MODE: NORMAL TAP"
        ext = cr.text_extents(mode_lbl)
        cr.move_to(mode_btn_x + (mode_btn_w - ext.width) / 2.0, mode_btn_y + 26)
        cr.show_text(mode_lbl)

        # Carried Cursor Item Badge
        if cursor_item != "minecraft:air":
            held_w = 260
            held_x = mode_btn_x + mode_btn_w + 20
            held_y = mode_btn_y
            cr.set_source_rgba(0.2, 0.15, 0.05, 0.9)
            cr.rectangle(held_x, held_y, held_w, mode_btn_h)
            cr.fill()
            cr.set_source_rgb(1.0, 0.7, 0.1)
            cr.rectangle(held_x, held_y, held_w, mode_btn_h)
            cr.stroke()

            # Item icon & text
            cur_short = cursor_item.split(":")[-1].replace("_", " ")[:12]
            cr.set_source_rgb(1.0, 1.0, 1.0)
            cr.set_font_size(14)
            cr.move_to(held_x + 12, held_y + 26)
            cr.show_text(f"Holding: {cur_short} x{cursor_count}")

            # Drop button
            drop_btn_w = 90
            drop_btn_x = held_x + held_w + 12
            self.container_action_btns.append((drop_btn_x, held_y, drop_btn_w, mode_btn_h, "DROP_HELD"))
            cr.set_source_rgb(0.5, 0.15, 0.15)
            cr.rectangle(drop_btn_x, held_y, drop_btn_w, mode_btn_h)
            cr.fill()
            cr.set_source_rgb(1.0, 1.0, 1.0)
            cr.move_to(drop_btn_x + 16, held_y + 26)
            cr.show_text("DROP")

        # Close [X] Button (top right)
        close_btn_w = 90
        close_btn_h = 42
        close_btn_x = margin_x + header_w - close_btn_w - 8
        close_btn_y = header_y + 8
        self.container_action_btns.append((close_btn_x, close_btn_y, close_btn_w, close_btn_h, "CLOSE"))
        cr.set_source_rgb(0.65, 0.18, 0.18)
        cr.rectangle(close_btn_x, close_btn_y, close_btn_w, close_btn_h)
        cr.fill()
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(16)
        ext = cr.text_extents("CLOSE [X]")
        cr.move_to(close_btn_x + (close_btn_w - ext.width) / 2.0, close_btn_y + 26)
        cr.show_text("CLOSE [X]")

        # 2. Container Slots Layout
        player_start = container.get("playerStart", 9)
        hotbar_start = container.get("hotbarStart", 36)
        total_slots = len(slots)

        cell_size = 56
        cell_pitch = 60
        player_w = 9 * cell_pitch
        player_x = (W - player_w) / 2.0

        # Draw container slots (if external container like chest, furnace, crafting)
        if player_start > 0:
            c_slots = slots[:player_start]
            c_cols = min(9, len(c_slots))
            c_rows = int(math.ceil(len(c_slots) / float(c_cols))) if c_cols > 0 else 0
            c_w = c_cols * cell_pitch
            c_x = (W - c_w) / 2.0
            c_y = 100

            # Container region box
            cr.set_source_rgba(0.12, 0.13, 0.17, 0.85)
            cr.rectangle(c_x - 12, c_y - 12, c_w + 24, (c_rows * cell_pitch) + 24)
            cr.fill()

            for i, slot in enumerate(c_slots):
                col = i % c_cols
                row = i // c_cols
                sx = c_x + col * cell_pitch
                sy = c_y + row * cell_pitch
                self.draw_container_slot(cr, sx, sy, cell_size, slot, i)

            player_y = c_y + (c_rows * cell_pitch) + 36
        else:
            # Player inventory only: Draw armor and crafting block
            player_y = 440
            self.draw_player_equipment_block(cr, player_x, 110, cell_size, cell_pitch, slots)

        # Draw Player 3x9 Main Inventory
        cr.set_source_rgba(0.12, 0.13, 0.17, 0.85)
        cr.rectangle(player_x - 12, player_y - 12, player_w + 24, (4 * cell_pitch) + 36)
        cr.fill()

        # Label
        cr.set_source_rgb(0.7, 0.75, 0.85)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(15)
        cr.move_to(player_x, player_y - 18)
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

        # Draw 1x9 Hotbar (with small separation)
        hotbar_y = player_y + (3 * cell_pitch) + 12
        cr.set_source_rgb(0.7, 0.75, 0.85)
        cr.set_font_size(15)
        cr.move_to(player_x, hotbar_y - 18)
        cr.show_text("HOTBAR")

        hotbar_count = min(9, total_slots - hotbar_start)
        for i in range(hotbar_count):
            slot_idx = hotbar_start + i
            slot = slots[slot_idx]
            sx = player_x + i * cell_pitch
            sy = hotbar_y
            self.draw_container_slot(cr, sx, sy, cell_size, slot, slot_idx)

    def draw_player_equipment_block(self, cr, player_x, eq_y, cell_size, cell_pitch, slots):
        # Armor slots: 4 vertical slots on the left
        # Crafting grid: 2x2 + result on the right
        cr.set_source_rgba(0.12, 0.13, 0.17, 0.85)
        cr.rectangle(player_x - 12, eq_y - 12, (9 * cell_pitch) + 24, (4 * cell_pitch) + 16)
        cr.fill()

        cr.set_source_rgb(0.7, 0.75, 0.85)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(15)
        cr.move_to(player_x, eq_y - 18)
        cr.show_text("ARMOR & CRAFTING")

        armor_indices = [5, 6, 7, 8]  # Helmet, Chest, Legs, Boots
        for i, a_idx in enumerate(armor_indices):
            if a_idx < len(slots):
                sx = player_x + 40
                sy = eq_y + i * cell_pitch
                self.draw_container_slot(cr, sx, sy, cell_size, slots[a_idx], a_idx)

        # Offhand slot (slot 45)
        if len(slots) > 45:
            off_x = player_x + 40 + cell_pitch + 16
            off_y = eq_y + 3 * cell_pitch
            self.draw_container_slot(cr, off_x, off_y, cell_size, slots[45], 45)
            cr.set_source_rgb(0.6, 0.65, 0.75)
            cr.set_font_size(12)
            cr.move_to(off_x, off_y - 6)
            cr.show_text("OFFHAND")

        # 2x2 Crafting Grid (slots 1, 2, 3, 4) & Result (slot 0)
        craft_x = player_x + 360
        craft_y = eq_y + 30
        craft_indices = [[1, 2], [3, 4]]
        for r in range(2):
            for c in range(2):
                c_idx = craft_indices[r][c]
                if c_idx < len(slots):
                    sx = craft_x + c * cell_pitch
                    sy = craft_y + r * cell_pitch
                    self.draw_container_slot(cr, sx, sy, cell_size, slots[c_idx], c_idx)

        # Arrow pointing to result
        cr.set_source_rgb(0.6, 0.65, 0.75)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(24)
        cr.move_to(craft_x + 2 * cell_pitch + 20, craft_y + cell_pitch)
        cr.show_text("->")

        # Crafting Result (slot 0)
        if len(slots) > 0:
            res_x = craft_x + 2 * cell_pitch + 70
            res_y = craft_y + 25
            self.draw_container_slot(cr, res_x, res_y, cell_size + 8, slots[0], 0)

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
                # Shadow
                cr.set_source_rgb(0.0, 0.0, 0.0)
                cr.move_to(tx + 2, ty + 2)
                cr.show_text(count_str)
                # White numeral
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
    # ROAMING SCREEN RENDERING
    # -------------------------------------------------------------------------
    def draw_roaming_screen(self, cr, W, H):
        # 1. Header with Status & Coordinates
        self.draw_header(cr, W, H)

        # 2. Live Minimap Display
        self.draw_minimap(cr, W, H)

        # 3. GUI Layout Scaling & Centering
        scale = 4.0
        hotbar_w = 182 * scale
        hotbar_h = 22 * scale
        center_x = (W - hotbar_w) / 2.0

        # Anchor HUD stack
        hotbar_y = 660
        xp_y = hotbar_y - (5 * scale) - 14
        vitals_y = xp_y - (9 * scale) - 16

        # Draw Vitals & Hotbar
        self.draw_vitals(cr, center_x, vitals_y, xp_y, scale)
        self.draw_hotbar(cr, center_x, hotbar_y, scale)

        # Draw Quick Actions
        self.draw_quick_actions(cr, W, H)

    def draw_minimap(self, cr, W, H):
        map_tile = self.map_tile
        sheet_size = 440
        sheet_x = (W - sheet_size) / 2.0
        sheet_y = 90
        border = 20
        tile_draw_size = sheet_size - 2 * border

        # Paper Parchment Background
        if self.map_bg_surface:
            surf = self.map_bg_surface
            draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                        sheet_x, sheet_y, float(sheet_size) / surf.get_width())
        else:
            cr.set_source_rgb(0.84, 0.75, 0.59)  # Vanilla map paper beige
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
        # Marker center relative to tile
        origin_x = map_tile.get("originX", 0)
        origin_z = map_tile.get("originZ", 0)
        player_x = map_tile.get("playerX", 0.0)
        player_z = map_tile.get("playerZ", 0.0)
        yaw = map_tile.get("yaw", 0.0)

        marker_px = (player_x - origin_x) * tile_scale
        marker_pz = (player_z - origin_z) * tile_scale
        mx = sheet_x + border + marker_px
        my = sheet_y + border + marker_pz

        # Draw player orientation arrowhead
        cr.save()
        cr.translate(mx, my)
        # Minecraft yaw: 0 = South, +90 = West, 180 = North, 270 = East. Arrow points up (-Z).
        cr.rotate(math.radians(yaw + 180.0))

        r = 12.0
        cr.move_to(0, -r)
        cr.line_to(r * 0.65, r * 0.75)
        cr.line_to(0, r * 0.35)
        cr.line_to(-r * 0.65, r * 0.75)
        cr.close_path()

        # Black outline
        cr.set_source_rgb(0.0, 0.0, 0.0)
        cr.set_line_width(4.0)
        cr.stroke_preserve()
        # White fill
        cr.set_source_rgb(1.0, 1.0, 1.0)
        cr.fill()
        cr.restore()

        # Coordinates label under the map
        coord_str = f"X: {player_x:.1f}   Z: {player_z:.1f}"
        cr.set_source_rgb(0.9, 0.95, 1.0)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(15)
        ext = cr.text_extents(coord_str)
        cr.move_to(sheet_x + (sheet_size - ext.width) / 2.0, sheet_y + sheet_size + 24)
        cr.show_text(coord_str)

    def draw_header(self, cr, W, H):
        header_h = 56
        margin_x = 40
        header_w = W - 2 * margin_x

        cr.set_source_rgba(0.12, 0.14, 0.18, 0.9)
        cr.rectangle(margin_x, 20, header_w, header_h)
        cr.fill()

        cr.set_source_rgba(0.24, 0.28, 0.36, 1.0)
        cr.set_line_width(2.0)
        cr.rectangle(margin_x, 20, header_w, header_h)
        cr.stroke()

        cr.set_source_rgb(0.92, 0.94, 0.98)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(20)
        cr.move_to(margin_x + 24, 54)
        cr.show_text("THOR MINECRAFT COMPANION")

        status_text = "CONNECTED" if self.connected else ("DEMO MODE" if self.demo_mode else "WAITING FOR GAME")
        dot_color = (0.2, 0.9, 0.3) if self.connected else ((0.3, 0.7, 1.0) if self.demo_mode else (1.0, 0.4, 0.2))

        dot_x = margin_x + header_w - 240
        cr.set_source_rgb(*dot_color)
        cr.arc(dot_x, 48, 7, 0, 2 * math.pi)
        cr.fill()

        cr.set_source_rgb(0.75, 0.82, 0.9)
        cr.set_font_size(15)
        cr.move_to(dot_x + 20, 54)
        cr.show_text(status_text)

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

        # Armor Row
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

        # --- Hunger / Food Row ---
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
            else:
                cr.set_source_rgb(0.25, 0.15, 0.05)
                cr.arc(fx + 4 * scale, fy + 4 * scale, 4 * scale, 0, 2 * math.pi)
                cr.fill()
                if val >= 1:
                    cr.set_source_rgb(0.85, 0.45, 0.15)
                    cr.arc(fx + 4 * scale, fy + 4 * scale, 3 * scale, 0, 2 * math.pi)
                    cr.fill()

        # Air Bubbles
        if self.state.air < 300 and icons:
            air_y = vitals_y - (10 * scale)
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

    def draw_quick_actions(self, cr, W, H):
        btn_w = 160
        btn_h = 58
        btn_gap = 20
        btns = [
            ("INV [E]", "E"),
            ("DROP [Q]", "Q"),
            ("SWAP [F]", "F"),
            ("CHAT [T]", "T"),
            ("MAP [M]", "M"),
            ("PAUSE", "ESCAPE")
        ]

        total_w = len(btns) * btn_w + (len(btns) - 1) * btn_gap
        btn_x0 = (W - total_w) / 2.0
        btn_y0 = H - 95

        self.action_btns = []
        for i, (label, cmd) in enumerate(btns):
            bx = btn_x0 + i * (btn_w + btn_gap)
            by = btn_y0
            self.action_btns.append((bx, by, btn_w, btn_h, label, cmd))

            cr.set_source_rgb(0.2, 0.22, 0.28)
            cr.rectangle(bx, by, btn_w, btn_h)
            cr.fill()

            cr.set_source_rgb(0.35, 0.38, 0.46)
            cr.rectangle(bx, by, btn_w, 3)
            cr.fill()
            cr.rectangle(bx, by, 3, btn_h)
            cr.fill()

            cr.set_source_rgb(0.1, 0.11, 0.14)
            cr.rectangle(bx, by + btn_h - 3, btn_w, 3)
            cr.fill()
            cr.rectangle(bx + btn_w - 3, by, 3, btn_h)
            cr.fill()

            cr.set_source_rgb(0.92, 0.94, 0.98)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(16)
            ext = cr.text_extents(label)
            cr.move_to(bx + (btn_w - ext.width) / 2.0, by + (btn_h + ext.height) / 2.0 - 2)
            cr.show_text(label)

    def init_demo_state(self):
        self.state.health = 18.0
        self.state.armor = 14
        self.state.food = 19
        self.state.xp_level = 42
        self.state.xp_progress = 0.68
        self.state.selected_slot = 1
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
