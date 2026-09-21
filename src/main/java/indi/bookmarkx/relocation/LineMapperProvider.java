package indi.bookmarkx.relocation;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;
import java.util.Map;

/**
 * 行号映射能力的抽象，用于把 VCS 依赖隔离在核心流程之外。
 * <p>唯一的实现是 git 实现，它仅在 Git4Idea 插件存在时才会被注册。
 * 查不到实现时，书签重定位退化为纯内容匹配，功能降级但不失效。</p>
 *
 * @author Nonoas
 */
public interface LineMapperProvider {

    /**
     * 扩展点名称必须是「插件 id + 扩展点名」。
     * 本插件 id 为 {@code indi.nonoas.bookmarkx}，不是包名 {@code indi.bookmarkx}。
     */
    ExtensionPointName<LineMapperProvider> EP_NAME =
            ExtensionPointName.create("indi.nonoas.bookmarkx.lineMapperProvider");

    /**
     * @return 第一个可处理该文件的实现；没有任何实现时返回 null
     */
    static LineMapperProvider findAvailable(Project project, VirtualFile file) {
        if (file == null) {
            return null;
        }
        List<LineMapperProvider> providers = EP_NAME.getExtensionList();
        for (LineMapperProvider provider : providers) {
            try {
                if (provider.isAvailable(project, file)) {
                    return provider;
                }
            } catch (Exception ignored) {
                // 某个实现不可用时不应影响其余实现
            }
        }
        return null;
    }

    /**
     * @return 该文件是否处于本实现可处理的版本库中
     */
    boolean isAvailable(Project project, VirtualFile file);

    /**
     * 取文件所属版本库的当前 revision 标识
     *
     * @return revision 标识；无法获取时返回 null
     */
    String currentRevision(Project project, VirtualFile file);

    /**
     * 文件是否存在未提交的本地改动。
     * <p>为 true 时调用方必须跳过行号映射：映射基于两个提交计算，
     * 而书签行号对应的是工作区内容，存在本地改动时二者不一致。</p>
     */
    boolean hasLocalChanges(Project project, VirtualFile file);

    /**
     * 批量计算 oldRev 到 newRev 的行号映射。
     *
     * @param files  待计算的文件，应属于同一个版本库
     * @param oldRev 旧 revision
     * @param newRev 新 revision
     * @return 文件绝对路径 → 行号映射；无法计算时返回空 Map
     */
    Map<String, GitDiffHunkParser.LineMapping> mapLines(
            Project project, List<VirtualFile> files, String oldRev, String newRev);
}
