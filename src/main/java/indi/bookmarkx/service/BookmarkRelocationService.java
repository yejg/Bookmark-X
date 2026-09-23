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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 分支切换世代号。每次 {@link #relocateAfterBranchChange()} 触发都会自增。
     * <p>{@code BookmarkBranchChangeListener}（有 git 提议，走 diff + 锚点验收）与
     * {@code BookmarkFileReloadListener}（无 git 提议，纯内容匹配兜底）会在同一次
     * IDE 内切分支时被同时触发——checkout 改写的文件既产生「分支已切换」事件，也产生
     * 「文件内容从磁盘重载」事件。两条链路各自异步排队（{@code invokeLater}/
     * {@link Alarm} 定时器），互相之间没有先后保证：曾经发生过内容匹配那条低精度链路
     * 在 git 提议链路算完「之后」才把结果应用到 EDT，用不准确的匹配结果覆盖了刚刚才
     * 算对的行号，表现为切完分支后書签又被重新（错误地）挪动一次。</p>
     * <p>{@link #flushPending()} 发起计算时会记下当时的世代号；结果算完准备应用到
     * EDT 时重新核对世代号，如果分支切换在此期间已经把世代号推得更新，说明这份纯
     * 内容匹配的结果已经过时（存在更权威的 git 提议结果可能已经或即将覆盖它），
     * 直接丢弃，不应用、也不覆盖。</p>
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * {@code branchWillChange} 触发的时间戳（毫秒），0 表示当前不处于分支切换窗口期。
     * <p>用于让 {@link #scheduleRelocate(VirtualFile)} 在分支切换进行中时直接忽略
     * 文件重载事件——checkout 改写文件必然触发 {@code fileContentReloaded}，与其等
     * 计算跑完再靠 {@link #generation} 甄别丢弃，不如提前不排这次计算，省去无谓的
     * 后台线程开销。设超时兜底（见 {@link #IN_PROGRESS_TIMEOUT_MS}）而不是一直等
     * {@code branchHasChanged} 来清除，避免万一后续通知异常丢失导致这个窗口永久
     * 卡住，此后所有命令行 checkout 场景都被误伤。</p>
     */
    private volatile long branchChangeStartedAt;

    /**
     * 分支切换窗口期的兜底超时。超过这个时长还没等到 {@code branchHasChanged}，
     * 就不再信任这个窗口标记，避免异常场景下永久拦截文件重载事件。
     */
    private static final long IN_PROGRESS_TIMEOUT_MS = 30_000;

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
        branchChangeStartedAt = System.currentTimeMillis();
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
        branchChangeStartedAt = 0;
        List<VirtualFile> files = bookmarkedFiles();
        Map<String, String> snapshot = new HashMap<>(revisionBeforeSwitch);
        revisionBeforeSwitch.clear();
        // 推进世代号：即使下面清空了防抖队列，仍可能有一份 flushPending 已经在
        // 后台线程算到一半（早于本方法调用），它算完准备应用时会靠世代号识别出
        // 「已经过时」而自行放弃，见 generation 字段注释。
        long myGeneration = generation.incrementAndGet();
        // 这里本来就要全量重算，切分支过程中积压的文件重载事件无需再触发一轮
        alarm.cancelAllRequests();
        pendingFiles.clear();
        startRelocate(files, snapshot, myGeneration);
    }

    /**
     * 文件重载后调用。带防抖，因为一次 checkout 可能重载大量文件。
     */
    public void scheduleRelocate(VirtualFile file) {
        if (file == null || project.isDisposed()) {
            return;
        }
        long startedAt = branchChangeStartedAt;
        if (startedAt != 0 && System.currentTimeMillis() - startedAt < IN_PROGRESS_TIMEOUT_MS) {
            // 正处于 branchWillChange 到 branchHasChanged 之间的窗口：这次文件重载
            // 大概率是 checkout 改写磁盘内容引发的，稍后 branchHasChanged 会带着
            // 更精确的 git 提议重算全部文件，这里排一次纯内容匹配纯属浪费，直接跳过。
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
            startRelocate(bookmarkedFiles(), Collections.emptyMap(), generation.get());
        });
    }

    private void flushPending() {
        List<VirtualFile> files = new ArrayList<>(pendingFiles);
        pendingFiles.clear();
        if (files.isEmpty()) {
            return;
        }
        // 记录发起时的世代号：如果在本次计算跑完之前又发生了一次分支切换
        // （generation 被推进），说明这份纯内容匹配的结果已经过时，不应用它。
        long myGeneration = generation.get();
        // 本方法跑在 Alarm 的线程池线程上，而要读的书签表只在 EDT 上安全，故先回到 EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            // 此路径拿不到切换前的 revision，直接走内容匹配
            startRelocate(files, Collections.emptyMap(), myGeneration);
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
     * @param files              含书签的文件
     * @param oldRevisions       文件绝对路径 → 切换前 revision；为空表示无 git 提议
     * @param expectedGeneration 发起本轮计算时的世代号，应用结果前会重新核对
     */
    private void startRelocate(List<VirtualFile> files, Map<String, String> oldRevisions,
                               long expectedGeneration) {
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
                // getLine() 会按 Gutter 图标当前实际位置校正 line 字段，且只能在 EDT
                // 上调用（内部访问 MarkupModelEx）；这里正是安全窗口。校正一次后，
                // 后台线程只需读字段（见 doRelocate 里的 getPersistedLine()），
                // 不必再碰任何编辑器 UI 状态。
                model.getLine();
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
        runInBackground(() -> doRelocate(snapshot, oldRevisions, expectedGeneration));
    }

    /**
     * 重定位主流程。在后台线程执行，只做纯计算，不碰 UI 与 markup model。
     *
     * @param bookmarksByFile    文件 → 该文件的全部书签，已在 EDT 上取好快照
     * @param oldRevisions       文件绝对路径 → 切换前 revision；为空表示无 git 提议
     * @param expectedGeneration 发起本轮计算时的世代号，最终应用前会重新核对
     */
    private void doRelocate(Map<VirtualFile, List<BookmarkNodeModel>> bookmarksByFile,
                            Map<String, String> oldRevisions, long expectedGeneration) {
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
            GitDiffHunkParser.LineMapping mapping = mappings.get(file.getPath());
            // git diff 明确判定该文件在两个 revision 间毫无变化：原行号必然仍然正确，
            // 连内容匹配兜底都不需要跑。这一步至关重要——此前对「没有 git 提议」的文件
            // 一律退化成纯内容匹配，导致完全没变化的文件里的书签，也会被内容匹配算法
            // 按「周围几行 + 相似度打分」误判挪到文件中另一处相似的代码块上。一次涉及
            // 少数几个文件的分支同步，往往会因此错误地「重新定位」大量原本根本不需要
            // 改动的书签，这正是用户反馈「切完分支书签还是对不上」的根因。
            if (mapping != null && mapping.isUnchanged()) {
                if (entry.getValue().stream().anyMatch(BookmarkNodeModel::isAnchorLost)) {
                    // 文件没变，但书签之前处于失效状态（比如上一轮误判导致的）：
                    // 用当前内容回填锚点、恢复正常展示，行号本身不动
                    List<String> lines = ReadAction.compute(() -> BookmarkAnchorCapturer.readLines(file));
                    if (lines != null) {
                        for (BookmarkNodeModel model : entry.getValue()) {
                            if (model.isAnchorLost()) {
                                updates.add(PendingUpdate.relocated(model, model.getPersistedLine(), lines));
                            }
                        }
                    }
                }
                continue;
            }

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

                int gitCandidate = gitCandidateOf(mappings, file, model.getPersistedLine());
                MatchResult result = AnchorMatcher.match(model.getAnchor(), model.getPersistedLine(), gitCandidate, lines);

                if (result.isFound()) {
                    if (result.getConfidence() == MatchResult.Confidence.CONTENT_AMBIGUOUS) {
                        LOG.info("书签「" + model.getName() + "」存在多个同分候选，取距起点最近的 "
                                + result.getLine() + " 行");
                    }
                    // 相似度兜底可能落在原行号上而文本已变（精确匹配若在原行号命中，
                    // 快速路径早就返回 EXACT_AT_ORIGIN 了）。这种命中行号虽然没动，
                    // 锚点也必须按新内容刷新，否则锚点文本一轮比一轮旧。
                    if (model.getPersistedLine() != result.getLine() || model.isAnchorLost()
                            || result.getConfidence().isFuzzyTextMatch()) {
                        updates.add(PendingUpdate.relocated(model, result.getLine(), lines));
                    }
                } else if (!model.isAnchorLost()) {
                    updates.add(PendingUpdate.lost(model));
                }
            }
        }

        if (!updates.isEmpty()) {
            ApplicationManager.getApplication().invokeLater(() -> applyUpdates(updates, oldRevisions, expectedGeneration));
        }
    }

    /**
     * 回到 EDT 应用结果：更新模型与行标记、通知书签树、持久化。
     *
     * @param oldRevisions       本轮计算是否带有 git 提议；为空表示来自纯内容匹配
     *                           兜底路径（{@link #flushPending()}/{@link #validateAll()}）
     * @param expectedGeneration 发起本轮计算时的世代号
     */
    private void applyUpdates(List<PendingUpdate> updates, Map<String, String> oldRevisions,
                              long expectedGeneration) {
        if (project.isDisposed()) {
            return;
        }
        // 只拦截「无 git 提议」的低精度结果：它可能是 BookmarkFileReloadListener 那条
        // 兜底链路在本次分支切换期间被同时触发的产物。如果计算跑到这里的这段时间里，
        // 世代号已经被 relocateAfterBranchChange 推进过，说明存在一次更权威的、带
        // git 提议的重算或者正在进行或者已经算完，这份纯内容匹配结果已经过时，
        // 必须丢弃——否则会出现「git 提议已经把行号修对，随后又被内容匹配错误覆盖」。
        // 带 git 提议的结果（oldRevisions 非空）本身就代表当前最新世代，不需要拦截。
        if (oldRevisions.isEmpty() && expectedGeneration != generation.get()) {
            LOG.info("发现更新的分支切换世代，丢弃过时的内容匹配结果（" + updates.size() + " 条）");
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
                // 图标本身一直保留在原行号上（不再像早期版本那样被摘掉），这里只需要
                // 把已有图标换成失效样式；没有图标（比如从未画过）时新建一个
                if (model.findMyHighlighter() != null) {
                    model.refreshLineMarker();
                } else {
                    model.createLineMarker();
                }
                lost++;
            } else if (update.kind == PendingUpdate.Kind.BACKFILL) {
                // 位置不动，仅按当前内容补建锚点
                BookmarkAnchorCapturer.capture(model, update.lines);
                backfilled++;
            } else {
                boolean wasLost = model.isAnchorLost();
                int oldLine = model.getLine();
                model.setAnchorLost(false);
                model.updateBookmarkLine(update.line, false);
                if (model.getLine() == oldLine) {
                    // 行号未变时 updateBookmarkLine 会提前返回，不会碰图标；
                    // 若刚才还是失效状态，图标需要从失效样式切回正常样式
                    if (wasLost) {
                        model.refreshLineMarker();
                    }
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
            // 该工厂方法只在后台线程的 doRelocate 里被调用，不能碰会查询编辑器 UI
            // 状态的 getLine()，这里的行号只是留痕用（BACKFILL 场景位置本不该变）。
            return new PendingUpdate(Kind.BACKFILL, model, model.getPersistedLine(), lines);
        }
    }
}
