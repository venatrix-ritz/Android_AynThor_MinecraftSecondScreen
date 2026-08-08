"""
Stand-in for the Fabric mod's HudStateServer.

Speaks the same newline-delimited JSON protocol on 48291, so the companion app
can be driven into any HUD state -- absorption, drowning, low health -- without
Minecraft running at all. Paired with `adb reverse tcp:48291 tcp:48291`, the
app on the Thor connects to this instead of the real mod.

Textures come out of a real client jar, so the app takes the same asset path it
would in production rather than falling back to its drawn placeholders.

Usage:  python hud_sim.py <client.jar> <scenario>
"""
import base64
import json
import socket
import sys
import threading
import time
import zipfile

PORT = 48291

# Mirrors HudAssetCatalog on the mod side. Keys are the wire contract; paths
# are what they resolve to in the jar.
ASSETS = {
    "heart_full": "textures/gui/sprites/hud/heart/full.png",
    "heart_half": "textures/gui/sprites/hud/heart/half.png",
    "heart_container": "textures/gui/sprites/hud/heart/container.png",
    "heart_absorbing_full": "textures/gui/sprites/hud/heart/absorbing_full.png",
    "heart_absorbing_half": "textures/gui/sprites/hud/heart/absorbing_half.png",
    "armor_full": "textures/gui/sprites/hud/armor_full.png",
    "armor_half": "textures/gui/sprites/hud/armor_half.png",
    "armor_empty": "textures/gui/sprites/hud/armor_empty.png",
    "food_full": "textures/gui/sprites/hud/food_full.png",
    "food_half": "textures/gui/sprites/hud/food_half.png",
    "food_empty": "textures/gui/sprites/hud/food_empty.png",
    "air": "textures/gui/sprites/hud/air.png",
    "air_bursting": "textures/gui/sprites/hud/air_bursting.png",
    "xp_bar_background": "textures/gui/sprites/hud/experience_bar_background.png",
    "xp_bar_progress": "textures/gui/sprites/hud/experience_bar_progress.png",
    "hotbar": "textures/gui/sprites/hud/hotbar.png",
    "hotbar_selection": "textures/gui/sprites/hud/hotbar_selection.png",
    "hotbar_offhand_right": "textures/gui/sprites/hud/hotbar_offhand_right.png",
    # The enchantment shimmer the app scrolls over glinted items. Not a hud/
    # sprite -- it lives under textures/misc/ -- but it comes down the same
    # asset channel.
    "enchanted_glint_item": "textures/misc/enchanted_glint_item.png",
    "font_ascii": "textures/font/ascii.png",
    "background": "textures/block/dirt.png",
    "map_background": "textures/map/map_background.png",
    "widget_button": "textures/gui/sprites/widget/button.png",
    "widget_button_highlighted": "textures/gui/sprites/widget/button_highlighted.png",
    "widget_button_disabled": "textures/gui/sprites/widget/button_disabled.png",
    "container_slot": "textures/gui/sprites/container/slot.png",
}

# The container sprites, keyed by their own path with the slashes flattened --
# exactly as HudAssetCatalog.putSprite registers them, because the app derives the
# key from the sprite id the mod reports at runtime rather than from a table.
for _slot_sprite in (
    "amethyst_shard", "axe", "banner", "banner_pattern", "boots", "brewing_fuel",
    "chestplate", "diamond", "dye", "emerald", "helmet", "hoe", "horse_armor",
    "ingot", "lapis_lazuli", "leggings", "llama_armor", "nautilus_armor",
    "nautilus_armor_inventory", "pickaxe", "potion", "quartz", "redstone_dust",
    "saddle", "shield", "shovel", "smithing_template_armor_trim",
    "smithing_template_netherite_upgrade", "spear", "sword",
):
    ASSETS[f"container_slot_{_slot_sprite}"] = (
        f"textures/gui/sprites/container/slot/{_slot_sprite}.png")

for _cooker in ("furnace", "blast_furnace", "smoker"):
    for _part in ("lit_progress", "burn_progress"):
        ASSETS[f"container_{_cooker}_{_part}"] = (
            f"textures/gui/sprites/container/{_cooker}/{_part}.png")

for _brew in ("fuel_length", "brew_progress", "bubbles"):
    ASSETS[f"container_brewing_stand_{_brew}"] = (
        f"textures/gui/sprites/container/brewing_stand/{_brew}.png")

ASSETS["container_crafter_disabled_slot"] = (
    "textures/gui/sprites/container/crafter/disabled_slot.png")

# The beacon's effect icons. Under textures/mob_effect/, not gui/sprites/, so they
# get their own key prefix -- the app derives it from the effect's registry id.
for _effect in ("speed", "haste", "resistance", "jump_boost", "strength",
                "regeneration"):
    ASSETS[f"mob_effect_{_effect}"] = f"textures/mob_effect/{_effect}.png"


