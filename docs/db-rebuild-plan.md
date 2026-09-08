# 数据库重建计划（配合原版 CMS079 NX 数据）

> 2026-09-08 整理。前提：`wz/*.nx` 已从原版客户端转好，`tools.NXCheck` 通过。
> 目标：让 `ms_20210813_234816.sql.gz` 这份原私服库与原版数据对得上。

---

## 0. 结论先行

**这个库不能像 wz 那样"整体重建"。** 库里表分三类，只有 2 张是运行期读、且能从 NX 派生的；
其余要么是死表，要么是私服手工数据（掉落 / 商店 / 商城），后者只能"校验 + 清洗"，不能重建。

### A 类：运行期读，可从 NX 重建（2 张）

| 表 | 运行期读取点 | dumper | 库里现有行数 |
|---|---|---|---|
| `wz_npcnamedata` | `src/server/life/MapleLifeFactory.java:81` `SELECT * FROM wz_npcnamedata ORDER BY npc` | `tools.wztosql.DumpNpcNames`（Npc.nx + String.nx/Npc.img） | 1553 |
| `wz_oxdata` | `src/server/events/MapleOxQuizFactory.java:57,96` | `tools.wztosql.DumpOxQuizData`（Etc.nx） | 336 |

两个 dumper 都自带 `DELETE FROM`（`DumpNpcNames.java:39`、`DumpOxQuizData.java:36`），幂等可重跑。

### B 类：死表 / 死 dumper（不要跑）

- `wz_mobskilldata`、`wz_questdata`：除 `src/tools/wztosql/` 外全库零引用。
  运行期实际走 WZ：`src/server/life/MobSkillFactory.java:66` 读 `Skill.wz`，`src/server/quest/MapleQuest.java:127` 读 `Quest.wz`。
  → `DumpMobSkills` / `DumpQuests` 不用跑。
- `DumpItems` 写 `wz_itemdata` / `wz_itemequipdata` / `wz_itemadddata` / `wz_itemrewarddata`（`DumpItems.java:72-75, 446-449`），
  这 4 张表在 sql.gz 里**不存在**，运行期也无引用。它只有一个用处：当"合法 itemid 全集"的参考实现。
- `questactions` / `questrequirements`（`src/server/quest/MapleCustomQuest.java:18,39`）：库里是空表。
- 无运行期读取点的表：`drop_data_vana`、`cashshop_items`、`csitems`、`csequipment`、`fishing_rewards`。

### C 类：私服手工数据，无 dumper，只能校验清洗

| 表 | 运行期读取点 | 行数 | 需要校验的外键 |
|---|---|---|---|
| `drop_data` | `src/server/life/MapleMonsterInformationProvider.java:73`、`src/scripting/NPCConversationManager.java:1638` | 13557 | `dropperid`→Mob，`itemid`→Item，`questid`→Quest |
| `drop_data_global` | `MapleMonsterInformationProvider.java:41` | 13 | `itemid` |
| `reactordrops` | `src/scripting/ReactorScriptManager.java:64` | 871 | `reactorid`→Reactor，`itemid` |
| `shops` / `shopitems` | `src/server/MapleShop.java:35,47` | 97 / 3097 | `npcid`→Npc，`itemid` |
| `cashshop_modified_items` | `src/server/CashItemFactory.java:66` | 2645 | `itemid` |
| `wz_customlife` | `src/server/maps/MapleMapFactory.java:62` | 113 | `mid`→Map，`dataid`→Npc/Mob |

---

## 1. 环境现状（2026-09-08 本机 macOS）

| 项 | 状态 |
|---|---|
| Java | JDK 20.0.2（javac 可用） |
| MySQL 服务端 | **未安装**（`mysql`/`mysqld` 都没有；brew 只装了 `mysql-client`） |
| Docker | 有 CLI（28.4.0），但 context 是 `remote`，本机跑容器要先确认 |
| `lib/` | **空的**，只有 README.md。dumper 连库必须先放驱动 |
| `config/db.properties` | 已配好：`127.0.0.1:3306/maple`，账号 `maple/maple` |
| `wz/` | 10 个 `.nx` 齐全（String/Etc/Item/Character/Mob/Npc/Quest/Reactor/Skill/Map） |

---

## 2. 阶段步骤

### 阶段 0 · 前置

1. **装 MySQL**（二选一，未定）：
   - `brew install mysql@8.0 && brew services start mysql@8.0`
   - 或 Docker：`docker run -d --name maple-mysql -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 mysql:8.0`
   然后建库建号：
   ```sql
   CREATE DATABASE maple DEFAULT CHARSET utf8mb4;
   CREATE USER 'maple'@'%' IDENTIFIED BY 'maple';
   GRANT ALL ON maple.* TO 'maple'@'%';
   ```
2. **下载驱动**（macOS 没有 ps1 的等价脚本，手动）：
   ```bash
   curl -sSL --fail -o lib/mysql-connector-j-8.0.33.jar \
     https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar
   ```
3. **dumper 的运行方式**：所有 dumper 靠两个系统属性
   （`src/database/DatabaseConnection.java:106` 读 `server_property_db_path`；各 dumper 读 `wzPath`）：
   ```bash
   java -cp "lib/mysql-connector-j-8.0.33.jar:bin/maple.jar" \
     -DwzPath=./wz -Dserver_property_db_path=config/db.properties \
     tools.wztosql.DumpNpcNames
   ```

### 阶段 1 · 导入基线 + 回滚点

```bash
gunzip -c ms_20210813_234816.sql.gz | mysql -umaple -pmaple maple
mysqldump -umaple -pmaple maple > /tmp/maple-baseline.sql   # 清洗前的回滚点
```

