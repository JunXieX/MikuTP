# MikuTP

## 声明

本项目为 MikuMC 服务器原创插件，公开给大众免费使用，MikuMC 服务器与作者 JunXieX 享有项目著作权，本项目非开源项目，请注意。

MikuMC 系列插件交流群：1105054380

## 简介

MikuTP 是一套面向 Paper / Folia / Velocity 的多功能传送插件：家、地标、传送请求、随机传送、位置回溯，以及完整的跨服传送体系。所有菜单基于 MC 原生对话框（Dialog）实现，同时提供可点击聊天菜单回退。

## 功能总览

- **家**：`/sethome` 保存、`/home` 秒回，数量上限按权限伸缩，跨服务器照样回家
- **地标**：`/setwarp` 建立全服共享的传送点，按服务器独立管理
- **传送请求**：`/tpa` / `/tpahere` 弹出原生对话框，对方可**接受 / 拒绝 / 屏蔽 10 分钟**，防骚扰体系完整（永久屏蔽、全屏蔽、屏蔽列表）
- **随机传送（/wild）**：环形均匀取样 + 群系标签过滤（默认避开海洋/河流/海滩）+ 安全落点检测，绝不把你丢进海里或岩浆上
- **位置回溯**：`/back` 回到上次传送出发的位置、`/dback` 回到死亡地点、`/outtp` 管理员直达玩家最后下线位置
- **管理员强制传送**：`/otp` 直达任意玩家身边、`/otph` 把玩家拉到身边、`/otph all [子服名]` 一键拉取全网玩家
- **传送预热**：可配置的倒计时与移动/受击取消，管理员与付费玩家可豁免
- **PlaceholderAPI**：`%mikutp_...%` 系列变量可用于计分板、聊天插件等

## 环境要求

| 项目 | 要求 |
|---|---|
| 后端服务端 | Paper 26.2+ 或 Folia 26.2+（只面向最新版 API，不提供旧版本兼容分支） |
| Java | 25 |
| 跨服代理 | Velocity 4.2+（仅跨服模式需要） |
| 客户端 | 原生对话框需 MC 1.21.6+；插件不会自动探测客户端版本，旧版客户端需**手动**把 `dialogs.enabled` 设为 `false` 才会回退为可点击聊天菜单 |

首次启动会自动从 Maven Central 下载 SQLite / HikariCP 运行库，需要服务器能访问外网。

## 安装

### 单服务器（默认，零依赖）

1. 把 `MikuTP-Paper-1.7.0.jar` 放入 `plugins/`，重启。
2. 完成。数据存储在 `plugins/MikuTP/data.db`（SQLite），无需任何外部服务。

### 跨服网络（Velocity）

1. 每台后端服务器安装 `MikuTP-Paper`，代理安装 `MikuTP-Velocity`。
2. **设置共享密钥（必做）**：跨服消息走的是插件消息通道，而该通道同样能被改造过的客户端访问，因此必须用密钥区分「真实代理」与「伪造数据」。两端填同一个值：
   - 每台后端的 `plugins/MikuTP/config.yml`：
     ```yaml
     sync:
       mode: VELOCITY       # 开启跨服同步
       token: "请填写一串随机字符串"
     cross_server:
       server_id: survival  # 必须与 velocity.toml 中的服务器名完全一致
     ```
   - 代理首次启动会生成 `plugins/MikuTP/config.properties`，把其中的 `token` 改成**完全相同**的值。
3. 依次重启全部后端。此后家、玩家档案、屏蔽列表自动全网同步；`/tpa`、`/home`、`/back`、`/otp` 全部支持跨服。

> **部署硬性约定**：`cross_server.server_id` 必须与 `velocity.toml` 中该后端的服务器名**完全一致，且全网唯一**。跨服传送（`/home`、`/back`、`/otp` 等）依赖代理按服务器名路由；一旦重名，代理无法把玩家送到正确目标，后端之间还会把对方的同步事件误判为自身回声而丢弃。
>
> 插件只能检测"本机仍在使用默认值 `server`"这一种情况，并会在启动与 `/mtp reload` 时告警；**两台后端使用相同的非默认 id 无法由单个后端发现**（它看不到别人的配置），只能靠部署规范避免——请确保每台后端的 id 与其 `velocity.toml` 服务器名一一对应。

> 密钥留空时：插件会拒绝所有跨服同步消息并在控制台报错（安全默认）；单服务器模式（`mode: NONE`）下完全不受影响。