def slot(item_id, count=1, damage=0, max_damage=0, glint=False):
    return {
        "itemId": item_id,
        "count": count,
        "damage": damage,
        "maxDamage": max_damage,
        "hasGlint": glint,
    }


EMPTY = slot("minecraft:air", 0)

HOTBAR = [
    slot("minecraft:diamond_sword", 1, 120, 1561, True),
    slot("minecraft:diamond_pickaxe", 1, 40, 1561),
    slot("minecraft:jungle_stairs", 64),
    slot("minecraft:stripped_birch_wood", 32),
    slot("minecraft:golden_apple", 3),
    slot("minecraft:cooked_beef", 12),
    slot("minecraft:torch", 64),
    EMPTY,
    EMPTY,
]

# Something with a flat item texture, so the sim can actually draw it -- a
# shield would be the obvious off-hand item but it renders from an entity
# model and has no textures/item/shield.png to fall back on.
OFFHAND = slot("minecraft:totem_of_undying", 1)

BASE = {
    "health": 20.0,
    "maxHealth": 20.0,
    "absorption": 0.0,
    "armor": 12,
    "food": 17,
    "xpLevel": 30,
    "xpProgress": 0.45,
    "selectedSlot": 0,
    "air": 300,
    "maxAir": 300,
    "hotbar": HOTBAR,
    "offhand": OFFHAND,
}

# --------------------------------------------------------------------------
# Containers
#
# Every coordinate below was read out of the handler's own bytecode, not
# recalled -- `javap -p -c` on each ScreenHandler, taking the two ints pushed
# before its `addSlot`. They matter because they *are* the layout: the app draws
# a container's shape from these and nothing else, so a wrong pair here would
# make a screenshot misrepresent the thing it's meant to show.
#
# Vanilla puts a container's player slots at (8,84) for the main rows and (8,142)
# for the hotbar. The app ignores those and lays the player's rows out itself, but
# they're sent anyway so the message is shaped like the real one.
# --------------------------------------------------------------------------

def cslot(x, y, item=None, count=1, bg=None, may_place=True, enabled=True,
          damage=0, max_damage=0, glint=False):
    """One container slot in the wire shape ContainerRelay.SlotState produces."""
    entry = {
        "stack": slot(item or "minecraft:air", count if item else 0,
                      damage, max_damage, glint),
        "mayPlace": may_place,
        "x": x,
        "y": y,
    }
    if bg:
        entry["background"] = bg
    # Sent inverted and only when true, matching the mod: "enabled" is the
    # default and a field on every slot to say so would be the costly encoding.
    if not enabled:
        entry["disabled"] = True
    return entry


PLAYER_FILL = [
    "minecraft:stone", "minecraft:oak_planks", "minecraft:iron_ingot",
    "minecraft:lapis_lazuli", "minecraft:coal", None, None,
    "minecraft:bread", "minecraft:torch",
]


def player_slots():
    """The player's 27 main + 9 hotbar slots at vanilla's coordinates."""
    out = []
    for row in range(3):
        for col in range(9):
            item = PLAYER_FILL[col] if row == 0 else None
            out.append(cslot(8 + col * 18, 84 + row * 18, item,
                             count=(col + 1) * 7 if item else 1))
    for col in range(9):
        item = PLAYER_FILL[col]
        out.append(cslot(8 + col * 18, 142, item, count=(col + 3) * 5 if item else 1))
    return out


def container(handler_type, title, slots, extras=None, progress=None, cursor=None):
    player = player_slots()
    msg = {
        "type": "container",
        "syncId": 3,
        "handlerType": handler_type,
        "title": title,
        "cursor": cursor or slot("minecraft:air", 0),
        "slots": slots + player,
        "playerStart": len(slots),
        "hotbarStart": len(slots) + 27,
        "armorStart": -1,
        "offhandIndex": -1,
    }
    if progress:
        msg["progress"] = progress
    if extras:
        msg["extras"] = extras
    return msg


def cut_option(item, label):
    return {"label": label, "itemId": item}


