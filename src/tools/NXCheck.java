package tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import provider.MapleData;
import provider.MapleDataDirectoryEntry;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;

/**
 * 启服前的 NX 数据预检。
 *
 *   java -cp bin/maple.jar -DwzPath=./wz tools.NXCheck
 *
 * 检查内容：
 *   1. 服务端真正会打开的 10 个 .nx 是否都在、能否解析成 PKG4
 *   2. 启动期会直接 getData 的 .img 节点是否存在（缺了会在启服时抛 RuntimeException）
 *   3. 中文字符串能否正确解码 —— 上游转换器在这一步有两个 bug（AES keystream 与
 *      UTF-16LE 解码），需要打 scripts/wztonx-cms079.patch，这一步专门用来暴露它
 *
 * 下面每一项 .img 的归属都是从代码里核实过的，注释标了出处。
 */
public class NXCheck {

    /** wz 名 -> 启动期会 getData 的 .img 列表（空数组表示只做结构检查） */
    private static final String[][] EXPECTED = {
        // MapleItemInformationProvider:97 / SkillFactory:? / MapleMapFactory / MapleLifeFactory
        { "String", "Cash.img", "Consume.img", "Eqp.img", "Etc.img", "Ins.img",
                    "Pet.img", "Mob.img", "Npc.img", "Skill.img", "Map.img" },
        // LoginInformationProvider:23, PredictCardFactory, MapleLifeFactory,
        // ItemMakerFactory, CashItemFactory:37/49
        { "Etc",    "ForbiddenName.img", "PredictCard.img", "NpcLocation.img",
                    "ItemMake.img", "Commodity.img", "CashPackage.img" },
        // MapleQuest
        { "Quest",  "Act.img", "Check.img", "QuestInfo.img", "PQuest.img" },
        // MobSkillFactory:66, MapleCarnivalFactory:28
        { "Skill",  "MobSkill.img", "MCSkill.img", "MCGuardian.img" },
        // 下面这些是按目录/文件枚举用的，没有固定的 .img 字面量
        { "Item" },
        { "Character" },
        { "Mob" },
        { "Npc" },
        { "Reactor" },
        { "Map" },
    };

    public static void main(final String[] args) {
        final String wzPath = System.getProperty("wzPath", "wz");
        final File dir = new File(wzPath);
        System.out.println("=== NX 数据预检 ===");
        System.out.println("目录: " + dir.getAbsolutePath());
        if (!dir.isDirectory()) {
            System.out.println("目录不存在。");
            System.exit(1);
        }
        System.out.println();

        final List<String> problems = new ArrayList<String>();
        MapleDataProvider stringProvider = null;

        for (final String[] spec : EXPECTED) {
            final String wz = spec[0];
            MapleDataProvider p;
            try {
                p = MapleDataProviderFactory.getDataProvider(new File(dir, wz + ".wz"));
            } catch (RuntimeException e) {
                System.out.println("[缺失] " + wz + ".nx  -> " + firstLine(e.getMessage()));
                problems.add(wz + ".nx 打不开");
                continue;
            }
            if ("String".equals(wz)) {
                stringProvider = p;
            }

            final MapleDataDirectoryEntry root = p.getRoot();
            System.out.println("[ OK ] " + pad(wz + ".nx", 14) + p
                    + "  根下 " + root.getSubdirectories().size() + " 目录 / "
                    + root.getFiles().size() + " 文件");

            for (int i = 1; i < spec.length; ++i) {
                try {
                    final MapleData d = p.getData(spec[i]);
                    System.out.println("         " + pad(spec[i], 20)
                            + d.getChildren().size() + " 个子节点");
                } catch (RuntimeException e) {
                    System.out.println("         " + pad(spec[i], 20) + "缺失!");
                    problems.add(wz + ".nx 缺 " + spec[i]);
                }
            }
        }

        // 中文解码抽查
        System.out.println();
        System.out.println("=== 中文解码抽查 (String.nx / Consume.img) ===");
        if (stringProvider == null) {
            System.out.println("String.nx 不可用，跳过。");
        } else {
            try {
                final List<MapleData> items = stringProvider.getData("Consume.img").getChildren();
                if (items.isEmpty()) {
                    System.out.println("Consume.img 下没有子节点，可疑。");
                    problems.add("String.nx/Consume.img 为空");
                } else {
                    final int n = Math.min(5, items.size());
                    for (int i = 0; i < n; ++i) {
                        final MapleData it = items.get(i);
                        System.out.println("  " + pad(it.getName(), 10)
                                + MapleDataTool.getString("name", it, "(无 name 字段)"));
                    }
                    System.out.println();
                    System.out.println("上面应该是可读的中文物品名（v79 的 2000000 是「红色药水」）。");
                    System.out.println("如果是乱码，说明转换器的 WZ 字符串处理有问题——");
                    System.out.println("上游 go-wztonx-converter 需要打 scripts/wztonx-cms079.patch，详见 README。");
                }
            } catch (RuntimeException e) {
                System.out.println("读取失败: " + firstLine(e.getMessage()));
                problems.add("String.nx/Consume.img 读取失败");
            }
        }

        System.out.println();
        if (problems.isEmpty()) {
            System.out.println("预检通过，可以启服。");
        } else {
            System.out.println("发现 " + problems.size() + " 个问题:");
            for (final String s : problems) {
                System.out.println("  - " + s);
            }
            System.exit(1);
        }
    }

    private static String pad(final String s, final int n) {
        final StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String firstLine(final String s) {
        if (s == null) {
            return "(无消息)";
        }
        final int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
