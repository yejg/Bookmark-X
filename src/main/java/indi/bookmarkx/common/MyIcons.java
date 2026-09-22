package indi.bookmarkx.common;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;
import java.util.Objects;

/**
 * @author Nonoas
 * @version 2.2.0
 * @date 2025/1/1
 * @since 2.2.0
 */
public interface MyIcons {
    Icon BOOKMARK = getIcon("icons/bookmark.svg");
    /**
     * 锚点失效书签用的图标：与 gutter 上的正常书签图标区分，
     * 提示用户这个位置不一定准确，需要人工核实或拖拽纠正。
     */
    Icon BOOKMARK_LOST = getIcon("icons/dissmiss.svg");

    static Icon getIcon(String path) {
        return Objects.requireNonNull(IconLoader.findIcon(path, MyIcons.class));
    }
}
