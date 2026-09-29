package indi.bookmarkx.ui;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.GutterDraggableObject;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import indi.bookmarkx.action.BookmarkEditAction;
import indi.bookmarkx.action.BookmarkRemoveAction;
import indi.bookmarkx.common.I18N;
import indi.bookmarkx.common.MyIcons;
import indi.bookmarkx.model.BookmarkNodeModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Cursor;
import java.util.Objects;

public class MyGutterIconRenderer extends GutterIconRenderer {

    private final BookmarkNodeModel model;

    private RangeHighlighter lastHighlighter;

    public MyGutterIconRenderer(BookmarkNodeModel model) {
        this.model = model;
    }

    @Override
    public @NotNull ActionGroup getPopupMenuActions() {
        DefaultActionGroup actionGroup = new DefaultActionGroup();
        actionGroup.add(new BookmarkEditAction(model));
        actionGroup.add(new BookmarkRemoveAction(model));
        return actionGroup;
    }

    @Override
    public @Nullable AnAction getClickAction() {
        return new AnAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                // do nothing，覆盖默认弹菜单的逻辑
            }
        };
    }

    @Override
    public @Nullable String getTooltipText() {
        if (model.isAnchorLost()) {
            // gutter 图标现在即便定位失败也照常展示，悬停时要说明这一点，
            // 否则用户会把这行当成确定无疑的书签位置。
            String desc = model.getDesc();
            String lostTip = I18N.get("bookmark.anchorLostTip");
            return desc == null || desc.isBlank() ? lostTip : desc + "\n" + lostTip;
        }
        return model.getDesc();
    }

    @Override
    @NotNull
    public Icon getIcon() {
        return model.isAnchorLost() ? MyIcons.BOOKMARK_LOST : MyIcons.BOOKMARK;
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.RIGHT; // 图标对齐方式
    }

    /**
     * 按持有的 {@link #model} 及其可见状态判等，而不是「只要类型相同就相等」。
     * <p>平台的 {@code RangeHighlighterImpl#setGutterIconRenderer} 内部用
     * {@code Comparing.equal(old, renderer)} 判断新旧 renderer 是否等价，只有
     * 不等价时才会触发 {@code fireChanged}（进而让编辑器重绘该 highlighter）。
     * 此前所有实例互相 equals 恒为 true，导致 {@link #refreshLineMarker}
     * 每次重新 set 同一个 renderer 类型的新实例时，平台判定"没有变化"而跳过
     * 重绘——这正是 anchorLost 状态切换、以及批量编辑后需要刷新图标外观时
     * 表现为"数据已经改了、图标却没重绘"的根因之一。改为比较 model（用其
     * uuid 判等）和 anchorLost 状态后，状态确实变化时才会被判定为不相等。</p>
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MyGutterIconRenderer)) {
            return false;
        }
        MyGutterIconRenderer other = (MyGutterIconRenderer) obj;
        return Objects.equals(model, other.model) && model.isAnchorLost() == other.model.isAnchorLost();
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, model.isAnchorLost());
    }

    public BookmarkNodeModel getModel() {
        return model;
    }

    @Override
    public GutterDraggableObject getDraggableObject() {
        // 拖拽
        return new GutterDraggableObject() {
            @Override
            public boolean copy(int line, VirtualFile file, int actionId) {
                Editor editor = getEditorForFile(file);
                if (editor != null) {
                    clearDragHighlights(editor);
                }
                model.updateBookmarkLine(line, true);
                return true;
            }

            @Override
            public Cursor getCursor(int line, VirtualFile file, int actionId) {
                Editor editor = getEditorForFile(file);
                if (editor != null) {
                    addDragHighlight(editor, line);
                }
                return GutterDraggableObject.super.getCursor(line, file, actionId);
            }

            @Override
            public void remove() {
                GutterDraggableObject.super.remove();
            }
        };
    }

    private Editor getEditorForFile(VirtualFile file) {
        if (file == null) {
            return null;
        }
        Project project = model.getOpenFileDescriptor().getProject();
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
        FileEditor[] fileEditors = fileEditorManager.getEditors(file);
        for (FileEditor fileEditor : fileEditors) {
            if (fileEditor instanceof TextEditor) {
                return ((TextEditor) fileEditor).getEditor();
            }
        }
        return null;
    }

    private void addDragHighlight(Editor editor, int line) {
        // 清除之前添加的拖拽高亮
        clearDragHighlights(editor);

        MarkupModel markupModel = editor.getMarkupModel();
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(JBColor.YELLOW);
        lastHighlighter = markupModel.addLineHighlighter(
                line,
                HighlighterLayer.SELECTION - 1, // 层级略低于选中层
                attributes
        );
    }

    private void clearDragHighlights(Editor editor) {
        MarkupModel markupModel = editor.getMarkupModel();
        if (lastHighlighter != null) {
            markupModel.removeHighlighter(lastHighlighter);
        }
    }
}
