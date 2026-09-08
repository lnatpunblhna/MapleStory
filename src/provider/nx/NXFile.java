package provider.nx;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

import provider.MapleData;
import provider.MapleDataDirectoryEntry;
import provider.MapleDataProvider;
import provider.WzXML.MapleDataType;

/**
 * NX (PKG4) 格式的数据源，替代原先的 WZ-XML dump。
 *
 * 由 go-wztonx-converter 之类的工具从原版客户端 WZ 生成，建议用 --server 模式
 * （跳过 bitmap/audio 数据）——本服务端运行期完全不读图像，见 MapleDataProviderFactory。
 *
 * 关于并发：整个文件 mmap 后只做「绝对下标」读取（buf.getInt(i) 而非 buf.getInt()），
 * 因为 MappedByteBuffer 的相对读会改 position，多线程共享时会互相踩。
 */
public class NXFile implements MapleDataProvider {

    private static final int NODE_SIZE = 20;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File file;
    private final ByteBuffer buf;

    private final int nodeCount;
    private final int nodeOffset;
    private final int stringCount;
    private final int stringTableOffset;
    private final String[] stringCache;

    /** 有效根节点，见构造里的 layout 探测 */
    private final int rootNode;

    private NXDirectoryEntry rootEntry;

    public NXFile(final File in) {
        this.file = in;
        if (!in.isFile()) {
            throw new RuntimeException("NX 数据文件不存在: " + in.getAbsolutePath());
        }

        MappedByteBuffer mapped;
        try {
            final RandomAccessFile raf = new RandomAccessFile(in, "r");
            try {
                final FileChannel ch = raf.getChannel();
                final long size = ch.size();
                if (size < 52L) {
                    throw new RuntimeException("NX 文件太小，不可能是合法的 PKG4: " + in.getAbsolutePath());
                }
                if (size > Integer.MAX_VALUE) {
                    throw new RuntimeException("NX 文件超过 2GB，本读取器无法 mmap: "
                            + in.getAbsolutePath()
                            + "\n请用转换器的 --server / -s 模式重新生成（跳过 bitmap 与 audio 数据）。");
                }
                mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, size);
            } finally {
                // 关闭 channel 不会让已建立的映射失效
                raf.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("打开 NX 文件失败: " + in.getAbsolutePath(), e);
        }
        mapped.order(ByteOrder.LITTLE_ENDIAN);
        this.buf = mapped;

        if (buf.get(0) != (byte) 'P' || buf.get(1) != (byte) 'K'
                || buf.get(2) != (byte) 'G' || buf.get(3) != (byte) '4') {
            throw new RuntimeException("不是 NX(PKG4) 文件: " + in.getAbsolutePath());
        }

        this.nodeCount = buf.getInt(0x04);
        this.nodeOffset = checkedOffset(buf.getLong(0x08), "node block");
        this.stringCount = buf.getInt(0x10);
        this.stringTableOffset = checkedOffset(buf.getLong(0x14), "string offset table");

        if (nodeCount <= 0 || stringCount <= 0) {
            throw new RuntimeException("NX 头部 nodeCount/stringCount 非法: " + in.getAbsolutePath());
        }
        this.stringCache = new String[stringCount];

        // layout 探测：有的转换器把 WZ 自身当成一层节点（base -> "Item" -> "Consume" -> ...），
        // 有的直接把 WZ 根目录的条目挂在 base 下（base -> "Consume" -> ...）。
        // 原先的 XML dump 是后者，这里把前者透明地剥掉一层，两种产物都能用。
        int root = 0;
        if (childCount(0) == 1) {
            final String only = nodeName(firstChild(0));
            if (only.equalsIgnoreCase(baseName(in))) {
                root = firstChild(0);
                System.out.println("[NX] " + in.getName() + ": 剥掉外层节点 \"" + only + "\"");
            }
        }
        this.rootNode = root;
    }

    private int checkedOffset(final long off, final String what) {
        if (off < 0L || off > Integer.MAX_VALUE) {
            throw new RuntimeException("NX " + what + " 偏移超出可寻址范围 (" + off + "): "
                    + this.file.getAbsolutePath());
        }
        return (int) off;
    }