### 阶段 2 · 重建 A 类两张表

```bash
CP="lib/mysql-connector-j-8.0.33.jar:bin/maple.jar"
java -cp "$CP" -DwzPath=./wz -Dserver_property_db_path=config/db.properties tools.wztosql.DumpNpcNames
java -cp "$CP" -DwzPath=./wz -Dserver_property_db_path=config/db.properties tools.wztosql.DumpOxQuizData
```

验收：`SELECT COUNT(*) FROM wz_npcnamedata` 应接近原版 1608 个 NPC（README 里记的加载数），而不是 1553。

### 阶段 3 · 建"原版合法 ID 全集"并出差集报告（核心，需新写工具）

新增 `src/tools/DbAudit.java`。枚举逻辑直接抄现成的：
- 物品：`src/tools/wztosql/MonsterDropCreator.java:706-746` `getAllItems()`，
  扫 String.nx 的 `Cash.img` / `Consume.img` / `Eqp.img→Eqp` / `Etc.img→Etc` / `Ins.img` / `Pet.img`
- 怪物：同文件 `:749` `getAllMobs()`
- NPC：`DumpNpcNames.dumpNpcNameData()` 的做法（String/Npc.img + Npc.nx 存在性）
- 地图 / 反应堆：Map.nx `Map/MapN/*.img`、Reactor.nx 根目录

写入临时表 `chk_valid_item(id)` / `chk_valid_mob(id)` / `chk_valid_npc(id)` / `chk_valid_map(id)` / `chk_valid_reactor(id)`，然后跑：

```sql
SELECT 'shopitems.itemid' t, COUNT(*) FROM shopitems s
  LEFT JOIN chk_valid_item v ON v.id = s.itemid WHERE v.id IS NULL
UNION ALL SELECT 'shops.npcid', COUNT(*) FROM shops s
  LEFT JOIN chk_valid_npc v ON v.id = s.npcid WHERE v.id IS NULL
UNION ALL SELECT 'drop_data.itemid', COUNT(*) FROM drop_data d
  LEFT JOIN chk_valid_item v ON v.id = d.itemid WHERE v.id IS NULL
UNION ALL SELECT 'drop_data.dropperid', COUNT(*) FROM drop_data d
  LEFT JOIN chk_valid_mob v ON v.id = d.dropperid WHERE v.id IS NULL
UNION ALL SELECT 'drop_data_global.itemid', COUNT(*) FROM drop_data_global d
  LEFT JOIN chk_valid_item v ON v.id = d.itemid WHERE v.id IS NULL
UNION ALL SELECT 'reactordrops.itemid', COUNT(*) FROM reactordrops r
  LEFT JOIN chk_valid_item v ON v.id = r.itemid WHERE v.id IS NULL
UNION ALL SELECT 'reactordrops.reactorid', COUNT(*) FROM reactordrops r
  LEFT JOIN chk_valid_reactor v ON v.id = r.reactorid WHERE v.id IS NULL
UNION ALL SELECT 'cashshop_modified_items.itemid', COUNT(*) FROM cashshop_modified_items c
  LEFT JOIN chk_valid_item v ON v.id = c.itemid WHERE v.id IS NULL
UNION ALL SELECT 'wz_customlife.mid', COUNT(*) FROM wz_customlife w
  LEFT JOIN chk_valid_map v ON v.id = w.mid WHERE v.id IS NULL;
```

**先只看报告，不删。**

### 阶段 4 · 按报告清洗

- 只删"引用不存在 ID"的行，不重建整表。
- 删前逐表备份：`CREATE TABLE xxx_bak AS SELECT * FROM xxx;`
- `wz_customlife` 是私服自己摆的 NPC，很可能整表指向已删的自定义地图，按 `mid` 结果决定是否清空。
- `shops` 删掉 npcid 不存在的行后，要级联删对应 `shopitems`。

### 阶段 5 · 验证

```bash
java -cp bin/maple.jar -DwzPath=./wz tools.NXCheck
./start.sh      # 看 logs/ 有没有 ClassCastException / NPE / 找不到 ID
```

进游戏抽查：NPC 开商店、打怪掉落、反应堆掉落、OX 问答。

---

## 3. 已定的取舍：`drop_data` 清洗，不重建

不用 `MonsterDropCreator` 重建掉落表，理由：

1. **原版 WZ 里没有掉落表**。`MonsterDropCreator.getChance()` 是启发式：按怪物等级 + boss 标记 + 硬编码倍率
   （`case 9400121: rate *= 5`，`:60-68`），数据源只是 `String.wz/MonsterBook.img` 的 `reward` 列表（`:98`）。这不是"恢复原版"。
2. 库里 13557 行 drop_data 有一批 `questid != 0` 的任务道具掉落（如 `(3,6230100,4031213,1,1,2097,200000)`），重建会全部丢掉，任务做不下去。
3. 它不写库，输出到文件 `数据库怪物爆率表.sql`（`:54`，append 模式，重复跑会累积）。
4. 开头 `System.console().readLine()`（`:44`），非交互终端下 `System.console()` 为 null 会 NPE。

掉落本来就是服务端侧数据，没有"原版"可回，"贴近原版 CMS079"的目标对这块不适用。

---

## 4. 进度

- [x] 现状核实（三类表分类、运行期读取点、行数）
- [x] 环境摸底（无 MySQL、lib 空、JDK 20）
- [ ] 阶段 0：装 MySQL（方式未定）、下驱动
- [ ] 阶段 1：导入基线
- [ ] 阶段 2：跑 DumpNpcNames / DumpOxQuizData
- [ ] 阶段 3：写 `DbAudit.java`，出差集报告
- [ ] 阶段 4：按报告清洗
- [ ] 阶段 5：验证
