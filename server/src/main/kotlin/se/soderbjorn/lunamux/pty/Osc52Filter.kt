/**
 * Removal of OSC 52 clipboard sequences from the byte stream that feeds the server's grid.
 *
 * This file contains [Osc52Filter], a streaming filter that deletes every
 * `ESC ] 52 ; ... (BEL | ESC \)` sequence from a PTY or replay byte stream, whatever its
 * length and wherever the chunk boundaries fall.
 *
 * Why it exists: the vendored emulator caps an OSC string at 8192 characters and, past the cap,
 * abandons the sequence and prints the rest of it as ordinary text. A program that copies a
 * large selection to the clipboard (Claude Code does) therefore left a wall of base64 in the
 * canonical grid, and from there in every attach redraw and every persisted scrollback blob. The
 * grid has no clipboard, so nothing is lost by never giving it these sequences. The raw bytes
 * still reach connected clients untouched: copying to the user's clipboard happens there.
 *
 * @see SessionGrid.feed the only caller
 */
package se.soderbjorn.lunamux.pty

/**
 * Streaming remover of OSC 52 (clipboard) sequences.
 *
 * Carries its parse state between [filter] calls, so a sequence split anywhere (inside the
 * `ESC ]` introducer, between the `ESC` and `\` of its terminator, or in the middle of the
 * payload) is still removed in full. Bytes that turn out not to start an OSC 52 sequence are
 * passed on unchanged and in order, including any held back while the introducer was incomplete.
 *
 * A sequence ends at BEL or `ESC \`. It is also abandoned, without swallowing what follows, when
 * it is cancelled (CAN, SUB), when another escape sequence starts inside it, or once its payload
 * passes [MAX_PAYLOAD]: a stream that never terminates must not hide the rest of the session's
 * output.
 *
 * Not thread-safe; [SessionGrid] calls it under its emulator lock.
 */
internal class Osc52Filter {

    private enum class State {
        /** Passing bytes through. */
        NORMAL,

        /** Saw `ESC`; it is held until the next byte says whether an OSC follows. */
        ESC,

        /** Saw `ESC ]` and possibly some digits of the command number; all held. */
        OSC,

        /** Inside an OSC 52 payload: every byte is discarded. */
        DROP,

        /** Saw `ESC` inside a payload: `\` ends it, anything else abandons it. */
        DROP_ESC,
    }

    private var state = State.NORMAL

    /** The introducer bytes seen so far (`ESC`, `]`, then command digits) while the state is [State.ESC] or [State.OSC]. */
    private val held = ByteArray(HELD_MAX)
    private var heldLen = 0

    /** Payload bytes discarded so far in the current sequence. */
    private var dropped = 0

    /**
     * Filter one chunk and hand the result to [emit].
     *
     * Called by [SessionGrid.feed] for every chunk the PTY produced and for restored scrollback.
     * [emit] is called once, or not at all when the whole chunk was removed or held back. A chunk
     * with no escape in it, arriving while nothing is held, is passed through without a copy.
     *
     * @param buf the bytes; only `[0, len)` is read.
     * @param len how many bytes of [buf] are valid.
     * @param emit receives the bytes to give the emulator and how many of them are valid; it may
     *   be handed [buf] itself, and must not keep the array.
     */
    fun filter(buf: ByteArray, len: Int, emit: (ByteArray, Int) -> Unit) {
        if (len <= 0) return
        if (state == State.NORMAL && (0 until len).none { buf[it] == ESC }) {
            emit(buf, len)
            return
        }
        val out = ByteArray(len + HELD_MAX)
        var n = 0
        fun put(b: Byte) { out[n++] = b }
        fun flushHeld() {
            for (i in 0 until heldLen) put(held[i])
            heldLen = 0
        }
        for (i in 0 until len) {
            var b = buf[i]
            // A byte that ends a state without being part of it is run again in the state it leads to.
            var again: Boolean
            do {
                again = false
                when (state) {
                    State.NORMAL ->
                        if (b == ESC) { held[0] = ESC; heldLen = 1; state = State.ESC } else put(b)

                    State.ESC ->
                        if (b == RIGHT_BRACKET) { held[heldLen++] = b; state = State.OSC }
                        else { flushHeld(); state = State.NORMAL; again = true }

                    State.OSC -> when {
                        b in ZERO..NINE && heldLen < HELD_MAX -> held[heldLen++] = b
                        b == SEMICOLON && isClipboardCommand() -> { heldLen = 0; dropped = 0; state = State.DROP }
                        else -> { flushHeld(); state = State.NORMAL; again = true }
                    }

                    State.DROP -> when {
                        b == BEL || b == CAN || b == SUB -> state = State.NORMAL
                        b == ESC -> state = State.DROP_ESC
                        ++dropped > MAX_PAYLOAD -> state = State.NORMAL
                        else -> {}
                    }

                    State.DROP_ESC -> when (b) {
                        BACKSLASH -> state = State.NORMAL
                        ESC -> {}
                        else -> { held[0] = ESC; heldLen = 1; state = State.ESC; again = true }
                    }
                }
            } while (again)
        }
        if (n > 0) emit(out, n)
    }

    /** Whether the digits held after `ESC ]` spell 52. */
    private fun isClipboardCommand() = heldLen == 4 && held[2] == FIVE && held[3] == TWO

    private companion object {
        const val ESC: Byte = 0x1b
        const val BEL: Byte = 0x07
        const val CAN: Byte = 0x18
        const val SUB: Byte = 0x1a
        const val RIGHT_BRACKET: Byte = ']'.code.toByte()
        const val BACKSLASH: Byte = '\\'.code.toByte()
        const val SEMICOLON: Byte = ';'.code.toByte()
        const val ZERO: Byte = '0'.code.toByte()
        const val FIVE: Byte = '5'.code.toByte()
        const val TWO: Byte = '2'.code.toByte()
        const val NINE: Byte = '9'.code.toByte()

        /** `ESC`, `]` and at most three digits of the command number. */
        const val HELD_MAX = 5

        /**
         * Longest payload removed before giving up on a sequence that never ends: 1 MiB of base64 is
         * far beyond any clipboard copy a terminal program makes.
         */
        const val MAX_PAYLOAD = 1 shl 20
    }
}
