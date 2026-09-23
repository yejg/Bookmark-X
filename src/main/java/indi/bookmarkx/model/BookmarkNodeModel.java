package indi.bookmarkx.model;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
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

    /**
     * 获取当前行号。
     * <p>Gutter 图标依附的 {@link RangeHighlighter} 才是行号的实时真相来源——它由编辑器
     * markup 层维护，会随文档增删行自动跟随移动，不需要任何人手动同步。此前的实现是
     * 反过来的：{@code line} 字段是独立存储，寄望 {@code BookmarkDocumentListener} 在每次
     * 文档变化时把 highlighter 算出的最新行号“手动搬运”回这个字段，而搬运用的
     * {@code OpenFileDescriptor#getRangeMarker()} 只有 descriptor 曾被 {@code navigate()}
     * 过才可能非空，很多场景下同步压根不会发生，于是字段停留在旧值——表现为 Gutter
     * 图标（读 highlighter，位置准）与书签名称文字/双击跳转（读这个字段，位置旧）在
     * 编辑器里分家，跳到不同行。</p>
     * <p>现在有 highlighter 时优先以它为准，顺手回填字段，这样字段本身也不会再过期；
     * 没有 highlighter（书签尚未画过图标、文件未打开、后台线程计算等）时退回字段值，
     * 不强求这些场景下也能拿到实时行号。</p>
     */
    public int getLine() {
        if (openFileDescriptor == null) {
            // 尚未关联文件（刚 new 出来、还没走完创建流程）时压根不可能有 highlighter，
            // findMyHighlighter() 会因为 openFileDescriptor 为 null 而抛异常，这里提前拦掉。
            return line;
        }
        RangeHighlighter highlighter = findMyHighlighter();
        if (highlighter != null && highlighter.isValid()) {
            Document document = highlighter.getDocument();
            int liveLine = document.getLineNumber(highlighter.getStartOffset());
            if (liveLine != this.line) {
                this.line = liveLine;
            }
            return liveLine;
        }
        return line;
    }

    /**
     * 直接读取持久化字段值，不查询 highlighter。
     * <p>{@link #getLine()} 会访问 {@code MarkupModelEx}/{@code Document} 等编辑器
     * UI 状态，只应在 EDT 上调用。{@link indi.bookmarkx.service.BookmarkRelocationService}
     * 的重定位计算跑在后台线程池，不能碰这些对象，那里必须用这个方法读起点行号。</p>
     */
    public int getPersistedLine() {
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

    /**
     * 跳转到书签所在位置。
     * <p>{@link #getOpenFileDescriptor()} 返回的 descriptor 行号是创建时固化的值，即使
     * {@link #getLine()} 已经从 highlighter 校正过，缓存的 descriptor 也不会跟着变——
     * 双击书签跳错行的另一半原因就在这里：即便字段已经修好，跳转用的还是旧 descriptor。
     * 跳转前用 {@link #getLine()} 的最新结果重新构造一次 descriptor，确保落点与 Gutter
     * 图标当前的真实位置一致。</p>
     *
     * @return 是否成功发起跳转
     */
    public boolean navigate() {
        OpenFileDescriptor descriptor = getOpenFileDescriptor();
        if (descriptor == null) {
            return false;
        }
        int currentLine = getLine();
        if (currentLine != descriptor.getLine()) {
            descriptor = new OpenFileDescriptor(descriptor.getProject(), descriptor.getFile(), currentLine, 0);
            setOpenFileDescriptor(descriptor);
        }
        descriptor.navigate(true);
        return true;
    }

    public RangeHighlighter findMyHighlighter() {
        if (openFileDescriptor == null) {
            return null;
        }
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
        if (openFileDescriptor == null) {
            return null;
        }
        return FileDocumentManager.getInstance().getCachedDocument(openFileDescriptor.getFile());
    }

    public void release() {
        // 故意不用 getLine()：它读到 highlighter 后会把结果写回 this.line 字段，
        // 而 release() 常在 updateBookmarkLine() 里于 this.line 已经改成新值、
        // 旧 highlighter 还没摘掉的中间状态被调用——一旦这里查到旧 highlighter
        // 又把字段覆盖回旧位置，紧接着 createLineMarker() 就会在错误的旧行建图标。
        // release() 只是要摘掉当前挂着的旧 highlighter，直接查 highlighter 本身即可，
        // 不需要经过会有副作用的 getLine()。
        RangeHighlighter highlighter = findMyHighlighter();
        if (highlighter == null) {
            return;
        }
        int line = highlighter.getDocument().getLineNumber(highlighter.getStartOffset());
        if (line < 0) {
            return;
        }
        final Document document = getCachedDocument();
        if (document == null) return;
        if (document.getLineCount() <= line) return;
        refHighlighter = null;
        highlighter.dispose();
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
        // 用 getLine() 而不是直接读字段：字段只在 getLine() 被调用时才会顺带校正，
        // 直接比较 this.line 可能拿到尚未校正的陈旧值，导致该更新的没更新、或者
        // 明明没变化却重建一次 highlighter。
        int oldLine = getLine();
        if (oldLine == newLine) {
            return;
        }
        OpenFileDescriptor oldDescriptor = getOpenFileDescriptor();
        if (oldDescriptor == null) {
            // openFileDescriptor 为 null 时，只更新行号，不操作行标记
            this.line = newLine;
            return;
        }
        // 行号发生了任何一次改变（无论是自动重定位还是手动拖拽纠正），都视为
        // 「现在这个位置是可信的」——手动拖拽是用户明确告诉插件正确位置在哪，
        // 理应立刻清掉失效标记恢复正常黄色图标，不能让它继续停在灰色状态。
        this.anchorLost = false;
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
        // gutter 图标随 RangeHighlighter 一起挪动，但行尾的书签名文字来自
        // EditorLinePainter 扩展点，平台只在编辑器重绘对应行时才会重新调用它，
        // 不会因为 RangeHighlighter 变化自动刷新。必须显式重绘旧行号与新行号，
        // 否则行尾文字会停留在旧行上，与已经挪动到位的 gutter 图标不同步。
        repaintLine(oldDescriptor, oldLine, newLine);
        if (doPersistentSave) {
            BookmarksManager.getInstance(oldDescriptor.getProject()).persistentSave();
        }
    }

    /**
     * 重绘旧行号与新行号所在区域，让 {@code EditorLinePainter}（行尾书签名注释）
     * 与刚刚已经跟随 {@code RangeHighlighter} 挪动的 gutter 图标保持同步。
     */
    private static void repaintLine(OpenFileDescriptor descriptor, int oldLine, int newLine) {
        VirtualFile file = descriptor.getFile();
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(descriptor.getProject());
        for (FileEditor fileEditor : fileEditorManager.getEditors(file)) {
            if (fileEditor instanceof TextEditor) {
                Editor editor = ((TextEditor) fileEditor).getEditor();
                if (editor instanceof EditorEx) {
                    int from = Math.max(0, Math.min(oldLine, newLine));
                    int to = Math.max(oldLine, newLine);
                    ((EditorEx) editor).repaint(from, to);
                }
            }
        }
    }
}
