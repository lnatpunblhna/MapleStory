package provider.nx;

import provider.MapleDataEntity;
import provider.MapleDataFileEntry;

/**
 * 一个 .img 节点。名字带 .img 后缀，与原 WZ-XML 实现一致
 * （调用方依赖这点，例如 MapleLifeFactory.loadQuestCounts 会去掉末尾 4 个字符）。
 */
public class NXFileEntry extends NXEntry implements MapleDataFileEntry {

    private int offset;

    public NXFileEntry(final String name, final int size, final int checksum,
                       final MapleDataEntity parent) {
        super(name, size, checksum, parent);
    }

    @Override
    public int getOffset() {
        return this.offset;
    }

    @Override
    public void setOffset(final int offset) {
        this.offset = offset;
    }
}
