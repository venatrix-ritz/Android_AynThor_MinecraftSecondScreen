package com.exojosh.minecraftsecondscreen.net

/**
 * The wire form of every container command that isn't a slot click.
 *
 * **Android- and Compose-free so it can be unit tested** — same line as
 * `InputSlotCodec`, `ContainerLayout` and `ChatLineWrapper`, and for the sharpest
 * version of the same reason. These strings are the only thing the mod sees. A
 * wrong one doesn't crash: it enchants the wrong row, selects the wrong trade, or
 * clears a beacon the player had just set, on a live world, and it looks from the
 * outside like the button simply did nothing.
 *
 * Two of them carry a trap that a test is the only sensible guard for:
 *
 * - **A beacon's "no effect" is a dash, not an empty field.** The format is
 *   comma-separated, so an empty field is what a null renders to by accident —
 *   and `BEACON:1,,` is indistinguishable from a deliberate clear. The mod reads
 *   `-` and treats a genuinely empty field as none too, but the app must not rely
 *   on that: sending the sentinel is what makes the intent explicit on the wire.
 * - **A rename must not be escaped or split.** A player's item name is arbitrary
 *   text and commas in it are perfectly ordinary, so the name goes last and
 *   verbatim; the mod splits this one command on its *first* comma only. What does
 *   have to be stripped is newlines, because the protocol is newline-delimited and
 *   one inside a name would arrive as two commands — the second of them parsed as
 *   something else entirely.
 */
object ContainerCommands {

    /** Stands in for "no effect" in a beacon command. Must match
     *  `ContainerRelay.NO_EFFECT` on the mod side. */
    const val NO_EFFECT = "-"

    /** Presses a container button: an enchanting offer row (0..2), or an index
     *  into a stonecutter's recipe or a loom's pattern list. */
    fun button(syncId: Int, buttonId: Int) = "BUTTON:$syncId,$buttonId"

    /** Selects a villager trade by its index in the list the mod sent. */
    fun trade(syncId: Int, index: Int) = "TRADE:$syncId,$index"

    /** Switches a crafter's cell on or off. */
    fun slotState(syncId: Int, slotId: Int, enabled: Boolean) =
        "SLOTSTATE:$syncId,$slotId,${if (enabled) 1 else 0}"

    /** Both beacon effects at once — the packet behind this carries the pair, so
     *  there's no way to set one and leave the other. */
    fun beaconEffects(syncId: Int, primary: String?, secondary: String?) =
        "BEACON:$syncId,${primary ?: NO_EFFECT},${secondary ?: NO_EFFECT}"

    /**
     * Renames the anvil's result. An empty name is legitimate — it's how vanilla
     * clears a rename back to the item's own name.
     *
     * Commas survive; newlines and carriage returns become spaces.
     */
    fun rename(syncId: Int, name: String) =
        "RENAME:$syncId,${name.replace('\n', ' ').replace('\r', ' ')}"
}
