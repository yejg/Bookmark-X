package indi.bookmarkx.service;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import indi.bookmarkx.model.BookmarkAnchor;
import indi.bookmarkx.model.BookmarkNodeModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 负责为书签抓取和刷新内容锚点。
 * <p>锚点需要与「最后一次已知正确的书签位置」保持一致，因此在这些时机抓取：
 * 书签创建、行号被改动、用户在编辑器内编辑导致行号漂移、以及一次成功的重定位之后。
 * 书签已标记失效时不再刷新，否则会用错误位置的文本覆盖掉「最后一次已知正确位置」的凭据。</p>
 *
 * @author Nonoas
 */
public final class BookmarkAnchorCapturer {

    private static final Logger LOG = Logger.getInstance(BookmarkAnchorCapturer.class);

    private BookmarkAnchorCapturer() {
    }

    /**
     * 读取文件的全部文本行。
     * <p>优先读取编辑器中的 Document，以便拿到尚未保存到磁盘的内容。</p>
     *
     * @return 文本行；文件不可读、不存在或为二进制时返回 null
     */
    public static List<String> readLines(VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory()) {
            return null;
        }
        try {
            if (file.getFileType().isBinary()) {
                return null;
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            String text = document != null
                    ? document.getText()
                    : VfsUtilCore.loadText(file);
            // limit 传 -1 保留末尾空行，使行号与编辑器一致
            return Arrays.asList(text.split("\n", -1));
        } catch (Exception e) {
            LOG.info("读取文件内容失败: " + file.getPath(), e);
            return null;
        }
    }

    /**
     * 按书签当前行号抓取锚点并写回模型，同时清除失效标记。
     *
     * @return 是否成功抓取
     */
    public static boolean capture(BookmarkNodeModel model, List<String> lines) {
        if (model == null) {
            return false;
        }
        return applyAnchor(model, lines, model.getLine());
    }

    /**
     * 读取书签所在文件并抓取锚点
     *
     * @return 是否成功抓取
     */
    public static boolean capture(BookmarkNodeModel model) {
        if (model == null) {
            return false;
        }
        OpenFileDescriptor descriptor = model.getOpenFileDescriptor();
        if (descriptor == null) {
            return false;
        }
        return applyAnchor(model, readLines(descriptor.getFile()), model.getLine());
    }

    /**
     * 依据编辑器文档中当前位置抓取锚点。
     * <p>只读取书签行附近的窗口而不是整篇文本，因此可以安全地在每次文档变更时调用。</p>
     *
     * @return 是否成功抓取
     */
    public static boolean capture(BookmarkNodeModel model, Document document) {
        if (model == null || document == null) {
            return false;
        }
        int line = model.getLine();
        int lineCount = document.getLineCount();
        if (line < 0 || line >= lineCount) {
            return false;
        }
        int start = Math.max(0, line - BookmarkAnchor.CONTEXT_SIZE);
        int end = Math.min(lineCount - 1, line + BookmarkAnchor.CONTEXT_SIZE);

        List<String> window = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            try {
                window.add(document.getText(new TextRange(
                        document.getLineStartOffset(i), document.getLineEndOffset(i))));
            } catch (Exception e) {
                LOG.info("读取文档第 " + i + " 行失败", e);
                return false;
            }
        }
        // 窗口内的下标要换算回书签行的相对位置
        return applyAnchor(model, window, line - start);
    }

    private static boolean applyAnchor(BookmarkNodeModel model, List<String> lines, int index) {
        if (model == null || lines == null) {
            return false;
        }
        BookmarkAnchor anchor = BookmarkAnchor.capture(lines, index);
        if (anchor == null) {
            return false;
        }
        model.setAnchor(anchor);
        model.setAnchorLost(false);
        return true;
    }
}
