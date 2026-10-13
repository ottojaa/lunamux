package com.termux.terminal;

/**
 * LUNAMUX ADDITION. Tests for {@link TerminalBuffer#prependTranscript(TerminalRow[])} — putting
 * history OLDER than everything the buffer already holds in front of it.
 *
 * The counterpart to {@link TerminalBuffer#backfillAboveScreen}, which rotates a screen-only
 * ring and refuses on a buffer that has a transcript. This one is what a client needs when the
 * server splits a resync: the screen and a recent tail arrive first and are painted, and the
 * older scrollback follows and has to land above them without moving anything on screen.
 */
public class PrependTranscriptTest extends TerminalTestCase {

	/** Rows of the given texts, laid out at {@code cols}, as the caller of the real thing does. */
	private TerminalRow[] rowsOf(int cols, String... texts) {
		TerminalRow[] rows = new TerminalRow[texts.length];
		for (int i = 0; i < texts.length; i++) {
			TerminalRow row = new TerminalRow(cols, TextStyle.NORMAL);
			for (int x = 0; x < texts[i].length(); x++) {
				row.setChar(x, texts[i].charAt(x), TextStyle.NORMAL);
			}
			rows[i] = row;
		}
		return rows;
	}

	public void testPrependedRowsBecomeTheOldestHistory() {
		withTerminalSized(3, 2).enterString("111222333");
		assertLinesAre("222", "333");
		assertHistoryStartsWith("111");

		TerminalBuffer screen = mTerminal.getScreen();
		assertEquals(2, screen.prependTranscript(rowsOf(3, "AAA", "BBB")));

		// Screen untouched; the two new rows sit BEFORE the line that was already there,
		// so walking back from the screen gives newest-first: 111, then BBB, then AAA.
		assertLinesAre("222", "333");
		assertHistoryStartsWith("111", "BBB", "AAA");
	}

	public void testPrependIsAdditiveAndKeepsOrderAcrossCalls() {
		withTerminalSized(3, 2).enterString("111222333");
		TerminalBuffer screen = mTerminal.getScreen();
		screen.prependTranscript(rowsOf(3, "BBB"));
		screen.prependTranscript(rowsOf(3, "AAA"));
		assertHistoryStartsWith("111", "BBB", "AAA");
	}

	/**
	 * A 3-wide emulator with a {@link TerminalEmulator#TERMINAL_TRANSCRIPT_ROWS_MIN}-row ring —
	 * the smallest the constructor honours; anything less silently becomes the 2000-row default.
	 * Feeds {@code lines} rows of digits so the transcript fills to a known depth.
	 */
	private TerminalBuffer smallRingWith(int lines) {
		mTerminal = new TerminalEmulator(new MockTerminalOutput(), 3, 2, 3, 3,
			TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN, null);
		StringBuilder sb = new StringBuilder(lines * 3);
		for (int i = 0; i < lines; i++) sb.append(String.format("%03d", i % 1000));
		byte[] b = sb.toString().getBytes();
		mTerminal.append(b, b.length);
		return mTerminal.getScreen();
	}

	public void testOverflowKeepsTheNewestRows() {
		// 99 lines through a 100-row ring with a 2-row screen: 97 in the transcript, one slot free.
		TerminalBuffer screen = smallRingWith(99);
		assertEquals(1, screen.transcriptFreeRows());

		// Three offered, one slot: the NEWEST is what the user reaches by scrolling up a little.
		assertEquals(1, screen.prependTranscript(rowsOf(3, "AAA", "BBB", "CCC")));
		assertEquals("CCC", screen.getSelectedText(0, -98, 2, -98).trim());
	}

	public void testFullTranscriptRefuses() {
		TerminalBuffer screen = smallRingWith(100);
		assertEquals(0, screen.transcriptFreeRows());
		assertEquals(0, screen.prependTranscript(rowsOf(3, "AAA")));
	}

	public void testScreenOnlyBufferRefuses() {
		// The server's canonical grid shape: no transcript at all, so nothing to prepend into.
		mTerminal = new TerminalEmulator(new MockTerminalOutput(), 3, 2, 3, 3, 0, null);
		mTerminal.append("111222333".getBytes(), 9);
		TerminalBuffer screen = mTerminal.getScreen();
		assertEquals(0, screen.transcriptFreeRows());
		assertEquals(0, screen.prependTranscript(rowsOf(3, "AAA")));
	}

	public void testEmptyInputIsANoOp() {
		withTerminalSized(3, 2).enterString("111222333");
		TerminalBuffer screen = mTerminal.getScreen();
		assertEquals(0, screen.prependTranscript(new TerminalRow[0]));
		assertHistoryStartsWith("111");
	}

	/**
	 * The ring's write head wraps around the array as output scrolls, so the index arithmetic
	 * has to be modular in both directions. Scroll well past the ring size before prepending.
	 */
	public void testWorksAfterTheRingHasWrapped() {
		// 300 lines through a 100-row ring: the write head has wrapped it three times over.
		TerminalBuffer screen = smallRingWith(300);
		assertLinesAre("298", "299");
		assertEquals(0, screen.transcriptFreeRows());

		// Make room, then prepend into the wrapped ring — where the oldest transcript row is
		// at a higher array index than the screen, so the arithmetic has to be modular.
		screen.clearTranscript();
		assertEquals(98, screen.transcriptFreeRows());
		assertEquals(2, screen.prependTranscript(rowsOf(3, "AAA", "BBB")));
		assertLinesAre("298", "299");
		assertHistoryStartsWith("BBB", "AAA");
	}
}
