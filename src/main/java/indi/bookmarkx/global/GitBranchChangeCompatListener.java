package indi.bookmarkx.global;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import indi.bookmarkx.BookmarksManager;
import indi.bookmarkx.common.data.BookmarkArrayListTable;
import indi.bookmarkx.model.BookmarkNodeModel;
import org.apache.commons.collections.CollectionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Git分支切换监听器
 *
 * @author yejg
 * @date 2026/01/27
 */
public class GitBranchChangeCompatListener {
    private static final Logger LOG = Logger.getInstance(GitBranchChangeCompatListener.class);
    private final Project project;
    private final Map<GitRepository, String> repoBranchCache = new ConcurrentHashMap<>();
    private MessageBusConnection messageBusConnection;
    // 防重入标记：避免无限循环
    private final AtomicBoolean isCorrecting = new AtomicBoolean(false);

    private GitBranchChangeCompatListener(Project project) {
        this.project = project;
    }

    public static void register(@NotNull Project project) {
        GitBranchChangeCompatListener listener = new GitBranchChangeCompatListener(project);
        listener.init();
    }

    private void init() {
        GitRepositoryManager repoManager = GitUtil.getRepositoryManager(project);
        for (GitRepository repo : repoManager.getRepositories()) {
            cacheCurrentBranch(repo);
        }
        subscribeGitRepoChange();
    }

    private void subscribeGitRepoChange() {
        messageBusConnection = project.getMessageBus().connect();
        messageBusConnection.subscribe(GitRepository.GIT_REPO_CHANGE, (GitRepository repository) -> {
            String oldBranchKey = repoBranchCache.get(repository);
            String newBranchKey = getBranchUniqueKey(repository);

            // 分支未变化 直接返回
            if (equalsBranchKey(oldBranchKey, newBranchKey)) {
                return;
            }
            // 正在修正书签 直接返回（防重入）
            if (!isCorrecting.compareAndSet(false, true)) {
                return;
            }

            try {
                LOG.info(String.format("Git分支切换：%s -> %s，准备修正书签行号",
                        oldBranchKey == null ? "NULL" : oldBranchKey,
                        newBranchKey == null ? "NULL" : newBranchKey));
                repoBranchCache.put(repository, newBranchKey);

                // 用Application.invokeLater实现异步执行
                Application application = ApplicationManager.getApplication();
                application.invokeLater(() -> {
                    try {
                        correctBookmarkLines();
                        LOG.info("书签行号修正完成");
                    } catch (Exception e) {
                        LOG.error("书签行号修正失败", e);
                    }
                });
            } finally {
                // 释放防重入标记
                isCorrecting.set(false);
            }
        });
    }

    private void correctBookmarkLines() {
        BookmarkArrayListTable bookmarkTable = BookmarkArrayListTable.getInstance(project);
        List<BookmarkNodeModel> allBookmarks = bookmarkTable.getDataList();

        if (CollectionUtils.isEmpty(allBookmarks)) {
            LOG.info("无书签需要修正");
            return;
        }

        List<BookmarkNodeModel> removeList = new ArrayList<>();
        Document document;
        boolean needSave = false; // 仅在有修改时保存

        for (BookmarkNodeModel node : allBookmarks) {
            if (node == null || !node.isBookmark()) continue;

            OpenFileDescriptor descriptor = node.getOpenFileDescriptor();
            if (descriptor == null) {
                removeList.add(node);
                needSave = true;
                continue;
            }

            RangeMarker rangeMarker = descriptor.getRangeMarker();
            if (null == rangeMarker || !rangeMarker.isValid()) {
                removeList.add(node);
                needSave = true;
            } else {
                document = rangeMarker.getDocument();
                int newLine = document.getLineNumber(rangeMarker.getStartOffset());

                if (node.getLine() != newLine) {
                    LOG.info(String.format("修正书签[%s]行号：%d -> %d", node.getName(), node.getLine(), newLine));
                    node.setLine(newLine);
                    needSave = true;
                }
            }
        }

        // 仅在有修改时执行删除+延迟保存
        if (needSave) {
            removeList.forEach(bookmarkTable::delete);
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    // 确保在EDT线程执行UI相关操作（持久化可能涉及UI刷新）
                    ApplicationManager.getApplication().invokeLater(() -> {
                        BookmarksManager.getInstance(project).persistentSave();
                    });
                }
            }, 1000); // 延迟1s，避开Git回调窗口期
        }
    }

    private String getBranchUniqueKey(@NotNull GitRepository repository) {
        if (repository.getCurrentBranch() == null) {
            return repository.getCurrentRevision() == null ? "NULL" : repository.getCurrentRevision();
        }
        return repository.getCurrentBranch().getName() + "_" + repository.getCurrentRevision();
    }

    private void cacheCurrentBranch(@NotNull GitRepository repository) {
        repoBranchCache.put(repository, getBranchUniqueKey(repository));
    }

    private boolean equalsBranchKey(String oldKey, String newKey) {
        if (oldKey == null && newKey == null) return true;
        if (oldKey == null || newKey == null) return false;
        return oldKey.equals(newKey);
    }

    public void dispose() {
        if (messageBusConnection != null) {
            messageBusConnection.disconnect();
        }
    }
}