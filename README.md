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
| `scripts/` | JS 脚本 + `scripts/wz` 资源 |
| `src/` | Java 源码（与 jar 对应） |
| `ms_20210813_234816.sql.gz` | MySQL 初始库（gzip） |
| `logs/` | 运行日志 |

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
- **建议不动**：频道内玩法、`scripts/`、WZ；只替换登录进程或在其前加一层代理。
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

## 许可与声明

- 上游整理：[aoaostar/MapleStory](https://github.com/aoaostar/MapleStory)
- 教育用途私服源码；请遵守当地法律，勿用于商业或侵害第三方权益。
