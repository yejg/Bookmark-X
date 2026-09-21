package indi.bookmarkx.relocation;

/**
 * 文本相似度计算，用于锚点匹配的最后兜底环节。
 *
 * @author Nonoas
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /**
     * 计算两个字符串的 Levenshtein 编辑距离。
     * <p>采用滚动数组，空间复杂度 O(min(m,n))。</p>
     *
     * @param a 字符串，可为 null
     * @param b 字符串，可为 null
     * @return 编辑距离，永不为负
     */
    public static int levenshtein(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;

        if (left.equals(right)) {
            return 0;
        }
        if (left.isEmpty()) {
            return right.length();
        }
        if (right.isEmpty()) {
            return left.length();
        }

        // 让较短的一侧作为列，滚动数组因此更小
        if (left.length() < right.length()) {
            String tmp = left;
            left = right;
            right = tmp;
        }

        int[] prev = new int[right.length() + 1];
        int[] curr = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[right.length()];
    }

    /**
     * 归一化相似度：{@code 1 - 距离 / 较长串长度}。
     *
     * @return [0, 1] 区间的相似度；两个空串视为完全相同，返回 1
     */
    public static double similarity(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        int max = Math.max(left.length(), right.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(left, right) / max;
    }
}
