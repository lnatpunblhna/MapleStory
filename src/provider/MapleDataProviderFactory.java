package provider;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import provider.nx.NXFile;

/**
 * 数据源入口。本服务端读 NX(PKG4) 格式，数据不随仓库分发，由使用者自己从原版客户端生成：
 *
 *   go-wztonx-converter --server /path/to/client/wz/
 *
 * 必须用 --server / -s 模式（跳过 bitmap 与 audio）——运行期完全不读图像：
 * MapleDataTool.getImage 没有任何调用者，CANVAS/MapleCanvas 在 provider 之外也没有引用。
 * 生成出的 *.nx 放到 -DwzPath 指向的目录即可。
 */
public class MapleDataProviderFactory {

    private static final String wzPath;

    /**
     * 按解析后的绝对路径缓存。调用方（SkillFactory、MapleItemInformationProvider 等）会为同一个
     * 文件反复调 getDataProvider，不缓存的话每次都要重新 mmap 一遍。
     */
    private static final Map<String, MapleDataProvider> providers = new HashMap<String, MapleDataProvider>();

    private static MapleDataProvider getWZ(final Object in) {
        if (!(in instanceof File)) {
            throw new IllegalArgumentException("Can't create data provider for input " + in);
        }
        final File resolved = resolveNX((File) in);
        final String key = resolved.getAbsolutePath();
        synchronized (providers) {
            MapleDataProvider provider = providers.get(key);
            if (provider == null) {
                provider = new NXFile(resolved);
                providers.put(key, provider);
            }
            return provider;
        }
    }

    /**
     * 把调用方给的 ".../Item.wz" 换算成 ".../Item.nx"。
     *
     * 全仓库有 43 处 getDataProvider 调用，路径字面量还混着大小写（如 fileInwzPath("string.wz")
     * 与 "/String.wz" 并存），所以在这里统一做后缀替换 + 大小写不敏感查找，
     * 调用方一处都不用改，顺带修掉了原先在大小写敏感文件系统上会踩的坑。
     */
    private static File resolveNX(final File in) {
        final String name = in.getName();
        final int dot = name.lastIndexOf('.');
        final String stem = (dot > 0) ? name.substring(0, dot) : name;
        final String target = stem + ".nx";

        File dir = in.getParentFile();
        if (dir == null) {
            dir = new File(MapleDataProviderFactory.wzPath);
        }

        final File exact = new File(dir, target);
        if (exact.isFile()) {
            return exact;
        }
        final File[] siblings = dir.listFiles();
        if (siblings != null) {
            for (final File f : siblings) {
                if (f.isFile() && f.getName().equalsIgnoreCase(target)) {
                    return f;
                }
            }
        }
        throw new RuntimeException("找不到 NX 数据文件 " + target + "（目录: " + dir.getAbsolutePath() + "）。\n"
                + "数据不随仓库分发，请自行从原版客户端生成后放进 -DwzPath 指向的目录：\n"
                + "  go-wztonx-converter --server /path/to/client/wz/");
    }

    public static MapleDataProvider getDataProvider(final Object in) {
        return getWZ(in);
    }

    /**
     * 保留给旧调用方；NX 在 --server 模式下没有图像数据，与 getDataProvider 等价。
     */
    public static MapleDataProvider getImageProvidingDataProvider(final Object in) {
        return getWZ(in);
    }

    public static File fileInwzPath(final String filename) {
        return new File(MapleDataProviderFactory.wzPath, filename);
    }

    static {
        wzPath = System.getProperty("wzPath", "wz");
    }
}
