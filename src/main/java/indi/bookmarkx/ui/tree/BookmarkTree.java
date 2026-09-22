package indi.bookmarkx.ui.tree;

import com.intellij.ide.DataManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.treeStructure.Tree;
import indi.bookmarkx.BookmarksManager;
import indi.bookmarkx.common.I18N;
import indi.bookmarkx.listener.BookmarkListener;
import indi.bookmarkx.model.AbstractTreeNodeModel;
import indi.bookmarkx.model.BookmarkNodeModel;
import indi.bookmarkx.model.GroupNodeModel;
import indi.bookmarkx.persistence.MySettings;
import indi.bookmarkx.service.BookmarkAnchorCapturer;
import indi.bookmarkx.ui.dialog.BookmarkCreatorDialog;
import indi.bookmarkx.ui.dialog.LineAdjustDialog;
import indi.bookmarkx.ui.panel.BookmarkTipPanel;
import indi.bookmarkx.utils.FileLineCounter;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 书签树
 *
 * @author Nonoas
 * @date 2023/6/1
 */
public class BookmarkTree extends Tree implements BookmarkListener {

    private static final Logger log = Logger.getInstance(BookmarkTree.class);

    /**
     * BookmarkTreeNode 缓存，便于通过 UUID 直接取到节点引用
     */
    private final Map<String, BookmarkTreeNode> nodeCache = new HashMap<>();

    private final GroupNavigator navigator = new GroupNavigator(this);

    private DefaultTreeModel model;

    private Project project;

    /**
     * 是否处于搜索过滤态。为 true 时 {@link javax.swing.JTree} 当前渲染的是
     * {@link #buildFilteredModel} 生成的只读克隆树，{@code this.model} 仍指向真实数据，
     * 不受影响。过滤态下拖拽排序、右键增删被禁用，只保留点击/双击跳转这类只读操作，
     * 因为它们改的都是当前挂载的树模型对象本身，不是通过 {@code BookmarksManager}
     * 数据服务——挂着克隆树时执行会导致编辑结果丢失，不会写回真实数据。
     */
    private boolean filtering = false;

    public BookmarkTree(Project project) {
        super();
        initData(project);
        initView();
        initDragHandler();
        initCellRenderer();
        initTreeListeners();
        initContextMenu();
    }

    private void initView() {
        TreeSpeedSearch treeSpeedSearch = new TreeSpeedSearch(this);
        treeSpeedSearch.setCanExpand(true);

        setShowsRootHandles(true);
    }

    private void initData(Project project) {
        this.project = project;

        BookmarkTreeNode root = new BookmarkTreeNode(new GroupNodeModel(project.getName()));
        model = new DefaultTreeModel(root);
        setModel(model);

        getSelectionModel().setSelectionMode(TreeSelectionModel.CONTIGUOUS_TREE_SELECTION);
        navigator.activatedGroup = root;
    }

    private void initDragHandler() {
        setDragEnabled(true);
        setDropMode(DropMode.ON_OR_INSERT);
        setTransferHandler(new DragHandler());
    }

    private void initCellRenderer() {
        setCellRenderer(new BmkTreeCellRenderer());
    }

    private void initTreeListeners() {
        // 订阅书签变化事件
        project.getMessageBus().connect().subscribe(BookmarkListener.TOPIC, this);

        // 选中监听
        addTreeSelectionListener(event -> {
            if (filtering) {
                // 过滤态下选中的是克隆节点。navigator 保存的是节点引用，一旦指向克隆
                // 节点，退出过滤换回真实模型后这些引用就是悬空的，"上一个/下一个书签"
                // 导航会出错，所以过滤态下不更新它。
                return;
            }
            int selectionCount = getSelectionCount();
            BookmarkTreeNode selectedNode = (BookmarkTreeNode) getLastSelectedPathComponent();
            if (selectionCount != 1 || null == selectedNode) {
                return;
            }

            if (selectedNode.isGroup()) {
                navigator.activeGroup(selectedNode);
            } else {
                navigator.activeBookmark(selectedNode);
            }
        });

        addMouseMotionListener(new TreeMouseMotionAdapter(this, project));

        // 鼠标点击事件
        addMouseListener(new DoubleClickAdapter(this));

    }

