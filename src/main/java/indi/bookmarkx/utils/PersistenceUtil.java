package indi.bookmarkx.utils;

import com.google.gson.Gson;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import indi.bookmarkx.model.BookmarkNodeModel;
import indi.bookmarkx.persistence.MyPersistent;
import indi.bookmarkx.model.AbstractTreeNodeModel;
import indi.bookmarkx.model.BookmarkConverter;
import indi.bookmarkx.model.GroupNodeModel;
import indi.bookmarkx.model.po.BookmarkPO;
import indi.bookmarkx.ui.tree.BookmarkTree;
import indi.bookmarkx.ui.tree.BookmarkTreeNode;
import org.apache.commons.collections.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Nonoas
 * @date 2023/6/6
 */
public class PersistenceUtil {

    /**
     * 持久化保存
     */
    public static void persistentSave(Project project, BookmarkTree tree) {
        BookmarkPO po = getPersistenceObject(tree);
        MyPersistent persistent = MyPersistent.getInstance(project);
        persistent.setState(po);
    }

    public static BookmarkPO getPersistenceObject(BookmarkTree tree) {
        BookmarkTreeNode rootNode = (BookmarkTreeNode) tree.getModel().getRoot();
        return covertToPO(rootNode);
    }

    private static BookmarkPO covertToPO(BookmarkTreeNode node) {

        int childCount = node.getChildCount();
        AbstractTreeNodeModel model = (AbstractTreeNodeModel) node.getUserObject();
        BookmarkPO po = BookmarkConverter.convertToPO(model);

        if (0 == childCount) {
            return po;
        }

        List<BookmarkPO> children = new ArrayList<>();
        BookmarkTreeNode child;
        for (int i = 0; i < childCount; i++) {
            child = (BookmarkTreeNode) node.getChildAt(i);
            children.add(covertToPO(child));
        }
        po.setChildren(children);
        return po;
    }

    public static BookmarkTreeNode generateTreeNode(BookmarkPO po, Project project) {
        if (po.isBookmark()) {
            AbstractTreeNodeModel model = BookmarkConverter.convertToModel(project, po);
            // 旧书签补充行哈希
            if (po.getLineContentHash() == null || po.getLineContentHash().isEmpty()) {
                BookmarkNodeModel bookmarkModel = (BookmarkNodeModel) model;
                OpenFileDescriptor descriptor = bookmarkModel.getOpenFileDescriptor();
                if (descriptor != null) {
                    VirtualFile file = descriptor.getFile();
                    Document document = FileDocumentManager.getInstance().getDocument(file);
                    if (document != null && bookmarkModel.getLine() < document.getLineCount()) {
                        int start = document.getLineStartOffset(bookmarkModel.getLine());
                        int end = document.getLineEndOffset(bookmarkModel.getLine());
                        String lineContent = document.getText().substring(start, end);
                        po.setLineContentHash(BookmarkPO.generateLineHash(lineContent));
                        // 补充上下文
                        String context = buildContext(document, bookmarkModel.getLine());
                        po.setLineContext(context);
                    }
                }
            }
            return new BookmarkTreeNode(model);
        }

        GroupNodeModel model = (GroupNodeModel) BookmarkConverter.convertToModel(project, po);
        BookmarkTreeNode node = new BookmarkTreeNode(model);

        List<BookmarkPO> children = po.getChildren();
        if (CollectionUtils.isEmpty(children)) {
            return node;
        }
        for (BookmarkPO child : children) {
            node.add(generateTreeNode(child, project));
        }
        return node;
    }

    private static String buildContext(Document document, int line) {
        String context = "";
        if (line > 0) {
            int prevStart = document.getLineStartOffset(line - 1);
            int prevEnd = document.getLineEndOffset(line - 1);
            context += document.getText().substring(prevStart, prevEnd) + "|";
        }
        int currStart = document.getLineStartOffset(line);
        int currEnd = document.getLineEndOffset(line);
        context += document.getText().substring(currStart, currEnd) + "|";
        if (line < document.getLineCount() - 1) {
            int nextStart = document.getLineStartOffset(line + 1);
            int nextEnd = document.getLineEndOffset(line + 1);
            context += document.getText().substring(nextStart, nextEnd);
        }
        return context;
    }

    /**
     * 可以为 null
     */
    public static <T> T deepCopy(T object, Class<T> clazz) {
        if (null == object) {
            return null;
        }
        Gson gson = new Gson();
        String json = gson.toJson(object, clazz);
        return gson.fromJson(json, clazz);
    }

    public static List<BookmarkNodeModel> treeToList(BookmarkTreeNode node) {
        List<BookmarkNodeModel> list = new ArrayList<>();
        int childCount = node.getChildCount();
        if (0 == childCount) {
            return list;
        }
        for (int i = 0; i < childCount; i++) {
            BookmarkTreeNode treeNode = (BookmarkTreeNode) node.getChildAt(i);
            Object userObject = treeNode.getUserObject();
            if (userObject instanceof BookmarkNodeModel) {
                list.add((BookmarkNodeModel) userObject);
            }
            list.addAll(treeToList(treeNode));
        }
        return list;
    }

}
