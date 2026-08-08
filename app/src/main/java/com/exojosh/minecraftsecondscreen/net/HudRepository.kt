package com.exojosh.minecraftsecondscreen.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ThorHudRepository"
private const val HOST = "127.0.0.1"
private const val PORT = 48291
/** Icons are ~4KB PNGs each. A player who opens a lot of containers can ask
 *  for hundreds; this caps what's held without being small enough to thrash on
 *  a normal inventory (46 slots) plus a container (54). */
private const val ICON_CACHE_LIMIT = 200

/** How long an unanswered ICON: request stays "in flight" before we re-send it.
 *  The mod renders at most a couple of icons per tick, so give it room. */
private const val ICON_REQUEST_TIMEOUT_MS = 3000L

/** Backoff after the mod explicitly says it has no icon for an item. */
private const val ICON_FAILURE_RETRY_MS = 60_000L

/** How many chat messages are kept. Matches the mod's own backlog cap, which
 *  in turn matches vanilla's ChatHud, so scrolling back here reaches exactly as
 *  far as scrolling back on the top screen would. */
private const val CHAT_HISTORY_LIMIT = 100

/**
 * How many connections may come and go without the mod ever identifying itself
 * before we call it a port conflict.
 *
 * More than one, because a genuine mod restart can drop a connection mid-
 * handshake; three in a row is not that.
 */
private const val SILENT_CONNECTIONS_BEFORE_SUSPECTING_CONFLICT = 3

data class HotbarSlot(
    val itemId: String,
    val count: Int,
    val damage: Int,
    val maxDamage: Int,
    val hasGlint: Boolean
) {
    /** null when the item isn't damageable at all -- distinguishes "no bar to draw"
     *  from "bar at 0%," matching how maxDamage=0 comes through from the mod. */
    val durabilityFraction: Float?
        get() = if (maxDamage <= 0) null else 1f - (damage.toFloat() / maxDamage.toFloat())
}

/**
 * One top-down map tile from the mod, one pixel per block.
 *
 * [originX]/[originZ] are the world coordinates of the tile's **top-left**
 * pixel, sent by the mod rather than derived here -- the app would otherwise
 * have to reimplement MapRenderer's centring and rounding just to know where
 * the player marker goes, and drift the moment either side changed.
 *
 * [yaw] is Minecraft's convention: degrees, 0 = facing south (+Z), increasing
 * clockwise.
 */
data class MapTile(
    val bitmap: Bitmap,
    val originX: Int,
    val originZ: Int,
    val playerX: Double,
    val playerZ: Double,
    val yaw: Float,
    val size: Int
) {
    /** Player position in tile pixels, fractional so the marker moves smoothly
     *  between blocks instead of snapping. */
    val playerPixelX: Float get() = (playerX - originX).toFloat()
    val playerPixelZ: Float get() = (playerZ - originZ).toFloat()
}

/**
 * One of the game's key bindings, as the mod reports it.
 *
 * [id] is the binding's translation key (`key.inventory`) and is the only part
 * that's a contract: it's what gets persisted into an input-grid slot and sent
 * back as `BIND:<id>`. The rest is display text the mod has already translated,
 * because only the game side has the language files.
 *
 * [boundKey] and [unbound] are a **snapshot** — a player rebinding a key
 * mid-session won't push an update, so the printed key name can lag until the
 * app re-requests the list. The ids don't go stale, so buttons keep working
 * either way; only the label can be wrong.
 */
data class GameBinding(
    val id: String,
    val label: String,
    val category: String,
    val boundKey: String,
    val unbound: Boolean
)

/**
 * One run of chat text sharing a colour.
 *
 * [color] is an RGB value from the game's own text styling, or null for "the
 * default" — the mod omits the field entirely in that case. Only colour
 * survives the mod's flattening of Minecraft's Text tree; bold/italic/
 * obfuscated are dropped, because this app draws from a bitmap font sheet
 * where each of those would need its own draw pass, and colour is the part
 * that carries meaning in a chat log (team colours, a red death message, a
 * yellow join notice).
 */
data class ChatSegment(val text: String, val color: Int?)

/**
 * One chat line as it arrived from the mod.
 *
 * [id] is assigned here rather than sent, purely so a list key is stable —
 * two identical messages ("hi" twice) are genuinely different entries and must
 * not collapse into one in a `LazyColumn`.
 */
data class ChatMessage(val id: Long, val segments: List<ChatSegment>) {
    val plainText: String get() = segments.joinToString("") { it.text }
}

/**
 * One inventory slot: what's in it, where it sits, and what may be done with it.
 *
 * [x]/[y] are vanilla's own GUI coordinates for the slot's 16x16 item area, as
 * the game's handler placed them — the 18x18 cell drawn around it starts one
 * pixel up and to the left. They're what lets `ContainerLayout` draw a furnace
 * as a furnace instead of as three cells in a row, with no per-container table
 * on this side. `-1` means the mod build predates them.
 */
data class ContainerSlot(
    val stack: HotbarSlot,
    val mayPlace: Boolean,
    val x: Int = -1,
    val y: Int = -1,
    /**
     * Sprite id of vanilla's empty-slot placeholder — the sword outline in a
     * grindstone, the flame in a brewing stand's fuel slot. Null for most slots.
     * Resolve it through [ResourcePackIconProvider.getSpriteBitmap].
     */
    val background: String? = null,
    /** False only when the handler has switched the slot off: a crafter's
     *  disabled cell, a horse with no chest. */
    val enabled: Boolean = true
)