CONTAINERS = {
    # Two slots and a recipe list -- the list is the whole point of the block, and
    # before BUTTON: existed there was no way to touch it.
    "stonecutter": container(
        "minecraft:stonecutter", "Stonecutter",
        [cslot(20, 33, "minecraft:stone", 48), cslot(143, 33, "minecraft:stone_stairs", 48,
                                                     may_place=False)],
        extras={"stonecutter": {
            "options": [
                cut_option("minecraft:stone_stairs", "Stone Stairs"),
                cut_option("minecraft:stone_slab", "Stone Slab"),
                cut_option("minecraft:stone_bricks", "Stone Bricks"),
                cut_option("minecraft:stone_brick_stairs", "Stone Brick Stairs"),
                cut_option("minecraft:stone_brick_slab", "Stone Brick Slab"),
                cut_option("minecraft:stone_brick_wall", "Stone Brick Wall"),
                cut_option("minecraft:chiseled_stone_bricks", "Chiseled Stone Bricks"),
                cut_option("minecraft:stone_button", "Stone Button"),
                cut_option("minecraft:stone_pressure_plate", "Stone Pressure Plate"),
            ],
            "selected": 3,
        }},
    ),
    # Three offers, one of which the player can't afford -- the disabled state is
    # half of what this panel is for.
    "enchanting": container(
        "minecraft:enchantment", "Enchant",
        [cslot(15, 47, "minecraft:diamond_sword", 1, damage=120, max_damage=1561),
         cslot(35, 47, "minecraft:lapis_lazuli", 3, bg="minecraft:container/slot/lapis_lazuli")],
        extras={"enchanting": {
            "offers": [
                {"level": 2, "cost": 5, "lapisCost": 1,
                 "name": "Sharpness II", "available": True},
                {"level": 3, "cost": 17, "lapisCost": 2,
                 "name": "Looting III", "available": True},
                {"level": 4, "cost": 30, "lapisCost": 3,
                 "name": "Sweeping Edge IV", "available": False},
            ],
            "lapisCount": 3,
            "playerLevel": 30,
        }},
    ),
    # An empty third row, which is what an item that can't be enchanted or a
    # table with too few bookshelves actually looks like.
    "enchanting-partial": container(
        "minecraft:enchantment", "Enchant",
        [cslot(15, 47, "minecraft:iron_pickaxe", 1),
         cslot(35, 47, "minecraft:lapis_lazuli", 1, bg="minecraft:container/slot/lapis_lazuli")],
        extras={"enchanting": {
            "offers": [
                {"level": 1, "cost": 3, "lapisCost": 1,
                 "name": "Efficiency I", "available": True},
                {"level": 1, "cost": 8, "lapisCost": 2,
                 "name": "Unbreaking I", "available": False},
                {"level": 0, "cost": 0, "lapisCost": 3, "name": "", "available": False},
            ],
            "lapisCount": 1,
            "playerLevel": 4,
        }},
    ),
    "anvil": container(
        "minecraft:anvil", "Repair & Name",
        [cslot(27, 47, "minecraft:diamond_sword", 1, damage=900, max_damage=1561),
         cslot(76, 47, "minecraft:diamond", 2),
         cslot(134, 47, "minecraft:diamond_sword", 1, damage=300, max_damage=1561,
               may_place=False)],
        extras={"anvil": {"levelCost": 7, "hasResult": True}},
    ),
    "loom": container(
        "minecraft:loom", "Loom",
        [cslot(13, 26, "minecraft:white_banner", 1, bg="minecraft:container/slot/banner"),
         cslot(33, 26, "minecraft:red_dye", 8, bg="minecraft:container/slot/dye"),
         cslot(23, 45, None, bg="minecraft:container/slot/banner_pattern"),
         cslot(143, 57, "minecraft:white_banner", 1, may_place=False)],
        extras={"loom": {
            "options": [{"label": label, "itemId": None} for label in (
                "Base", "Bottom Stripe", "Top Stripe", "Left Stripe", "Right Stripe",
                "Center Stripe", "Middle Stripe", "Down Right Stripe", "Down Left Stripe",
                "Small Stripes", "Diagonal Cross", "Square Cross", "Left of Diagonal",
            )],
            "selected": 3,
        }},
    ),
    # A full four-layer pyramid, so every tier is unlocked -- and the same panel
    # at level 1, where most rows are locked, is the more interesting screenshot.
    "beacon": container(
        "minecraft:beacon", "Beacon",
        [cslot(136, 110, "minecraft:iron_ingot", 1, bg="minecraft:container/slot/ingot")],
        extras={"beacon": {
            "primaryOptions": [
                {"id": "minecraft:speed", "name": "Speed", "tier": 1},
                {"id": "minecraft:haste", "name": "Haste", "tier": 1},
                {"id": "minecraft:resistance", "name": "Resistance", "tier": 2},
                {"id": "minecraft:jump_boost", "name": "Jump Boost", "tier": 2},
                {"id": "minecraft:strength", "name": "Strength", "tier": 3},
            ],
            "secondaryOptions": [
                {"id": "minecraft:regeneration", "name": "Regeneration", "tier": 4},
            ],
            "primary": "minecraft:haste",
            "secondary": None,
            "level": 4,
            "hasPayment": True,
        }},
    ),
    "beacon-low": container(
        "minecraft:beacon", "Beacon",
        [cslot(136, 110, None, bg="minecraft:container/slot/ingot")],
        extras={"beacon": {
            "primaryOptions": [
                {"id": "minecraft:speed", "name": "Speed", "tier": 1},
                {"id": "minecraft:haste", "name": "Haste", "tier": 1},
                {"id": "minecraft:resistance", "name": "Resistance", "tier": 2},
                {"id": "minecraft:jump_boost", "name": "Jump Boost", "tier": 2},
                {"id": "minecraft:strength", "name": "Strength", "tier": 3},
            ],
            "secondaryOptions": [
                {"id": "minecraft:regeneration", "name": "Regeneration", "tier": 4},
            ],
            "primary": None,
            "secondary": None,
            "level": 1,
            "hasPayment": False,
        }},
    ),
    "villager": container(
        "minecraft:merchant", "Librarian",
        [cslot(136, 37, "minecraft:emerald", 12), cslot(162, 37, None),
         cslot(220, 37, "minecraft:enchanted_book", 1, may_place=False, glint=True)],
        extras={"merchant": {
            "trades": [
                {"firstCost": slot("minecraft:paper", 24),
                 "secondCost": slot("minecraft:air", 0),
                 "sell": slot("minecraft:emerald", 1),
                 "disabled": False, "uses": 3, "maxUses": 16},
                {"firstCost": slot("minecraft:emerald", 12),
                 "secondCost": slot("minecraft:book", 1),
                 "sell": slot("minecraft:enchanted_book", 1),
                 "disabled": False, "uses": 0, "maxUses": 12},
                {"firstCost": slot("minecraft:emerald", 5),
                 "secondCost": slot("minecraft:air", 0),
                 "sell": slot("minecraft:lantern", 1),
                 "disabled": True, "uses": 16, "maxUses": 16},
                {"firstCost": slot("minecraft:emerald", 64),
                 "secondCost": slot("minecraft:air", 0),
                 "sell": slot("minecraft:name_tag", 1),
                 "disabled": False, "uses": 1, "maxUses": 3},
            ],
            "experience": 65,
            "levelProgress": 30,
            "leveled": True,
        }},
    ),
    # Tier 1 only -- no extras, but the geometry and the progress art are the
    # whole point: input above fuel, output to the side, flame and arrow between.
    "furnace": container(
        "minecraft:furnace", "Furnace",
        [cslot(56, 17, "minecraft:raw_iron", 23), cslot(56, 53, "minecraft:coal", 41),
         cslot(116, 35, "minecraft:iron_ingot", 7, may_place=False)],
        progress={"cookPixels": 14, "litPixels": 11},
    ),
    "furnace-idle": container(
        "minecraft:furnace", "Furnace",
        [cslot(56, 17, "minecraft:raw_iron", 23), cslot(56, 53, None),
         cslot(116, 35, None, may_place=False)],
        progress={"cookPixels": 0, "litPixels": 0},
    ),
    "brewing": container(
        "minecraft:brewing_stand", "Brewing Stand",
        [cslot(56, 51, "minecraft:potion", 1, bg="minecraft:container/slot/potion"),
         cslot(79, 58, "minecraft:potion", 1, bg="minecraft:container/slot/potion"),
         cslot(102, 51, None, bg="minecraft:container/slot/potion"),
         cslot(79, 17, "minecraft:nether_wart", 5),
         cslot(17, 17, "minecraft:blaze_powder", 6,
               bg="minecraft:container/slot/brewing_fuel")],
        progress={"fuelPixels": 14, "brewPixels": 12, "brewing": True},
    ),
    # No extras and no progress: the plain Tier 1 case, where the only thing
    # doing any work is the slot geometry and the ghost placeholders.
    "grindstone": container(
        "minecraft:grindstone", "Repair & Disenchant",
        [cslot(49, 19, "minecraft:diamond_sword", 1, damage=400, max_damage=1561, glint=True),
         cslot(49, 40, None),
         cslot(129, 34, "minecraft:diamond_sword", 1, damage=400, max_damage=1561,
               may_place=False)],
    ),
    # A crafter, for the disabled-cell state that `disabled` exists for.
    "crafter": container(
        "minecraft:crafter_3x3", "Crafter",
        [cslot(30 + col * 18, 17 + row * 18,
               "minecraft:oak_planks" if (row * 3 + col) % 2 == 0 else None,
               count=8, enabled=(row * 3 + col) not in (2, 5, 6))
         for row in range(3) for col in range(3)]
        + [cslot(124, 35, "minecraft:crafting_table", 1, may_place=False)],
    ),
}

