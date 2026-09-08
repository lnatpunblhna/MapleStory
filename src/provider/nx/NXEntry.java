package provider.nx;

import provider.MapleDataEntity;
import provider.MapleDataEntry;

/**
 * 目录骨架里的一个条目。size/checksum/offset 对 NX 没有意义（原 WZ-XML 实现里也一直是 0），
 * 保留是因为 MapleDataEntry 接口要求。
 */
public class NXEntry implements MapleDataEntry {

    private final String name;
    private final int size;
    private final int checksum;
    private int offset;
    private final MapleDataEntity parent;

    public NXEntry(final String name, final int size, final int checksum, final MapleDataEntity parent) {
        this.name = name;
        this.size = size;
        this.checksum = checksum;
        this.parent = parent;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public int getChecksum() {
        return this.checksum;
    }

    @Override
    public int getOffset() {
        return this.offset;
    }

    @Override
    public MapleDataEntity getParent() {
        return this.parent;
    }
}