/**
 * How far along whatever the open container is doing has got, in the pixel
 * counts vanilla itself would draw.
 *
 * The mod quantises to pixels rather than sending raw progress, because this
 * whole message is broadcast on change and a raw cook progress changes every
 * tick — see `ContainerRelay.ProgressState`. Null fields mean the container has
 * no such indicator.
 */
data class ContainerProgress(
    /** Furnace family: the burn arrow's width, 0..24. */
    val cookPixels: Int? = null,
    /** Furnace family: the flame's height, 0..14. Zero means "not lit" — vanilla
     *  always draws at least one row while burning. */
    val litPixels: Int? = null,
    /** Brewing stand: the fuel bar's width, 0..18. */
    val fuelPixels: Int? = null,
    /** Brewing stand: the brew arrow's height, 0..28. */
    val brewPixels: Int? = null,
    /** Brewing stand: whether it's brewing. The bubble column's phase is
     *  deliberately not sent — this app animates it from its own frame clock,
     *  the same split as the enchant glint. */
    val brewing: Boolean = false
)

/**
 * The open screen handler, as the mod reports it.
 *
 * With no container open this is the player's own inventory — the mod streams
 * `currentScreenHandler` either way, because a move is a *click on a slot* and
 * the server only honours those against the handler it believes is open.
 *
 * [playerStart]/[hotbarStart]/[armorStart]/[offhandIndex] are sent rather than
 * derived here. Vanilla's convention (the player's 36 slots are the last 36 of
 * any container) holds for every vanilla handler, but knowing it is the mod's
 * job — if something ever breaks the convention it gets fixed in one place.
 * [armorStart] and [offhandIndex] are -1 for anything but the player's own
 * inventory.
 *
 * [syncId] is echoed back with every click as a staleness check: a chest can
 * close between this arriving and a finger landing, and "slot 3" then means
 * something completely different.
 */
/**
 * One selectable thing in a container's list: a stonecutter recipe or a loom
 * pattern.
 *
 * [itemId] is present for a stonecutter, whose options are real items and so can
 * be drawn as icons through the existing `ICON:` path. It's null for a loom,
 * whose patterns aren't items at all — vanilla renders those previews off a
 * banner model — so those show as names. The index in the list **is** the button
 * id, so never sort or filter these.
 */
data class ContainerOption(val label: String, val itemId: String?)

/** A stonecutter's recipes or a loom's patterns, with the current choice.
 *  [selected] is -1 when nothing is chosen. */
data class SelectableList(val options: List<ContainerOption>, val selected: Int)

/**
 * One of the enchanting table's three offers.
 *
 * [name] is the enchantment's real name, which vanilla hides behind a hover
 * tooltip and prints as an obfuscated galactic phrase on the row itself. There's
 * no hover on a touch screen and the information isn't secret, so the mod sends
 * it plainly — see `ContainerExtras.Offer`. Empty for a row with no offer.
 *
 * [available] is vanilla's own enable rule (enough lapis, enough levels, an offer
 * present, or creative), computed mod-side so the app greys exactly what the game
 * would refuse.
 */
data class EnchantOffer(
    val level: Int,
    val cost: Int,
    val lapisCost: Int,
    val name: String,
    val available: Boolean
)

data class EnchantingOffers(
    val offers: List<EnchantOffer>,
    val lapisCount: Int,
    val playerLevel: Int
)

/** One villager trade. [secondCost] is empty for the many trades that want one
 *  item. [disabled] is out-of-stock, greyed rather than hidden — removing the row
 *  would renumber every trade below it while the player was looking at it. */
data class VillagerTrade(
    val firstCost: HotbarSlot,
    val secondCost: HotbarSlot,
    val sell: HotbarSlot,
    val disabled: Boolean,
    val uses: Int,
    val maxUses: Int
)

data class MerchantTrades(
    val trades: List<VillagerTrade>,
    val experience: Int,
    val levelProgress: Int,
    val leveled: Boolean
)

/** One beacon effect. [tier] is the beacon level it needs, so a locked option can
 *  be shown as locked rather than omitted — leaving it out would have the player
 *  wondering what the upper rows are for. */
data class BeaconEffect(val id: String, val name: String, val tier: Int)

data class BeaconState(
    val primaryOptions: List<BeaconEffect>,
    val secondaryOptions: List<BeaconEffect>,
    val primary: String?,
    val secondary: String?,
    val level: Int,
    val hasPayment: Boolean
)

/** The anvil's XP cost and whether it has a result to take. */
data class AnvilState(val levelCost: Int, val hasResult: Boolean)

/**
 * The part of a container that isn't slots.
 *
 * One nullable field per kind rather than a `kind` discriminator plus a union of
 * fields: the wire form says what it is by which key is present, and neither side
 * has a switch that can disagree with the data. Null for the great majority of
 * containers, which have nothing beyond their slots.
 */
data class ContainerExtras(
    val enchanting: EnchantingOffers? = null,
    val stonecutter: SelectableList? = null,
    val loom: SelectableList? = null,
    val merchant: MerchantTrades? = null,
    val beacon: BeaconState? = null,
    val anvil: AnvilState? = null
) {
    /** Whether there's anything at all to draw a panel for. Guards against a
     *  future kind arriving from a newer mod build that this app can't render:
     *  an `extras` object with every known field absent must read as "nothing",
     *  not as an empty panel taking half the screen. */
    val isEmpty: Boolean
        get() = enchanting == null && stonecutter == null && loom == null &&
            merchant == null && beacon == null && anvil == null

    /**
     * Whether these controls want a narrow rail down the right rather than a band
     * across the top.
     *
     * Only the villager does, and it's a shape argument rather than a preference.
     * Every other container's choice is a *row* of things — recipes, patterns,
     * effects, three offers — which reads naturally as a horizontal strip. A trade
     * is itself three stacks laid out left to right, so a list of trades is a list
     * of rows, and rows stack downwards. Turned sideways each trade became a wide
     * card and only three or four fitted on screen at once; stacked in a rail they
     * all fit, and the rail only has to be as wide as one trade.
     */
    val prefersSideRail: Boolean get() = merchant != null
}