SCENARIOS = {
    # The case the old clamp made unreachable: full health leaves no room in a
    # 10-slot row, so absorption has to wrap to a second row or vanish.
    "absorption": {"absorption": 4.0},
    "absorption-odd": {"absorption": 3.0},
    "absorption-big": {"absorption": 12.0},
    "plain": {},
    "hurt": {"health": 7.0, "food": 4},
    "half-heart": {"health": 19.0},
    "drowning": {"air": 130, "maxAir": 300},
    "drowning-low": {"air": 40, "maxAir": 300},
    # Vanilla draws no armor bar at 0, so neither does this -- but the row's
    # height stays reserved, or everything below it would jump 25dp the moment
    # a chestplate went on. The pair below is the check for that: the hearts
    # must sit at the same y in both.
    "no-armor": {"armor": 0},
    # ...and the bubbles have to stay hard right with the sockets gone.
    "no-armor-drowning": {"armor": 0, "air": 130, "maxAir": 300},
    "reduced-max": {"health": 6.0, "maxHealth": 12.0},
    "dead": {"health": 0.0},
    # The off-hand box stays on screen empty -- unlike vanilla, which hides it
    # -- because it's the tap target for swapping something into it.
    "offhand-empty": {"offhand": EMPTY},
    # No "offhand" key at all: what an older mod build sends. The box should
    # disappear entirely rather than draw an empty one.
    "offhand-absent": {"offhand": None},
    # Streams normally, then says the player is gone and keeps the socket open
    # -- i.e. quit to title, not quit the game. The app must fall back to its
    # waiting states rather than freezing on the last snapshot, and must NOT
    # show "not connected", because the mod is still right there.
    "leave-world": {"__leave_after": 8.0},
}

