#!/usr/bin/env python3
"""
Thor Minecraft HUD - Native Linux Second-Screen Companion for AYN Thor
----------------------------------------------------------------------
Connects to the AYN Thor Minecraft Second Screen Fabric/Forge mod over local loopback
TCP (127.0.0.1:48291) and renders the game HUD and controls natively on the Thor's
bottom 1080x1240 AMOLED display via Armada's secondary Gamescope session.

Compatible with Armada OS (Gamescope X11 on DISPLAY=:2 via armada-run-bottom).
"""

import os
import sys
import json
import socket
import select
import base64
import io
import math
import time
import threading
import argparse

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, Gdk, GLib, cairo

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 48291
RECONNECT_INTERVAL = 1.5

WIDTH = 1080
HEIGHT = 1240

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

class ThorMcHud(Gtk.Window):
    def __init__(self, host=DEFAULT_HOST, port=DEFAULT_PORT, demo_mode=False):
        super().__init__(type=Gtk.WindowType.TOPLEVEL)
        self.set_title("Thor Minecraft Second Screen")
        self.set_default_size(WIDTH, HEIGHT)
        self.set_decorated(False)
        self.set_resizable(False)

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

        # UI Layout calculations
        self.setup_layout_geometry()

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

        # Regular 60fps refresh timer for smooth animations/glint
        GLib.timeout_add(50, self.tick_redraw)

    def setup_layout_geometry(self):
        # Hotbar slots: 9 slots + 1 offhand
        self.slot_size = 96
        self.slot_gap = 14
        total_hotbar_w = 9 * self.slot_size + 8 * self.slot_gap
        self.hotbar_x0 = (WIDTH - total_hotbar_w) // 2
        self.hotbar_y0 = 380

        # Slot bounds for hit testing: rect -> (x, y, w, h, slot_index)
        self.slot_rects = []
        for i in range(9):
            x = self.hotbar_x0 + i * (self.slot_size + self.slot_gap)
            y = self.hotbar_y0
            self.slot_rects.append((x, y, self.slot_size, self.slot_size, i))

        # Quick actions bar at bottom
        self.btn_w = 140
        self.btn_h = 60
        self.btn_gap = 16
        btns = [("INV [E]", "E"), ("DROP [Q]", "Q"), ("SWAP [F]", "F"), ("CHAT [T]", "T"), ("MAP [M]", "M"), ("PAUSE", "ESCAPE")]
        total_btns_w = len(btns) * self.btn_w + (len(btns) - 1) * self.btn_gap
        btn_x0 = (WIDTH - total_btns_w) // 2
        btn_y0 = 1140
        self.action_btns = []
        for i, (label, cmd) in enumerate(btns):
            bx = btn_x0 + i * (self.btn_w + self.btn_gap)
            self.action_btns.append((bx, btn_y0, self.btn_w, self.btn_h, label, cmd))

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

                # Initial Handshake requests
                s.sendall(b"ASSETS\n")
                s.sendall(b"BINDINGS\n")

                reader = s.makefile("r", encoding="utf-8")
                while self.running:
                    # Check for outgoing commands
                    with self.lock:
                        while self.send_queue:
                            cmd = self.send_queue.pop(0)
                            if not cmd.endswith("\n"):
                                cmd += "\n"
                            s.sendall(cmd.encode("utf-8"))

                    # Read incoming line with short select
                    r, _, _ = select.select([s], [], [], 0.05)
                    if r:
                        line = reader.readline()
                        if not line:
                            break
                        self.handle_message(line.strip())
            except Exception as e:
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
                    loader = Gdk.PixbufLoader.new_with_type("png")
                    loader.write(png_bytes)
                    loader.close()
                    pixbuf = loader.get_pixbuf()
                    if pixbuf:
                        surface = Gdk.cairo_surface_create_from_pixbuf(pixbuf, 1, None)
                        with self.lock:
                            self.icon_cache[item_id] = surface
                except Exception as ex:
                    print(f"Error decoding icon for {item_id}: {ex}")
            return

        # Snapshot State update
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

            if "hotbar" in msg and isinstance(msg["hotbar"], list):
                new_hotbar = []
                for s in msg["hotbar"]:
                    slot = HotbarSlot(
                        item_id=s.get("itemId", "minecraft:air"),
                        count=int(s.get("count", 0)),
                        damage=int(s.get("damage", 0)),
                        max_damage=int(s.get("maxDamage", 0)),
                        has_glint=bool(s.get("hasGlint", False))
                    )
                    new_hotbar.append(slot)
                    # Request icon if not cached or requested
                    if slot.item_id != "minecraft:air" and slot.item_id not in self.icon_cache and slot.item_id not in self.requested_icons:
                        self.requested_icons.add(slot.item_id)
                        self.send_queue.append(f"ICON:{slot.item_id}")
                self.state.hotbar = new_hotbar

    def on_touch_event(self, widget, event):
        if event.type == Gdk.EventType.TOUCH_BEGIN:
            x, y = event.x, event.y
            self.handle_pointer_interaction(x, y)
        return True

    def on_button_press(self, widget, event):
        if event.button == 1:
            self.handle_pointer_interaction(event.x, event.y)
        return True

    def handle_pointer_interaction(self, x, y):
        # Check Hotbar tap
        for rx, ry, rw, rh, idx in self.slot_rects:
            if rx <= x <= rx + rw and ry <= y <= ry + rh:
                self.state.selected_slot = idx
                self.send_command(str(idx + 1))
                return

        # Check Quick Action button tap
        for bx, by, bw, bh, label, cmd in self.action_btns:
            if bx <= x <= bx + bw and by <= y <= by + bh:
                self.send_command(cmd)
                return

    def on_draw(self, widget, cr):
        # 1. Background: Dark textured stone/carbon tone
        cr.set_source_rgb(0.08, 0.09, 0.12)
        cr.paint()

        # Subtle grid pattern
        cr.set_source_rgba(0.14, 0.16, 0.20, 0.4)
        cr.set_line_width(1.0)
        for gy in range(0, HEIGHT, 40):
            cr.move_to(0, gy)
            cr.line_to(WIDTH, gy)
        cr.stroke()

        # 2. Header Status Bar
        self.draw_header(cr)

        # 3. Player Stats: Armor, Hearts, Hunger, Air, XP
        self.draw_vitals(cr)

        # 4. Main Hotbar
        self.draw_hotbar(cr)

        # 5. Quick Actions Bar
        self.draw_quick_actions(cr)

    def draw_header(self, cr):
        # Header banner box
        cr.set_source_rgba(0.12, 0.14, 0.18, 0.9)
        cr.rectangle(20, 20, WIDTH - 40, 54)
        cr.fill()

        # Border
        cr.set_source_rgba(0.25, 0.28, 0.35, 1.0)
        cr.set_line_width(2.0)
        cr.rectangle(20, 20, WIDTH - 40, 54)
        cr.stroke()

        # Title text
        cr.set_source_rgb(0.9, 0.92, 0.96)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(20)
        cr.move_to(45, 54)
        cr.show_text("THOR MINECRAFT HUD")

        # Connection status indicator
        status_text = "CONNECTED" if self.connected else ("DEMO MODE" if self.demo_mode else "WAITING FOR GAME")
        dot_color = (0.2, 0.9, 0.3) if self.connected else ((0.3, 0.7, 1.0) if self.demo_mode else (1.0, 0.4, 0.2))

        cr.set_source_rgb(*dot_color)
        cr.arc(WIDTH - 240, 47, 7, 0, 2 * math.pi)
        cr.fill()

        cr.set_source_rgb(0.75, 0.8, 0.88)
        cr.set_font_size(15)
        cr.move_to(WIDTH - 220, 53)
        cr.show_text(status_text)

    def draw_vitals(self, cr):
        y_armor = 110
        y_hearts = 150
        y_xp = 210

        # Armor Row (Up to 10 shield icons on left)
        armor_pts = self.state.armor
        for i in range(10):
            x = 100 + i * 36
            val = armor_pts - (i * 2)
            self.draw_shield(cr, x, y_armor, val)

        # Hearts Row (Left)
        health = self.state.health
        for i in range(10):
            x = 100 + i * 36
            val = health - (i * 2)
            self.draw_heart(cr, x, y_hearts, val, absorption=False)

        # Absorption Gold Hearts (if active, stacked or shifted)
        if self.state.absorption > 0:
            for i in range(10):
                x = 100 + i * 36
                val = self.state.absorption - (i * 2)
                if val > 0:
                    self.draw_heart(cr, x, y_hearts - 26, val, absorption=True)

        # Hunger Drumsticks (Right side)
        food_pts = self.state.food
        for i in range(10):
            x = WIDTH - 136 - i * 36
            val = food_pts - (i * 2)
            self.draw_hunger(cr, x, y_hearts, val)

        # Experience Bar
        xp_w = WIDTH - 200
        xp_h = 16
        xp_x = 100

        # Background
        cr.set_source_rgb(0.1, 0.15, 0.1)
        cr.rectangle(xp_x, y_xp, xp_w, xp_h)
        cr.fill()

        # Progress fill
        progress = max(0.0, min(1.0, self.state.xp_progress))
        cr.set_source_rgb(0.3, 0.9, 0.2)
        cr.rectangle(xp_x, y_xp, xp_w * progress, xp_h)
        cr.fill()

        # Outline & notches
        cr.set_source_rgb(0.0, 0.3, 0.0)
        cr.set_line_width(2.0)
        cr.rectangle(xp_x, y_xp, xp_w, xp_h)
        cr.stroke()

        # XP Level numeral
        if self.state.xp_level > 0:
            cr.set_source_rgb(0.3, 1.0, 0.2)
            cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
            cr.set_font_size(24)
            num_str = str(self.state.xp_level)
            ext = cr.text_extents(num_str)
            cr.move_to(WIDTH // 2 - ext.width / 2, y_xp - 8)
            cr.show_text(num_str)

    def draw_shield(self, cr, x, y, val):
        cr.set_source_rgb(0.18, 0.2, 0.25)
        cr.arc(x + 12, y + 10, 10, 0, 2 * math.pi)
        cr.fill()
        if val >= 2:
            cr.set_source_rgb(0.8, 0.85, 0.95)
            cr.arc(x + 12, y + 10, 8, 0, 2 * math.pi)
            cr.fill()
        elif val == 1:
            cr.set_source_rgb(0.8, 0.85, 0.95)
            cr.arc(x + 12, y + 10, 8, math.pi / 2, 3 * math.pi / 2)
            cr.fill()

    def draw_heart(self, cr, x, y, val, absorption=False):
        color = (1.0, 0.8, 0.0) if absorption else (0.9, 0.15, 0.2)
        cr.set_source_rgb(0.2, 0.05, 0.05)
        cr.arc(x + 12, y + 10, 11, 0, 2 * math.pi)
        cr.fill()
        if val >= 2:
            cr.set_source_rgb(*color)
            cr.arc(x + 12, y + 10, 9, 0, 2 * math.pi)
            cr.fill()
        elif val == 1:
            cr.set_source_rgb(*color)
            cr.arc(x + 12, y + 10, 9, math.pi / 2, 3 * math.pi / 2)
            cr.fill()

    def draw_hunger(self, cr, x, y, val):
        cr.set_source_rgb(0.25, 0.15, 0.05)
        cr.arc(x + 12, y + 10, 11, 0, 2 * math.pi)
        cr.fill()
        if val >= 2:
            cr.set_source_rgb(0.85, 0.45, 0.15)
            cr.arc(x + 12, y + 10, 9, 0, 2 * math.pi)
            cr.fill()
        elif val == 1:
            cr.set_source_rgb(0.85, 0.45, 0.15)
            cr.arc(x + 12, y + 10, 9, 3 * math.pi / 2, math.pi / 2)
            cr.fill()

    def draw_hotbar(self, cr):
        # Section Label
        cr.set_source_rgb(0.6, 0.68, 0.78)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(18)
        cr.move_to(self.hotbar_x0, self.hotbar_y0 - 24)
        cr.show_text("HOTBAR (TAP SLOT TO SELECT)")

        for x, y, w, h, idx in self.slot_rects:
            slot = self.state.hotbar[idx] if idx < len(self.state.hotbar) else HotbarSlot()
            is_selected = (idx == self.state.selected_slot)

            # Slot box background
            bg_color = (0.22, 0.26, 0.34) if is_selected else (0.13, 0.15, 0.20)
            cr.set_source_rgb(*bg_color)
            cr.rectangle(x, y, w, h)
            cr.fill()

            # Slot border
            if is_selected:
                cr.set_source_rgb(0.2, 0.85, 1.0)
                cr.set_line_width(4.0)
            else:
                cr.set_source_rgb(0.28, 0.32, 0.40)
                cr.set_line_width(2.0)
            cr.rectangle(x, y, w, h)
            cr.stroke()

            # Slot Number (top left)
            cr.set_source_rgb(0.5, 0.55, 0.65)
            cr.set_font_size(13)
            cr.move_to(x + 6, y + 18)
            cr.show_text(str(idx + 1))

            # Item Icon
            if slot.item_id != "minecraft:air":
                if slot.item_id in self.icon_cache:
                    surf = self.icon_cache[slot.item_id]
                    cr.save()
                    cr.translate(x + (w - 64) // 2, y + (h - 64) // 2)
                    cr.set_source_surface(surf, 0, 0)
                    cr.paint()
                    cr.restore()
                else:
                    # Generic item placeholder box
                    cr.set_source_rgb(0.4, 0.5, 0.6)
                    cr.rectangle(x + 24, y + 24, 48, 48)
                    cr.fill()
                    # Item short name
                    short_name = slot.item_id.split(":")[-1].replace("_", " ")[:6]
                    cr.set_source_rgb(1.0, 1.0, 1.0)
                    cr.set_font_size(11)
                    cr.move_to(x + 26, y + 52)
                    cr.show_text(short_name)

                # Stack count (bottom right)
                if slot.count > 1:
                    cr.set_source_rgb(1.0, 1.0, 1.0)
                    cr.set_font_size(18)
                    count_str = str(slot.count)
                    ext = cr.text_extents(count_str)
                    cr.move_to(x + w - ext.width - 8, y + h - 8)
                    cr.show_text(count_str)

                # Durability Bar (if damaged)
                dur = slot.durability_fraction
                if dur is not None:
                    bar_w = w - 16
                    bar_h = 5
                    bar_x = x + 8
                    bar_y = y + h - 10

                    cr.set_source_rgb(0.05, 0.05, 0.05)
                    cr.rectangle(bar_x, bar_y, bar_w, bar_h)
                    cr.fill()

                    r = (1.0 - dur) * 1.0
                    g = dur * 1.0
                    cr.set_source_rgb(r, g, 0.1)
                    cr.rectangle(bar_x, bar_y, bar_w * dur, bar_h)
                    cr.fill()

    def draw_quick_actions(self, cr):
        # Action bar title
        cr.set_source_rgb(0.6, 0.68, 0.78)
        cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        cr.set_font_size(18)
        cr.move_to(self.action_btns[0][0], self.action_btns[0][1] - 16)
        cr.show_text("QUICK ACTIONS")

        for bx, by, bw, bh, label, cmd in self.action_btns:
            # Button fill
            cr.set_source_rgb(0.18, 0.22, 0.30)
            cr.rectangle(bx, by, bw, bh)
            cr.fill()

            # Button outline
            cr.set_source_rgb(0.35, 0.42, 0.55)
            cr.set_line_width(2.0)
            cr.rectangle(bx, by, bw, bh)
            cr.stroke()

            # Text
            cr.set_source_rgb(0.9, 0.95, 1.0)
            cr.set_font_size(16)
            ext = cr.text_extents(label)
            cr.move_to(bx + (bw - ext.width) / 2, by + (bh + ext.height) / 2)
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