data class ContainerState(
    val syncId: Int,
    val handlerType: String?,
    /** The container's name as the game titles it ("Furnace", or a renamed
     *  chest's own name). Null for the player's own handler, which the mod
     *  deliberately leaves unlabelled — see `ContainerRelay.ScreenHandlerState`. */
    val title: String? = null,
    /** What's "on the mouse". Non-empty means a move is half-finished — it has
     *  to be drawn, or the player can't see where their item went. */
    val cursor: HotbarSlot,
    val slots: List<ContainerSlot>,
    val playerStart: Int,
    val hotbarStart: Int,
    val armorStart: Int,
    val offhandIndex: Int,
    val progress: ContainerProgress? = null,
    val extras: ContainerExtras? = null
) {
    val isPlayerInventory: Boolean get() = armorStart >= 0
    val hasCursorStack: Boolean get() = cursor.itemId != "minecraft:air" && cursor.count > 0

    /** Slots belonging to the open container rather than to the player. Empty
     *  for the player's own inventory. */
    val containerRange: IntRange get() = 0 until playerStart

    /**
     * Whether the mod sent slot coordinates, i.e. whether the container region
     * can be laid out in vanilla's own shape.
     *
     * Checked against the *container* slots rather than all of them, because
     * that's the only region whose layout depends on it, and a build old enough
     * to omit them should fall back for exactly that region and nothing else.
     */
    val hasSlotPositions: Boolean
        get() = containerRange.any { slots.getOrNull(it)?.x?.let { x -> x >= 0 } == true }
}

data class HudState(
    val health: Float,
    val maxHealth: Float,
    /** Extra "golden heart" health on top of [health], from absorption
     *  effects. Zero most of the time. */
    val absorption: Float,
    val food: Int,
    val xpLevel: Int,
    val xpProgress: Float,
    val armor: Int,
    val selectedSlot: Int,
    /** Remaining breath in ticks, and its current maximum (300 unenchanted,
     *  raised by Respiration). Vanilla only shows bubbles while air < maxAir. */
    val air: Int,
    val maxAir: Int,
    /**
     * The game mode's own name, or null against an older mod build.
     *
     * Sent raw rather than reduced to "show the status bars", so the HUD
     * decision stays on the side drawing the HUD — [hasStatusBars] applies
     * vanilla's rule (`interactionManager.hasStatusBars()`, i.e. survival or
     * adventure) and null defaults to *showing* them, which is what every build
     * before this field did.
     */
    val gameMode: String? = null,
    /** Which heart sprite set applies: NORMAL, POISONED, WITHERED or FROZEN. */
    val heartType: String = "NORMAL",
    /** Hardcore worlds use a different rim on every heart sprite. */
    val hardcore: Boolean = false,
    val hotbar: List<HotbarSlot>,
    /** What's in the off-hand, or null against a mod build that predates the
     *  field. Null hides the off-hand box entirely; an *empty* off-hand comes
     *  through as a minecraft:air slot and still draws the box, because it's
     *  the tap target for swapping into. */
    val offhand: HotbarSlot? = null
) {
    val isDrowning: Boolean get() = maxAir > 0 && air < maxAir

    /** Vanilla hides hearts, hunger, armor, bubbles and the XP bar outside
     *  survival-like modes (`ClientPlayerInteractionManager.hasStatusBars`).
     *  Unknown means show them — that's what every build before this did. */
    val hasStatusBars: Boolean
        get() = gameMode == null || gameMode == "SURVIVAL" || gameMode == "ADVENTURE"

    /** Vanilla draws no hotbar for a spectator either. */
    val isSpectator: Boolean get() = gameMode == "SPECTATOR"
}

/**
 * Owns the connection to the mod's loopback socket. Exposes:
 *  - hudState: null when disconnected (mod not running / game not loaded),
 *    non-null once we've received at least one snapshot.
 *  - isConnected: drives whether the Presentation should even be showing --
 *    this is how "only show the second screen if the mod is present" works,
 *    with zero mod-detection logic needed on the launcher side.
 *  - sendCommand(): fire-and-forget a short code (e.g. "R") to the mod.
 *
 * Runs its own reconnect loop so the companion app can be started before,
 * during, or after Minecraft, in any order, and just keeps trying.
 */
class HudRepository(private val scope: CoroutineScope) {

    private val _hudState = MutableStateFlow<HudState?>(null)
    val hudState: StateFlow<HudState?> = _hudState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * Something is accepting connections on 48291, but it isn't the mod.
     *
     * This has a real cause worth naming: a leftover
     * `adb reverse tcp:48291 tcp:48291` leaves **adbd** owning the port, and
     * adbd accepts every connection and instantly drops it. From here that used
     * to be indistinguishable from an ordinary flapping connection, so the
     * second screen alternated "Not connected" with "Waiting for map data…" and
     * said nothing about the actual problem — it looked exactly like a broken
     * app update.
     *
     * Detected by absence of the mod's hello line rather than by timing, so it
     * doesn't depend on how fast the other end drops us.
     */
    private val _portConflict = MutableStateFlow(false)
    val portConflict: StateFlow<Boolean> = _portConflict.asStateFlow()

