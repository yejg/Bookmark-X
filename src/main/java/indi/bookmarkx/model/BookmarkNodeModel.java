package indi.bookmarkx.model;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.reference.SoftReference;
import indi.bookmarkx.BookmarksManager;
import indi.bookmarkx.common.Constants;
import indi.bookmarkx.ui.MyGutterIconRenderer;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Optional;

/**
 * 书签数据模型
 *
 * @author Nonoas
 * @date 2023/6/4
 */
public class BookmarkNodeModel extends AbstractTreeNodeModel {

    private int index;
    private int line;

    private Icon icon;

    /**
     * 文件跳转器
     */
    private OpenFileDescriptor openFileDescriptor;

    private Reference<RangeHighlighter> refHighlighter;

    /**
     * 内容锚点，用于文件被外部改写后重新定位。null 表示尚未建立
     */
    private BookmarkAnchor anchor;

    /**
     * 上次重定位是否未能找到对应代码行
     */
    private boolean anchorLost;

    public BookmarkNodeModel() {
    }

    public OpenFileDescriptor getOpenFileDescriptor() {
        return openFileDescriptor;
    }

    public void setOpenFileDescriptor(OpenFileDescriptor openFileDescriptor) {
        this.openFileDescriptor = openFileDescriptor;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getLine() {
        return line;
    }

    /**
     * 设置行号值。
     * 注意：此方法仅更新行号字段，如需同步更新行标记（Gutter Icon）和 openFileDescriptor，
     * 请使用 {@link #updateBookmarkLine(int, boolean)}
     *
     * @param newLine 新行号值（从0开始）
     */
    public void setLine(int newLine) {
        this.line = newLine;
    }

    public Icon getIcon() {
        return icon;
    }

    public void setIcon(Icon icon) {
        this.icon = icon;
    }

    /**
     * @return 内容锚点；null 表示尚未建立
     */
    public BookmarkAnchor getAnchor() {
        return anchor;
    }

    public void setAnchor(BookmarkAnchor anchor) {
        this.anchor = anchor;
    }

    /**
     * @return 上次重定位是否未能定位到对应代码行
     */
    public boolean isAnchorLost() {
        return anchorLost;
    }

    public void setAnchorLost(boolean anchorLost) {
        this.anchorLost = anchorLost;
    }

    @Override
    public final boolean isBookmark() {
        return true;
    }

    public Optional<String> getFilePath() {
        return Optional.ofNullable(openFileDescriptor)
                .map(OpenFileDescriptor::getFile)
                .map(VirtualFile::getPath);
    }

    public RangeHighlighter findMyHighlighter() {
        Document document = getCachedDocument();
        if (document == null) return null;
        RangeHighlighter result = SoftReference.dereference(refHighlighter);
        if (result != null) {
            return result;
        }
        MarkupModelEx markup = (MarkupModelEx) DocumentMarkupModel.forDocument(document, openFileDescriptor.getProject(), true);
        final Document markupDocument = markup.getDocument();
        final int startOffset = 0;
        final int endOffset = markupDocument.getTextLength();

        final Ref<RangeHighlighterEx> found = new Ref<>();
        markup.processRangeHighlightersOverlappingWith(startOffset, endOffset, highlighter -> {
            GutterMark renderer = highlighter.getGutterIconRenderer();
            if (renderer instanceof MyGutterIconRenderer && ((MyGutterIconRenderer) renderer).getModel() == this) {
                found.set(highlighter);
                return false;
            }
            return true;
        });
        result = found.get();
        refHighlighter = result == null ? null : new WeakReference<>(result);
        return result;
    }

    @Nullable
    public Document getCachedDocument() {
        return FileDocumentManager.getInstance().getCachedDocument(openFileDescriptor.getFile());
    }

    public void release() {
        int line = getLine();
        if (line < 0) {
            return;
        }
        final Document document = getCachedDocument();
        if (document == null) return;
        MarkupModelEx markup = (MarkupModelEx) DocumentMarkupModel.forDocument(document, openFileDescriptor.getProject(), true);
        final Document markupDocument = markup.getDocument();
        if (markupDocument.getLineCount() <= line) return;
        RangeHighlighter highlighter = findMyHighlighter();
        if (highlighter != null) {
            refHighlighter = null;
            highlighter.dispose();
        }
    }

    public void createLineMarker() {
        // 未能定位时行号不可信，但仍照常画图标：用户能一眼看到它，凭代码内容
        // 目测判断该拖到哪一行，比完全不展示更方便手动纠正。图标本身在
        // MyGutterIconRenderer 会换成失效样式区分出来。
        RangeHighlighter myHighlighter = findMyHighlighter();

        if (myHighlighter != null) {
            return;
        }
        Document document = getCachedDocument();
        if (null == document) {
            return;
        }
        Optional.ofNullable(openFileDescriptor)
                .map(OpenFileDescriptor::getProject)
                .ifPresent(project -> {
                    MarkupModelEx markupModel = (MarkupModelEx) DocumentMarkupModel.forDocument(document, project, true);
                    RangeHighlighterEx bkx = markupModel.addPersistentLineHighlighter(Constants.TK_BOOKMARK_X, getLine(), HighlighterLayer.ERROR + 1);
                    if (bkx == null) {
                        return;
                    }
                    bkx.setGutterIconRenderer(new MyGutterIconRenderer(this));
                });

    }

    /**
     * 刷新已存在的 gutter 图标，用于 {@code anchorLost} 状态切换后强制重绘。
     * <p>{@link MyGutterIconRenderer#getIcon()} 是按 {@code model.isAnchorLost()}
     * 动态判断的，但平台只在调用 {@link RangeHighlighter#setGutterIconRenderer} 时
     * 才会触发重绘，仅仅改变 model 的字段不会让已有图标自动刷新，所以状态切换后
     * 必须重新 set 一次（哪怕换上去的还是同一个 renderer 实例）。</p>
     * <p>行号不存在图标（比如从未画过、或者已被移除）时什么都不做——那种情况该走
     * {@link #createLineMarker()} 新建，不是这里要处理的。</p>
     */
    public void refreshLineMarker() {
        RangeHighlighter highlighter = findMyHighlighter();
        if (highlighter == null) {
            return;
        }
        highlighter.setGutterIconRenderer(new MyGutterIconRenderer(this));
    }

    public void updateBookmarkLine(int newLine, boolean doPersistentSave) {
        if (this.line == newLine) {
            return;
        }
        OpenFileDescriptor oldDescriptor = getOpenFileDescriptor();
        if (oldDescriptor == null) {
            // openFileDescriptor 为 null 时，只更新行号，不操作行标记
            this.line = newLine;
            return;
        }
        this.line = newLine;
        this.setOpenFileDescriptor(
                new OpenFileDescriptor(
                        oldDescriptor.getProject(),
                        oldDescriptor.getFile(),
                        newLine,
                        0
                )
        );
        this.release();
        this.createLineMarker();
        if (doPersistentSave) {
            BookmarksManager.getInstance(oldDescriptor.getProject()).persistentSave();
        }
    }
}
