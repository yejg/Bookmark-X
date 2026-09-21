package indi.bookmarkx.relocation;

import java.util.regex.Pattern;

/**
 * 锚点文本规范化。
 * <p>规范化的目的是让「同一行代码在缩进或空白排版被改动后」仍然相等，
 * 因此去除首尾空白并把中间的连续空白压缩为单个空格。</p>
 * <p>除了标准正则 {@code \s}（ASCII 空白）以外，额外把两个常见但不在
 * {@code \s} / {@code \p{javaWhitespace}} 覆盖范围内的字符也当作空白处理：</p>
 * <ul>
 *     <li>U+3000 全角空格（IDEOGRAPHIC SPACE）——中文输入法全角模式下的常见产物，
 *     {@code \s}、{@code String#trim()} 均不识别，但 {@code String#strip()}
 *     （依赖 {@link Character#isWhitespace}）能识别。</li>
 *     <li>U+00A0 不换行空格（NO-BREAK SPACE，NBSP）——网页复制粘贴代码的常见产物，
 *     {@code \s}、{@code String#trim()}、{@code String#strip()} 均不识别
 *     （{@link Character#isWhitespace} 对 NBSP 返回 false）。</li>
 * </ul>
 * <p>因此没有任何一个内置手段（{@code \s}、{@code \p{javaWhitespace}}、
 * {@code trim()}、{@code strip()}）能同时覆盖这两个字符，这里用自定义字符类
 * 显式列出。</p>
 *
 * @author Nonoas
 */
public final class AnchorTextNormalizer {

    /**
     * 规范化后保留的最大字符数。
     * 用于防止压缩过的单行文件（minified js 等）把持久化文件撑爆。
     */
    public static final int MAX_LENGTH = 200;

    /**
     * 空白字符类：标准 {@code \s} 之外额外收录 U+3000（全角空格）与 U+00A0（NBSP）。
     * 预编译为常量，避免 {@link #normalize(String)} 在 AnchorMatcher 的匹配循环中
     * 被高频调用时反复编译正则。
     */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s　 ]+");

    private AnchorTextNormalizer() {
    }

    /**
     * @param raw 原始行文本，可为 null
     * @return 规范化文本，永不为 null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // 先把所有空白类字符（含全角空格、NBSP）的连续片段压缩为单个 ASCII 空格，
        // 这样首尾的空白片段也会变成普通空格，再用 trim() 去除即可。
        String collapsed = WHITESPACE.matcher(raw).replaceAll(" ").trim();
        if (collapsed.length() <= MAX_LENGTH) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_LENGTH);
    }
}
