package indi.bookmarkx.ui.tree;


import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.util.ui.UIUtil;
import indi.bookmarkx.common.BaseColors;
import indi.bookmarkx.model.BookmarkNodeModel;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * 标签树节点渲染
 *
 * @author Nonoas
 * @version 1.0
 * @date 2024/5/2
 * @since 1.2.1
 */
public class BmkTreeCellRenderer extends DefaultTreeCellRenderer {

    public BmkTreeCellRenderer() {
       // ReflectionUtil.setField(DefaultTreeCellRenderer.class, this, Boolean.TYPE, "fillBackground", false);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree,
                                                  Object value,
                                                  boolean selected,
                                                  boolean expanded,
                                                  boolean isLeaf,
                                                  int row,
                                                  boolean hasFocus) {

        setColor(selected, hasFocus);

        super.getTreeCellRendererComponent(tree, value, selected, expanded, isLeaf, row, hasFocus);

        BookmarkTreeNode node = (BookmarkTreeNode) value;
        Icon icon = null;
        boolean anchorLost = false;
        if (0 == row) {
            // if is root node
            icon = AllIcons.Nodes.Module;
        } else if (row > 0) {
            icon = node.isBookmark()
                    ? IconLoader.findIcon("icons/bookmark.svg", BmkTreeCellRenderer.class)
                    : AllIcons.Nodes.Folder;
            if (node.isBookmark()) {
                BookmarkNodeModel model =  (BookmarkNodeModel) node.getUserObject();
                anchorLost = model.isAnchorLost();
                if (anchorLost || null == model.getOpenFileDescriptor()) {
                    icon = IconLoader.findIcon("icons/dissmiss.svg", BmkTreeCellRenderer.class);
                }
            }
        }
        setIcon(icon);

        if (anchorLost) {
            // 锚点失效的书签不从树上摘掉，只灰显：切回原分支时它能自动恢复。
            // 选中行保持选中配色，否则灰字压在选中背景上反而更难看清。
            if (!selected) {
                setForeground(UIUtil.getInactiveTextColor());
            }
        }
        return this;
    }

    /**
     * This method fix the difference of background color between the icon and text in the hover state
     */
    private void setColor(boolean selected, boolean hasFocus) {
        this.setBorderSelectionColor(null);
        this.setBackgroundSelectionColor(null);
        this.setBackgroundNonSelectionColor(null);
        this.setBackground(UIUtil.getTreeBackground(selected, hasFocus));
        this.setForeground(UIUtil.getTreeForeground(selected, hasFocus));
    }

    @Override
    public Color getBorderSelectionColor() {
        return BaseColors.TRANSPARENT;
    }
}
