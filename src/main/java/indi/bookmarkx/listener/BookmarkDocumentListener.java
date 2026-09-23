package indi.bookmarkx.listener;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.EditorFactoryImpl;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import indi.bookmarkx.BookmarksManager;
import indi.bookmarkx.common.data.BookmarkArrayListTable;
import indi.bookmarkx.model.BookmarkNodeModel;
import indi.bookmarkx.service.BookmarkAnchorCapturer;
import indi.bookmarkx.service.BookmarkRelocationService;
import org.apache.commons.collections.CollectionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 文档变化监听。
 * <p>编辑器内的增量编辑由 Gutter 图标依附的 {@code RangeHighlighter} 自动跟踪行号
 * （见 {@link BookmarkNodeModel#getLine()}），这里只需要在每次编辑后用校正好的最新
 * 行号刷新一次锚点；整篇替换（切分支后重载、全选粘贴）则转交
 * {@link BookmarkRelocationService} 按内容锚点重算。</p>
 * <p>此前这里还兼职「用 {@code OpenFileDescriptor#getRangeMarker()} 手动同步行号、
 * marker 拿不到就删书签」，两个问题都源于 {@code getRangeMarker()} 只有 descriptor
 * 曾被 {@code navigate()} 过才可能非空：同步经常静默失效（表现为 Gutter 图标位置准、
 * 书签名称文字和跳转位置却停在旧行），更严重的是从未打开过的书签会被在这误删。
 * 改为直接读 highlighter 后，这两个问题一并消失，不再需要 marker 兜底。</p>
 *
 * @author codeleep
 * @createTime 2024/03/20 19:44
 */
public class BookmarkDocumentListener implements DocumentListener {

    private static final Logger LOG = Logger.getInstance(BookmarkDocumentListener.class);

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        try {
            Document document = event.getDocument();

            VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
            Editor editor = getEditor(document);

            if (virtualFile == null || editor == null) {
                return;
            }

            Project project = editor.getProject();
            if (project == null) {
                return;
            }

            // 整篇内容被替换（切分支后从磁盘重载、全选粘贴等）时，highlighter 会连同
            // 文本一起被换掉或漂移到文件末尾，普通的增量编辑处理已经不适用。此时交给
            // 重定位服务按内容锚点重算。
            if (isWholeDocumentReplaced(event, document)) {
                BookmarkRelocationService.getInstance(project).scheduleRelocate(virtualFile);
                return;
            }

            BookmarkArrayListTable bookmarkArrayListTable = BookmarkArrayListTable.getInstance(project);
            List<BookmarkNodeModel> indexList = bookmarkArrayListTable.getOnlyIndex(virtualFile.getPath());
            // 空的直接返回
            if (CollectionUtils.isEmpty(indexList)) {
                return;
            }

            perceivedLineChange(project, indexList, document);
        } catch (Exception e) {
            LOG.info("perceivedLineChange error", e);
        }
    }


    /**
     * 判断本次变更是否用新内容整体替换了文档。
     * <p>判据：变更从偏移 0 开始，且新内容长度等于变更后的文档总长度（配合 oldLength &gt; 0
     * 排除首次加载）。全选粘贴同样命中，此时按内容重定位也比依赖 RangeMarker 更准。</p>
     */
    private static boolean isWholeDocumentReplaced(DocumentEvent event, Document document) {
        return event.getOffset() == 0
                && event.getOldLength() > 0
                && event.getNewLength() == document.getTextLength();
    }

    private void perceivedLineChange(Project project, List<BookmarkNodeModel> indexList, Document eventDocument) {
        if (CollectionUtils.isEmpty(indexList)) {
            return;
        }
        BookmarksManager bookmarksManager = BookmarksManager.getInstance(project);
        for (BookmarkNodeModel node : indexList) {
            if (node == null) {
                continue;
            }
            // getLine() 会优先从 Gutter 图标依附的 highlighter 读取实时行号并顺带
            // 校正字段，不再需要这里手动同步；没有 highlighter（书签尚未画过图标）
            // 时保留原值，也不应该因此删除书签——文件仍然打开着，只是这个书签还
            // 没被渲染过，不代表它已经失效。
            // 锚点要跟随编辑器内的实际内容，否则下次切分支会拿过期文本去匹配。
            // 已失效的书签例外：它的锚点是「最后一次已知正确位置」的凭据，不能被覆盖。
            if (!node.isAnchorLost()) {
                BookmarkAnchorCapturer.capture(node, eventDocument);
            }
        }
        bookmarksManager.persistentSave();
    }

    private Editor getEditor(Document document) {
        Editor[] editors = EditorFactoryImpl.getInstance().getEditors(document);
        if (editors.length >= 1) {
            return editors[0];
        }
        return null;
    }

}
