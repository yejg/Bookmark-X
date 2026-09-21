package indi.bookmarkx.listener;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.BranchChangeListener;
import indi.bookmarkx.service.BookmarkRelocationService;
import org.jetbrains.annotations.NotNull;

/**
 * 分支切换监听，把「IDE 内切分支」这一事件的前后两次通知接到重定位服务上。
 * <p>{@link BranchChangeListener#VCS_BRANCH_CHANGED} 由 {@code git4idea} 的
 * {@code GitBranchOperation} 通过 {@code Project.getMessageBus().syncPublisher(...)} 发布，
 * 因此必须注册在 {@code <projectListeners>} 下；注册到应用级总线会静默失效。</p>
 * <p>两次通知分工不同：{@code branchWillChange} 是唯一能拿到「切换前 revision」的时机，
 * 用来记录 diff 基准；{@code branchHasChanged} 时磁盘内容已经变了，据此重算行号。</p>
 * <p>注意命令行 {@code git checkout} 不会走到这里，那条路径由
 * {@link BookmarkFileReloadListener} 与启动校验兜底，代价是拿不到旧 revision。</p>
 *
 * @author Nonoas
 */
public final class BookmarkBranchChangeListener implements BranchChangeListener {

    private final Project project;

    public BookmarkBranchChangeListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void branchWillChange(@NotNull String branchName) {
        if (project.isDisposed()) {
            return;
        }
        // 记录的是内存中缓存的 revision，不起 git 子进程，可以在 EDT 上安全执行；
        // 而且必须尽早执行，晚于 git 改写工作区就会把新 revision 当成旧基准。
        BookmarkRelocationService.getInstance(project).captureRevisions();
    }

    @Override
    public void branchHasChanged(@NotNull String branchName) {
        if (project.isDisposed()) {
            return;
        }
        BookmarkRelocationService.getInstance(project).relocateAfterBranchChange();
    }
}
