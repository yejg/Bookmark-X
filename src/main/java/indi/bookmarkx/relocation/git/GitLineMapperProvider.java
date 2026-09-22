package indi.bookmarkx.relocation.git;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import indi.bookmarkx.relocation.GitDiffHunkParser;
import indi.bookmarkx.relocation.LineMapperProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 git 的行号映射实现。
 * <p>本类是插件中唯一 import {@code git4idea} 的类，仅在 Git4Idea 插件存在时
 * 经 {@code bookmarkx-git.xml} 注册，因此未安装 Git 插件的 IDE 不会遇到
 * {@code NoClassDefFoundError}。</p>
 *
 * @author Nonoas
 */
public class GitLineMapperProvider implements LineMapperProvider {

    private static final Logger LOG = Logger.getInstance(GitLineMapperProvider.class);

    @Override
    public boolean isAvailable(Project project, VirtualFile file) {
        return findRepository(project, file) != null;
    }

    @Override
    public String currentRevision(Project project, VirtualFile file) {
        GitRepository repository = findRepository(project, file);
        return repository == null ? null : repository.getCurrentRevision();
    }

    @Override
    public boolean hasLocalChanges(Project project, VirtualFile file) {
        try {
            return ChangeListManager.getInstance(project).getChange(file) != null;
        } catch (Exception e) {
            LOG.info("判断本地改动失败，保守视为有改动: " + file.getPath(), e);
            // 判断失败时保守返回 true：跳过 git 提议走内容匹配，宁慢勿错
            return true;
        }
    }

    @Override
    public Map<String, GitDiffHunkParser.LineMapping> mapLines(
            Project project, List<VirtualFile> files, String oldRev, String newRev) {

        if (files == null || files.isEmpty() || oldRev == null || newRev == null || oldRev.equals(newRev)) {
            return Collections.emptyMap();
        }

        GitRepository repository = findRepository(project, files.get(0));
        if (repository == null) {
            return Collections.emptyMap();
        }
        VirtualFile root = repository.getRoot();

        // 收集仓库相对路径，同时记录「相对路径 → 绝对路径」以便回填结果
        List<String> relativePaths = new ArrayList<>(files.size());
        Map<String, String> relativeToAbsolute = new HashMap<>();
        for (VirtualFile file : files) {
            String relative = toRelativePath(root, file);
            if (relative == null) {
                continue;
            }
            relativePaths.add(relative);
            relativeToAbsolute.put(relative, file.getPath());
        }
        if (relativePaths.isEmpty()) {
            return Collections.emptyMap();
        }

        String output = runDiff(project, root, oldRev, newRev, relativePaths);
        if (output == null) {
            return Collections.emptyMap();
        }

        Map<String, GitDiffHunkParser.LineMapping> byRelative = GitDiffHunkParser.parseMultiFile(output);
        Map<String, GitDiffHunkParser.LineMapping> byAbsolute = new HashMap<>();
        for (String relative : relativePaths) {
            String absolute = relativeToAbsolute.get(relative);
            if (absolute == null) {
                continue;
            }
            // git diff 只为「确实有差异」的文件输出 +++/hunk：本次请求范围内的文件，
            // 如果没有出现在 diff 结果里，说明 git 明确判定它在两个 revision 间没有
            // 任何变化——必须显式记为 unchanged，而不是让调用方看到「查不到」，
            // 否则会被误判为「无法确定」而退化成内容匹配，导致完全没变化的文件里的
            // 书签被内容匹配误判挪到别的相似行上（这正是过度重定位的根因）。
            GitDiffHunkParser.LineMapping mapping = byRelative.getOrDefault(
                    relative, GitDiffHunkParser.LineMapping.unchanged());
            byAbsolute.put(absolute, mapping);
        }
        return byAbsolute;
    }

    /**
     * 执行一次 git diff，同时覆盖全部目标文件
     *
     * @return diff 输出；执行失败时返回 null
     */
    private String runDiff(Project project, VirtualFile root, String oldRev, String newRev, List<String> paths) {
        try {
            GitLineHandler handler = new GitLineHandler(project, root, GitCommand.DIFF);
            handler.addParameters("--unified=0", "--no-color", "--no-ext-diff", oldRev, newRev);
            handler.endOptions();
            handler.addParameters(paths);

            GitCommandResult result = Git.getInstance().runCommand(handler);
            if (!result.success()) {
                LOG.info("git diff 执行失败，降级为内容匹配: " + result.getErrorOutputAsJoinedString());
                return null;
            }
            return String.join("\n", result.getOutput());
        } catch (Exception e) {
            LOG.info("git diff 异常，降级为内容匹配", e);
            return null;
        }
    }

    private static String toRelativePath(VirtualFile root, VirtualFile file) {
        String rootPath = root.getPath();
        String filePath = file.getPath();
        if (!filePath.startsWith(rootPath + "/")) {
            return null;
        }
        return filePath.substring(rootPath.length() + 1);
    }

    private static GitRepository findRepository(Project project, VirtualFile file) {
        if (project == null || project.isDisposed() || file == null) {
            return null;
        }
        try {
            return GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file);
        } catch (Exception e) {
            LOG.info("查找 git 仓库失败: " + file.getPath(), e);
            return null;
        }
    }
}