    /** Consecutive connections that closed without the mod ever saying hello. */
    private var silentConnections = 0

    /** Whether the mod identified itself on the *current* connection. */
    private var sawHello = false

    /** Consecutive failed/short-lived connections, driving [ReconnectPolicy].
     *  Reset by the mod's hello, which is the only proof we're really talking
     *  to it — a socket that opens and instantly closes is not a success. */
    private var failedConnections = 0

    private val _mapTile = MutableStateFlow<MapTile?>(null)
    val mapTile: StateFlow<MapTile?> = _mapTile.asStateFlow()

    /**
     * Every key binding the game has, pushed by the mod on connect.
     *
     * Deliberately **not** cleared on disconnect, for the same reason
     * [assetCache] isn't: the mod re-sends the whole list on reconnect, and
     * keeping the old copy means the input grid keeps its labels through a
     * game restart instead of flashing back to raw binding ids.
     */
    private val _bindings = MutableStateFlow<List<GameBinding>>(emptyList())
    val bindings: StateFlow<List<GameBinding>> = _bindings.asStateFlow()

    /**
     * The chat log, oldest first, capped at [CHAT_HISTORY_LIMIT].
     *
     * Cleared on disconnect — unlike [bindings] and [assetCache], which are
     * kept precisely *because* the mod re-sends them. The mod re-sends chat
     * too: it holds its own backlog and pushes it to each new connection. So
     * keeping the old copy here wouldn't preserve anything, it would duplicate
     * every message the backlog repeats.
     */
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    /** Only ever touched from the socket reader thread. */
    private var nextChatId = 0L

    /**
     * The open screen handler — the player's own inventory when no container
     * is open.
     *
     * Cleared on disconnect: a stale inventory is worse than none, because
     * every slot in it is a tap target that would send a click against a
     * handler that no longer exists.
     */
    private val _container = MutableStateFlow<ContainerState?>(null)
    val container: StateFlow<ContainerState?> = _container.asStateFlow()

    /** Item id -> decoded icon bitmap. mutableStateMapOf so Compose recomposes
     *  automatically the moment a requested icon arrives -- no manual refresh needed. */
    val iconCache = mutableStateMapOf<String, Bitmap>()

    /**
     * Icon ids in least-recently-used order, so [iconCache] can be bounded.
     *
     * It used to grow without limit. That was survivable while the app only
     * ever showed nine hotbar slots; with the inventory and containers on
     * screen a session can touch hundreds of distinct items, each a decoded
     * ARGB_8888 bitmap held forever.
     *
     * LRU rather than insertion order because the items on screen are asked
     * for every frame — evicting by age would throw out exactly the icons
     * being looked at.
     */
    private val iconUsage = LinkedHashSet<String>()

    /** HUD texture key (HudAssetCatalog's names) -> decoded bitmap, pushed by
     *  the mod on connect. Also Compose-observable, so the HUD redraws itself
     *  as the bundle streams in rather than needing a load gate. */
    val assetCache = mutableStateMapOf<String, Bitmap>()

    /** Assets the mod explicitly reported it can't provide. Distinguishes
     *  "not delivered yet" from "never coming", so callers fall back at the
     *  right moment instead of rendering nothing indefinitely. */
    private val unavailableAssets = mutableSetOf<String>()

    fun isAssetUnavailable(assetId: String) = assetId in unavailableAssets

    /**
     * Item id -> when we last asked the mod for it. This used to be a plain
     * "already requested" Set, which meant a request that never got answered
     * (mod couldn't resolve the item, or we asked before the socket was up and
     * sendCommand silently dropped it) permanently blocked that item from ever
     * being requested again. That was the actual cause of icons showing up
     * inconsistently. Timestamping instead lets an unanswered request expire
     * and be retried.
     *
     * Touched from the UI thread (requestIcon, during composition) and the IO
     * thread (response handling), hence the concurrent map.
     */
    private val pendingIconRequests = ConcurrentHashMap<String, Long>()

    /** Item id -> when the mod last told us it has no icon for this item.
     *  Retried far more slowly than a dropped request, but not never -- the
     *  answer can change when the player switches resource packs. */
    private val failedIconRequests = ConcurrentHashMap<String, Long>()

    @Volatile
    private var writer: PrintWriter? = null

    fun start() {
        scope.launch(Dispatchers.IO) { connectionLoop() }
    }

    /** Returns the cached icon if we already have it; otherwise fires a
     *  request and returns null -- the UI will recompose once iconCache
     *  updates. Safe to call every frame: repeat calls for an item we're
     *  already waiting on are dropped until the request goes stale. */
    fun requestIcon(itemId: String): Bitmap? {
        iconCache[itemId]?.let {
            // Called during composition for everything on screen, which is
            // exactly the signal LRU wants.
            synchronized(iconUsage) {
                iconUsage.remove(itemId)
                iconUsage.add(itemId)
            }
            return it
        }
        if (itemId.isEmpty() || itemId == "minecraft:air") return null

        val now = SystemClock.elapsedRealtime()

        failedIconRequests[itemId]?.let { failedAt ->
            if (now - failedAt < ICON_FAILURE_RETRY_MS) return null
            failedIconRequests.remove(itemId)
        }

        val lastRequestedAt = pendingIconRequests[itemId]
        if (lastRequestedAt != null && now - lastRequestedAt < ICON_REQUEST_TIMEOUT_MS) return null

        // Don't mark it pending if there's nowhere to send it -- otherwise the
        // request is dropped on the floor but still counts as "asked," and the
        // item goes iconless until the timeout expires.
        if (writer == null) return null

        pendingIconRequests[itemId] = now
        sendCommand("ICON:$itemId")
        return null
    }