# One scenario per container, so `capture_hud.ps1 -Scenarios stonecutter,loom,...`
# walks the whole Items tab. The container rides on the state as `__container`
# and is sent once per connection, after the chat backlog -- the same position and
# the same once-only delivery the real mod uses, since this message is
# broadcast on change and opening a container is the change.
for _name, _msg in CONTAINERS.items():
    SCENARIOS[_name] = {"__container": _msg}


def load_assets(jar_path):
    out = {}
    with zipfile.ZipFile(jar_path) as z:
        names = set(z.namelist())
        for key, path in ASSETS.items():
            full = "assets/minecraft/" + path
            if full in names:
                out[key] = base64.b64encode(z.read(full)).decode("ascii")
            else:
                print(f"  MISSING in jar: {key} -> {full}")
                out[key] = None
    return out


def wanted_items():
    """Every item id any scenario can put on screen.

    Collected rather than hardcoded because the container scenarios reference
    dozens between them, and a missing one shows up as a blank cell that looks
    like an app bug rather than a simulator gap.
    """
    items = {s["itemId"] for s in HOTBAR + [OFFHAND]}
    for msg in CONTAINERS.values():
        for entry in msg["slots"]:
            items.add(entry["stack"]["itemId"])
        extras = msg.get("extras") or {}
        for listing in (extras.get("stonecutter"), extras.get("loom")):
            for option in (listing or {}).get("options") or []:
                if option.get("itemId"):
                    items.add(option["itemId"])
        for trade in (extras.get("merchant") or {}).get("trades") or []:
            for key in ("firstCost", "secondCost", "sell"):
                items.add(trade[key]["itemId"])
    items.discard("minecraft:air")
    return sorted(items)


# Items whose icon isn't a same-named texture file. The real mod renders these
# through the model pipeline and always gets something; this simulator only has
# flat files, so without a hint they come back blank -- which would make a
# screenshot of the stonecutter's grid look like a rendering failure rather than
# the simulator's known limitation.
ICON_FALLBACKS = {
    "minecraft:stone_stairs": "block/stone",
    "minecraft:stone_slab": "block/smooth_stone_slab_side",
    "minecraft:stone_brick_stairs": "block/stone_bricks",
    "minecraft:stone_brick_slab": "block/stone_bricks",
    "minecraft:stone_brick_wall": "block/stone_bricks",
    "minecraft:stone_button": "block/stone",
    "minecraft:stone_pressure_plate": "block/stone",
    "minecraft:crafting_table": "block/crafting_table_front",
    # Banners have no flat texture at all -- vanilla builds them from an entity
    # model plus pattern layers. The wool is the closest honest stand-in, and it's
    # why the loom's own options come across as names rather than previews.
    "minecraft:white_banner": "block/white_wool",
    "minecraft:oak_planks": "block/oak_planks",
    "minecraft:lantern": "item/lantern",
    "minecraft:torch": "block/torch",
}


