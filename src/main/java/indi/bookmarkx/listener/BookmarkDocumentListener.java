package indi.bookmarkx.listener;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.EditorFactoryImpl;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import indi.bookmarkx.BookmarksManager;
import indi.bookmarkx.common.data.BookmarkArrayListTable;
import indi.bookmarkx.model.BookmarkNodeModel;
import indi.bookmarkx.service.BookmarkAnchorCapturer;
import indi.bookmarkx.service.BookmarkRelocationService;
import org.apache.commons.collections.CollectionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档变化监听。
 * <p>编辑器内的增量编辑由 {@link RangeMarker} 跟踪行号；整篇替换（切分支后重载、
 * 全选粘贴）则转交 {@link BookmarkRelocationService} 按内容锚点重算。</p>
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

            // 整篇内容被替换（切分支后从磁盘重载、全选粘贴等）时 RangeMarker 的位置不再可信，
            // 书签行号会连同文本一起漂移到文件末尾。此时交给重定位服务按内容锚点重算，
            // 也不能沿用「marker 失效就删书签」的逻辑，否则切换分支会把书签弄丢。
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
        BookmarkArrayListTable bookmarkArrayListTable = BookmarkArrayListTable.getInstance(project);
        BookmarksManager bookmarksManager = BookmarksManager.getInstance(project);
        List<BookmarkNodeModel> removeList = new ArrayList<>();
        Document document;
        for (BookmarkNodeModel node : indexList) {
            if (node == null) {
                continue;
            }
            OpenFileDescriptor descriptor = node.getOpenFileDescriptor();
            RangeMarker rangeMarker = descriptor.getRangeMarker();

            if (null == rangeMarker || !rangeMarker.isValid()) {
                // 移除行尾描述信息
                removeList.add(node);
            } else {
                document = rangeMarker.getDocument();
                int line = document.getLineNumber(rangeMarker.getStartOffset());
                node.setLine(line);
                // 锚点要跟随编辑器内的实际内容，否则下次切分支会拿过期文本去匹配。
                // 已失效的书签例外：它的锚点是「最后一次已知正确位置」的凭据，不能被覆盖。
                if (!node.isAnchorLost()) {
                    BookmarkAnchorCapturer.capture(node, eventDocument);
                }
            }
        }
        removeList.forEach(bookmarkArrayListTable::delete);
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