数据安全：本地 SQLite 是唯一事实源，代理只承担同步。代理短暂重启不影响本地功能，恢复后自动对齐全网数据，不会丢数据。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/home [名称]`、`/homes` | 回家 / 打开家列表 | mikutp.home |
| `/sethome [名称]` | 保存当前位置为家 | mikutp.home.set |
| `/delhome [名称]` | 删除家 | mikutp.home.delete |
| `/warp [名称]`、`/warps` | 前往地标 / 列表 | mikutp.warp |
| `/setwarp`、`/delwarp` | 创建 / 删除地标 | mikutp.warp.manage |
| `/tpa [玩家]` | 请求传送到对方身边 | mikutp.tpa |
| `/tpahere [玩家]` | 邀请对方过来 | mikutp.tpa |
| `/tpaccept`、`/tpdeny` | 接受 / 拒绝（别名 /tpyes、/tpno） | mikutp.tpa |
| `/tpatoggle` | 开关接收所有请求 | mikutp.tpa |
| `/tpblock`、`/tpunblock` | 屏蔽该玩家的传送请求 / 解除（/tpignore）。屏蔽时长取 `tpa.block_duration_minutes`，0 或负数表示永久 | mikutp.tpa |
| `/wild`（/rtp） | 随机传送 | mikutp.wild |
| `/back` | 返回上次传送位置 | mikutp.back |
| `/dback` | 返回死亡地点 | mikutp.dback |
| `/otp <玩家>` | 强制传送到玩家身边 | mikutp.otp |
| `/otph <玩家>`、`/otph all [子服]` | 强制拉取玩家 / 全部玩家 | mikutp.otp |
| `/outtp <玩家>` | 去玩家最后下线的位置 | mikutp.otp |
| `/mtp reload \| resync \| permissions \| info` | 管理命令（别名 /mikutp） | mikutp.admin |

**不带参数时的行为**：菜单型命令（`/home`、`/delhome`、`/warp`、`/delwarp`、`/setwarp`、`/tpa`、`/tpahere`）会弹出对应的对话框菜单（`dialogs.enabled: false` 时改为可点击聊天菜单）；`/tpblock`、`/tpunblock` 不带参数时列出当前屏蔽名单；`/wild`、`/back`、`/dback`、`/tpatoggle` 不带参数时直接执行对应动作。

**热重载边界**：`/mtp reload` 只重载 `config.yml` 与语言文件；**命令名/别名的改动需要重启服务器**才生效。

## 权限

全部节点在 `paper-plugin.yml` 中声明，服主可直接输入 `/mtp permissions` 查看完整清单后在 LuckPerms 中配置。默认值：基础功能全员可用；`mikutp.warp.manage`、`mikutp.otp`、`mikutp.admin` 仅 OP；`mikutp.bypass.warmup`、`mikutp.bypass.cooldown`、`mikutp.homes.unlimited` 默认无人拥有（适合做会员特权）。

## PlaceholderAPI 占位符

`%mikutp_homes_used%`、`%mikutp_homes_max%`、`%mikutp_homes_left%`、`%mikutp_warps%`、`%mikutp_tpa_enabled%`、`%mikutp_cooldown_home%`、`%mikutp_cooldown_warp%`、`%mikutp_cooldown_tpa%`、`%mikutp_cooldown_wild%`、`%mikutp_cooldown_back%`、`%mikutp_server_id%`

**未知值统一返回空串**：`%mikutp_tpa_enabled%` 在开关尚未同步到本服时为空；`%mikutp_homes_used%`、`%mikutp_homes_max%`、`%mikutp_homes_left%` 在本后端尚未加载该玩家数据时同样为空。这样计分板/聊天插件不会把「未知」误显示成 0 或「开」。

## 常见问题

**玩家收不到对话框？**
客户端版本低于 1.21.6（例如经 ViaVersion 进来的老版本客户端）无法渲染 Dialog。把 `dialogs.enabled` 改为 `false` 即可整体回退为可点击的聊天菜单。

**能在更旧的服务端版本上运行吗？**
不能保证。插件面向最新版 API 编译（`api-version: '26.2'`），Paper 与 Folia 都需要 26.2 或更高；项目不写旧版本兼容分支，请先升级服务端。

**配置文件写错了会怎样？**
- **启动时解析失败**：插件会拒绝启用（不会回落到内置默认值——默认的 `table_prefix` 会打开另一个数据库，等于把原有数据"弄丢"），并在控制台打印具体原因；修好 `config.yml` 后重启服务器即可。
- **`/mtp reload` 解析失败**：保留当前运行配置、不做任何部分应用，并向执行者如实回报失败；具体键名与原因见控制台。

**跨服同步不生效，控制台提示 token？**
两端 token 必须完全一致：后端 `config.yml` 的 `sync.token` 与代理 `plugins/MikuTP/config.properties` 的 `token`；任一为空都会拒绝同步，这是防止伪造同步消息的安全默认。

**代理重启会有什么影响？**
本地功能（家、地标、单服传送、随机传送）完全正常；跨服传送暂停，代理恢复后插件自动重新对齐全网数据，无需人工干预。

**跨服请求/传送有延迟？**
事件经代理推送，通常毫秒级到达；跨服传送还包含可配置的预热倒计时与服务器切换时间，属于正常现象。

## 已知限制与规模建议

- **跨服全量同步的带宽随库大小线性增长**：一次全网 resync 的数据量约等于「家 + 档案 + 位置 + 屏蔽 + 墓碑」的总行数 × 每行事件大小 × 后端数。按约 5000 个家 / 5000 份档案估算，6 个后端时一次全网 resync 约 **190MB** 下行（3 个后端约 **49MB**）。超大库建议分服消化，或控制家/档案规模。
- **状态新旧以"最后写入时间"判定**：要求各后端系统时钟通过 NTP 保持同步；时钟严重漂移时，旧状态可能覆盖新状态。
- **代理重启会丢失在途数据**：待传送交接与离线信箱保存在代理内存中，代理重启会丢失；后端恢复后会自动重新对齐已落库数据。关服瞬间的下线位置记录也可能来不及落库（`/outtp` 到的位置偶有偏差）。

## 技术亮点

- 原生 Dialog 菜单 + 可点击聊天菜单双通道，所有文案可自定义
- 完全兼容 Folia：全程区域化调度器 + `teleportAsync`，无主线程假设
- 跨服消息带 HMAC 签名校验，插件消息通道无法被改造过的客户端伪造
- 本地 SQLite 为唯一事实源，Velocity 代理做事件扇出与路由，玩家加入时自动重新对齐全网状态，不丢任何本地数据
- 全网状态回填按批事务写入，状态量大时不会打满数据库连接
- 零打包依赖：运行库全部由服务端按需自动下载
