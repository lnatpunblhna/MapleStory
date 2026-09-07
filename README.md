# MapleStory CMS079（教育向私服）

本仓库为 [aoaostar/MapleStory](https://github.com/aoaostar/MapleStory) 的 fork，上游为 aoaostar 整理的 **CMS079 / RoyMS** 服务端（冒险岛中国版 v.079）。感谢原作者 **aoaostar**。

仅供学习私服架构与登录/频道切换协议；不含作弊工具，也不含任何 Nexon 官方客户端二进制。

---

## 运行说明

### 目录结构（顶层）

| 路径 | 作用 |
|------|------|
| `start.sh` | 启动入口：用捆绑 JRE 跑 `bin/maple.jar` |
| `bin/maple.jar` | 已编译服务端 |
| `jdk/` | 捆绑 JDK/JRE（`jdk/release` → Java **1.7.0**） |
| `config/` | `server.properties` / `db.properties` 等 |
| `scripts/` | JS 脚本 + `scripts/wz` 资源 |
| `src/` | Java 源码（与 jar 对应） |
| `ms_20210813_234816.sql.gz` | MySQL 初始库（gzip） |
| `logs/` | 运行日志 |

### 数据库

1. 准备 MySQL，字符集建议 UTF-8。
2. 解压并导入：`gunzip -c ms_20210813_234816.sql.gz | mysql -u... -p...`
3. 编辑 `config/db.properties`（默认示例）：
   - `url = jdbc:mysql://127.0.0.1:3306/maple?...`
   - `username` / `password`（仓库默认 `maple` / `maple`，请改掉）

### 服务端配置要点（`config/server.properties`）

- `RoyMS.IP`：对外宣告 IP（客户端重定向用）
- `RoyMS.LPort`：登录端口（默认 **9595**）
- `RoyMS.Port`：频道基址相关；单频道实际监听多为 `RoyMS.Port{N}`，未配置时回退 **2524+N**
- `RoyMS.CSPort`：商城（默认 **8600**）
- `RoyMS.Count`：频道数量（默认 6，上限代码内限制 10）
- `RoyMS.AutoRegister`：自动注册
- `RoyMS.Exp` / `Meso` / `Drop` 等：倍率

### 启动

在仓库根目录：

```bash
chmod +x start.sh
./start.sh
```

等价于：

```bash
./jdk/jre/bin/java -cp ./bin/maple.jar -server \
  -DhomePath=./config/ -DscriptsPath=./scripts/ -DwzPath=./scripts/wz \
  -Xms512m -Xmx2048m \
  server.Start
```

入口类：`src/server/Start.java`（依次启动 World → LoginServer → ChannelServer → CashShopServer）。

**环境**：Linux x86_64、捆绑 JRE 1.7、MySQL。Windows/GUI 控制台可通过 `RoyMS.loadGui=true` 尝试（非本 fork 重点）。

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
- **建议不动**：频道内玩法、`scripts/`、WZ；只替换登录进程或在其前加一层代理。
- **配置**：对外 IP/端口仍以 `config/server.properties` 为准，避免客户端重定向到错误地址。

---

## 许可与声明

- 上游整理：[aoaostar/MapleStory](https://github.com/aoaostar/MapleStory)
- 教育用途私服源码；请遵守当地法律，勿用于商业或侵害第三方权益。
