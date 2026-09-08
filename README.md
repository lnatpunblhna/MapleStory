# MapleStory CMS079（教育向私服）

本仓库为 [aoaostar/MapleStory](https://github.com/aoaostar/MapleStory) 的 fork，上游为 aoaostar 整理的 **CMS079 / RoyMS** 服务端（冒险岛中国版 v.079）。感谢原作者 **aoaostar**。

仅供学习私服架构与登录/频道切换协议；不含作弊工具，也不含任何 Nexon 官方客户端二进制。

---

## 运行说明

### 目录结构（顶层）

| 路径 | 作用 |
|------|------|
| `start.ps1` / `start.sh` | Windows / Linux 启动（classpath 含 Connector/J 8） |
| `bin/maple.jar` | 已编译服务端 |
| `lib/` | `mysql-connector-j-8.0.33.jar`（用 `scripts/fetch-mysql-connector.ps1` 下载） |
| `config/` | `server.properties` / `db.properties` 等 |
| `scripts/` | JS 脚本 + 构建/工具脚本 |
| `wz/` | **游戏数据（`*.nx`）——不随仓库分发，需自行生成，见下节** |
| `src/` | Java 源码（与 jar 对应） |
| `ms_20210813_234816.sql.gz` | MySQL 初始库（gzip） |
| `logs/` | 运行日志 |

### 游戏数据（NX）

本仓库**不分发游戏数据**。服务端读 [NX (PKG4)](https://nxformat.github.io/) 格式，请自行从原版 CMS079 客户端的 WZ 转换。

### ⚠️ 转换器需要打补丁

[go-wztonx-converter](https://github.com/ErwinsExpertise/go-wztonx-converter) v0.1.1 **原样无法转换 CMS079**（实测 GMS/MSEA 同样不行）。三个 bug，补丁见 `scripts/wztonx-cms079.patch`：

| 位置 | 问题 |
|------|------|
| `wz/encryption.go` `tryExpandXorKey` | 判断条件写反（`len(xorKey) < length` 就 return），AES keystream 永远是空的 |
| `wz/fileblob.go` `readWZString` | AES XOR 被 `IsEncrypted(uol)` 挡住，而 `encryptedStrings` 从未被填充，恒为 false → 整条解密路径是死代码 |
| `wz/fileblob.go` `readWZString` | unicode 分支直接 `return string(characters)`，把 UTF-16LE 字节当 UTF-8 用 → 所有中文变乱码 |
| `wz/utils.go` `expandXorKey` | `len(currentXorKey)` 被减两次，增量扩展时 `make()` 拿到负长度会 panic |

补丁还加了 `-iv` 参数（`sea` / `gms` / `none`）。**CMS079 (ZMS) 用的是 SEA/KMS 的 IV `B9 7D 63 E9`** —— 这是实测出来的：用它解 `String.wz` 根目录，15 个条目全部还原成干净 ASCII（`Consume.img`、`Item.img`、`Skill.img` …），GMS 的 IV 和零 key 都是乱码。

```bash
git clone https://github.com/ErwinsExpertise/go-wztonx-converter
cd go-wztonx-converter
git apply /path/to/MapleStory/scripts/wztonx-cms079.patch
go build -o wztonx .

# 必须 --server：跳过 bitmap 与 audio
cd /path/to/客户端 && /path/to/wztonx --server --iv sea \
  String.wz Etc.wz Quest.wz Skill.wz Item.wz Character.wz Mob.wz Npc.wz Reactor.wz Map.wz
mkdir -p /path/to/MapleStory/wz && mv *.nx /path/to/MapleStory/wz/
```

只需要上面这 10 个 —— 服务端代码只会打开它们；`Effect/Morph/Sound/UI/Base/TamingMob/List` 没有任何引用。

`--server` 模式是有意的：本服务端运行期完全不读图像（`MapleDataTool.getImage` 无调用者，`CANVAS`/`MapleCanvas` 在 `src/provider/` 之外无引用），跳过后产物小得多（Reactor.wz 85MB → 0.6MB）。`NXFile` 也因此不带 LZ4 解码——真要用图像得先补上。

### 预检

启服前跑一遍，它会检查那 10 个 `.nx`、启动期会直接读的 `.img` 节点，并抽查中文解码：

```bash
java -cp bin/maple.jar -DwzPath=./wz tools.NXCheck
```

正常情况下会打印 `2000000  红色药水`。数据目录由 `-DwzPath` 指定（默认 `./wz`）。

私服自定义数据的注意点：`ms_20210813_234816.sql.gz` 里的商城 / 掉落 / 任务数据是配套原私服 WZ 的，换成原版数据后可能引用不存在的 ID。`src/tools/wztosql/` 下的 dumper 也走 `MapleDataProvider`，可用来从原版数据重建这些表。

### 数据库（MySQL 5.7 / 8.x）

1. 准备 MySQL 5.7 或 8.x，字符集建议 UTF-8 / utf8mb4。
2. 解压并导入：`gunzip -c ms_20210813_234816.sql.gz | mysql -u... -p...`
3. 下载驱动：`.\scripts\fetch-mysql-connector.ps1`（Maven Central → `lib/mysql-connector-j-8.0.33.jar`）
4. 编辑 `config/db.properties`：
   - `driverClassName = com.mysql.cj.jdbc.Driver`
   - URL 建议带：`useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`（5.7/8 通用）
   - `username` / `password`（请改掉默认）

**说明**：旧版 `com.mysql.jdbc.Driver` 连不上 MySQL 8 默认的 `caching_sha2_password`。换 Connector/J 8 后一般无需再改认证插件；若仍失败，可把账号改成 `mysql_native_password` 作兜底。

### 服务端配置要点（`config/server.properties`）

- `RoyMS.IP`：对外宣告 IP（客户端重定向用）
- `RoyMS.LPort`：登录端口（默认 **9595**）
- `RoyMS.Port`：频道基址相关；单频道实际监听多为 `RoyMS.Port{N}`，未配置时回退 **2524+N**
- `RoyMS.CSPort`：商城（默认 **8600**）
- `RoyMS.Count`：频道数量（默认 6，上限代码内限制 10）
- `RoyMS.AutoRegister`：自动注册
- `RoyMS.LoginBridge` / `RoyMS.LoginBridgePort`：本机 Go 登录桥（默认 `127.0.0.1:17979`）
- `RoyMS.Exp` / `Meso` / `Drop` 等：倍率

### 启动

**Windows（推荐）**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_202"  # 按本机改
.\scripts\fetch-mysql-connector.ps1
.\start.ps1
```

**Linux**

```bash
chmod +x start.sh
./start.sh
```

Classpath 为 `lib/mysql-connector-j-8.0.33.jar` + `bin/maple.jar`（lib 在前）。

入口类：`src/server/Start.java`（依次启动 World → LoginServer → ChannelServer → CashShopServer）。

**环境**：使用**本机自带的 JDK/JRE 8+**（仓库不再捆绑 `jdk/`）+ MySQL 5.7/8。启动脚本按 `JAVA_HOME` → PATH 上的 `java` 顺序查找；都找不到会直接报错。

---

## 登录相关扩展点

面向后续 **Go 登录壳**：只替换登录/选大区/选角，再把手柄交给现有 CMS079 频道服与原版客户端协议。

### 流程概览

1. TCP 连登录服 → Hello / AES IV（`MapleServerHandler.sessionOpened`）
2. `LOGIN_PASSWORD` → 账密校验 / 自动注册
3. 下发区服列表 → `SERVERLIST_REQUEST` / `SERVERSTATUS_REQUEST`
4. `CHARLIST_REQUEST` → 选频道 + 角色列表
5. `CHAR_SELECT` → `LoginServer.putLoginAuth` + `getServerIP` 重定向到频道端口
6. 客户端连频道 → `PLAYER_LOGGEDIN` → `InterServerHandler.Loggedin`

### 关键路径

| 职责 | 路径 |
|------|------|
| 进程入口 | `src/server/Start.java` |
| 登录 TCP 监听 | `src/handling/login/LoginServer.java`（`RoyMS.LPort`） |
| 会话 / 状态机 | `src/client/MapleClient.java` |
| 收包分发 | `src/handling/MapleServerHandler.java` → `handlePacket` |
| 登录业务 | `src/handling/login/handler/CharLoginHandler.java` |
| 登录成功后区服列表 | `src/handling/login/LoginWorker.java` |
| 自动注册 | `src/handling/login/handler/AutoRegister.java` |
| 频道服 | `src/handling/channel/ChannelServer.java` |
| 进图 / 换频 / 商城交接 | `src/handling/channel/handler/InterServerHandler.java` |
| 登录发包 | `src/tools/packet/LoginPacket.java` |
| 通用发包（含 `getServerIP`） | `src/tools/MaplePacketCreator.java` |
| 收包 opcode 表 | `src/recvops.properties` + `src/handling/RecvPacketOpcode.java` |
| 发包 opcode 表 | `src/sendops.properties` + `src/handling/SendPacketOpcode.java` |
| Mina 编解码 | `src/handling/mina/`（`MapleCodecFactory` 等） |
| 密码学辅助 | `src/client/LoginCrypto.java`、`LoginCryptoLegacy.java` |
| 角色移交缓存 | `src/handling/world/CharacterTransfer.java`、`World.java` |

### 关键登录 opcode（`recvops.properties`）

| 名称 | 值 | 处理 |
|------|-----|------|
| `LOGIN_PASSWORD` | `0x01` | `CharLoginHandler.login` |
| `SERVERLIST_REQUEST` | `0x02` | `ServerListRequest` |
| `LICENSE_REQUEST` | `0x03` | 同区服列表 |
| `SET_GENDER` | `0x04` | `SetGenderRequest` |
| `SERVERSTATUS_REQUEST` | `0x05` | `ServerStatusRequest` |
| `CHARLIST_REQUEST` | `0x09` | `CharlistRequest` |
| `CHAR_SELECT` | `0x0A` | `Character_WithoutSecondPassword`（频道 handoff） |
| `PLAYER_LOGGEDIN` | `0x0B` | 频道侧 `InterServerHandler.Loggedin` |
| `CHECK_CHAR_NAME` | `0x0C` | 创角名检查 |
| `CREATE_CHAR` | `0x11` | `CreateChar` |
| `CHANGE_CHANNEL` | `0x22` | `InterServerHandler.ChangeChannel` |

### Go 登录壳对接提示

- **必须兼容**：CMS079 Hello、MapleAESOFB IV、上述 opcode 与 `LoginPacket` / `getServerIP` 字节布局。
- **handoff 契约**：选角成功后 `LoginServer.putLoginAuth(charId, ip, tempIp, channel)`，客户端再以 `PLAYER_LOGGEDIN` 连目标频道；Go 壳若自管登录，需同样写入频道侧认可的 auth / `CharacterTransfer` 或保持 Java 登录服仅做 auth 表协作。
- **建议不动**：频道内玩法、`scripts/`、数据层；只替换登录进程或在其前加一层代理。
- **配置**：对外 IP/端口仍以 `config/server.properties` 为准，避免客户端重定向到错误地址。

---

## LoginBridge（Go 登录壳 · Phase 1）

本机回环 **HTTP JSON** 桥，供外部 Go 登录 UI：登录 → 区服/频道 → 角色列表 → 选角（调用现有 `LoginServer.putLoginAuth`）→ 取得频道 host/port，再启动原版 CMS079 客户端连频道。

### 配置（`config/server.properties`）

| 键 | 默认 | 说明 |
|----|------|------|
| `RoyMS.LoginBridge` | `true`（本 fork） | `false` 时不启动 |
| `RoyMS.LoginBridgePort` | `17979` | 仅绑定 **127.0.0.1**（永不 `0.0.0.0`） |

源码：`src/handling/login/bridge/`（JDK 内置 `com.sun.net.httpserver`）。`LoginServer.setOn()` 后启动；`shutdown`/`ShutdownServer` 时停止。

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 探活 |
| POST | `/api/login` | `{"username","password"}` → token |
| GET | `/api/worlds` | 世界/频道 |
| GET | `/api/characters?world=0` | 角色列表 |
| POST | `/api/select` | `{"characterId","channel"}` → putLoginAuth + host/port |

### curl

```bash
curl -s http://127.0.0.1:17979/health
TOKEN=$(curl -s -X POST http://127.0.0.1:17979/api/login -H 'Content-Type: application/json' -d '{"username":"demo","password":"demo"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s http://127.0.0.1:17979/api/worlds -H "Authorization: Bearer $TOKEN"
curl -s 'http://127.0.0.1:17979/api/characters?world=0' -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://127.0.0.1:17979/api/select -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"characterId":1,"channel":1}'
```

### 重建 jar（Windows）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_202"
.\scripts\rebuild-login-bridge.ps1
```

---

## 数据层（NX provider）

| 路径 | 作用 |
|------|------|
| `src/provider/nx/NXFile.java` | 打开 `.nx`（mmap + PKG4 头 + 节点/字符串表），实现 `MapleDataProvider` |
| `src/provider/nx/NXMapleData.java` | 单个节点，实现 `MapleData` |
| `src/provider/nx/NX{Entry,DirectoryEntry,FileEntry}.java` | `getRoot()` 的目录骨架 |
| `src/provider/MapleDataProviderFactory.java` | 入口；`.wz` → `.nx` 后缀替换、大小写不敏感查找、按路径缓存 provider |
| `src/tools/NXCheck.java` | 启服前的数据预检 |

调用方一处都不用改：43 个 `getDataProvider` 调用点仍然传 `.../Item.wz`，由 factory 换算成 `Item.nx`。

**NX 与 WZ-XML 的类型差异**（改动集中在这几处）：NX 只有 7 种节点类型，WZ 的 `short`/`int` 合并成 Int64、`float`/`double` 合并成 Double。原先 `MapleDataTool` 里 `(float)data.getData()` 这类精确装箱强转会直接 `ClassCastException`，已统一改成 `((Number)...).floatValue()`；运行期绕过 `MapleDataTool` 的直接强转只有 `MapleMapFactory` 的两处 `mobRate`，也一并改了。`MapleLifeFactory` 里判断 `firstAttack` 是否为 `FLOAT` 的分支加上了 `DOUBLE`。

另外 `go-wztonx-converter` 明确「Does NOT sort nodes」，保持 WZ 原始顺序，所以 `NXFile.findChild` 只能线性扫描，不能按 NX 规范假定兄弟节点有序。

### 换用原版数据后补的三处兼容（`MapleLifeFactory.getMonster`）

原私服那份 WZ 被改过、字段比原版齐，所以下面三处既有代码的脆弱点一直没暴露。**都与 NX 无关**，换回原版数据才会踩到：

| 怪物 | 问题 | 修法 |
|------|------|------|
| 9300295 / 9300296（迷宫恶树/树荫） | `info/revive/N` 是**字符串**形式的怪物 id，`getInt` 抛 `ClassCastException`（原来的 `(int)getData()` 同样会抛） | 改用 `getIntConvert`，它会 parse 字符串 |
| 9501016（妖怪书测试） | `info` 里只有一个 `link`，没有任何属性，`getIntConvert("maxHP", …)` 抛 NPE | `info` 缺 `maxHP` 且有 `link` 时，属性整体改从被链接的怪取 |
| 9999999（黄金蛋） | 没有 `level` 字段 | `getIntConvert("level", …, 1)` 给默认值 |

属性回退**只在 `maxHP` 缺失时触发**：原版 Mob.wz 里 403 只怪带 `link`，其中 402 只自带完整属性、`link` 只用来复用动画，它们不受影响（回归测试逐只核对过 maxHP 没被链接目标覆盖）。

改完后 **1614/1614 怪物、1608/1608 NPC 全部加载成功**。

### 重建 jar

```bash
./scripts/rebuild-nx-provider.sh          # macOS / Linux
```

```powershell
.\scripts\rebuild-nx-provider.ps1         # Windows
```

---

## 许可与声明

- 上游整理：[aoaostar/MapleStory](https://github.com/aoaostar/MapleStory)
- 教育用途私服源码；请遵守当地法律，勿用于商业或侵害第三方权益。
