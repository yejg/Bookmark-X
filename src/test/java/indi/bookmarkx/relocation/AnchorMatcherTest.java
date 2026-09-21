package indi.bookmarkx.relocation;

import indi.bookmarkx.model.BookmarkAnchor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnchorMatcherTest {

    /**
     * 无 git 提议
     */
    private static final int NO_GIT = -1;

    @Test
    void shouldHitAtOriginWhenLineUnchanged() {
        List<String> lines = Arrays.asList("a", "b", "target", "d", "e");
        BookmarkAnchor anchor = BookmarkAnchor.capture(lines, 2);

        MatchResult result = AnchorMatcher.match(anchor, 2, NO_GIT, lines);

        assertEquals(2, result.getLine());
        assertEquals(MatchResult.Confidence.EXACT_AT_ORIGIN, result.getConfidence());
    }

    @Test
    void shouldAcceptGitCandidateThatPassesVerification() {
        List<String> oldLines = Arrays.asList("a", "b", "target", "d", "e");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 2);
        // 新文件在开头插入两行，target 移到第 4 行
        List<String> newLines = Arrays.asList("x", "y", "a", "b", "target", "d", "e");

        MatchResult result = AnchorMatcher.match(anchor, 2, 4, newLines);

        assertEquals(4, result.getLine());
        assertEquals(MatchResult.Confidence.GIT_VERIFIED, result.getConfidence());
    }

    @Test
    void shouldFallBackToContentSearchWhenGitCandidateFailsVerification() {
        List<String> oldLines = Arrays.asList("a", "b", "target", "d", "e");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 2);
        List<String> newLines = Arrays.asList("x", "y", "a", "b", "target", "d", "e");

        // 传入一个错误的 git 候选（第 3 行是 "b" 而非 "target"）
        MatchResult result = AnchorMatcher.match(anchor, 2, 3, newLines);

        assertEquals(4, result.getLine());
        assertEquals(MatchResult.Confidence.CONTENT_UNIQUE, result.getConfidence());
    }

    @Test
    void shouldFindUniqueMatchAfterShift() {
        List<String> oldLines = Arrays.asList("a", "b", "target", "d", "e");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 2);
        List<String> newLines = Arrays.asList("x", "y", "z", "a", "b", "target", "d", "e");

        MatchResult result = AnchorMatcher.match(anchor, 2, NO_GIT, newLines);

        assertEquals(5, result.getLine());
        assertEquals(MatchResult.Confidence.CONTENT_UNIQUE, result.getConfidence());
    }

    @Test
    void shouldDisambiguateRepeatedLinesByContextScore() {
        // 锚点行是右花括号，在新文件中出现三次，只有一处的上文吻合
        List<String> oldLines = Arrays.asList(
                "void foo() {", "    doFoo();", "}",
                "void bar() {", "    doBar();", "}");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 5);

        List<String> newLines = Arrays.asList(
                "void inserted() {",  // 0
                "    nothing();",     // 1
                "    more();",        // 2
                "}",                  // 3
                "void foo() {",       // 4
                "    doFoo();",       // 5  原行号处已不是右花括号，不会走快速路径
                "}",                  // 6
                "void bar() {",       // 7
                "    doBar();",       // 8
                "}");                 // 9  上文三行完全吻合

        MatchResult result = AnchorMatcher.match(anchor, 5, NO_GIT, newLines);

        assertEquals(9, result.getLine());
        assertEquals(MatchResult.Confidence.CONTENT_SCORED, result.getConfidence());
    }

    @Test
    void shouldPreferOriginOverOtherIdenticalCandidates() {
        List<String> lines = Arrays.asList(
                "pad", "target", "pad", "pad", "pad", "pad", "pad", "target", "pad");
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of("pad"), List.of("pad"));

        MatchResult result = AnchorMatcher.match(anchor, 1, NO_GIT, lines);

        assertEquals(1, result.getLine());
        assertEquals(MatchResult.Confidence.EXACT_AT_ORIGIN, result.getConfidence());
    }

    @Test
    void shouldPickNearestWhenContextScoresTie() {
        List<String> lines = Arrays.asList(
                "pad", "target", "pad", "MOVED", "pad", "pad", "pad", "target", "pad");
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of("pad"), List.of("pad"));

        // 起点 3 处是 "MOVED"，两个 target 候选（1 和 7）上下文同分，距离分别是 2 和 4
        MatchResult result = AnchorMatcher.match(anchor, 3, NO_GIT, lines);

        assertEquals(1, result.getLine());
        assertEquals(MatchResult.Confidence.CONTENT_AMBIGUOUS, result.getConfidence());
    }

    @Test
    void shouldFallBackToSimilarityAfterVariableRename() {
        List<String> oldLines = Arrays.asList(
                "public void run() {",
                "    int userCount = repository.count();",
                "}");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 1);

        List<String> newLines = Arrays.asList(
                "public void run() {",
                "    int userTotal = repository.count();",
                "}");

        MatchResult result = AnchorMatcher.match(anchor, 1, NO_GIT, newLines);

        assertEquals(1, result.getLine());
        assertEquals(MatchResult.Confidence.SIMILARITY, result.getConfidence());
    }

    @Test
    void shouldReportLostWhenCodeIsDeleted() {
        List<String> oldLines = Arrays.asList("a", "b", "int userCount = repository.count();", "d");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 2);

        List<String> newLines = Arrays.asList("a", "b", "d");

        MatchResult result = AnchorMatcher.match(anchor, 2, NO_GIT, newLines);

        assertFalse(result.isFound());
        assertEquals(MatchResult.Confidence.LOST, result.getConfidence());
    }

    @Test
    void shouldReportLostForEmptyFile() {
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of(), List.of());

        MatchResult result = AnchorMatcher.match(anchor, 0, NO_GIT, List.of());

        assertFalse(result.isFound());
    }

    @Test
    void shouldReportLostWhenAnchorIsNull() {
        MatchResult result = AnchorMatcher.match(null, 0, NO_GIT, List.of("a"));

        assertFalse(result.isFound());
    }

    @Test
    void shouldReportLostWhenLinesIsNull() {
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of(), List.of());

        assertFalse(AnchorMatcher.match(anchor, 0, NO_GIT, null).isFound());
    }

    @Test
    void shouldSearchByContentWhenOriginLineIsOutOfBounds() {
        List<String> lines = Arrays.asList("a", "target", "c");
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of("a"), List.of("c"));

        MatchResult result = AnchorMatcher.match(anchor, 999, NO_GIT, lines);

        assertEquals(1, result.getLine());
    }

    @Test
    void shouldIgnoreIndentationChanges() {
        List<String> oldLines = Arrays.asList("class A {", "    int x;", "}");
        BookmarkAnchor anchor = BookmarkAnchor.capture(oldLines, 1);

        // 新分支把缩进从 4 空格改成 8 空格
        List<String> newLines = Arrays.asList("class A {", "        int x;", "}");

        MatchResult result = AnchorMatcher.match(anchor, 1, NO_GIT, newLines);

        assertEquals(1, result.getLine());
        assertEquals(MatchResult.Confidence.EXACT_AT_ORIGIN, result.getConfidence());
    }

    @Test
    void shouldExpandToWholeFileBeyondNearRadius() {
        // 构造一个 target 距离起点超过 50 行的文件
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            lines.add("pad" + i);
        }
        lines.set(150, "target");
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of(), List.of());

        MatchResult result = AnchorMatcher.match(anchor, 0, NO_GIT, lines);

        assertEquals(150, result.getLine());
        assertEquals(MatchResult.Confidence.CONTENT_UNIQUE, result.getConfidence());
    }

    @Test
    void shouldNotExpandBeyondLargeFileRadius() {
        // 大文件不做全文件扫描：超过 20000 行时上限为起点 ±500 行
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < AnchorMatcher.LARGE_FILE_LINES + 100; i++) {
            lines.add("pad" + i);
        }
        lines.set(1500, "target");
        BookmarkAnchor anchor = new BookmarkAnchor("target", List.of(), List.of());

        MatchResult result = AnchorMatcher.match(anchor, 0, NO_GIT, lines);

        assertFalse(result.isFound(), "超出大文件搜索半径的目标不应被命中");
    }

    @Test
    void shouldReportLostWhenAnchorTextIsBlank() {
        BookmarkAnchor anchor = new BookmarkAnchor("", List.of("a"), List.of("b"));

        assertFalse(AnchorMatcher.match(anchor, 0, NO_GIT, List.of("a", "b")).isFound());
    }
}