    /**
     * 初始化右键菜单
     */
    private void initContextMenu() {
        JBPopupMenu popupMenu = new JBPopupMenu();
        JBMenuItem imEdit = new JBMenuItem(I18N.get("bookmark.edit"));
        JBMenuItem imDel = new JBMenuItem(I18N.get("bookmark.delete"));
        JBMenuItem imAddGroup = new JBMenuItem(I18N.get("bookmark.addGroup"));
        JPopupMenu.Separator expandCollapseSeparator = new JPopupMenu.Separator();
        JBMenuItem imExpandAll = new JBMenuItem(I18N.get("bookmark.expandAll"));
        JBMenuItem imCollapseAll = new JBMenuItem(I18N.get("bookmark.collapseAll"));
        // TODO 需要添加可以将某个，目录拉出全局显示标签的按钮
        popupMenu.add(imEdit);
        popupMenu.add(imDel);
        popupMenu.add(imAddGroup);
        // 展开全部/折叠全部只对分组节点有意义，选中书签时不加进菜单——
        // 见下面 popupMenu 的 PopupMenuListener，与 batchAdjustLineItem 同一套动态显示模式

        JBPopupMenu popupMenuRoot = new JBPopupMenu();
        JBMenuItem imAddGroupRoot = new JBMenuItem(I18N.get("bookmark.addGroup"));
        JBMenuItem imExpandAllRoot = new JBMenuItem(I18N.get("bookmark.expandAll"));
        JBMenuItem imCollapseAllRoot = new JBMenuItem(I18N.get("bookmark.collapseAll"));
        popupMenuRoot.add(imAddGroupRoot);
        popupMenuRoot.add(new JPopupMenu.Separator());
        popupMenuRoot.add(imExpandAllRoot);
        popupMenuRoot.add(imCollapseAllRoot);

        imEdit.addActionListener(e -> {
            TreePath path = getSelectionPath();
            if (null == path) {
                return;
            }
            BookmarkTreeNode selectedNode = (BookmarkTreeNode) path.getLastPathComponent();
            AbstractTreeNodeModel nodeModel = (AbstractTreeNodeModel) selectedNode.getUserObject();
            BookmarksManager.getInstance(project).editBookRemark(nodeModel);
        });

        imDel.addActionListener(e -> {
            int result = Messages.showOkCancelDialog(project,
                    I18N.get("bookmark.delete.msg"),
                    I18N.get("bookmark.delete.ok"),
                    I18N.get("delete"),
                    I18N.get("cancel"),
                    Messages.getQuestionIcon());

            if (result == Messages.CANCEL) {
                return;
            }
            // 获取选定的节点
            TreePath[] selectionPaths = BookmarkTree.this.getSelectionPaths();
            if (selectionPaths == null) {
                return;
            }
            for (TreePath path : selectionPaths) {
                BookmarkTreeNode node = (BookmarkTreeNode) path.getLastPathComponent();
                BookmarkTreeNode parent = (BookmarkTreeNode) node.getParent();
                if (null == parent) {
                    continue;
                }
                this.remove(node);
            }
        });

        ActionListener addGroupListener = e -> {
            // 获取选定的节点
            BookmarkTreeNode selectedNode = (BookmarkTreeNode) BookmarkTree.this.getLastSelectedPathComponent();
            if (null == selectedNode) {
                return;
            }

            BookmarkTreeNode parent;
            if (selectedNode.isGroup()) {
                parent = selectedNode;
            } else {
                parent = (BookmarkTreeNode) selectedNode.getParent();
            }

            final GroupNodeModel groupNodeModel = new GroupNodeModel();

            BookmarkCreatorDialog.BookmarkDialogResult result = new BookmarkCreatorDialog(project, I18N.get("group.create.title"))
                    .showAndGetResult();
            if (!result.isOk()) {
                return;
            }

            String uuid = UUID.randomUUID().toString();
            groupNodeModel.setUuid(uuid);
            groupNodeModel.setName(result.getName());
            groupNodeModel.setDesc(result.getDesc());

            // 新的分组节点
            BookmarkTreeNode groupNode = new BookmarkTreeNode(groupNodeModel);
            model.insertNodeInto(groupNode, parent, 0);
            BookmarkTree.this.model.nodeChanged(selectedNode);

        };

        imAddGroup.addActionListener(addGroupListener);
        imAddGroupRoot.addActionListener(addGroupListener);

        imExpandAll.addActionListener(e -> {
            BookmarkTreeNode selectedNode = (BookmarkTreeNode) BookmarkTree.this.getLastSelectedPathComponent();
            if (selectedNode != null) {
                expandAllNodes(this.model, selectedNode);
            }
        });
        imCollapseAll.addActionListener(e -> {
            BookmarkTreeNode selectedNode = (BookmarkTreeNode) BookmarkTree.this.getLastSelectedPathComponent();
            if (selectedNode != null) {
                collapseAllNodes(this.model, selectedNode);
            }
        });
        imExpandAllRoot.addActionListener(e -> expandAllNodes(this.model, (BookmarkTreeNode) this.model.getRoot()));
        imCollapseAllRoot.addActionListener(e -> collapseAllNodes(this.model, (BookmarkTreeNode) this.model.getRoot()));

        // 右键点击事件
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) {
                    return;
                }
                if (filtering) {
                    // 过滤态下树上是只读克隆节点，编辑类菜单（编辑/删除/新建分组）
                    // 一旦执行只会作用在克隆节点上，不会写回真实数据，直接不弹出菜单
                    return;
                }
                int row = getClosestRowForLocation(e.getX(), e.getY());
                if (row < 0) {
                    return;
                }
                if (!isRowSelected(row)) {
                    setSelectionRow(row);
                }

