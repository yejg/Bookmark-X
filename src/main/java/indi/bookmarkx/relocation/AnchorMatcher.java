package indi.bookmarkx.relocation;

import indi.bookmarkx.model.BookmarkAnchor;

import java.util.ArrayList;
import java.util.List;

/**
 * 锚点匹配算法，实现设计文档中「提议—验收」流水线的步骤 ①④⑤。
 * <p>本类不依赖任何 IntelliJ API，可独立单元测试。</p>
 *
 * @author Nonoas
 */
public final class AnchorMatcher {

    /**
     * 近距离搜索半径，优先在此范围内查找
     */
    public static final int NEAR_RADIUS = 50;

    /**
     * 超过此行数视为大文件，不做全文件扫描
     */
    public static final int LARGE_FILE_LINES = 20000;

    /**
     * 大文件的搜索半径上限
     */
    public static final int LARGE_FILE_RADIUS = 500;

    /**
     * 相似度兜底的接受阈值
     */
    public static final double SIMILARITY_THRESHOLD = 0.75;

    private AnchorMatcher() {
    }

    /**
     * 为书签在新的文件内容中寻找行号。
     *
     * @param anchor       书签锚点，为 null 时直接返回失败
     * @param originLine   书签原行号，0-based，允许越界
     * @param gitCandidate git diff 提议的行号，0-based；无提议时传 -1
     * @param lines        新文件的全部行，原始文本
     * @return 匹配结果，永不为 null
     */
    public static MatchResult match(BookmarkAnchor anchor, int originLine, int gitCandidate, List<String> lines) {
        if (anchor == null || lines == null || lines.isEmpty()) {
            return MatchResult.lost();
        }

        String target = anchor.getAnchorText();
        if (target.isEmpty()) {
            return MatchResult.lost();
        }

        // ① 快速路径：原行号处文本未变，覆盖绝大多数书签
        if (isExactAt(lines, originLine, target)) {
            return MatchResult.found(originLine, MatchResult.Confidence.EXACT_AT_ORIGIN);
        }

        // ③ 锚点验收：git 提议的行号是否真的是那行代码
        if (isExactAt(lines, gitCandidate, target)) {
            return MatchResult.found(gitCandidate, MatchResult.Confidence.GIT_VERIFIED);
        }

        int origin = gitCandidate >= 0 ? gitCandidate : originLine;
        // 起点越界时夹到文件范围内，保证搜索窗口有意义
        origin = Math.max(0, Math.min(origin, lines.size() - 1));

        // ④ 内容兜底：先近后远
        MatchResult exact = searchExact(anchor, target, origin, lines);
        if (exact.isFound()) {
            return exact;
        }

        // ⑤ 相似度兜底
        return searchSimilar(target, origin, lines);
    }

    private static boolean isExactAt(List<String> lines, int line, String target) {
        if (line < 0 || line >= lines.size()) {
            return false;
        }
        return target.equals(AnchorTextNormalizer.normalize(lines.get(line)));
    }

    /**
     * 在搜索窗口内查找精确匹配，实现步骤 ④
     */
    private static MatchResult searchExact(BookmarkAnchor anchor, String target, int origin, List<String> lines) {
        for (int radius : radiusSequence(lines.size())) {
            int[] bounds = bounds(origin, radius, lines.size());
            List<Integer> candidates = new ArrayList<>();
            for (int i = bounds[0]; i <= bounds[1]; i++) {
                if (target.equals(AnchorTextNormalizer.normalize(lines.get(i)))) {
                    candidates.add(i);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            if (candidates.size() == 1) {
                return MatchResult.found(candidates.get(0), MatchResult.Confidence.CONTENT_UNIQUE);
            }
            return pickByContext(anchor, candidates, origin, lines);
        }
        return MatchResult.lost();
    }

    /**
     * 多个精确匹配时用上下文打分消歧
     */
    private static MatchResult pickByContext(BookmarkAnchor anchor, List<Integer> candidates,
                                            int origin, List<String> lines) {
        int bestScore = -1;
        int bestLine = -1;
        boolean tie = false;

        for (int candidate : candidates) {
            int score = contextScore(anchor, lines, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestLine = candidate;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
                // 同分时取距起点更近的
                if (Math.abs(candidate - origin) < Math.abs(bestLine - origin)) {
                    bestLine = candidate;
                }
            }
        }

        return MatchResult.found(bestLine,
                tie ? MatchResult.Confidence.CONTENT_AMBIGUOUS : MatchResult.Confidence.CONTENT_SCORED);
    }

    /**
     * 上下文吻合度：候选行的前后文与锚点上下文逐行比对，每命中一行记 1 分
     */
    private static int contextScore(BookmarkAnchor anchor, List<String> lines, int candidate) {
        int score = 0;

        // contextBefore 按文件自然顺序存放，最后一个元素紧邻书签行
        List<String> before = anchor.getContextBefore();
        for (int k = 0; k < before.size(); k++) {
            int lineIndex = candidate - before.size() + k;
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                continue;
            }
            if (before.get(k).equals(AnchorTextNormalizer.normalize(lines.get(lineIndex)))) {
                score++;
            }
        }

        // contextAfter 按文件自然顺序存放，第一个元素紧邻书签行
        List<String> after = anchor.getContextAfter();
        for (int k = 0; k < after.size(); k++) {
            int lineIndex = candidate + 1 + k;
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                continue;
            }
            if (after.get(k).equals(AnchorTextNormalizer.normalize(lines.get(lineIndex)))) {
                score++;
            }
        }

        return score;
    }

    /**
     * 相似度兜底，实现步骤 ⑤。搜索窗口与步骤 ④ 保持一致。
     */
    private static MatchResult searchSimilar(String target, int origin, List<String> lines) {
        for (int radius : radiusSequence(lines.size())) {
            int[] bounds = bounds(origin, radius, lines.size());
            double bestScore = -1;
            int bestLine = -1;

            for (int i = bounds[0]; i <= bounds[1]; i++) {
                // 比较必须发生在规范化后的文本上，否则缩进会拉低相似度
                double score = TextSimilarity.similarity(target, AnchorTextNormalizer.normalize(lines.get(i)));
                boolean better = score > bestScore
                        || (score == bestScore && bestLine >= 0
                        && Math.abs(i - origin) < Math.abs(bestLine - origin));
                if (better) {
                    bestScore = score;
                    bestLine = i;
                }
            }

            if (bestLine >= 0 && bestScore >= SIMILARITY_THRESHOLD) {
                return MatchResult.found(bestLine, MatchResult.Confidence.SIMILARITY);
            }
        }
        return MatchResult.lost();
    }

    /**
     * 搜索半径序列：先近后远。大文件不扩展到全文，避免一次切换扫遍整个文件。
     */
    private static int[] radiusSequence(int fileSize) {
        if (fileSize > LARGE_FILE_LINES) {
            return new int[]{NEAR_RADIUS, LARGE_FILE_RADIUS};
        }
        return new int[]{NEAR_RADIUS, fileSize};
    }

    /**
     * @return 长度为 2 的数组，[0] 为窗口起始行，[1] 为窗口结束行，均为闭区间
     */
    private static int[] bounds(int origin, int radius, int fileSize) {
        int from = Math.max(0, origin - radius);
        int to = Math.min(fileSize - 1, origin + radius);
        return new int[]{from, to};
    }
}
