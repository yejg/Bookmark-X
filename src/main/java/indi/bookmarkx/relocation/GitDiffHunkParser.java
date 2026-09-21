package indi.bookmarkx.relocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code git diff --unified=0} 的输出，构建旧行号到新行号的映射。
 * <p>git 输出的行号是 1-based，而插件内部一律使用 0-based，
 * 转换只在本类的边界上发生。</p>
 *
 * @author Nonoas
 */
public final class GitDiffHunkParser {

    /**
     * 匹配形如 {@code @@ -5,2 +5,3 @@} 或 {@code @@ -5 +5 @@} 的 hunk 头
     */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    /**
     * 匹配 {@code +++ b/src/Foo.java} 形式的新文件路径行
     */
    private static final Pattern NEW_FILE_HEADER = Pattern.compile("^\\+\\+\\+ (?:b/)?(.+)$");

    /**
     * 被删除的文件在新侧表现为该路径
     */
    private static final String DEV_NULL = "/dev/null";

    private GitDiffHunkParser() {
    }

    /**
     * @param diffOutput 单个文件的 diff 输出，可为 null
     * @return 行号映射，永不为 null
     */
    public static LineMapping parse(String diffOutput) {
        List<Hunk> hunks = new ArrayList<>();
        if (diffOutput == null || diffOutput.isEmpty()) {
            return new LineMapping(hunks);
        }

        for (String line : diffOutput.split("\n")) {
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (matcher.find()) {
                hunks.add(toHunk(matcher));
            }
        }
        return new LineMapping(hunks);
    }

    /**
     * 拆分一次 git diff 调用中多个文件的输出。
     *
     * @param diffOutput git diff 的完整输出，可为 null
     * @return 仓库相对路径 → 行号映射；被删除的文件不会出现在结果中
     */
    public static Map<String, LineMapping> parseMultiFile(String diffOutput) {
        Map<String, LineMapping> result = new HashMap<>();
        if (diffOutput == null || diffOutput.isEmpty()) {
            return result;
        }

        String currentPath = null;
        List<Hunk> currentHunks = new ArrayList<>();

        for (String line : diffOutput.split("\n")) {
            Matcher fileMatcher = NEW_FILE_HEADER.matcher(line);
            if (fileMatcher.find()) {
                // 收尾上一个文件
                flush(result, currentPath, currentHunks);
                currentHunks = new ArrayList<>();
                String path = fileMatcher.group(1).trim();
                // 文件被删除时新路径为 /dev/null，跳过
                currentPath = DEV_NULL.equals(path) ? null : path;
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.find() && currentPath != null) {
                currentHunks.add(toHunk(hunkMatcher));
            }
        }
        flush(result, currentPath, currentHunks);
        return result;
    }

    private static void flush(Map<String, LineMapping> result, String path, List<Hunk> hunks) {
        if (path != null) {
            result.put(path, new LineMapping(hunks));
        }
    }

    private static Hunk toHunk(Matcher matcher) {
        int oldStart = Integer.parseInt(matcher.group(1));
        int oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
        return new Hunk(oldStart, oldCount, newCount);
    }

    /**
     * 单个 hunk，行号为 git 原始的 1-based 值
     */
    private static final class Hunk {

        /**
         * 旧侧起始行号，1-based
         */
        final int oldStart;

        /**
         * 旧侧行数，0 表示纯插入
         */
        final int oldCount;

        /**
         * 新侧行数
         */
        final int newCount;

        Hunk(int oldStart, int oldCount, int newCount) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newCount = newCount;
        }
    }

    /**
     * 旧行号到新行号的映射。对外接口一律使用 0-based 行号。
     */
    public static final class LineMapping {

        private final List<Hunk> hunks;

        private LineMapping(List<Hunk> hunks) {
            this.hunks = hunks;
        }

        /**
         * @param oldLine 旧文件中的行号，0-based
         * @return 新文件中的行号（0-based）；该行已被删除或修改时返回空
         */
        public OptionalInt map(int oldLine) {
            if (oldLine < 0) {
                return OptionalInt.empty();
            }

            // 转为 git 的 1-based 基准参与计算，返回前再转回 0-based
            int oldLine1 = oldLine + 1;
            int delta = 0;

            for (Hunk hunk : hunks) {
                if (hunk.oldCount == 0) {
                    // 纯插入：oldStart 是插入点之前那一行，位于其上（含）的行不受影响
                    if (oldLine1 <= hunk.oldStart) {
                        return OptionalInt.of(oldLine1 + delta - 1);
                    }
                    delta += hunk.newCount;
                } else {
                    if (oldLine1 < hunk.oldStart) {
                        return OptionalInt.of(oldLine1 + delta - 1);
                    }
                    if (oldLine1 < hunk.oldStart + hunk.oldCount) {
                        // 落在被删除或被修改的区间内
                        return OptionalInt.empty();
                    }
                    delta += hunk.newCount - hunk.oldCount;
                }
            }
            return OptionalInt.of(oldLine1 + delta - 1);
        }
    }
}
