package server.life;

import database.DatabaseConnection;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import provider.MapleData;
import provider.MapleDataDirectoryEntry;
import provider.MapleDataFileEntry;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import provider.WzXML.MapleDataType;
import tools.FileoutputUtil;
import tools.Pair;
import tools.StringUtil;

public class MapleLifeFactory
{
    private static final MapleDataProvider data;
    private static final MapleDataProvider stringDataWZ;
    private static final MapleDataProvider etcDataWZ;
    private static final MapleData mobStringData;
    private static final MapleData npcStringData;
    private static final MapleData npclocData;
    private static final Map<Integer, String> npcNames;
    private static final Map<Integer, MapleMonsterStats> monsterStats;
    private static final Map<Integer, Integer> NPCLoc;
    private static final Map<Integer, List<Integer>> questCount;
    
    public static AbstractLoadedMapleLife getLife(final int id, final String type) {
        if (type.equalsIgnoreCase("n")) {
            return getNPC(id);
        }
        if (type.equalsIgnoreCase("m")) {
            return getMonster(id);
        }
        System.err.println("Unknown Life type: " + type + "");
        return null;
    }
    
    public static int getNPCLocation(final int npcid) {
        if (MapleLifeFactory.NPCLoc.containsKey(npcid)) {
            return MapleLifeFactory.NPCLoc.get(npcid);
        }
        final int map = MapleDataTool.getIntConvert(Integer.toString(npcid) + "/0", MapleLifeFactory.npclocData, -1);
        MapleLifeFactory.NPCLoc.put(npcid, map);
        return map;
    }
    
