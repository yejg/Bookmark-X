package indi.bookmarkx.model;

import indi.bookmarkx.model.po.BookmarkPO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookmarkConverterTest {

    @Test
    void shouldWriteAnchorFieldsToPo() {
        BookmarkNodeModel model = new BookmarkNodeModel();
        model.setUuid("uuid-1");
        model.setName("书签A");
        model.setLine(10);
        model.setAnchor(new BookmarkAnchor("int a = 1;", List.of("before1", "before2"), List.of("after1")));
        model.setAnchorLost(true);

        BookmarkPO po = BookmarkConverter.convertToPO(model);

        assertEquals("int a = 1;", po.getAnchorText());
        assertEquals("before1\nbefore2", po.getContextBefore());
        assertEquals("after1", po.getContextAfter());
        assertTrue(po.isAnchorLost());
    }

    @Test
    void shouldLeaveAnchorFieldsNullWhenModelHasNoAnchor() {
        BookmarkNodeModel model = new BookmarkNodeModel();
        model.setUuid("uuid-2");
        model.setName("书签B");
        model.setLine(3);

        BookmarkPO po = BookmarkConverter.convertToPO(model);

        assertNull(po.getAnchorText());
        assertNull(po.getContextBefore());
        assertNull(po.getContextAfter());
        assertFalse(po.isAnchorLost());
    }

    @Test
    void shouldNotAffectGroupNodeConversion() {
        GroupNodeModel group = new GroupNodeModel();
        group.setUuid("group-1");
        group.setName("分组");

        BookmarkPO po = BookmarkConverter.convertToPO(group);

        assertFalse(po.isBookmark());
        assertEquals("分组", po.getName());
        assertNull(po.getAnchorText());
    }

    @Test
    void shouldWriteEmptyContextAsEmptyStringNotNull() {
        BookmarkNodeModel model = new BookmarkNodeModel();
        model.setUuid("uuid-3");
        model.setAnchor(new BookmarkAnchor("target", List.of(), List.of()));

        BookmarkPO po = BookmarkConverter.convertToPO(model);

        assertEquals("target", po.getAnchorText());
        assertEquals("", po.getContextBefore());
        assertEquals("", po.getContextAfter());
    }

    @Test
    void shouldRestoreAnchorFromPersistedFields() {
        BookmarkPO po = new BookmarkPO();
        po.setBookmark(true);
        po.setAnchorText("int a = 1;");
        po.setContextBefore("b1\nb2");
        po.setContextAfter("a1");
        po.setAnchorLost(true);

        BookmarkAnchor anchor = BookmarkAnchor.fromPersisted(
                po.getAnchorText(), po.getContextBefore(), po.getContextAfter());

        assertEquals("int a = 1;", anchor.getAnchorText());
        assertEquals(List.of("b1", "b2"), anchor.getContextBefore());
        assertEquals(List.of("a1"), anchor.getContextAfter());
        assertTrue(po.isAnchorLost());
    }

    @Test
    void shouldRestoreNoAnchorForLegacyData() {
        // 旧版本导出的持久化数据没有锚点字段，不得抛异常
        BookmarkPO legacy = new BookmarkPO();
        legacy.setBookmark(true);
        legacy.setLine(7);
        legacy.setVirtualFilePath("$PROJECT_DIR$/src/Foo.java");

        assertNull(BookmarkAnchor.fromPersisted(
                legacy.getAnchorText(), legacy.getContextBefore(), legacy.getContextAfter()));
        assertFalse(legacy.isAnchorLost());
    }
}
