package indi.bookmarkx.listener;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import indi.bookmarkx.service.BookmarkRelocationService;
import org.jetbrains.annotations.NotNull;

/**
 * 文档内容从磁盘重载的监听，覆盖 {@link BookmarkBranchChangeListener} 抓不到的场景：
 * 命令行 {@code git checkout} / {@code git pull} / {@code git rebase}、以及在 IDE 外部
 * 改写文件。这些操作绕过了编辑器文档事件，只剩下「磁盘内容变了 → 文档重载」这一个信号。
 *
 * <ul>
 *   <li>{@code fileContentReloaded}：文件已在编辑器中打开，磁盘内容变化后重载，立即重定位。</li>
 *   <li>{@code fileContentLoaded}：文件首次载入文档时触发，用于书签文件此前未打开、
 *       在磁盘上被切换过的情况——用户下次打开该文件时才按内容锚点校正，属于惰性补偿。</li>
 * </ul>
 *
 * <p>{@code FileDocumentManagerListener} 在 2022.3 已是应用级扩展点
 * （{@code com.intellij.fileDocumentManagerListener}，不是消息总线 topic），回调里拿不到
 * {@code Project}，所以遍历所有打开的项目自行分发。过滤「是否有书签」的活交给服务层，
 * 这里只做分发，避免在非 EDT 上下文访问书签表。</p>
 *
 * @author Nonoas
 */
public class BookmarkFileReloadListener implements FileDocumentManagerListener {

    @Override
    public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
        scheduleRelocate(file);
    }

    @Override
    public void fileContentLoaded(@NotNull VirtualFile file, @NotNull Document document) {
        scheduleRelocate(file);
    }

    /**
     * 向所有打开的项目分发文件重载事件。
     * <p>{@code scheduleRelocate} 内部带防抖，一次 checkout 密集重载大量文件时只会触发一轮重算。</p>
     */
    private static void scheduleRelocate(VirtualFile file) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) {
                continue;
            }
            BookmarkRelocationService.getInstance(project).scheduleRelocate(file);
        }
    }
}