def load_item_textures(jar_path):
    """Cheap stand-in for ItemIconRenderer: the flat item/block texture.

    Not isometric -- this simulator is for exercising the app's layout, not for
    judging icon fidelity. See ICON_FALLBACKS for the items where a flat file
    doesn't exist under the item's own name.
    """
    tex = {}
    with zipfile.ZipFile(jar_path) as z:
        names = set(z.namelist())

        def read(path):
            full = "assets/minecraft/textures/" + path + ".png"
            if full in names:
                return base64.b64encode(z.read(full)).decode("ascii")
            return None

        missing = []
        for item in wanted_items():
            short = item.split(":", 1)[1]
            data = read(f"item/{short}") or read(f"block/{short}")
            if data is None and item in ICON_FALLBACKS:
                data = read(ICON_FALLBACKS[item])
            if data is None:
                missing.append(item)
            else:
                tex[item] = data
        if missing:
            print(f"  no flat texture for: {', '.join(missing)}")
    return tex


MAP_SIZE = 128


def fake_map_png():
    """A synthetic map tile, so the map tab can be exercised without a world.

    Deliberately not a real terrain render -- it's a coarse blob pattern with a
    river and a beach, enough to check scaling, whole-pixel snapping, marker
    placement and rotation. Judging actual map fidelity needs the real mod.
    """
    import struct
    import zlib

    def px(x, z):
        # Rough continent shape from a couple of offset sine ridges.
        import math
        h = (math.sin(x / 19.0) * math.cos(z / 23.0)
             + 0.5 * math.sin((x + z) / 11.0))
        checker = (x + z) & 1
        if h < -0.45:                       # water, depth-shaded like vanilla
            base = (64, 64, 255)
            f = (-h - 0.45) * 3.0 + checker * 0.2
            scale = 255 if f < 0.5 else (180 if f > 0.9 else 220)
        elif h < -0.30:                     # sand
            base, scale = (247, 233, 163), 220 if checker else 255
        elif h < 0.55:                      # grass
            base, scale = (127, 178, 56), 220 if checker else 255
        else:                               # stone
            base, scale = (112, 112, 112), 180 if checker else 220
        return bytes(min(255, c * scale // 255) for c in base) + b"\xff"

    raw = b"".join(
        b"\x00" + b"".join(px(x, z) for x in range(MAP_SIZE))
        for z in range(MAP_SIZE)
    )

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", MAP_SIZE, MAP_SIZE, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    return base64.b64encode(png).decode("ascii")


# A representative slice of what KeyBindingCatalog sends: enough categories to
# exercise the picker's grouping, one unbound entry, and one from a "mod" so the
# fact that the list is data rather than a hardcoded table is actually visible.
BINDINGS = [
    {"id": "key.attack", "label": "Attack/Destroy", "category": "Gameplay",
     "boundKey": "Left Button", "unbound": False},
    {"id": "key.use", "label": "Use Item/Place Block", "category": "Gameplay",
     "boundKey": "Right Button", "unbound": False},
    {"id": "key.pickItem", "label": "Pick Block", "category": "Gameplay",
     "boundKey": "Middle Button", "unbound": False},
    {"id": "key.drop", "label": "Drop Selected Item", "category": "Inventory",
     "boundKey": "Q", "unbound": False},
    {"id": "key.inventory", "label": "Open/Close Inventory", "category": "Inventory",
     "boundKey": "E", "unbound": False},
    {"id": "key.swapOffhand", "label": "Swap Item With Offhand", "category": "Inventory",
     "boundKey": "F", "unbound": False},
    {"id": "key.hotbar.1", "label": "Hotbar Slot 1", "category": "Inventory",
     "boundKey": "1", "unbound": False},
    {"id": "key.jump", "label": "Jump", "category": "Movement",
     "boundKey": "Space", "unbound": False},
    {"id": "key.sneak", "label": "Sneak", "category": "Movement",
     "boundKey": "Left Shift", "unbound": False},
    {"id": "key.sprint", "label": "Sprint", "category": "Movement",
     "boundKey": "Left Control", "unbound": False},
    {"id": "key.advancements", "label": "Advancements", "category": "Miscellaneous",
     "boundKey": "L", "unbound": False},
    {"id": "key.screenshot", "label": "Take Screenshot", "category": "Miscellaneous",
     "boundKey": "F2", "unbound": False},
    # The case worth seeing in the picker: usable from the second screen even
    # though no key triggers it in game.
    {"id": "key.spectatorOutlines", "label": "Highlight Players", "category": "Miscellaneous",
     "boundKey": "Not bound", "unbound": True},
    {"id": "key.examplemod.dash", "label": "Dash", "category": "Example Mod",
     "boundKey": "V", "unbound": False},
]


# What the mod pushes as its chat backlog on connect. Picked to exercise the
# cases the log can get wrong rather than to look like a real session:
# multi-run colouring, a line long enough to wrap several times, an empty-ish
# system line, and characters outside the ascii sheet.
CHAT_BACKLOG = [
    [{"text": "Steve joined the game", "color": 0xFFFF55}],
    [{"text": "<Steve> ", "color": 0xFFFFFF}, {"text": "hey, over here"}],
    [{"text": "<Alex> "}, {"text": "the diamonds are at "}, {"text": "-412, 12, 288",
                                                             "color": 0x55FFFF}],
    [{"text": "<Steve> "},
     {"text": "this one is deliberately long enough to wrap across several "
              "lines of the panel, which is the thing most likely to be wrong "
              "-- a break in the middle of a word, a lost space, or a line that "
              "runs off the right edge"}],
    [{"text": "Alex was slain by Zombie", "color": 0xFF5555}],
    # Outside the ascii.png sheet: should draw as '?' rather than vanish or
    # shift the rest of the line.
    [{"text": "<Ελένη> "}, {"text": "καλημέρα"}],
]


def chat_line(text, color=None):
    return {"type": "chat", "segments": [{"text": text, "color": color}]}


def apply_container_command(msg, cmd):
    """Applies a container verb to the open container. True if it changed.

    Only the effects that are *visible* are modelled -- a selection moving, a
    beacon's chosen effects. `RENAME:` and `TRADE:` change the result slot and the
    cost slots in game, which would mean reimplementing anvil pricing and trade
    lists; they're acknowledged and logged instead. That's the honest line for a
    layout simulator, and it's why the README says green screenshots here mean
    "the app renders this correctly", not "the feature works end to end".
    """
    extras = msg.get("extras") or {}
    try:
        if cmd.startswith("BUTTON:"):
            _sync, button = (int(p) for p in cmd[len("BUTTON:"):].split(","))
            for key in ("stonecutter", "loom"):
                listing = extras.get(key)
                if listing and 0 <= button < len(listing["options"]):
                    listing["selected"] = button
                    return True
            # An enchanting offer: the table consumes the item and the offers go
            # away, which is a state worth being able to see.
            if extras.get("enchanting"):
                offers = extras["enchanting"]["offers"]
                if 0 <= button < len(offers) and offers[button]["available"]:
                    for offer in offers:
                        offer.update(level=0, cost=0, name="", available=False)
                    msg["slots"][0]["stack"] = slot("minecraft:air", 0)
                    return True
            return False

        if cmd.startswith("BEACON:"):
            _sync, primary, secondary = cmd[len("BEACON:"):].split(",")
            beacon = extras.get("beacon")
            if beacon:
                beacon["primary"] = None if primary == "-" else primary
                beacon["secondary"] = None if secondary == "-" else secondary
                return True
            return False

        if cmd.startswith("SLOTSTATE:"):
            _sync, slot_id, enabled = (int(p) for p in cmd[len("SLOTSTATE:"):].split(","))
            if 0 <= slot_id < len(msg["slots"]):
                if enabled:
                    msg["slots"][slot_id].pop("disabled", None)
                else:
                    msg["slots"][slot_id]["disabled"] = True
                return True
            return False
    except ValueError:
        print(f"[sim] could not parse {cmd!r}")
    return False


def handle(conn, addr, assets, item_tex, state, open_container=None):
    """Serves one client.

    [open_container] is passed alongside [state] rather than living inside it,
    because `state` is streamed verbatim as the HUD snapshot twice a second -- a
    container riding in that dict would be re-sent with every snapshot, which is
    the opposite of the on-change delivery it's meant to demonstrate. Taking it
    out of `state` by popping wouldn't do either: `state` is shared across
    connections, so the first client would consume it and every reconnect after
    (which is exactly what capture_hud.ps1 does) would see no container at all.
    """
    print(f"[sim] client connected: {addr}")
    send_lock = threading.Lock()

    def send(obj):
        with send_lock:
            conn.sendall((json.dumps(obj) + "\n").encode("utf-8"))

    # Asset bundle first, then the key bindings -- the order the mod pushes
    # them to each new connection.
    for key, data in assets.items():
        send({"type": "asset", "assetId": key, "data": data})
    print(f"[sim] sent {len(assets)} assets")
    send({"type": "bindings", "bindings": BINDINGS})
    print(f"[sim] sent {len(BINDINGS)} key bindings")
    # Then the chat backlog, in the mod's order -- it goes last there because
    # it needs the font sheet that came with the asset bundle.
    for segments in CHAT_BACKLOG:
        send({"type": "chat", "segments": segments})
    print(f"[sim] sent {len(CHAT_BACKLOG)} chat messages")

    # The open container, last and once -- the mod's own order and frequency. It's
    # broadcast on change rather than streamed, and it's replayed to each new
    # connection precisely so a client that connects while nothing moves still has
    # something to draw.
    if open_container:
        send(open_container)
        print(f"[sim] sent container {open_container['handlerType']} "
              f"({len(open_container['slots'])} slots, "
              f"extras={sorted((open_container.get('extras') or {}).keys())})")

    stop = threading.Event()

    def reader():
        buf = b""
        while not stop.is_set():
            try:
                chunk = conn.recv(4096)
            except OSError:
                break
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                cmd = line.decode("utf-8", "replace").strip()
                if not cmd:
                    continue
                print(f"[sim] <- {cmd}")
                if cmd.startswith("ICON:"):
                    item = cmd[5:]
                    send({"type": "icon", "itemId": item, "data": item_tex.get(item)})
                elif cmd == "ASSETS":
                    for k, d in assets.items():
                        send({"type": "asset", "assetId": k, "data": d})
                elif cmd == "BINDINGS":
                    send({"type": "bindings", "bindings": BINDINGS})
                elif cmd.startswith("CHAT:"):
                    said = cmd[5:]
                    # The real server echoes what you say back as a chat
                    # message, and that echo is the only on-screen confirmation
                    # the app ever gets that sending worked -- so the sim has
                    # to do it too, or the Chat tab looks broken here and fine
                    # on device.
                    if said.startswith("/"):
                        send(chat_line(f"Unknown command: {said}", 0xFF5555))
                    else:
                        send({"type": "chat", "segments": [
                            {"text": "<You> "}, {"text": said}]})
                elif open_container is not None:
                    # The container verbs. Answered by mutating the open
                    # container and re-sending it, because the app applies
                    # nothing optimistically -- a tap that isn't reflected back
                    # looks like a dead button, so a simulator that only logged
                    # these would make every panel look broken.
                    changed = apply_container_command(open_container, cmd)
                    if changed:
                        send(open_container)
        stop.set()

    threading.Thread(target=reader, daemon=True).start()

    map_png = fake_map_png()
    yaw = 0.0
    walk = 0.0
    ticks = 0

    # "quit to title": stop describing a player, but stay connected. The app
    # has to tell that apart from a disconnect, which is the whole reason the
    # mod sends a message instead of just going quiet.
    leave_after = state.pop("__leave_after", None)
    started = time.time()
    left = False

    try:
        while not stop.is_set():
            if leave_after is not None and not left and time.time() - started >= leave_after:
                send({"type": "noplayer"})
                print("[sim] sent noplayer; holding the socket open")
                left = True
            if left:
                time.sleep(0.5)
                continue

            send(state)

            # A message every ~10s, so the log's live behaviour -- following the
            # tail, and *not* following it while you're scrolled up reading --
            # is visible without needing a second player.
            ticks += 1
            if ticks % 20 == 0:
                send(chat_line(f"<Alex> still here ({ticks // 20})"))

            # Drift the player and spin the yaw so the marker is visibly live
            # and the tile origin actually moves, which is what catches
            # origin/marker mismatches.
            walk += 0.6
            yaw = (yaw + 7.0) % 360.0
            origin_x, origin_z = -64, -64
            send({
                "type": "map",
                "data": map_png,
                "originX": origin_x,
                "originZ": origin_z,
                "playerX": origin_x + MAP_SIZE / 2 + (walk % 20) - 10,
                "playerZ": origin_z + MAP_SIZE / 2,
                "yaw": yaw,
                "size": MAP_SIZE,
            })
            time.sleep(0.5)
    except OSError:
        pass
    print(f"[sim] client gone: {addr}")


def main():
    jar = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) > 2 else "absorption"
    if name not in SCENARIOS:
        print(f"unknown scenario {name!r}; have: {', '.join(sorted(SCENARIOS))}")
        return 2

    state = dict(BASE)
    state.update(SCENARIOS[name])
    # Kept out of the streamed snapshot -- see handle().
    open_container = state.pop("__container", None)
    # Summarised, not dumped: a container scenario's value is a 38-slot message
    # and printing it verbatim buries every other line of the log.
    overrides = {k: v for k, v in SCENARIOS[name].items() if k != "__container"}
    summary = ", ".join(f"{k}={v}" for k, v in overrides.items()) or "(base)"
    if open_container:
        summary += (f", container={open_container['handlerType']}"
                    f" extras={sorted((open_container.get('extras') or {}).keys()) or 'none'}")
    print(f"[sim] scenario {name}: {summary}")

    print("[sim] loading textures from jar...")
    assets = load_assets(jar)
    item_tex = load_item_textures(jar)
    print(f"[sim] {sum(1 for v in assets.values() if v)} assets, {len(item_tex)} item textures")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(4)
    print(f"[sim] listening on {PORT}")

    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle,
                         args=(conn, addr, assets, item_tex, state, open_container),
                         daemon=True).start()


if __name__ == "__main__":
    sys.exit(main() or 0)