                if (0 == row) {
                    popupMenuRoot.show(BookmarkTree.this, e.getX() + 16, e.getY());
                } else if (row < getRowCount()) {
                    popupMenu.show(BookmarkTree.this, e.getX() + 16, e.getY());
                }
            }
        });

        // 批量调整行号菜单项
        JPopupMenu.Separator batchAdjustLineSeparator = new JPopupMenu.Separator();
        JBMenuItem batchAdjustLineItem = new JBMenuItem(I18N.get("bookmark.batchAdjustLine"));
        batchAdjustLineItem.addActionListener(e -> {
            TreePath[] selectionPaths = BookmarkTree.this.getSelectionPaths();
            if (!this.showBatchAdjustMenu()) {
                return;
            }
            LineAdjustDialog dialog = new LineAdjustDialog(project);
            if (dialog.showAndGet()) {
                int adjustValue = dialog.getAdjustValue();
                if (adjustValue == 0) {
                    return;
                }

                // 调整所有选中书签的行号
                for (TreePath path : selectionPaths) {
                    BookmarkTreeNode node = (BookmarkTreeNode) path.getLastPathComponent();
                    BookmarkNodeModel model = (BookmarkNodeModel) node.getUserObject();

                    // 计算新行号，确保不小于0
                    int newLine = Math.max(0, model.getLine() + adjustValue);
                    int maxLine = FileLineCounter.getFileMaxLine(model.getOpenFileDescriptor());
                    if (maxLine > 0) {
                        newLine = Math.min(newLine, maxLine - 1);// 从0开始
                    }
                    model.updateBookmarkLine(newLine, false);
                    // 按用户指定的新位置重建锚点并清除失效标记，
                    // 否则下一次重定位会按旧锚点把书签拉回原处，手动修正白做
                    BookmarkAnchorCapturer.capture(model);
                }
                BookmarksManager.getInstance(project).persistentSave();
            }
        });

        popupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean showBatchAdjust = showBatchAdjustMenu();
                if (showBatchAdjust && popupMenu.getComponentIndex(batchAdjustLineItem) == -1) {
                    popupMenu.add(batchAdjustLineSeparator);
                    popupMenu.add(batchAdjustLineItem);
                }
                // 展开全部/折叠全部只对分组节点有意义，选中书签时不展示
                BookmarkTreeNode selectedNode = (BookmarkTreeNode) BookmarkTree.this.getLastSelectedPathComponent();
                if (selectedNode != null && selectedNode.isGroup()
                        && popupMenu.getComponentIndex(imExpandAll) == -1) {
                    popupMenu.add(expandCollapseSeparator);
                    popupMenu.add(imExpandAll);
                    popupMenu.add(imCollapseAll);
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                popupMenu.remove(batchAdjustLineSeparator);
                popupMenu.remove(batchAdjustLineItem);
                popupMenu.remove(expandCollapseSeparator);
                popupMenu.remove(imExpandAll);
                popupMenu.remove(imCollapseAll);
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                popupMenu.remove(batchAdjustLineSeparator);
                popupMenu.remove(batchAdjustLineItem);
                popupMenu.remove(expandCollapseSeparator);
                popupMenu.remove(imExpandAll);
                popupMenu.remove(imCollapseAll);
            }
        });
    }

    private boolean showBatchAdjustMenu() {
        TreePath[] selectionPaths = this.getSelectionPaths();
        if (selectionPaths == null || selectionPaths.length < 2) {
            return false;
        }
        // 检查是否所有选中项都是书签
        for (TreePath path : selectionPaths) {
            BookmarkTreeNode node = (BookmarkTreeNode) path.getLastPathComponent();
            if (!node.isBookmark()) {
                return false;
            }
        }
        return true;
    }


    public BookmarkTreeNode getEventSourceNode(MouseEvent event) {
        int row = getRowForLocation(event.getX(), event.getY());
        return row >= 0
                ? (BookmarkTreeNode) getPathForRow(row).getLastPathComponent()
                : null;
    }

    /**
     * 向当前激活的分组添加指定节点，并刷新树结构
     *
     * @param node 要添加的节点
     */
    public void add(BookmarkTreeNode node) {

        navigator.activatedBookmark = node;
        BookmarkTreeNode parent = navigator.ensureActivatedGroup();

        model.insertNodeInto(node, parent, parent.getChildCount());
        // 定位到新增的节点并使其可见
        scrollPathToVisible(new TreePath(node.getPath()));

        addToCache(node);
    }

    /**
     * 删除指定节点，并刷新树结构
     *
     * @param node 要删除的节点
     */
    private void remove(@NotNull BookmarkTreeNode node) {
        if (node.isBookmark()) {
            BookmarksManager.getInstance(project).removeBookRemark((BookmarkNodeModel) node.getUserObject());
            return;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            remove((BookmarkTreeNode) node.getChildAt(i));
        }
        // 删除完所有书签节点之后，删除分组节点
        this.model.removeNodeFromParent(node);
    }

    public BookmarkTreeNode getNodeByModel(BookmarkNodeModel nodeModel) {
        String uuid = nodeModel.getUuid();
        return nodeCache.get(uuid);
    }

    private void addToCache(BookmarkTreeNode node) {
        AbstractTreeNodeModel userObject = (AbstractTreeNodeModel) node.getUserObject();
        if (userObject.getUuid() != null) {
            nodeCache.put(userObject.getUuid(), node);
        }
    }

    private void removeFromCache(BookmarkTreeNode node) {
        if (node == null) {
            return;
        }
        Object userObject = node.getUserObject();
        if (!(userObject instanceof AbstractTreeNodeModel)) {
            return;
        }
        String uuid = ((AbstractTreeNodeModel) userObject).getUuid();
        if (uuid != null) {
            nodeCache.remove(uuid);
        }
    }

    public void insertNodeInto(BookmarkTreeNode node, BookmarkTreeNode parent, int index) {
        model.insertNodeInto(node, parent, index);
        if (node.isBookmark()) {
            addToCache(node);
        }
    }

    public void moveNode(BookmarkTreeNode node, BookmarkTreeNode parent, int index) {
        if (node == null || parent == null) {
            return;
        }
        if (node.getParent() != null) {
            model.removeNodeFromParent(node);
        }
        model.insertNodeInto(node, parent, index);
        if (node.isBookmark()) {
            addToCache(node);
        }
    }

    public void removeNodeFromParent(BookmarkTreeNode node) {
        if (node == null || node.getParent() == null) {
            return;
        }
        removeNodeCacheRecursive(node);
        model.removeNodeFromParent(node);
    }

    private void removeNodeCacheRecursive(BookmarkTreeNode node) {
        if (node.isBookmark()) {
            removeFromCache(node);
            return;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            removeNodeCacheRecursive((BookmarkTreeNode) node.getChildAt(i));
        }
    }

    @Override
    public void setModel(TreeModel newModel) {
        this.model = (DefaultTreeModel) newModel;
        Object root = model.getRoot();
        if (!(root instanceof BookmarkTreeNode)) {
            super.setModel(model);
            return;
        }
        navigator.activatedGroup = (BookmarkTreeNode) root;
        nodeCache.clear();
        loadNodeCache((BookmarkTreeNode) root);
        super.setModel(model);
    }

    /**
     * 是否处于搜索过滤态。
     */
    public boolean isFiltering() {
        return filtering;
    }

    /**
     * 按关键词过滤展示的书签。
     * <p>只切换 {@link javax.swing.JTree} 当前渲染用的模型（走 {@code super.setModel}），
     * 不碰 {@link #model}/{@link #nodeCache}/{@link #navigator}——这几个字段必须一直
     * 指向真实数据，其余所有增删改逻辑都是直接基于它们操作的。</p>
     *
     * @param query 过滤关键词，为空或全空白时恢复展示真实树
     */
    public void applyFilter(@Nullable String query) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            if (filtering) {
                filtering = false;
                super.setModel(this.model);
                expandRow(0);
            }
            return;
        }

        BookmarkTreeNode root = (BookmarkTreeNode) this.model.getRoot();
        BookmarkTreeNode filteredRoot = cloneMatching(root, keyword.toLowerCase(Locale.ROOT));
        if (filteredRoot == null) {
            // 根节点本身不含 userObject 意义上的"匹配"判断，兜底保证根节点始终存在
            filteredRoot = new BookmarkTreeNode(true);
        }
        filtering = true;
        DefaultTreeModel filteredModel = new DefaultTreeModel(filteredRoot);
        super.setModel(filteredModel);
        // 不用 TreeUtil.expandAll：反编译确认它内部是 promiseExpandAll(tree) 后
        // 直接丢弃 Promise 的 fire-and-forget 调用，不保证在 setModel 之后的这一帧
        // 就完成遍历，深层分组可能来不及展开。这棵克隆树是我们自己刚构建出来的，
        // 结构已知且通常不深，直接同步递归 expandPath 更可靠，也不需要等待。
        expandAllNodes(filteredModel, filteredRoot);
    }

    /**
     * 同步展开某节点及其全部后代分组，确保过滤结果的书签叶子节点立即可见，
     * 也用于右键菜单的「展开全部」。
     */
    private void expandAllNodes(DefaultTreeModel treeModel, BookmarkTreeNode node) {
        if (node.isBookmark()) {
            return;
        }
        expandPath(new TreePath(treeModel.getPathToRoot(node)));
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            expandAllNodes(treeModel, (BookmarkTreeNode) node.getChildAt(i));
        }
    }

    /**
     * 同步折叠某节点及其全部后代分组，用于右键菜单的「折叠全部」。
     * <p>自底向上折叠：先递归折叠全部子分组，再折叠自身。折叠子节点不会使
     * 父节点的 {@link TreePath} 失效（父路径不依赖子节点是否展开），但反过来
     * 如果先折叠父节点，子节点的展开状态在视觉上立刻不可见，逻辑上更绕；
     * 自底向上与用户对「折叠全部」的直觉一致，且不依赖折叠顺序的正确性。</p>
     */
    private void collapseAllNodes(DefaultTreeModel treeModel, BookmarkTreeNode node) {
        if (node.isBookmark()) {
            return;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            collapseAllNodes(treeModel, (BookmarkTreeNode) node.getChildAt(i));
        }
        collapsePath(new TreePath(treeModel.getPathToRoot(node)));
    }

    /**
     * 递归构建一份只读克隆子树：书签节点的名称或描述命中关键词才保留；
     * 分组节点只要有任意后代命中就连同该分组一起保留（分组本身文案不参与匹配，
     * 用户是在找书签，不是在找分组名）。
     * <p>克隆节点与原节点共享同一个 {@link AbstractTreeNodeModel} 引用（{@code userObject}
     * 没有被复制），因此点击、双击跳转等只读取 {@code userObject} 的操作在克隆树上
     * 结果与在真实树上完全一致。</p>
     *
     * @return 命中过滤条件的克隆节点；本节点与其全部后代都不命中时返回 {@code null}
     */
    @Nullable
    private static BookmarkTreeNode cloneMatching(BookmarkTreeNode node, String lowerKeyword) {
        if (node.isBookmark()) {
            AbstractTreeNodeModel userObject = (AbstractTreeNodeModel) node.getUserObject();
            boolean nameHit = userObject.getName() != null
                    && userObject.getName().toLowerCase(Locale.ROOT).contains(lowerKeyword);
            boolean descHit = userObject.getDesc() != null
                    && userObject.getDesc().toLowerCase(Locale.ROOT).contains(lowerKeyword);
            if (!nameHit && !descHit) {
                return null;
            }
            BookmarkTreeNode clone = new BookmarkTreeNode(userObject);
            return clone;
        }

        BookmarkTreeNode clone = null;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            BookmarkTreeNode childClone = cloneMatching((BookmarkTreeNode) node.getChildAt(i), lowerKeyword);
            if (childClone == null) {
                continue;
            }
            if (clone == null) {
                Object userObject = node.getUserObject();
                clone = userObject == null ? new BookmarkTreeNode(false) : new BookmarkTreeNode((AbstractTreeNodeModel) userObject);
            }
            clone.add(childClone);
        }
        return clone;
    }

    /**
     * 加载当前节点下的所有节点到缓存
     *
     * @param node 当前节点
     */
    private void loadNodeCache(BookmarkTreeNode node) {
        AbstractTreeNodeModel model = (AbstractTreeNodeModel) node.getUserObject();
        if (node.isBookmark()) {
            nodeCache.put(model.getUuid(), node);
            return;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            loadNodeCache((BookmarkTreeNode) node.getChildAt(i));
        }
    }

    @Override
    public DefaultTreeModel getModel() {
        return this.model;
    }

    public GroupNavigator getGroupNavigator() {
        return this.navigator;
    }

    public String getActivatedGroupName() {
        BookmarkTreeNode group = navigator.ensureActivatedGroup();
        AbstractTreeNodeModel nodeModel = (AbstractTreeNodeModel) group.getUserObject();
        return nodeModel.getName();
    }

    public String getActivatedGroupPath() {
        BookmarkTreeNode group = navigator.ensureActivatedGroup();
        List<String> names = new ArrayList<>();
        for (TreeNode node : group.getPath()) {
            if (!(node instanceof BookmarkTreeNode)) {
                continue;
            }
            AbstractTreeNodeModel nodeModel = (AbstractTreeNodeModel) ((BookmarkTreeNode) node).getUserObject();
            String name = nodeModel.getName();
            if (name != null && !name.trim().isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }

    public BookmarkTreeNode getNodeForRow(int row) {
        TreePath path = getPathForRow(row);
        if (path != null) {
            return (BookmarkTreeNode) path.getLastPathComponent();
        } else {
            return null;
        }
    }

    @Override
    public void bookmarkAdded(@NotNull AbstractTreeNodeModel model) {
        if (model.isGroup()) {
            return;
        }
        BookmarkTreeNode treeNode = new BookmarkTreeNode(model);
        if (nodeCache.containsKey(model.getUuid())) {
            this.model.nodeChanged(nodeCache.get(model.getUuid()));
            return;
        }
        this.add(treeNode);

        BookmarkNodeModel bookmarkNodeModel = (BookmarkNodeModel) model;
        bookmarkNodeModel.setIndex(treeNode.getParent().getIndex(treeNode));
        BookmarkTreeNode nodeByModel = getNodeByModel(bookmarkNodeModel);
        this.model.nodeChanged(nodeByModel);
    }

    @Override
    public void bookmarkChanged(@NotNull AbstractTreeNodeModel model) {
        if (model.isGroup()) {
            return;
        }
        BookmarkNodeModel bookmarkNodeModel = (BookmarkNodeModel) model;
        BookmarkTreeNode node = nodeCache.get(bookmarkNodeModel.getUuid());
        if (node == null) {
            return;
        }
        this.model.nodeChanged(node);
    }

    @Override
    public void bookmarkRemoved(@NotNull AbstractTreeNodeModel model) {
        if (model.isGroup()) {
            return;
        }
        BookmarkNodeModel bookmarkNodeModel = (BookmarkNodeModel) model;
        BookmarkTreeNode node = nodeCache.remove(bookmarkNodeModel.getUuid());
        if (node == null || node.getParent() == null) {
            return;
        }
        this.model.removeNodeFromParent(node);
    }

    /**
     * 标签树的导航器，与快捷键绑定，用于遍历当前选中的分组下的标签，当前分组的下级分组不会被遍历
     */
    public static class GroupNavigator {

        private final BookmarkTree tree;

        private BookmarkTreeNode activatedGroup;
        private BookmarkTreeNode activatedBookmark;

        GroupNavigator(BookmarkTree tree) {
            this.tree = tree;
        }

        public void pre() {
            BookmarkTreeNode group = ensureActivatedGroup();
            if (0 == group.getBookmarkChildCount()) {
                return;
            }

            BookmarkTreeNode bookmark = ensureActivatedBookmark();
            int index = preTreeNodeIndex(group, bookmark);
            navigateTo(index);
        }

        public void next() {
            BookmarkTreeNode group = ensureActivatedGroup();
            if (0 == group.getBookmarkChildCount()) {
                return;
            }

            BookmarkTreeNode bookmark = ensureActivatedBookmark();
            int index = nextTreeNodeIndex(group, bookmark);
            navigateTo(index);
        }

        public void activeGroup(BookmarkTreeNode node) {
            activatedGroup = node;
            if (node.getChildCount() > 0) {
                activatedBookmark = (BookmarkTreeNode) node.getChildAt(0);
            } else {
                activatedBookmark = null;
            }
        }

        public void activeBookmark(BookmarkTreeNode node) {
            activatedBookmark = node;
            activatedGroup = (BookmarkTreeNode) node.getParent();

            TreePath treePath = new TreePath(node.getPath());
            tree.setSelectionPath(treePath);

            if (!tree.isVisible(treePath)) {
                tree.scrollPathToVisible(treePath);
            }
        }

        /**
         * 确保 {@code activatedGroup} 一定是一个在当前树上的节点，
         * 所有读取 {@code activatedGroup} 值的地方都应该调用这个方法，
         * 避免当 {@code activatedGroup} 指向的节点已经从当前的 tree 中移除
         *
         * @return 激活的节点或者根节点
         */
        private BookmarkTreeNode ensureActivatedGroup() {
            if (null == activatedGroup) {
                return (BookmarkTreeNode) tree.getModel().getRoot();
            }
            TreeNode[] path = activatedGroup.getPath();
            int row = tree.getRowForPath(new TreePath(path));
            if (row < 0) {
                return (BookmarkTreeNode) tree.getModel().getRoot();
            }
            return activatedGroup;
        }

        /**
         * 确保 {@code activatedBookmark} 一定是一个在当前树上的节点，
         * 所有读取 {@code activatedBookmark} 值的地方都应该调用这个方法，
         * 避免当 {@code activatedBookmark} 指向的节点已经从当前的 tree 中移除
         *
         * @return 激活的节点 或者 {@code null}
         */
        private BookmarkTreeNode ensureActivatedBookmark() {
            if (null == activatedBookmark) {
                return null;
            }
            TreeNode[] path = activatedBookmark.getPath();
            int row = tree.getRowForPath(new TreePath(path));

            return row < 0 ? null : activatedBookmark;
        }

        private void navigateTo(int index) {
            Validate.isTrue(index >= 0, "index must be greater than 0");

            BookmarkTreeNode nextNode = (BookmarkTreeNode) activatedGroup.getChildAt(index);
            activeBookmark(nextNode);

            BookmarkNodeModel model = (BookmarkNodeModel) nextNode.getUserObject();
            OpenFileDescriptor openFileDescriptor = model.getOpenFileDescriptor();
            if (null == openFileDescriptor) {
                log.warn("Can't find open file descriptor for " + model.getName());
                return;
            }
            openFileDescriptor.navigate(true);
        }

        private int preTreeNodeIndex(BookmarkTreeNode activeGroup, BookmarkTreeNode activatedBookmark) {
            Validate.isTrue(activeGroup.getBookmarkChildCount() > 0, "activeGroup has no child");
            if (null == activatedBookmark) {
                return activeGroup.firstChildIndex();
            }
            int currIndex = activeGroup.getIndex(activatedBookmark);
            int groupSize = activeGroup.getChildCount();

            BookmarkTreeNode node;
            do {
                currIndex = (currIndex - 1 + groupSize) % groupSize;
                node = (BookmarkTreeNode) activeGroup.getChildAt(currIndex);
            } while (node.isGroup());
            return currIndex;
        }

        private int nextTreeNodeIndex(BookmarkTreeNode activeGroup, BookmarkTreeNode activatedBookmark) {
            Validate.isTrue(activeGroup.getBookmarkChildCount() > 0, "activeGroup has no child");
            if (null == activatedBookmark) {
                return activeGroup.firstChildIndex();
            }

            int currIndex = activeGroup.getIndex(activatedBookmark);
            BookmarkTreeNode node;
            do {
                currIndex = (currIndex + 1) % activeGroup.getChildCount();
                node = (BookmarkTreeNode) activeGroup.getChildAt(currIndex);
            } while (node.isGroup());
            return currIndex;
        }

    }

    // 自定义传输对象
    static class NodesTransferable implements Transferable {

        public static final DataFlavor NODES_FLAVOR = new DataFlavor(int[].class, "Tree Rows");

        private final int[] rows;

        public NodesTransferable(int[] rows) {
            this.rows = rows;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{NODES_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return flavor.equals(NODES_FLAVOR);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (isDataFlavorSupported(flavor)) {
                return rows;
            } else {
                throw new UnsupportedFlavorException(flavor);
            }
        }
    }

    /**
     * 节点拖拽处理器
     */
    static class DragHandler extends TransferHandler {

        @Override
        public int getSourceActions(JComponent c) {
            if (((BookmarkTree) c).isFiltering()) {
                // 搜索过滤态下拖的是克隆节点，排序结果不会写回真实数据，禁止拖动
                return NONE;
            }
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            BookmarkTree tree = (BookmarkTree) c;
            int[] paths = tree.getSelectionRows();
            if (paths != null && paths.length > 0) {
                return new NodesTransferable(paths);
            }
            return null;
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            if (action != MOVE) {
                return;
            }
            super.exportDone(source, data, action);
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (((BookmarkTree) support.getComponent()).isFiltering()) {
                return false;
            }
            JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
            TreePath destPath = dl.getPath();
            if (destPath == null) {
                return false;
            }
            BookmarkTreeNode targetNode = (BookmarkTreeNode) destPath.getLastPathComponent();
            return targetNode != null && targetNode.isGroup();
        }

        @Override
        public boolean importData(TransferSupport support) {
            JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
            BookmarkTree tree = (BookmarkTree) support.getComponent();
            TreePath destPath = dl.getPath();
            BookmarkTreeNode targetNode = (BookmarkTreeNode) destPath.getLastPathComponent();

            try {
                Transferable transferable = support.getTransferable();
                int[] rows = (int[]) transferable.getTransferData(NodesTransferable.NODES_FLAVOR);
                DefaultTreeModel model = tree.getModel();

                List<BookmarkTreeNode> nodes = Arrays.stream(rows)
                        .mapToObj(tree::getNodeForRow)
                        .collect(Collectors.toList());

                int childIndex = dl.getChildIndex();

                if (-1 == childIndex) {
                    for (BookmarkTreeNode node : nodes) {
                        // 目标节点不能是拖动节点的后代，拖动节点不能是目标节点的直接子代
                        if (!targetNode.isNodeAncestor(node) && !targetNode.isNodeChild(node)) {
                            model.removeNodeFromParent(node);
                            model.insertNodeInto(node, targetNode, targetNode.getChildCount());
                        }
                    }
                } else {
                    Collections.reverse(nodes);
                    for (BookmarkTreeNode node : nodes) {
                        // 目标节点不能是拖动节点的后代，拖动节点不能是目标节点的直接子代
                        if (!targetNode.isNodeAncestor(node)) {
                            if (targetNode.isNodeChild(node)) {
                                int index = targetNode.getIndex(node);
                                if (childIndex > index) {
                                    childIndex = childIndex - 1;
                                }
                            }
                            model.removeNodeFromParent(node);
                            model.insertNodeInto(node, targetNode, childIndex);
                        }
                    }
                }
                tree.expandPath(destPath);
                return true;

            } catch (Exception e) {
                log.error(e);
            }

            return false;
        }
    }

    /**
     * 鼠标选父监听
     */
    static class TreeMouseMotionAdapter extends MouseMotionAdapter {
        private Timer timer;
        private TreePath selectedPath;
        private final JTree tree;
        private JBPopup lastPopup;
        private AbstractTreeNodeModel lastAbstractTreeNodeModel;
        private Project project;

        public TreeMouseMotionAdapter(JTree tree, Project project) {
            this.tree = tree;
            this.project = project;
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            MySettings instance = MySettings.getInstance();
            int tipDelay = instance.getTipDelay();
            if (tipDelay < 0) {
                return;
            }

            // Get the selected node
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            if (path != null) {
                if (Objects.equals(path, selectedPath)) {
                    return;
                }
                removeTimer();
                selectedPath = path;

                timer = new Timer(tipDelay, te -> showToolTip(getToolTipText(e), e));
                timer.setRepeats(false);
                timer.restart();
            } else {
                selectedPath = null;
                lastAbstractTreeNodeModel = null;
                removeTimer();
            }
        }

        private void removeTimer() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }

            if (this.lastPopup != null) {
                lastPopup.cancel();
            }
        }


        private AbstractTreeNodeModel getToolTipText(MouseEvent e) {
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            if (path != null) {
                BookmarkTreeNode selectedNode = (BookmarkTreeNode) path.getLastPathComponent();
                if (selectedNode != null) {
                    return (AbstractTreeNodeModel) selectedNode.getUserObject();
                }
            }
            return null;
        }

        private void showToolTip(AbstractTreeNodeModel abstractTreeNodeModel, MouseEvent e) {
            if (abstractTreeNodeModel == null) {
                return;
            }
            if (lastAbstractTreeNodeModel == abstractTreeNodeModel) {
                return;
            }
            if (this.lastPopup != null) {
                lastPopup.cancel();
            }
            lastAbstractTreeNodeModel = abstractTreeNodeModel;

            JBPopupFactory popupFactory = JBPopupFactory.getInstance();
            lastPopup = popupFactory.createComponentPopupBuilder(new BookmarkTipPanel(project, lastAbstractTreeNodeModel, null), null)
                    .setFocusable(true)
                    .setResizable(true)
                    .setRequestFocus(true)
                    .createPopup();

            // 3. 智能显示
            if (e.getSource() instanceof Component) {
                // 这种方式会自动处理屏幕边界，如果右边放不下就往左边弹
                Point point = e.getPoint();
                point.translate(0, 6);
                lastPopup.show(new RelativePoint((Component) e.getSource(), point));
            } else {
                lastPopup.showInBestPositionFor(DataManager.getInstance().getDataContext(e.getComponent()));
            }
        }

    }

    /**
     * 鼠标双击事件适配器
     */
    static class DoubleClickAdapter extends MouseAdapter {
        // 假设 log 变量已在外部或父类中正确声明和初始化
        private static final Logger log = Logger.getInstance(DoubleClickAdapter.class);

        // 记录上一次点击的时间（毫秒）
        private long lastClickTime = 0;
        // 记录上一次点击的坐标
        private Point lastClickPoint = new Point();

        // 可以根据用户体验调整这些阈值
        // 设置双击时间阈值（毫秒）。标准通常是 300ms
        private static final int DOUBLE_CLICK_TIME_THRESHOLD = 300;
        // 设置双击距离阈值（像素）。防止鼠标轻微移动导致双击失败
        private static final int DOUBLE_CLICK_DISTANCE_THRESHOLD = 5;

        private final JTree tree;

        DoubleClickAdapter(JTree tree) {
            this.tree = tree;
        }

        /**
         * 将双击判断逻辑从 mouseClicked 转移到更可靠的 mousePressed
         */
        @Override
        public void mousePressed(MouseEvent e) {
            // 1. 只处理左键事件
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return;
            }

            long currentTime = e.getWhen();
            Point currentPoint = e.getPoint();

            // 2. 检查时间间隔是否满足双击要求
            if (currentTime - lastClickTime < DOUBLE_CLICK_TIME_THRESHOLD) {

                // 3. 检查距离是否满足双击要求
                double distance = currentPoint.distance(lastClickPoint);

                if (distance < DOUBLE_CLICK_DISTANCE_THRESHOLD) {

                    // *** 手动识别为双击！ ***
                    log.info("【手动识别】进入鼠标双击逻辑");
                    log.info("【手动识别】识别为双击");

                    // 执行双击动作
                    performDoubleClickAction(e);

                    // 4. 重置状态：将 lastClickTime 设为 0，防止用户快速三连击被误判为两次双击
                    lastClickTime = 0;
                    return;
                }
            }

            // 5. 如果不是双击，则记录本次点击，作为下一次判断的基础
            lastClickTime = currentTime;
            lastClickPoint = currentPoint;
        }

        // 移除原有的 mouseClicked 方法，因为它不再是主逻辑

        /**
         * 封装双击成功后的实际导航逻辑
         */
        private void performDoubleClickAction(MouseEvent e) {
            // 关键：使用 getClosestPathForLocation(e.getX(), e.getY())
            // 替代 getSelectionPath()，直接通过坐标获取节点，避免选区丢失问题。
            TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());

            if (Objects.isNull(path)) {
                log.info("【手动识别】退出鼠标双击逻辑：path 为空");
                return;
            }

            // 可选：强制选中路径，提供视觉反馈
            tree.setSelectionPath(path);

            BookmarkTreeNode selectedNode = (BookmarkTreeNode) path.getLastPathComponent();
            if (selectedNode != null && selectedNode.isBookmark()) {
                BookmarkNodeModel bookmark = (BookmarkNodeModel) selectedNode.getUserObject();

                OpenFileDescriptor fileDescriptor = bookmark.getOpenFileDescriptor();
                if (null == fileDescriptor) {
                    log.info("【手动识别】退出鼠标双击逻辑：fileDescriptor为空");
                    return;
                }
                fileDescriptor.navigate(true);
            }
            log.info("【手动识别】退出鼠标双击逻辑，导航成功");
        }
    }

}
