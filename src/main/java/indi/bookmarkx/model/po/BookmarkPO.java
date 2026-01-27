package indi.bookmarkx.model.po;

import javax.xml.bind.annotation.XmlRootElement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 书签持久化对象<br/>
 * bookmark：true 为书签<br/>
 * bookmark：false 为书签分组 <br/><br/>
 * <p>需要注意，一些「书签」使用的属性，和「书签分组」</p>
 *
 * @author Nonoas
 * @date 2023/6/5
 */
@XmlRootElement
public class BookmarkPO {

    private String uuid;

    private int index;
    private int line;

    private String desc;

    private String name;

    private String iconPath;

    private boolean bookmark;

    /**
     * 虚拟文件路径
     */
    private String virtualFilePath;

    private List<BookmarkPO> children = new ArrayList<>();

    private String lineContentHash; // 行内容的MD5哈希
    private String lineContext;     // 行上下文（前后1行）

    public BookmarkPO() {

    }

    public BookmarkPO(String name, String desc) {
        this.name = name;
        this.desc = desc;
    }


    public List<BookmarkPO> getChildren() {
        List<BookmarkPO> list = children.stream().distinct().collect(Collectors.toList());
        children.clear();
        children.addAll(list);
        return children;
    }

    public void setChildren(List<BookmarkPO> children) {
        if (bookmark) {
            throw new UnsupportedOperationException("Bookmarks do not support adding child nodes");
        }
        this.children = children;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    public String getVirtualFilePath() {
        return virtualFilePath;
    }

    public void setVirtualFilePath(String virtualFilePath) {
        this.virtualFilePath = virtualFilePath;
    }

    public boolean isBookmark() {
        return bookmark;
    }

    public void setBookmark(boolean bookmark) {
        this.bookmark = bookmark;
    }

    @Override
    public String toString() {
        return name;
    }

    public String getLineContentHash() {
        return lineContentHash;
    }

    public void setLineContentHash(String lineContentHash) {
        this.lineContentHash = lineContentHash;
    }

    public String getLineContext() {
        return lineContext;
    }

    public void setLineContext(String lineContext) {
        this.lineContext = lineContext;
    }

    public static String generateLineHash(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return content.hashCode() + ""; // 降级方案
        }
    }
}
