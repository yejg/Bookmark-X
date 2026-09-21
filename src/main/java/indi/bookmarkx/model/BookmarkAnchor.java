package indi.bookmarkx.model;

import indi.bookmarkx.relocation.AnchorTextNormalizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 书签的内容锚点。
 * <p>记录书签所在行及其前后若干行的规范化文本，用于在文件被外部改写
 * （切换分支、拉取更新、外部编辑器修改）后重新定位书签。</p>
 *
 * @author Nonoas
 */
public final class BookmarkAnchor {

    /**
     * 上下文行数，前后各取这么多行
     */
    public static final int CONTEXT_SIZE = 3;

    /**
     * 持久化时连接多行上下文的分隔符
     */
    private static final String SEPARATOR = "\n";

    private final String anchorText;
    private final List<String> contextBefore;
    private final List<String> contextAfter;

    public BookmarkAnchor(String anchorText, List<String> contextBefore, List<String> contextAfter) {
        this.anchorText = anchorText == null ? "" : anchorText;
        this.contextBefore = contextBefore == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(contextBefore));
        this.contextAfter = contextAfter == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(contextAfter));
    }

    /**
     * 从文件内容中抓取锚点。
     *
     * @param lines 文件的全部行，原始文本
     * @param line  书签行号，0-based
     * @return 锚点；行号为负或越界时返回 null
     */
    public static BookmarkAnchor capture(List<String> lines, int line) {
        if (lines == null || line < 0 || line >= lines.size()) {
            return null;
        }

        String anchorText = AnchorTextNormalizer.normalize(lines.get(line));

        List<String> before = new ArrayList<>(CONTEXT_SIZE);
        for (int i = Math.max(0, line - CONTEXT_SIZE); i < line; i++) {
            before.add(AnchorTextNormalizer.normalize(lines.get(i)));
        }

        List<String> after = new ArrayList<>(CONTEXT_SIZE);
        for (int i = line + 1; i < Math.min(lines.size(), line + 1 + CONTEXT_SIZE); i++) {
            after.add(AnchorTextNormalizer.normalize(lines.get(i)));
        }

        return new BookmarkAnchor(anchorText, before, after);
    }

    /**
     * 从持久化的三个字段还原锚点。
     *
     * @return 锚点；锚点文本为空时返回 null，表示该书签尚未建立锚点
     */
    public static BookmarkAnchor fromPersisted(String anchorText, String contextBefore, String contextAfter) {
        if (anchorText == null || anchorText.isEmpty()) {
            return null;
        }
        return new BookmarkAnchor(anchorText, split(contextBefore), split(contextAfter));
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(joined.split(SEPARATOR, -1));
    }

    public String getAnchorText() {
        return anchorText;
    }

    /**
     * @return 书签行之前的上下文，按文件自然顺序，最后一个元素紧邻书签行
     */
    public List<String> getContextBefore() {
        return contextBefore;
    }

    /**
     * @return 书签行之后的上下文，按文件自然顺序，第一个元素紧邻书签行
     */
    public List<String> getContextAfter() {
        return contextAfter;
    }

    public String persistedContextBefore() {
        return String.join(SEPARATOR, contextBefore);
    }

    public String persistedContextAfter() {
        return String.join(SEPARATOR, contextAfter);
    }
}