    public static void loadQuestCounts() {
        if (MapleLifeFactory.questCount.size() > 0) {
            return;
        }
        for (final MapleDataDirectoryEntry mapz : MapleLifeFactory.data.getRoot().getSubdirectories()) {
            if (mapz.getName().equals("QuestCountGroup")) {
                for (final MapleDataFileEntry entry : mapz.getFiles()) {
                    final int id = Integer.parseInt(entry.getName().substring(0, entry.getName().length() - 4));
                    final MapleData dat = MapleLifeFactory.data.getData("QuestCountGroup/" + entry.getName());
                    if (dat != null && dat.getChildByPath("info") != null) {
                        final List<Integer> z = new ArrayList<Integer>();
                        for (final MapleData da : dat.getChildByPath("info")) {
                            z.add(MapleDataTool.getInt(da, 0));
                        }
                        MapleLifeFactory.questCount.put(id, z);
                    }
                    else {
                        System.out.println("null questcountgroup");
                    }
                }
            }
        }
        final Connection con = DatabaseConnection.getConnection();
        try (final PreparedStatement ps = con.prepareStatement("SELECT * FROM wz_npcnamedata ORDER BY `npc`");
             final ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MapleLifeFactory.npcNames.put(rs.getInt("npc"), rs.getString("name"));
            }
        }
        catch (SQLException ex) {
            System.out.println("Failed to load npc name data. " + ex);
            FileoutputUtil.outputFileError("logs/数据库异常.txt", ex);
        }
        System.out.println("共加载NPC：" + MapleLifeFactory.npcNames.size());
    }
    
    public static List<Integer> getQuestCount(final int id) {
        return MapleLifeFactory.questCount.get(id);
    }
    
    public static MapleMonster getMonster(final int mid) {
        MapleMonsterStats stats = MapleLifeFactory.monsterStats.get(mid);
        if (stats == null) {
            MapleData monsterData = MapleLifeFactory.data.getData(StringUtil.getLeftPaddedStr(Integer.toString(mid) + ".img", '0', 11));
            if (monsterData == null) {
                return null;
            }
            MapleData monsterInfoData = monsterData.getChildByPath("info");
            // link 提前取：下面的属性回退和后面找 fly/move 都要用同一个值。
            // 原版 Mob.wz 里 403 只怪带 link，其中 402 只自带完整属性、link 只用于复用动画；
            // 只有 9501016 的 info 里除了 link 什么都没有，属性得整体从被链接的怪身上取。
            final int link = MapleDataTool.getIntConvert("link", monsterInfoData, 0);
            if (link != 0 && monsterInfoData.getChildByPath("maxHP") == null) {
                final MapleData linkedData = MapleLifeFactory.data.getData(StringUtil.getLeftPaddedStr(link + ".img", '0', 11));
                if (linkedData != null && linkedData.getChildByPath("info") != null) {
                    monsterInfoData = linkedData.getChildByPath("info");
                }
            }
            stats = new MapleMonsterStats();
            stats.setHp(MapleDataTool.getIntConvert("maxHP", monsterInfoData));
            stats.setMp(MapleDataTool.getIntConvert("maxMP", monsterInfoData, 0));
            stats.setExp(MapleDataTool.getIntConvert("exp", monsterInfoData, 0));
            // 9999999（隐形 dummy 怪）没有 level，给默认 1
            stats.setLevel((short)MapleDataTool.getIntConvert("level", monsterInfoData, 1));
            stats.setRemoveAfter(MapleDataTool.getIntConvert("removeAfter", monsterInfoData, 0));
            stats.setrareItemDropLevel((byte)MapleDataTool.getIntConvert("rareItemDropLevel", monsterInfoData, 0));
            stats.setFixedDamage(MapleDataTool.getIntConvert("fixedDamage", monsterInfoData, -1));
            stats.setOnlyNormalAttack(MapleDataTool.getIntConvert("onlyNormalAttack", monsterInfoData, 0) > 0);
            stats.setBoss(MapleDataTool.getIntConvert("boss", monsterInfoData, 0) > 0 || mid == 8810018 || mid == 9410066 || (mid >= 8810118 && mid <= 8810122));
            stats.setExplosiveReward(MapleDataTool.getIntConvert("explosiveReward", monsterInfoData, 0) > 0);
            stats.setFfaLoot(MapleDataTool.getIntConvert("publicReward", monsterInfoData, 0) > 0);
            stats.setUndead(MapleDataTool.getIntConvert("undead", monsterInfoData, 0) > 0 || mid == 9700004 || mid == 9700009 || mid == 9700010);
            stats.setName(MapleDataTool.getString(mid + "/name", MapleLifeFactory.mobStringData, "MISSINGNO"));
            stats.setBuffToGive(MapleDataTool.getIntConvert("buff", monsterInfoData, -1));
            stats.setFriendly(MapleDataTool.getIntConvert("damagedByMob", monsterInfoData, 0) > 0);
            stats.setExplosiveReward(MapleDataTool.getIntConvert("explosiveReward", monsterInfoData, 0) > 0);
            stats.setNoDoom(MapleDataTool.getIntConvert("noDoom", monsterInfoData, 0) > 0);
            stats.setFfaLoot(MapleDataTool.getIntConvert("publicReward", monsterInfoData, 0) > 0);
            stats.setCP((byte)MapleDataTool.getIntConvert("getCP", monsterInfoData, 0));
            stats.setPoint(MapleDataTool.getIntConvert("point", monsterInfoData, 0));
            stats.setDropItemPeriod(MapleDataTool.getIntConvert("dropItemPeriod", monsterInfoData, 0));
            stats.setPhysicalDefense((short)MapleDataTool.getIntConvert("PDDamage", monsterInfoData, 0));
            stats.setMagicDefense((short)MapleDataTool.getIntConvert("MDDamage", monsterInfoData, 0));
            stats.setEva((short)MapleDataTool.getIntConvert("eva", monsterInfoData, 0));
            final boolean hideHP = MapleDataTool.getIntConvert("HPgaugeHide", monsterInfoData, 0) > 0 || MapleDataTool.getIntConvert("hideHP", monsterInfoData, 0) > 0;
            final MapleData selfd = monsterInfoData.getChildByPath("selfDestruction");
            if (selfd != null) {
                stats.setSelfDHP(MapleDataTool.getIntConvert("hp", selfd, 0));
                stats.setSelfD((byte)MapleDataTool.getIntConvert("action", selfd, -1));
            }
            else {
                stats.setSelfD((byte)(-1));
            }
            final MapleData firstAttackData = monsterInfoData.getChildByPath("firstAttack");
            if (firstAttackData != null) {
                // NX 把 WZ 的 float 和 double 合并成一种类型，所以这里要一起判
                if (firstAttackData.getType() == MapleDataType.FLOAT
                        || firstAttackData.getType() == MapleDataType.DOUBLE) {
                    stats.setFirstAttack(Math.round(MapleDataTool.getFloat(firstAttackData)) > 0);
                }
                else {
                    stats.setFirstAttack(MapleDataTool.getInt(firstAttackData) > 0);
                }
            }
            if (stats.isBoss() || isDmgSponge(mid)) {
                if (hideHP || monsterInfoData.getChildByPath("hpTagColor") == null || monsterInfoData.getChildByPath("hpTagBgcolor") == null) {
                    stats.setTagColor(0);
                    stats.setTagBgColor(0);
                }
                else {
                    stats.setTagColor(MapleDataTool.getIntConvert("hpTagColor", monsterInfoData));
                    stats.setTagBgColor(MapleDataTool.getIntConvert("hpTagBgcolor", monsterInfoData));
                }
            }
            final MapleData banishData = monsterInfoData.getChildByPath("ban");
            if (banishData != null) {
                stats.setBanishInfo(new BanishInfo(MapleDataTool.getString("banMsg", banishData), MapleDataTool.getInt("banMap/0/field", banishData, -1), MapleDataTool.getString("banMap/0/portal", banishData, "sp")));
            }
            final MapleData reviveInfo = monsterInfoData.getChildByPath("revive");
            if (reviveInfo != null) {
                final List<Integer> revives = new LinkedList<Integer>();
                for (final MapleData bdata : reviveInfo) {
                    // 9300295/9300296 的 revive 项是字符串形式的怪物 id，getIntConvert 会 parse
                    revives.add(MapleDataTool.getIntConvert(bdata));
                }
                stats.setRevives(revives);
            }
            final MapleData monsterSkillData = monsterInfoData.getChildByPath("skill");
            if (monsterSkillData != null) {
                int i = 0;
                final List<Pair<Integer, Integer>> skills = new ArrayList<Pair<Integer, Integer>>();
                while (monsterSkillData.getChildByPath(Integer.toString(i)) != null) {
                    skills.add(new Pair<Integer, Integer>(MapleDataTool.getInt(i + "/skill", monsterSkillData, 0), MapleDataTool.getInt(i + "/level", monsterSkillData, 0)));
                    ++i;
                }
                stats.setSkills(skills);
            }
            decodeElementalString(stats, MapleDataTool.getString("elemAttr", monsterInfoData, ""));
            // link 已在上面取过（属性回退可能已经把 monsterInfoData 换成被链接的怪，
            // 那样这里再读就取不到了），这里直接复用
            if (link != 0) {
                monsterData = MapleLifeFactory.data.getData(StringUtil.getLeftPaddedStr(link + ".img", '0', 11));
            }
            for (final MapleData idata : monsterData) {
                if (idata.getName().equals("fly")) {
                    stats.setFly(true);
                    stats.setMobile(true);
                    break;
                }
                if (!idata.getName().equals("move")) {
                    continue;
                }
                stats.setMobile(true);
            }
            byte hpdisplaytype = -1;
            if (stats.getTagColor() > 0) {
                hpdisplaytype = 0;
            }
            else if (stats.isFriendly()) {
                hpdisplaytype = 1;
            }
            else if (mid >= 9300184 && mid <= 9300215) {
                hpdisplaytype = 2;
            }
            else if (!stats.isBoss() || mid == 9410066) {
                hpdisplaytype = 3;
            }
            stats.setHPDisplayType(hpdisplaytype);
            MapleLifeFactory.monsterStats.put(mid, stats);
        }
        return new MapleMonster(mid, stats);
    }
    
    public static void decodeElementalString(final MapleMonsterStats stats, final String elemAttr) {
        for (int i = 0; i < elemAttr.length(); i += 2) {
            stats.setEffectiveness(Element.getFromChar(elemAttr.charAt(i)), ElementalEffectiveness.getByNumber(Integer.valueOf(String.valueOf(elemAttr.charAt(i + 1)))));
        }
    }
    
    private static boolean isDmgSponge(final int mid) {
        switch (mid) {
            case 8810018:
            case 8810118:
            case 8810119:
            case 8810120:
            case 8810121:
            case 8810122:
            case 8820009:
            case 8820010:
            case 8820011:
            case 8820012:
            case 8820013:
            case 8820014: {
                return true;
            }
            default: {
                return false;
            }
        }
    }
    
    public static MapleNPC getNPC(final int nid) {
        String name = MapleLifeFactory.npcNames.get(nid);
        if (name == null) {
            name = MapleDataTool.getString(nid + "/name", MapleLifeFactory.npcStringData, "MISSINGNO");
            MapleLifeFactory.npcNames.put(nid, name);
        }
        if (name.indexOf("Maple TV") != -1) {
            return null;
        }
        return new MapleNPC(nid, name);
    }
    
    static {
        data = MapleDataProviderFactory.getDataProvider(new File(System.getProperty("wzPath") + "/Mob.wz"));
        stringDataWZ = MapleDataProviderFactory.getDataProvider(new File(System.getProperty("wzPath") + "/String.wz"));
        etcDataWZ = MapleDataProviderFactory.getDataProvider(new File(System.getProperty("wzPath") + "/Etc.wz"));
        mobStringData = MapleLifeFactory.stringDataWZ.getData("Mob.img");
        npcStringData = MapleLifeFactory.stringDataWZ.getData("Npc.img");
        npclocData = MapleLifeFactory.etcDataWZ.getData("NpcLocation.img");
        npcNames = new HashMap<Integer, String>();
        monsterStats = new HashMap<Integer, MapleMonsterStats>();
        NPCLoc = new HashMap<Integer, Integer>();
        questCount = new HashMap<Integer, List<Integer>>();
    }
}