    private static String baseName(final File f) {
        final String n = f.getName();
        final int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ---------------- 节点表访问 ----------------

    private int nodePos(final int id) {
        if (id < 0 || id >= this.nodeCount) {
            throw new IndexOutOfBoundsException("NX 节点 id 越界: " + id + " / " + this.nodeCount);
        }
        return this.nodeOffset + id * NODE_SIZE;
    }

    String nodeName(final int id) {
        return getString(this.buf.getInt(nodePos(id)));
    }

    int firstChild(final int id) {
        return this.buf.getInt(nodePos(id) + 4);
    }

    int childCount(final int id) {
        return this.buf.getShort(nodePos(id) + 8) & 0xFFFF;
    }

    private int rawType(final int id) {
        return this.buf.getShort(nodePos(id) + 10) & 0xFFFF;
    }

    /**
     * 按名字找子节点，返回节点 id，找不到返回 -1。
     *
     * 只能线性扫描：NX 规范要求兄弟节点按名字 UTF-8 升序排列，但
     * go-wztonx-converter 明确「Does NOT sort nodes」，保持 WZ 原始顺序，
     * 所以不能二分。原先的 XML DOM 实现同样是线性扫描，没有性能回退。
     */
    int findChild(final int parentId, final String name) {
        final int first = firstChild(parentId);
        final int count = childCount(parentId);
        for (int i = 0; i < count; ++i) {
            if (name.equals(nodeName(first + i))) {
                return first + i;
            }
        }
        return -1;
    }

    String getString(final int id) {
        if (id < 0 || id >= this.stringCount) {
            throw new IndexOutOfBoundsException("NX 字符串 id 越界: " + id + " / " + this.stringCount);
        }
        // String 不可变，这里的竞态最坏结果是同一条字符串被解码两次，无需加锁
        final String cached = this.stringCache[id];
        if (cached != null) {
            return cached;
        }
        final int pos = checkedOffset(this.buf.getLong(this.stringTableOffset + id * 8), "string entry");
        final int len = this.buf.getShort(pos) & 0xFFFF;
        final byte[] raw = new byte[len];
        for (int i = 0; i < len; ++i) {
            raw[i] = this.buf.get(pos + 2 + i);
        }
        final String s = new String(raw, UTF8);
        this.stringCache[id] = s;
        return s;
    }

    // ---------------- 类型与取值 ----------------

    /**
     * NX 只有 7 种节点类型，比 WZ-XML 的类型集粗：short/int 合并成 Int64，
     * float/double 合并成 Double，uol 由转换器处理掉。映射见下。
     */
    MapleDataType mapleType(final int id) {
        switch (rawType(id)) {
            case 0:
                // WZ 里的 imgdir(PROPERTY) 和空节点(IMG_0x00) 在 NX 都是 type 0，
                // 按有无子节点区分。运行期没有任何地方判断这两者，见 README。
                return childCount(id) > 0 ? MapleDataType.PROPERTY : MapleDataType.IMG_0x00;
            case 1:
                return MapleDataType.INT;
            case 2:
                return MapleDataType.DOUBLE;
            case 3:
                return MapleDataType.STRING;
            case 4:
                return MapleDataType.VECTOR;
            case 5:
                return MapleDataType.CANVAS;
            case 6:
                return MapleDataType.SOUND;
            default:
                return MapleDataType.UNKNOWN_TYPE;
        }
    }

    Object nodeValue(final int id) {
        final int pos = nodePos(id) + 12;
        switch (rawType(id)) {
            case 1:
                // v79 的 WZ 没有 64 位整数，转换器只是把 short/int 一律加宽成 Int64，
                // 这里窄回 Integer，上层 MapleDataTool 才能按 Number 取值。
                return Integer.valueOf((int) this.buf.getLong(pos));
            case 2:
                return Double.valueOf(this.buf.getDouble(pos));
            case 3:
                return getString(this.buf.getInt(pos));
            case 4:
                return new Point(this.buf.getInt(pos), this.buf.getInt(pos + 4));
            default:
                // type 0 无值；type 5/6 是 bitmap/audio，--server 模式下没有数据，
                // 而本服务端运行期不读图像/音频（MapleDataTool.getImage 无调用者）。
                return null;
        }
    }

    // ---------------- MapleDataProvider ----------------

    @Override
    public MapleData getData(final String path) {
        MapleData node = new NXMapleData(this, this.rootNode, null);
        if (path != null && path.length() > 0) {
            node = node.getChildByPath(path);
        }
        if (node == null) {
            // 与原 XMLWZFile.getData 一致：找不到就抛，而不是返回 null
            throw new RuntimeException("Datafile " + path + " does not exist in "
                    + this.file.getAbsolutePath());
        }
        return node;
    }

    @Override
    public synchronized MapleDataDirectoryEntry getRoot() {
        if (this.rootEntry == null) {
            this.rootEntry = buildDirectory(this.rootNode, this.file.getName(), null);
        }
        return this.rootEntry;
    }

    /**
     * 复刻 XMLWZFile.fillMapleDataEntitys 的规则：名字以 .img 结尾的当「文件」且不再往下走，
     * 其余当「目录」递归。只建目录骨架，.img 内部按需读取。
     */
    private NXDirectoryEntry buildDirectory(final int nodeId, final String name,
                                            final NXDirectoryEntry parent) {
        final NXDirectoryEntry dir = new NXDirectoryEntry(name, 0, 0, parent);
        final int first = firstChild(nodeId);
        final int count = childCount(nodeId);
        for (int i = 0; i < count; ++i) {
            final int child = first + i;
            final String childName = nodeName(child);
            if (childName.endsWith(".img")) {
                dir.addFile(new NXFileEntry(childName, 0, 0, dir));
            } else {
                dir.addDirectory(buildDirectory(child, childName, dir));
            }
        }
        return dir;
    }

    @Override
    public String toString() {
        return "NXFile[" + this.file.getName() + ", nodes=" + this.nodeCount
                + ", strings=" + this.stringCount + "]";
    }
}
