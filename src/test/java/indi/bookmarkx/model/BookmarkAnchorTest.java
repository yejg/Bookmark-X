package indi.bookmarkx.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BookmarkAnchorTest {

    private static final List<String> LINES = Arrays.asList(
            "line0", "line1", "line2", "line3", "line4", "line5", "line6", "line7");

    @Test
    void shouldCaptureThreeLinesOnEachSideInMiddleOfFile() {
        BookmarkAnchor anchor = BookmarkAnchor.capture(LINES, 4);

        assertEquals("line4", anchor.getAnchorText());
        assertEquals(Arrays.asList("line1", "line2", "line3"), anchor.getContextBefore());
        assertEquals(Arrays.asList("line5", "line6", "line7"), anchor.getContextAfter());
    }

    @Test
    void shouldHaveEmptyContextBeforeAtFirstLine() {
        BookmarkAnchor anchor = BookmarkAnchor.capture(LINES, 0);

        assertEquals("line0", anchor.getAnchorText());
        assertEquals(List.of(), anchor.getContextBefore());
        assertEquals(Arrays.asList("line1", "line2", "line3"), anchor.getContextAfter());
    }

    @Test
    void shouldHaveEmptyContextAfterAtLastLine() {
        BookmarkAnchor anchor = BookmarkAnchor.capture(LINES, 7);

        assertEquals("line7", anchor.getAnchorText());
        assertEquals(Arrays.asList("line4", "line5", "line6"), anchor.getContextBefore());
        assertEquals(List.of(), anchor.getContextAfter());
    }

    @Test
    void shouldTakeWhatIsAvailableWhenContextIsShorterThanThree() {
        BookmarkAnchor anchor = BookmarkAnchor.capture(LINES, 1);

        assertEquals(List.of("line0"), anchor.getContextBefore());
    }

    @Test
    void shouldNormalizeTextWhenCapturing() {
        List<String> raw = Arrays.asList("  a  ", "\tb\tb\t", "  c ");
        BookmarkAnchor anchor = BookmarkAnchor.capture(raw, 1);

        assertEquals("b b", anchor.getAnchorText());
        assertEquals(List.of("a"), anchor.getContextBefore());
        assertEquals(List.of("c"), anchor.getContextAfter());
    }

    @Test
    void shouldReturnNullWhenLineIsOutOfBounds() {
        assertNull(BookmarkAnchor.capture(LINES, -1));
        assertNull(BookmarkAnchor.capture(LINES, 8));
        assertNull(BookmarkAnchor.capture(List.of(), 0));
        assertNull(BookmarkAnchor.capture(null, 0));
    }

    @Test
    void shouldSurvivePersistenceRoundTrip() {
        BookmarkAnchor origin = BookmarkAnchor.capture(LINES, 4);

        BookmarkAnchor restored = BookmarkAnchor.fromPersisted(
                origin.getAnchorText(),
                origin.persistedContextBefore(),
                origin.persistedContextAfter());

        assertEquals(origin.getAnchorText(), restored.getAnchorText());
        assertEquals(origin.getContextBefore(), restored.getContextBefore());
        assertEquals(origin.getContextAfter(), restored.getContextAfter());
    }

    @Test
    void shouldKeepBlankContextLinesThroughRoundTrip() {
        // 上下文中的空行是有效信息，不能被丢掉
        List<String> lines = Arrays.asList("a", "", "target", "", "b");
        BookmarkAnchor origin = BookmarkAnchor.capture(lines, 2);

        BookmarkAnchor restored = BookmarkAnchor.fromPersisted(
                origin.getAnchorText(),
                origin.persistedContextBefore(),
                origin.persistedContextAfter());

        assertEquals(origin.getContextBefore(), restored.getContextBefore());
        assertEquals(origin.getContextAfter(), restored.getContextAfter());
        assertEquals(Arrays.asList("a", ""), restored.getContextBefore());
        assertEquals(Arrays.asList("", "b"), restored.getContextAfter());
    }

    @Test
    void shouldRestoreToNullWhenAnchorTextIsBlank() {
        assertNull(BookmarkAnchor.fromPersisted(null, "a", "b"));
        assertNull(BookmarkAnchor.fromPersisted("", "a", "b"));
    }

    @Test
    void shouldRestoreEmptyContextAsEmptyListNotSingletonBlank() {
        BookmarkAnchor anchor = BookmarkAnchor.fromPersisted("x", null, "");

        assertEquals(List.of(), anchor.getContextBefore());
        assertEquals(List.of(), anchor.getContextAfter());
    }

    @Test
    void shouldTreatNullContextCollectionsAsEmpty() {
        BookmarkAnchor anchor = new BookmarkAnchor("x", null, null);

        assertEquals(List.of(), anchor.getContextBefore());
        assertEquals(List.of(), anchor.getContextAfter());
    }
}
