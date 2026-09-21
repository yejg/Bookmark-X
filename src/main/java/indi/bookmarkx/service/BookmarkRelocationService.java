package indi.bookmarkx.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.util.Alarm;
import indi.bookmarkx.BookmarksManager;
import indi.bookmarkx.common.I18N;
import indi.bookmarkx.common.data.BookmarkArrayListTable;
import indi.bookmarkx.listener.BookmarkListener;
import indi.bookmarkx.model.BookmarkNodeModel;
import indi.bookmarkx.relocation.AnchorMatcher;
import indi.bookmarkx.relocation.GitDiffHunkParser;
import indi.bookmarkx.relocation.LineMapperProvider;
import indi.bookmarkx.relocation.MatchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 书签重定位编排服务。
 * <p>在文件被外部改写（切换分支、pull、rebase、外部编辑器保存）后，按
 * 「git 提议 → 锚点验收 → 内容兜底」的顺序为书签重新计算行号。</p>
 * <p>git 永远只是一个高质量的搜索起点：它给出的候选必须通过与书签行文本的
 * 比对才会被采纳，因此即使脏工作区检测漏判也不会造成系统性偏移。</p>
 *
 * @author Nonoas
 */
@Service(Service.Level.PROJECT)
public final class BookmarkRelocationService {

    private static final Logger LOG = Logger.getInstance(BookmarkRelocationService.class);

    /**
     * 文件重载事件的合并窗口，单位毫秒。
     * 一次 checkout 会密集重载大量文件，必须合并处理。
     */
    private static final int DEBOUNCE_MS = 300;

    private final Project project;

    /**
     * 分支切换前记录的 revision，key 为文件绝对路径
     */
    private final Map<String, String> revisionBeforeSwitch = new ConcurrentHashMap<>();

    /**
     * 等待处理的文件
     */
    private final Set<VirtualFile> pendingFiles = ConcurrentHashMap.newKeySet();

    private final Alarm alarm;

