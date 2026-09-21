package indi.bookmarkx.relocation;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitDiffHunkParserTest {

    /**
     * 断言 0-based 的 oldLine 映射到 0-based 的 expectedNewLine
     */
    private static void assertMapped(GitDiffHunkParser.LineMapping mapping, int oldLine, int expectedNewLine) {
        OptionalInt actual = mapping.map(oldLine);
        assertTrue(actual.isPresent(), "期望行 " + oldLine + " 有映射，实际无映射");
        assertEquals(expectedNewLine, actual.getAsInt(), "行 " + oldLine + " 的映射结果不符");
    }

    private static void assertUnmapped(GitDiffHunkParser.LineMapping mapping, int oldLine) {
        assertFalse(mapping.map(oldLine).isPresent(), "期望行 " + oldLine + " 无映射，实际有映射");
    }

    @Test
    void shouldTreatEmptyDiffAsIdentityMapping() {
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("");

        assertMapped(mapping, 0, 0);
        assertMapped(mapping, 100, 100);
    }

    @Test
    void shouldTreatNullDiffAsIdentityMapping() {
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse(null);

        assertMapped(mapping, 42, 42);
    }

    @Test
    void shouldShiftOnlyLinesAfterPureInsertion() {
        // 在旧文件第 5 行（1-based）之后插入 3 行
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("@@ -5,0 +6,3 @@\n");

        // 1-based 第 5 行 == 0-based 第 4 行，位于插入点之前，不变
        assertMapped(mapping, 4, 4);
        // 1-based 第 6 行 == 0-based 第 5 行，位于插入点之后，下移 3
        assertMapped(mapping, 5, 8);
    }

    @Test
    void shouldHandleInsertionAtFileStart() {
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("@@ -0,0 +1,3 @@\n");

        // 旧文件首行整体下移 3 行
        assertMapped(mapping, 0, 3);
        assertMapped(mapping, 10, 13);
    }

    @Test
    void shouldUnmapDeletedLinesAndShiftFollowingUp() {
        // 删除旧文件 1-based 第 5、6、7 行
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("@@ -5,3 +4,0 @@\n");

        // 0-based 第 3 行（1-based 第 4 行）在删除区之前，不变
        assertMapped(mapping, 3, 3);
        // 0-based 第 4、5、6 行（1-based 第 5、6、7 行）被删除
        assertUnmapped(mapping, 4);
        assertUnmapped(mapping, 5);
        assertUnmapped(mapping, 6);
        // 0-based 第 7 行（1-based 第 8 行）上移 3
        assertMapped(mapping, 7, 4);
    }

    @Test
    void shouldUnmapModifiedLinesAndShiftByDelta() {
        // 旧文件 1-based 第 5、6 两行被替换为 3 行
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("@@ -5,2 +5,3 @@\n");

        assertMapped(mapping, 3, 3);
        assertUnmapped(mapping, 4);
        assertUnmapped(mapping, 5);
        assertMapped(mapping, 6, 7);
    }

    @Test
    void shouldDefaultOmittedCountToOne() {
        // @@ -5 +5 @@ 等价于 @@ -5,1 +5,1 @@
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("@@ -5 +5 @@\n");

        assertMapped(mapping, 3, 3);
        assertUnmapped(mapping, 4);
        assertMapped(mapping, 5, 5);
    }

    @Test
    void shouldAccumulateDeltaAcrossMultipleHunks() {
        String diff = String.join("\n",
                "@@ -10,0 +11,5 @@",
                "@@ -20,3 +26,0 @@",
                "");
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse(diff);

        // 第一个 hunk 之前
        assertMapped(mapping, 5, 5);
        // 两个 hunk 之间：只受 +5 影响
        assertMapped(mapping, 14, 19);
        // 第二个 hunk 的删除区（1-based 20~22 == 0-based 19~21）
        assertUnmapped(mapping, 19);
        assertUnmapped(mapping, 21);
        // 第二个 hunk 之后：+5 -3 = +2
        assertMapped(mapping, 22, 24);
    }

    @Test
    void shouldIgnoreNonHunkHeaderLines() {
        String diff = String.join("\n",
                "diff --git a/Foo.java b/Foo.java",
                "index 1234567..89abcde 100644",
                "--- a/Foo.java",
                "+++ b/Foo.java",
                "@@ -5,0 +6,2 @@",
                "+    // added",
                "+    // added too",
                "");
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse(diff);

        assertMapped(mapping, 4, 4);
        assertMapped(mapping, 5, 7);
    }

    @Test
    void shouldUnmapNegativeLineNumber() {
        GitDiffHunkParser.LineMapping mapping = GitDiffHunkParser.parse("");

        assertUnmapped(mapping, -1);
    }

    @Test
    void shouldSplitMultiFileDiff() {
        String diff = String.join("\n",
                "diff --git a/src/Foo.java b/src/Foo.java",
                "index 111..222 100644",
                "--- a/src/Foo.java",
                "+++ b/src/Foo.java",
                "@@ -5,0 +6,2 @@",
                "diff --git a/src/Bar.java b/src/Bar.java",
                "index 333..444 100644",
                "--- a/src/Bar.java",
                "+++ b/src/Bar.java",
                "@@ -10,3 +10,0 @@",
                "");

        Map<String, GitDiffHunkParser.LineMapping> result = GitDiffHunkParser.parseMultiFile(diff);

        assertEquals(2, result.size());
        assertMapped(result.get("src/Foo.java"), 5, 7);
        assertUnmapped(result.get("src/Bar.java"), 9);
    }

    @Test
    void shouldSkipDeletedFileWhenSplitting() {
        String diff = String.join("\n",
                "diff --git a/src/Gone.java b/src/Gone.java",
                "deleted file mode 100644",
                "--- a/src/Gone.java",
                "+++ /dev/null",
                "@@ -1,10 +0,0 @@",
                "");

        Map<String, GitDiffHunkParser.LineMapping> result = GitDiffHunkParser.parseMultiFile(diff);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyMapForBlankInput() {
        assertTrue(GitDiffHunkParser.parseMultiFile("").isEmpty());
        assertTrue(GitDiffHunkParser.parseMultiFile(null).isEmpty());
    }
}
