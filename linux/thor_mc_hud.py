#!/usr/bin/env python3
"""
Thor Minecraft HUD - Native Linux Second-Screen Companion for AYN Thor
----------------------------------------------------------------------
Connects to the AYN Thor Minecraft Second Screen Fabric/Forge mod over local loopback
TCP (127.0.0.1:48291) and renders the actual Minecraft GUI (hotbar, hearts, food,
armor, XP bar, air bubbles, items) natively on the Thor's bottom display via Gamescope.

Features:
- Actual Minecraft sprites extracted directly from client jar (icons.png, widgets.png)
- Pixel-perfect nearest-neighbor scaling (matching vanilla GUI scale 4)
- Dynamically centered for Thor's secondary display (1240x1080)
- Touch-responsive hotbar slot selection & quick action keys
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

        # Texture surfaces
        self.icons_surface = None
        self.widgets_surface = None
        self.load_minecraft_textures()

        # Dynamic layout rects for hit-testing
        self.slot_rects = []
        self.action_btns = []

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
        """Locates the Minecraft client jar and extracts icons.png and widgets.png."""
        jar_path = None
        for pattern in CLIENT_JAR_SEARCH_PATHS:
            matches = glob.glob(pattern)
            if matches:
                jar_path = matches[0]
                break

        if not jar_path or not os.path.isfile(jar_path):
            print(f"[ThorMcHud] Notice: Client jar not found, fallback to vector primitives.")
            return

        try:
            print(f"[ThorMcHud] Loading vanilla textures from: {jar_path}")
            with zipfile.ZipFile(jar_path, "r") as z:
                if "assets/minecraft/textures/gui/icons.png" in z.namelist():
                    icons_bytes = z.read("assets/minecraft/textures/gui/icons.png")
                    self.icons_surface = cairo.ImageSurface.create_from_png(io.BytesIO(icons_bytes))
                    print(f"[ThorMcHud] Loaded icons.png ({self.icons_surface.get_width()}x{self.icons_surface.get_height()})")

                if "assets/minecraft/textures/gui/widgets.png" in z.namelist():
                    widgets_bytes = z.read("assets/minecraft/textures/gui/widgets.png")
                    self.widgets_surface = cairo.ImageSurface.create_from_png(io.BytesIO(widgets_bytes))
                    print(f"[ThorMcHud] Loaded widgets.png ({self.widgets_surface.get_width()}x{self.widgets_surface.get_height()})")
        except Exception as e:
            print(f"[ThorMcHud] Failed to load textures from jar: {e}")

    def tick_redraw(self):
        self.darea.queue_draw()
        return True

    def send_command(self, cmd_text):
        if self.demo_mode:
            print(f"[DEMO] Simulated command sent: {cmd_text}")
            return
        with self.lock:
            self.send_queue.append(cmd_text)

    def network_loop(self):
        while self.running:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(3.0)
                s.connect((self.host, self.port))
                s.settimeout(None)
                self.sock = s
                self.connected = True
                print(f"[ThorMcHud] Connected to Minecraft at {self.host}:{self.port}")

                s.sendall(b"ASSETS\n")
                s.sendall(b"BINDINGS\n")

                reader = s.makefile("r", encoding="utf-8")
                while self.running:
                    with self.lock:
                        while self.send_queue:
                            cmd = self.send_queue.pop(0)
                            if not cmd.endswith("\n"):
                                cmd += "\n"
                            s.sendall(cmd.encode("utf-8"))

                    r, _, _ = select.select([s], [], [], 0.05)
                    if r:
                        line = reader.readline()
                        if not line:
                            break
                        self.handle_message(line.strip())
            except Exception:
                self.connected = False
                self.sock = None
                time.sleep(RECONNECT_INTERVAL)
            finally:
                if self.sock:
                    try:
                        self.sock.close()
                    except Exception:
                        pass
                self.connected = False

    def handle_message(self, line):
        if not line:
            return
        try:
            msg = json.loads(line)
        except Exception:
            return

        msg_type = msg.get("type")
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
            self.handle_pointer_interaction(event.x, event.y)
        return True

    def on_button_press(self, widget, event):
        if event.button == 1:
            self.handle_pointer_interaction(event.x, event.y)
        return True

    def handle_pointer_interaction(self, x, y):
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

        # 1. Background: Dark Minecraft UI theme
        cr.set_source_rgb(0.08, 0.09, 0.11)
        cr.paint()

        # Subtle vignette / frame
        cr.set_source_rgba(0.0, 0.0, 0.0, 0.4)
        cr.rectangle(0, 0, W, H)
        cr.stroke()

        # 2. Header
        self.draw_header(cr, W, H)

        # 3. GUI Layout Scaling & Centering
        # Standard Minecraft hotbar is 182 px wide. Scale factor 4.0 gives 728 px width.
        scale = 4.0
        hotbar_w = 182 * scale
        hotbar_h = 22 * scale
        center_x = (W - hotbar_w) / 2.0

        # Anchor HUD stack comfortably in the center-to-lower portion
        hotbar_y = H * 0.52
        xp_y = hotbar_y - (5 * scale) - 14
        vitals_y = xp_y - (9 * scale) - 16

        # Draw Vitals: Armor, Hearts, Hunger, XP, Air
        self.draw_vitals(cr, center_x, vitals_y, xp_y, scale)

        # Draw Hotbar & Items
        self.draw_hotbar(cr, center_x, hotbar_y, scale)

        # Draw Quick Action buttons
        self.draw_quick_actions(cr, W, H)

    def draw_header(self, cr, W, H):
        header_h = 56
        margin_x = 40
        header_w = W - 2 * margin_x

        cr.set_source_rgba(0.12, 0.14, 0.18, 0.9)
        cr.rectangle(margin_x, 24, header_w, header_h)
        cr.fill()

        cr.set_source_rgba(0.24, 0.28, 0.36, 1.0)
        cr.set_line_width(2.0)
        cr.rectangle(margin_x, 24, header_w, header_h)
        cr.stroke()

        cr.set_source_rgb(0.92, 0.94, 0.98)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(20)
        cr.move_to(margin_x + 24, 58)
        cr.show_text("THOR MINECRAFT COMPANION")

        # Connection status indicator
        status_text = "CONNECTED" if self.connected else ("DEMO MODE" if self.demo_mode else "WAITING FOR GAME")
        dot_color = (0.2, 0.9, 0.3) if self.connected else ((0.3, 0.7, 1.0) if self.demo_mode else (1.0, 0.4, 0.2))

        dot_x = margin_x + header_w - 240
        cr.set_source_rgb(*dot_color)
        cr.arc(dot_x, 52, 7, 0, 2 * math.pi)
        cr.fill()

        cr.set_source_rgb(0.75, 0.82, 0.9)
        cr.set_font_size(15)
        cr.move_to(dot_x + 20, 58)
        cr.show_text(status_text)

    def draw_vitals(self, cr, hotbar_x, vitals_y, xp_y, scale):
        icons = self.icons_surface
        hotbar_w = 182 * scale

        # --- Experience Bar ---
        xp_h = 5 * scale
        if icons:
            # Background
            draw_sprite(cr, icons, 0, 64, 182, 5, hotbar_x, xp_y, scale)
            # Fill
            prog = max(0.0, min(1.0, self.state.xp_progress))
            prog_w = int(182 * prog)
            if prog_w > 0:
                draw_sprite(cr, icons, 0, 69, prog_w, 5, hotbar_x, xp_y, scale)
        else:
            # Vector fallback
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

            # Shadow
            cr.set_source_rgb(0.0, 0.0, 0.0)
            for ox, oy in [(-2, 0), (2, 0), (0, -2), (0, 2), (2, 2)]:
                cr.move_to(tx + ox, ty + oy)
                cr.show_text(num_str)

            # Green text
            cr.set_source_rgb(0.5, 1.0, 0.15)
            cr.move_to(tx, ty)
            cr.show_text(num_str)

        # --- Hearts Row (Left aligned with XP bar) ---
        hearts_x = hotbar_x
        health = self.state.health
        step = 8 * scale

        for i in range(10):
            hx = hearts_x + i * step
            hy = vitals_y
            val = health - (i * 2)

            if icons:
                # Container
                draw_sprite(cr, icons, 16, 0, 9, 9, hx, hy, scale)
                # Heart Fill
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

        # Absorption Hearts (if any, stacked above hearts)
        if self.state.absorption > 0:
            for i in range(10):
                hx = hearts_x + i * step
                hy = vitals_y - (10 * scale)
                val = self.state.absorption - (i * 2)
                if val >= 1 and icons:
                    draw_sprite(cr, icons, 16, 0, 9, 9, hx, hy, scale)
                    abs_sx = 160 if val >= 2 else 169
                    draw_sprite(cr, icons, abs_sx, 0, 9, 9, hx, hy, scale)

        # Armor Row (stacked above hearts / absorption)
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

        # --- Hunger / Food Row (Right aligned with XP bar) ---
        food_right = hotbar_x + hotbar_w
        food_pts = self.state.food

        for i in range(10):
            fx = food_right - (9 * scale) - i * step
            fy = vitals_y
            val = food_pts - (i * 2)

            if icons:
                # Container
                draw_sprite(cr, icons, 16, 27, 9, 9, fx, fy, scale)
                # Fill
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

        # Air Bubbles (above hunger when submerged)
        if self.state.air < 300 and icons:
            air_y = vitals_y - (10 * scale)
            num_bubbles = max(0, min(10, int(math.ceil(self.state.air / 30.0))))
            for i in range(num_bubbles):
                bx = food_right - (9 * scale) - i * step
                draw_sprite(cr, icons, 16, 18, 9, 9, bx, air_y, scale)

    def draw_hotbar(self, cr, hotbar_x, hotbar_y, scale):
        widgets = self.widgets_surface

        # 1. Hotbar Background Strip (182x22)
        if widgets:
            draw_sprite(cr, widgets, 0, 0, 182, 22, hotbar_x, hotbar_y, scale)
        else:
            cr.set_source_rgb(0.15, 0.17, 0.22)
            cr.rectangle(hotbar_x, hotbar_y, 182 * scale, 22 * scale)
            cr.fill()

        # 2. Selected Slot Indicator (24x24 box)
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

        # 3. Offhand slot (to the left of hotbar)
        offhand_x = hotbar_x - (29 * scale) - 20
        offhand_y = hotbar_y
        if widgets:
            draw_sprite(cr, widgets, 24, 22, 29, 24, offhand_x, offhand_y, scale)
            if self.state.offhand.item_id != "minecraft:air" and self.state.offhand.item_id in self.icon_cache:
                surf = self.icon_cache[self.state.offhand.item_id]
                draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                            offhand_x + 9 * scale, offhand_y + 4 * scale, (16 * scale) / surf.get_width())

        # 4. Item Slots
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

            # Draw item texture
            if slot.item_id != "minecraft:air":
                if slot.item_id in self.icon_cache:
                    surf = self.icon_cache[slot.item_id]
                    draw_sprite(cr, surf, 0, 0, surf.get_width(), surf.get_height(),
                                item_x, item_y, item_size / float(surf.get_width()))
                else:
                    # Item text label placeholder
                    short_name = slot.item_id.split(":")[-1].replace("_", " ")[:5]
                    cr.set_source_rgb(0.9, 0.9, 0.9)
                    cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
                    cr.set_font_size(12)
                    cr.move_to(item_x + 4, item_y + item_size / 2)
                    cr.show_text(short_name)

                # Stack count (bottom right)
                if slot.count > 1:
                    count_str = str(slot.count)
                    cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
                    cr.set_font_size(18)
                    ext = cr.text_extents(count_str)
                    tx = item_x + item_size - ext.width - 2
                    ty = item_y + item_size - 2

                    # Shadow
                    cr.set_source_rgb(0.0, 0.0, 0.0)
                    cr.move_to(tx + 2, ty + 2)
                    cr.show_text(count_str)

                    # White numeral
                    cr.set_source_rgb(1.0, 1.0, 1.0)
                    cr.move_to(tx, ty)
                    cr.show_text(count_str)

                # Durability Bar
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
        btn_y0 = H - 110

        self.action_btns = []
        for i, (label, cmd) in enumerate(btns):
            bx = btn_x0 + i * (btn_w + btn_gap)
            by = btn_y0
            self.action_btns.append((bx, by, btn_w, btn_h, label, cmd))

            # Minecraft-style button bevel
            cr.set_source_rgb(0.2, 0.22, 0.28)
            cr.rectangle(bx, by, btn_w, btn_h)
            cr.fill()

            # Highlights
            cr.set_source_rgb(0.35, 0.38, 0.46)
            cr.rectangle(bx, by, btn_w, 3)
            cr.fill()
            cr.rectangle(bx, by, 3, btn_h)
            cr.fill()

            # Shadows
            cr.set_source_rgb(0.1, 0.11, 0.14)
            cr.rectangle(bx, by + btn_h - 3, btn_w, 3)
            cr.fill()
            cr.rectangle(bx + btn_w - 3, by, 3, btn_h)
            cr.fill()

            # Text
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