    public BookmarkRelocationService(Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, project);
    }

    public static BookmarkRelocationService getInstance(Project project) {
        return project.getService(BookmarkRelocationService.class);
    }

    /**
     * 分支切换前调用，记录当前 revision 以便切换后计算 diff
     */
    public void captureRevisions() {
        revisionBeforeSwitch.clear();
        for (VirtualFile file : bookmarkedFiles()) {
            LineMapperProvider provider = LineMapperProvider.findAvailable(project, file);
            if (provider == null) {
                continue;
            }
            // 有本地改动时 diff 基准不可靠，索性不记录，切换后直接走内容匹配
            if (provider.hasLocalChanges(project, file)) {
                continue;
            }
            String revision = provider.currentRevision(project, file);
            if (revision != null) {
                revisionBeforeSwitch.put(file.getPath(), revision);
            }
        }
        LOG.info("分支切换前记录了 " + revisionBeforeSwitch.size() + " 个文件的 revision");
    }

    /**
     * 分支切换后调用，对全部含书签的文件执行重定位
     */
    public void relocateAfterBranchChange() {
        List<VirtualFile> files = bookmarkedFiles();
        Map<String, String> snapshot = new HashMap<>(revisionBeforeSwitch);
        revisionBeforeSwitch.clear();
        // 这里本来就要全量重算，切分支过程中积压的文件重载事件无需再触发一轮
        alarm.cancelAllRequests();
        pendingFiles.clear();
        startRelocate(files, snapshot);
    }

    /**
     * 文件重载后调用。带防抖，因为一次 checkout 可能重载大量文件。
     */
    public void scheduleRelocate(VirtualFile file) {
        if (file == null || project.isDisposed()) {
            return;
        }
        pendingFiles.add(file);
        alarm.cancelAllRequests();
        alarm.addRequest(this::flushPending, DEBOUNCE_MS);
    }

    /**
     * 项目启动时校验全部书签，并为升级前的旧数据回填锚点。
     * <p>延后到 EDT 待处理队列之后执行，确保书签表已完成初始化。</p>
     */
    public void validateAll() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            startRelocate(bookmarkedFiles(), Collections.emptyMap());
        });
    }

    private void flushPending() {
        List<VirtualFile> files = new ArrayList<>(pendingFiles);
        pendingFiles.clear();
        if (files.isEmpty()) {
            return;
        }
        // 本方法跑在 Alarm 的线程池线程上，而要读的书签表只在 EDT 上安全，故先回到 EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            // 此路径拿不到切换前的 revision，直接走内容匹配
            startRelocate(files, Collections.emptyMap());
        });
    }

    /**
     * 在 EDT 上取好书签快照，再把纯计算交给后台线程。
     * <p>{@code BookmarkArrayListTable} 内部是普通的 {@code ArrayList}，由 EDT 侧的
     * {@code insert}/{@code delete} 维护。后台线程直接遍历它，会与这些写操作并发：
     * 轻则读到脏数据，重则抛 {@code ConcurrentModificationException}——而异常会被
     * {@link #runInBackground} 吞掉并只记一条日志，表现出来就是
     * 「书签重定位静默不生效」，极难排查。所以凡是要访问书签表的动作都在这里收口。</p>
     *
     * @param files        含书签的文件
     * @param oldRevisions 文件绝对路径 → 切换前 revision；为空表示无 git 提议
     */
    private void startRelocate(List<VirtualFile> files, Map<String, String> oldRevisions) {
        Set<String> wanted = new HashSet<>();
        for (VirtualFile file : files) {
            wanted.add(file.getPath());
        }

        // 一次遍历全表就把命中文件的书签归好组。若改成每个文件各扫一遍全表，
        // 一次大 checkout 会变成「文件数 × 书签数」的乘积级开销，而现在这段跑在 EDT 上。
        Map<String, List<BookmarkNodeModel>> byPath = new HashMap<>();
        for (BookmarkNodeModel model : BookmarkArrayListTable.getInstance(project).listAll()) {
            String path = model.getFilePath().orElse(null);
            if (path != null && wanted.contains(path)) {
                byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(model);
            }
        }
        if (byPath.isEmpty()) {
            return;
        }

        Map<VirtualFile, List<BookmarkNodeModel>> snapshot = new LinkedHashMap<>();
        for (VirtualFile file : files) {
            List<BookmarkNodeModel> models = byPath.get(file.getPath());
            if (models != null) {
                snapshot.put(file, models);
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }
        runInBackground(() -> doRelocate(snapshot, oldRevisions));
    }

    /**
     * 重定位主流程。在后台线程执行，只做纯计算，不碰 UI 与 markup model。
     *
     * @param bookmarksByFile 文件 → 该文件的全部书签，已在 EDT 上取好快照
     * @param oldRevisions    文件绝对路径 → 切换前 revision；为空表示无 git 提议
     */
    private void doRelocate(Map<VirtualFile, List<BookmarkNodeModel>> bookmarksByFile,
                            Map<String, String> oldRevisions) {
        if (project.isDisposed() || bookmarksByFile.isEmpty()) {
            return;
        }

        Map<String, GitDiffHunkParser.LineMapping> mappings =
                buildMappings(new ArrayList<>(bookmarksByFile.keySet()), oldRevisions);
        List<PendingUpdate> updates = new ArrayList<>();

        for (Map.Entry<VirtualFile, List<BookmarkNodeModel>> entry : bookmarksByFile.entrySet()) {
            if (project.isDisposed()) {
                return;
            }
            VirtualFile file = entry.getKey();
            List<String> lines = ReadAction.compute(() -> BookmarkAnchorCapturer.readLines(file));

            for (BookmarkNodeModel model : entry.getValue()) {
                if (lines == null) {
                    // 文件不可读或已不存在：保留书签并标记失效，切回原分支后自动恢复
                    if (!model.isAnchorLost()) {
                        updates.add(PendingUpdate.lost(model));
                    }
                    continue;
                }

                if (model.getAnchor() == null) {
                    // 旧版本数据没有锚点，没有依据可供定位，回填而不是乱猜
                    updates.add(PendingUpdate.backfill(model, lines));
                    continue;
                }

                int gitCandidate = gitCandidateOf(mappings, file, model.getLine());
                MatchResult result = AnchorMatcher.match(model.getAnchor(), model.getLine(), gitCandidate, lines);

                if (result.isFound()) {
                    if (result.getConfidence() == MatchResult.Confidence.CONTENT_AMBIGUOUS) {
                        LOG.info("书签「" + model.getName() + "」存在多个同分候选，取距起点最近的 "
                                + result.getLine() + " 行");
                    }
                    // 相似度兜底可能落在原行号上而文本已变（精确匹配若在原行号命中，
                    // 快速路径早就返回 EXACT_AT_ORIGIN 了）。这种命中行号虽然没动，
                    // 锚点也必须按新内容刷新，否则锚点文本一轮比一轮旧。
                    if (model.getLine() != result.getLine() || model.isAnchorLost()
                            || result.getConfidence().isFuzzyTextMatch()) {
                        updates.add(PendingUpdate.relocated(model, result.getLine(), lines));
                    }
                } else if (!model.isAnchorLost()) {
                    updates.add(PendingUpdate.lost(model));
                }
            }
        }

        if (!updates.isEmpty()) {
            ApplicationManager.getApplication().invokeLater(() -> applyUpdates(updates));
        }
    }

    /**
     * 回到 EDT 应用结果：更新模型与行标记、通知书签树、持久化。
     */
    private void applyUpdates(List<PendingUpdate> updates) {
        if (project.isDisposed()) {
            return;
        }
        BookmarksManager manager = BookmarksManager.getInstance(project);
        BookmarkListener publisher = project.getMessageBus().syncPublisher(BookmarkListener.TOPIC);

        int relocated = 0;
        int backfilled = 0;
        int lost = 0;

        for (PendingUpdate update : updates) {
            BookmarkNodeModel model = update.model;
            if (update.kind == PendingUpdate.Kind.LOST) {
                model.setAnchorLost(true);
                releaseMarker(model);
                lost++;
            } else if (update.kind == PendingUpdate.Kind.BACKFILL) {
                // 位置不动，仅按当前内容补建锚点
                BookmarkAnchorCapturer.capture(model, update.lines);
                backfilled++;
            } else {
                int oldLine = model.getLine();
                model.setAnchorLost(false);
                model.updateBookmarkLine(update.line, false);
                if (model.getLine() == oldLine) {
                    // 行号未变时 updateBookmarkLine 会提前返回，但失效期间图标曾被打掉，需要补建
                    model.createLineMarker();
                }
                // 命中可能来自相似度兜底，代码已有细微变动，按新内容刷新锚点避免误差累积
                BookmarkAnchorCapturer.capture(model, update.lines);
                relocated++;
            }
            publisher.bookmarkChanged(model);
        }

        manager.persistentSave();
        manager.getToolWindowRootPanel().tree().repaint();
        LOG.info("书签重定位完成，已更新 " + relocated + " 个，回填锚点 " + backfilled
                + " 个，失效 " + lost + " 个");

        if (lost > 0) {
            // 只在真的丢过书签时提示，且每个书签只在首次失效时计一次，不会反复打扰。
            // 文案里要说明恢复方式，否则用户会以为书签被删了。
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("Bookmark-X")
                    .createNotification(I18N.get("bookmark.relocationNotificationTitle"),
                            I18N.get("bookmark.relocationNotificationContent", relocated, lost),
                            NotificationType.WARNING)
                    .notify(project);
        }
    }

    private static void releaseMarker(BookmarkNodeModel model) {
        try {
            if (model.getOpenFileDescriptor() != null) {
                model.release();
            }
        } catch (Exception e) {
            LOG.info("释放书签行标记失败: " + model.getName(), e);
        }
    }

    /**
     * 对有旧 revision 的文件批量取 git 行号映射
     */
    private Map<String, GitDiffHunkParser.LineMapping> buildMappings(
            List<VirtualFile> files, Map<String, String> oldRevisions) {

        if (oldRevisions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, GitDiffHunkParser.LineMapping> result = new HashMap<>();
        // 按旧 revision 分组，同组文件属于同一仓库，可合并为一次进程调用
        Map<String, List<VirtualFile>> byRevision = new HashMap<>();
        for (VirtualFile file : files) {
            String oldRev = oldRevisions.get(file.getPath());
            if (oldRev != null && file.isValid()) {
                byRevision.computeIfAbsent(oldRev, k -> new ArrayList<>()).add(file);
            }
        }

        for (Map.Entry<String, List<VirtualFile>> entry : byRevision.entrySet()) {
            List<VirtualFile> group = entry.getValue();
            LineMapperProvider provider = LineMapperProvider.findAvailable(project, group.get(0));
            if (provider == null) {
                continue;
            }
            String newRev = provider.currentRevision(project, group.get(0));
            if (newRev == null) {
                continue;
            }
            try {
                result.putAll(provider.mapLines(project, group, entry.getKey(), newRev));
            } catch (Exception e) {
                LOG.info("计算 git 行号映射失败，降级为内容匹配", e);
            }
        }
        return result;
    }

    /**
     * @return git 提议的行号（0-based）；无提议时返回 -1
     */
    private static int gitCandidateOf(Map<String, GitDiffHunkParser.LineMapping> mappings,
                                      VirtualFile file, int oldLine) {
        GitDiffHunkParser.LineMapping mapping = mappings.get(file.getPath());
        if (mapping == null) {
            return -1;
        }
        OptionalInt mapped = mapping.map(oldLine);
        return mapped.isPresent() ? mapped.getAsInt() : -1;
    }

    private void runInBackground(Runnable task) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.info("书签重定位任务异常", e);
            }
        });
    }

    /**
     * @return 当前含书签的文件，已去重
     */
    private List<VirtualFile> bookmarkedFiles() {
        List<VirtualFile> files = new ArrayList<>();
        Set<VirtualFile> seen = new HashSet<>();
        for (BookmarkNodeModel model : BookmarkArrayListTable.getInstance(project).listAll()) {
            OpenFileDescriptor descriptor = model.getOpenFileDescriptor();
            if (descriptor == null) {
                continue;
            }
            VirtualFile file = descriptor.getFile();
            if (file.isValid() && seen.add(file)) {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * 待应用的变更，由后台线程产出、在 EDT 消费
     */
    private static final class PendingUpdate {

        /**
         * 变更种类
         */
        enum Kind {
            /**
             * 已重新定位到新行号
             */
            RELOCATED,
            /**
             * 未能定位
             */
            LOST,
            /**
             * 位置不变，仅回填锚点
             */
            BACKFILL
        }

        final Kind kind;

        final BookmarkNodeModel model;

        /**
         * 新行号，0-based；仅 RELOCATED 有意义
         */
        final int line;

        /**
         * 该文件当时的全部文本行，用于重建锚点
         */
        final List<String> lines;

        private PendingUpdate(Kind kind, BookmarkNodeModel model, int line, List<String> lines) {
            this.kind = kind;
            this.model = model;
            this.line = line;
            this.lines = lines;
        }

        static PendingUpdate relocated(BookmarkNodeModel model, int line, List<String> lines) {
            return new PendingUpdate(Kind.RELOCATED, model, line, lines);
        }

        static PendingUpdate lost(BookmarkNodeModel model) {
            return new PendingUpdate(Kind.LOST, model, -1, null);
        }

        static PendingUpdate backfill(BookmarkNodeModel model, List<String> lines) {
            return new PendingUpdate(Kind.BACKFILL, model, model.getLine(), lines);
        }
    }
}
