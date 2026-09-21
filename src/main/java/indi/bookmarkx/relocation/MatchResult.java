package indi.bookmarkx.relocation;

/**
 * 锚点匹配的结果。
 *
 * @author Nonoas
 */
public final class MatchResult {

    /**
     * 匹配置信度，同时表明命中来自流水线的哪一步
     */
    public enum Confidence {
        /**
         * 原行号处文本未变，直接命中
         */
        EXACT_AT_ORIGIN,
        /**
         * git 提议的行号通过了锚点验收
         */
        GIT_VERIFIED,
        /**
         * 搜索窗口内存在唯一的精确匹配
         */
        CONTENT_UNIQUE,
        /**
         * 多个精确匹配，由上下文打分选出唯一最高分
         */
        CONTENT_SCORED,
        /**
         * 多个精确匹配且最高分并列，取距起点最近者
         */
        CONTENT_AMBIGUOUS,
        /**
         * 无精确匹配，由相似度兜底命中
         */
        SIMILARITY,
        /**
         * 未能定位
         */
        LOST
    }

    private static final MatchResult LOST = new MatchResult(-1, Confidence.LOST);

    private final int line;
    private final Confidence confidence;

    private MatchResult(int line, Confidence confidence) {
        this.line = line;
        this.confidence = confidence;
    }

    public static MatchResult found(int line, Confidence confidence) {
        return new MatchResult(line, confidence);
    }

    public static MatchResult lost() {
        return LOST;
    }

    public boolean isFound() {
        return confidence != Confidence.LOST;
    }

    /**
     * @return 命中的行号（0-based）；未命中时为 -1
     */
    public int getLine() {
        return line;
    }

    public Confidence getConfidence() {
        return confidence;
    }
}