    private suspend fun connectionLoop() {
        while (true) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), 1000)
                    socket.soTimeout = 5000

                    sawHello = false
                    writer = PrintWriter(socket.getOutputStream(), true)
                    _isConnected.value = true
                    // Not "connected to the mod" -- at this point all we know
                    // is that something accepted. The hello line is what says
                    // which; see [portConflict].
                    Log.i(TAG, "Socket open to $HOST:$PORT")

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    var line: String?
                    while (true) {
                        line = try {
                            reader.readLine()
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        if (line == null) break // mod closed the connection
                        parseAndPublish(line)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Disconnected/failed to connect: ${e.message}")
            }

            writer = null

            // A connection that came and went without the mod ever identifying
            // itself is the signature of something *else* holding this port.
            // Counted rather than acted on immediately, because a genuine mod
            // restart can also drop one mid-handshake.
            if (!sawHello) {
                silentConnections++
                if (silentConnections >= SILENT_CONNECTIONS_BEFORE_SUSPECTING_CONFLICT
                    && !_portConflict.value
                ) {
                    Log.w(TAG, "$silentConnections connections closed with no hello -- "
                        + "something other than the mod is on port $PORT")
                    _portConflict.value = true
                }
            }

            _isConnected.value = false
            _hudState.value = null
            // Drop the map too -- a tile from wherever the player was before
            // the socket died is worse than showing nothing.
            _mapTile.value = null
            // The mod re-sends its whole chat backlog to every new connection,
            // so anything kept here would come back a second time.
            _chatMessages.value = emptyList()
            // Every slot in a stale inventory is a live tap target aimed at a
            // handler that no longer exists -- worse than showing nothing.
            _container.value = null
            // Anything we were still waiting on died with the socket. Drop the
            // in-flight bookkeeping (but keep iconCache) so the next
            // connection re-asks immediately instead of waiting out timeouts.
            pendingIconRequests.clear()
            failedIconRequests.clear()
            // Keep assetCache -- the mod re-sends the bundle on reconnect, and
            // holding the old bitmaps avoids the HUD flashing back to
            // placeholders in between. Misses are cleared so a re-send can
            // fill in anything that was unavailable last time.
            unavailableAssets.clear()

            // Backs off only after a sustained failure -- see ReconnectPolicy
            // for why the first few stay fast.
            val wait = ReconnectPolicy.delayMs(failedConnections)
            failedConnections++
            delay(wait)
        }
    }

    private fun parseAndPublish(line: String) {
        try {
            val json = JSONObject(line)
            when (json.optString("type")) {
                "icon" -> handleIconResponse(json)
                "asset" -> handleAssetResponse(json)
                "map" -> handleMapResponse(json)
                "bindings" -> handleBindingsResponse(json)
                "chat" -> handleChatResponse(json)
                "container" -> handleContainerResponse(json)
                "noplayer" -> handleNoPlayer()
                "hello" -> handleHello(json)
                else -> handleHudState(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse line: $line", e)
        }
    }

    /**
     * HUD textures pushed by the mod straight after we connect, keyed by
     * HudAssetCatalog's short names. The mod resolves these through
     * Minecraft's own resource manager, so they already reflect whatever
     * resource pack the player has active -- the app doesn't parse packs.
     *
     * A null `data` means the mod looked and that texture isn't available;
     * recorded as a miss so the caller falls back rather than waiting.
     */
    private fun handleAssetResponse(json: JSONObject) {
        val assetId = json.getString("assetId")
        val base64Png = if (json.isNull("data")) null else json.optString("data").takeIf { it.isNotEmpty() }

        if (base64Png == null) {
            Log.w(TAG, "Mod has no HUD asset for $assetId")
            unavailableAssets.add(assetId)
            return
        }

        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Pixel art, and the font sheet's pixels get read directly to
            // measure glyphs -- never let the decoder resample or hardware-back it.
            inScaled = false
        })

        if (bitmap != null) {
            assetCache[assetId] = bitmap
            unavailableAssets.remove(assetId)
        } else {
            Log.w(TAG, "Failed to decode HUD asset $assetId")
            unavailableAssets.add(assetId)
        }
    }

    /** Asks the mod to re-send the HUD texture bundle -- call after the
     *  player changes resource packs. New connections get it unprompted. */
    fun requestAssets() = sendCommand("ASSETS")

    /**
     * The game's key bindings, replacing the list wholesale.
     *
     * Always the full set, never a delta: the picker is built straight from
     * this, and a partial list would read as "that action doesn't exist"
     * rather than as an error anyone would notice.
     */
    private fun handleBindingsResponse(json: JSONObject) {
        val array = json.optJSONArray("bindings") ?: return
        _bindings.value = buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                add(
                    GameBinding(
                        id = entry.getString("id"),
                        label = entry.optString("label").ifEmpty { entry.getString("id") },
                        category = entry.optString("category"),
                        boundKey = entry.optString("boundKey"),
                        unbound = entry.optBoolean("unbound", false)
                    )
                )
            }
        }
        Log.i(TAG, "Received ${_bindings.value.size} key bindings")
    }

    /** Asks the mod to re-send the key bindings -- worth doing whenever the
     *  picker opens, since a rebind in game pushes nothing on its own. */
    fun requestBindings() = sendCommand("BINDINGS")

    /**
     * One chat line, as coloured runs.
     *
     * Arrives both live and as a backlog replay right after connecting — the
     * two are indistinguishable on the wire and don't need to be told apart,
     * since the log is cleared on disconnect.
     */
    private fun handleChatResponse(json: JSONObject) {
        val array = json.optJSONArray("segments") ?: return
        val segments = buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                add(
                    ChatSegment(
                        text = entry.optString("text"),
                        // Absent means "the default colour" -- the mod omits
                        // the field rather than sending a sentinel.
                        color = if (entry.isNull("color")) null else entry.optInt("color")
                    )
                )
            }
        }
        if (segments.isEmpty()) return

        val message = ChatMessage(id = nextChatId++, segments = segments)
        _chatMessages.value = (_chatMessages.value + message).takeLast(CHAT_HISTORY_LIMIT)
    }

    /**
     * The mod's own greeting, the first line on every connection.
     *
     * Its *absence* is the signal that matters — see [portConflict].
     */
    private fun handleHello(json: JSONObject) {
        Log.i(TAG, "Mod said hello: ${json.optString("mod")} protocol ${json.optInt("protocol")}")
        sawHello = true
        silentConnections = 0
        failedConnections = 0
        _portConflict.value = false
    }

    /**
     * The player left the world — main menu, world unloading, kicked.
     *
     * Everything player-shaped is dropped, which puts each tab back into the
     * waiting state it already shows before the first snapshot arrives. The
     * socket is still up, so [isConnected] stays true: the mod is running, it
     * just has nothing to report. That distinction is the whole reason this is
     * a message rather than the mod going quiet — silence is indistinguishable
     * from a stalled game, and the second screen used to sit there displaying a
     * frozen snapshot of wherever you last were.
     *
     * Assets and bindings are kept: they don't belong to a world, and the mod
     * doesn't re-send them on re-entry.
     */
    private fun handleNoPlayer() {
        Log.i(TAG, "Mod reports no player; waiting")
        _hudState.value = null
        _mapTile.value = null
        _container.value = null
        // Vanilla clears chat on disconnect and the mod drops its backlog to
        // match, so holding ours would strand the previous world's messages
        // with nothing behind them.
        _chatMessages.value = emptyList()
        pendingIconRequests.clear()
        failedIconRequests.clear()
    }

    /**
     * The open screen handler's contents.
     *
     * Sent by the mod only when something actually changes — a player walking
     * around leaves this untouched for minutes — plus once to each new
     * connection so the tab isn't empty until the first move.
     */
    private fun handleContainerResponse(json: JSONObject) {
        val array = json.optJSONArray("slots") ?: return
        val slots = buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                add(
                    ContainerSlot(
                        stack = parseSlot(entry.getJSONObject("stack")),
                        mayPlace = entry.optBoolean("mayPlace", true),
                        // -1 rather than 0 for "absent": (0,0) is a position a
                        // handler could legitimately place a slot at, so a
                        // zero default would read as a real coordinate and
                        // stack every slot of an old mod build's container in
                        // the top-left corner.
                        x = entry.optInt("x", -1),
                        y = entry.optInt("y", -1),
                        // The mod omits the field entirely for the great
                        // majority of slots, which have no placeholder.
                        background = entry.optString("background").ifEmpty { null },
                        // Sent inverted, and only when true: "disabled" is the
                        // exception worth a field, "enabled" is the default.
                        enabled = !entry.optBoolean("disabled", false)
                    )
                )
            }
        }

        _container.value = ContainerState(
            syncId = json.getInt("syncId"),
            handlerType = if (json.isNull("handlerType")) null else json.optString("handlerType"),
            title = if (json.isNull("title")) null else json.optString("title").ifEmpty { null },
            cursor = parseSlot(json.getJSONObject("cursor")),
            slots = slots,
            playerStart = json.getInt("playerStart"),
            hotbarStart = json.getInt("hotbarStart"),
            armorStart = json.getInt("armorStart"),
            offhandIndex = json.getInt("offhandIndex"),
            progress = json.optJSONObject("progress")?.let(::parseContainerProgress),
            extras = json.optJSONObject("extras")
                ?.let(::parseContainerExtras)
                // An extras object with nothing this build understands reads as
                // no extras at all, rather than as an empty panel eating half the
                // screen -- which is what a newer mod adding a sixth kind would
                // otherwise do here.
                ?.takeUnless { it.isEmpty }
        )
    }

    /** Each kind is absent unless the open handler is that kind, so every field is
     *  a nullable lookup rather than a branch on a discriminator. */
    private fun parseContainerExtras(json: JSONObject) = ContainerExtras(
        enchanting = json.optJSONObject("enchanting")?.let { obj ->
            EnchantingOffers(
                offers = obj.optJSONArray("offers").mapObjects { offer ->
                    EnchantOffer(
                        level = offer.optInt("level"),
                        cost = offer.optInt("cost"),
                        lapisCost = offer.optInt("lapisCost"),
                        name = offer.optString("name"),
                        available = offer.optBoolean("available", false)
                    )
                },
                lapisCount = obj.optInt("lapisCount"),
                playerLevel = obj.optInt("playerLevel")
            )
        },
        stonecutter = json.optJSONObject("stonecutter")?.let(::parseSelectableList),
        loom = json.optJSONObject("loom")?.let(::parseSelectableList),
        merchant = json.optJSONObject("merchant")?.let { obj ->
            MerchantTrades(
                trades = obj.optJSONArray("trades").mapObjects { trade ->
                    VillagerTrade(
                        firstCost = parseSlot(trade.getJSONObject("firstCost")),
                        secondCost = parseSlot(trade.getJSONObject("secondCost")),
                        sell = parseSlot(trade.getJSONObject("sell")),
                        disabled = trade.optBoolean("disabled", false),
                        uses = trade.optInt("uses"),
                        maxUses = trade.optInt("maxUses")
                    )
                },
                experience = obj.optInt("experience"),
                levelProgress = obj.optInt("levelProgress"),
                leveled = obj.optBoolean("leveled", false)
            )
        },
        beacon = json.optJSONObject("beacon")?.let { obj ->
            BeaconState(
                primaryOptions = obj.optJSONArray("primaryOptions").mapObjects(::parseBeaconEffect),
                secondaryOptions = obj.optJSONArray("secondaryOptions").mapObjects(::parseBeaconEffect),
                // Null is a real state -- a beacon with nothing chosen yet.
                primary = if (obj.isNull("primary")) null else obj.optString("primary"),
                secondary = if (obj.isNull("secondary")) null else obj.optString("secondary"),
                level = obj.optInt("level"),
                hasPayment = obj.optBoolean("hasPayment", false)
            )
        },
        anvil = json.optJSONObject("anvil")?.let { obj ->
            AnvilState(
                levelCost = obj.optInt("levelCost"),
                hasResult = obj.optBoolean("hasResult", false)
            )
        }
    )

    private fun parseSelectableList(json: JSONObject) = SelectableList(
        options = json.optJSONArray("options").mapObjects { option ->
            ContainerOption(
                label = option.optString("label"),
                // Absent for a loom's patterns, which aren't items.
                itemId = option.optString("itemId").ifEmpty { null }
            )
        },
        selected = json.optInt("selected", -1)
    )

    private fun parseBeaconEffect(json: JSONObject) = BeaconEffect(
        id = json.optString("id"),
        name = json.optString("name"),
        tier = json.optInt("tier")
    )

    /** A null array reads as empty — the mod omits a list it has nothing for, and
     *  every caller here wants "no entries" rather than a throw. */
    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { transform(getJSONObject(it)) }
    }

    /** Absent fields mean "this container has no such indicator", so every one
     *  of them stays null rather than defaulting to zero — a furnace with a
     *  `cookPixels` of 0 is idle, which is a different thing from a brewing
     *  stand that has no cook arrow at all. */
    private fun parseContainerProgress(json: JSONObject) = ContainerProgress(
        cookPixels = if (json.has("cookPixels")) json.optInt("cookPixels") else null,
        litPixels = if (json.has("litPixels")) json.optInt("litPixels") else null,
        fuelPixels = if (json.has("fuelPixels")) json.optInt("fuelPixels") else null,
        brewPixels = if (json.has("brewPixels")) json.optInt("brewPixels") else null,
        brewing = json.optBoolean("brewing", false)
    )

    /**
     * Clicks an inventory slot, exactly as a mouse would.
     *
     * [syncId] comes from the [ContainerState] the click was aimed at, not from
     * whatever is current — it's a staleness check, and the mod refuses the
     * click if the open handler has changed underneath. Without it, closing a
     * chest at the wrong moment would apply the click to the same-numbered slot
     * of the player's inventory.
     *
     * [action] is a `SlotActionType` name: `PICKUP` (a plain click — take,
     * place, or swap), `QUICK_MOVE` (shift-click), `THROW`, `SWAP`, `CLONE`,
     * `PICKUP_ALL`.
     *
     * [button] is the mouse button for `PICKUP` (0 left, 1 right — right takes
     * or places half), and for `SWAP` it's the destination hotbar slot.
     */
    fun sendSlotClick(syncId: Int, slotId: Int, button: Int = 0, action: String = "PICKUP") =
        sendCommand("SLOT:$syncId,$slotId,$button,$action")

    /**
     * The container commands that aren't slot clicks.
     *
     * The strings themselves are built by [ContainerCommands], which is
     * Android-free and unit tested — these are the ones where a formatting slip
     * silently performs a *different* action on a live world (the wrong enchant
     * row, the wrong trade, a cleared beacon) rather than failing visibly.
     *
     * [syncId] is the same staleness check every container command carries: the
     * mod refuses the command if the open handler has changed underneath, which
     * matters more here than for a slot click, since "recipe 4" in whatever
     * replaced the stonecutter is not a recipe at all.
     */
    fun sendButton(syncId: Int, buttonId: Int) =
        sendCommand(ContainerCommands.button(syncId, buttonId))

    fun sendRename(syncId: Int, name: String) =
        sendCommand(ContainerCommands.rename(syncId, name))

    fun sendTrade(syncId: Int, index: Int) =
        sendCommand(ContainerCommands.trade(syncId, index))

    fun sendBeaconEffects(syncId: Int, primary: String?, secondary: String?) =
        sendCommand(ContainerCommands.beaconEffects(syncId, primary, secondary))

    fun sendSlotState(syncId: Int, slotId: Int, enabled: Boolean) =
        sendCommand(ContainerCommands.slotState(syncId, slotId, enabled))

    /**
     * Says [message] in game, as the player. A leading `/` makes it a command;
     * the mod does that split, following the game's own chat box.
     *
     * Newlines are flattened because the protocol is newline-delimited — a
     * message containing one would arrive at the mod as two separate commands,
     * the second of which would be interpreted as something else entirely.
     */
    fun sendChat(message: String) {
        val oneLine = message.replace('\n', ' ').replace('\r', ' ').trim()
        if (oneLine.isEmpty()) return
        sendCommand("CHAT:$oneLine")
    }

    /**
     * Presses a key binding by id, the form the configurable input grid uses.
     *
     * Prefer this over the fixed action codes: the id came from the mod's own
     * binding list, so there's no table on either side that can drift, and
     * other mods' bindings work with no entry anywhere.
     */
    fun sendBinding(bindingId: String) = sendCommand("BIND:$bindingId")

    /**
     * Tells the mod which HUD elements the *game* should draw on the main
     * screen -- the ones the player switched off down here, handed back rather
     * than simply lost.
     *
     * [elementKeys] is the full set every time, not a delta, and is re-sent on
     * every change and every reconnect. A dropped or reordered delta could
     * leave the two screens disagreeing about who owns an element, which shows
     * up as a HUD element drawn on both displays or on neither.
     */
    fun sendGameHudElements(elementKeys: Collection<String>) =
        sendCommand("HUD:" + elementKeys.joinToString(","))

    /**
     * A map tile from the mod, rendered with vanilla's own map colours.
     *
     * The bitmap is decoded with `inScaled = false` like every other pixel-art
     * asset here: it's one pixel per block, so any decoder resampling would
     * blur the terrain before it ever reached the screen.
     */
    private fun handleMapResponse(json: JSONObject) {
        val base64Png = if (json.isNull("data")) null else json.optString("data").takeIf { it.isNotEmpty() }
        if (base64Png == null) {
            Log.w(TAG, "Map response had no data")
            return
        }

        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        })

        if (bitmap == null) {
            Log.w(TAG, "Failed to decode map tile")
            return
        }

        _mapTile.value = MapTile(
            bitmap = bitmap,
            originX = json.getInt("originX"),
            originZ = json.getInt("originZ"),
            playerX = json.getDouble("playerX"),
            playerZ = json.getDouble("playerZ"),
            yaw = json.getDouble("yaw").toFloat(),
            size = json.getInt("size")
        )
    }

    private fun handleIconResponse(json: JSONObject) {
        val itemId = json.getString("itemId")
        pendingIconRequests.remove(itemId)

        // The mod omits "data" entirely when it couldn't produce an icon.
        // That's a real answer, not a dropped request -- record it so we back
        // off instead of re-asking on every recomposition.
        val base64Png = if (json.isNull("data")) null else json.optString("data").takeIf { it.isNotEmpty() }
        if (base64Png == null) {
            Log.w(TAG, "Mod reported no icon available for $itemId")
            failedIconRequests[itemId] = SystemClock.elapsedRealtime()
            return
        }

        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) {
            iconCache[itemId] = bitmap
            rememberIconUse(itemId)
            failedIconRequests.remove(itemId)
        } else {
            Log.w(TAG, "Failed to decode icon bitmap for $itemId")
            failedIconRequests[itemId] = SystemClock.elapsedRealtime()
        }
    }

    /** Records a use and evicts the least recently used icons over the cap. */
    private fun rememberIconUse(itemId: String) {
        val evicted = synchronized(iconUsage) {
            iconUsage.remove(itemId)
            iconUsage.add(itemId)
            val over = iconUsage.size - ICON_CACHE_LIMIT
            if (over <= 0) {
                emptyList()
            } else {
                val oldest = iconUsage.take(over)
                iconUsage.removeAll(oldest.toSet())
                oldest
            }
        }
        // Outside the lock: touching the state map notifies Compose.
        evicted.forEach { iconCache.remove(it) }
    }

    private fun handleHudState(json: JSONObject) {
        val hotbarArray = json.getJSONArray("hotbar")
        val hotbar = buildList {
            for (i in 0 until hotbarArray.length()) {
                add(parseSlot(hotbarArray.getJSONObject(i)))
            }
        }

        _hudState.value = HudState(
            health = json.getDouble("health").toFloat(),
            maxHealth = json.getDouble("maxHealth").toFloat(),
            absorption = json.optDouble("absorption", 0.0).toFloat(),
            food = json.getInt("food"),
            xpLevel = json.getInt("xpLevel"),
            xpProgress = json.getDouble("xpProgress").toFloat(),
            armor = json.getInt("armor"),
            selectedSlot = json.getInt("selectedSlot"),
            // Tolerated as optional so a newer app still runs against an older
            // mod build that predates these fields -- defaults read as "not
            // drowning", which hides the bubbles.
            air = json.optInt("air", 0),
            maxAir = json.optInt("maxAir", 0),
            // Optional so a newer app still runs against an older mod build;
            // the defaults reproduce exactly what that build's HUD looked like.
            gameMode = if (json.isNull("gameMode")) null else json.optString("gameMode").ifEmpty { null },
            heartType = json.optString("heartType").ifEmpty { "NORMAL" },
            hardcore = json.optBoolean("hardcore", false),
            hotbar = hotbar,
            // Optional for the same reason: an older mod build doesn't send it,
            // and null means "don't draw the box" rather than "empty hand".
            offhand = json.optJSONObject("offhand")?.let(::parseSlot)
        )
    }

    private fun parseSlot(slot: JSONObject) = HotbarSlot(
        itemId = slot.getString("itemId"),
        count = slot.getInt("count"),
        damage = slot.getInt("damage"),
        maxDamage = slot.getInt("maxDamage"),
        hasGlint = slot.getBoolean("hasGlint")
    )

    /** Sends a short command code to the mod. No-op if not currently connected. */
    fun sendCommand(code: String) {
        val currentWriter = writer ?: run {
            Log.w(TAG, "sendCommand($code) dropped -- not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                currentWriter.println(code)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send command $code: ${e.message}")
            }
        }
    }
}
