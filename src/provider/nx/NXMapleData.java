package provider.nx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import provider.MapleData;
import provider.MapleDataEntity;
import provider.WzXML.MapleDataType;

/**
 * 一个 NX 节点，语义与原先的 XMLDomMapleData 对齐。
 *
 * 与 XML 版的一个差别：NX 节点表里没有 parent 指针，所以父节点是在向下导航时
 * 一路带下来的。副作用是 getParent() 能一直走到文件根，而 XML 版走到 .img
 * 文件的根元素就返回 null（每个 .img 是独立文档）。这是超集，不影响现有调用方
 * ——代码里没有任何地方用 ".." 路径或 getFullDataPath。
 */
public class NXMapleData implements MapleData {

    private final NXFile file;
    private final int nodeId;
    private final NXMapleData parent;

    NXMapleData(final NXFile file, final int nodeId, final NXMapleData parent) {
        this.file = file;
        this.nodeId = nodeId;
        this.parent = parent;
    }

    @Override
    public String getName() {
        return this.file.nodeName(this.nodeId);
    }

    @Override
    public MapleDataEntity getParent() {
        return this.parent;
    }

    @Override
    public MapleDataType getType() {
        return this.file.mapleType(this.nodeId);
    }

    @Override
    public Object getData() {
        return this.file.nodeValue(this.nodeId);
    }

    @Override
    public List<MapleData> getChildren() {
        final int count = this.file.childCount(this.nodeId);
        final List<MapleData> ret = new ArrayList<MapleData>(count);
        final int first = this.file.firstChild(this.nodeId);
        for (int i = 0; i < count; ++i) {
            ret.add(new NXMapleData(this.file, first + i, this));
        }
        return ret;
    }

    @Override
    public MapleData getChildByPath(final String path) {
        final String[] segments = path.split("/");
        if (segments[0].equals("..")) {
            if (this.parent == null) {
                return null;
            }
            return this.parent.getChildByPath(path.substring(path.indexOf("/") + 1));
        }
        NXMapleData cur = this;
        for (int i = 0; i < segments.length; ++i) {
            final int child = this.file.findChild(cur.nodeId, segments[i]);
            if (child < 0) {
                return null;
            }
            cur = new NXMapleData(this.file, child, cur);
        }
        return cur;
    }

    @Override
    public Iterator<MapleData> iterator() {
        return this.getChildren().iterator();
    }

    @Override
    public String toString() {
        return this.getName() + " (" + this.getType() + ")";
    }
}
