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
| 后端服务端 | Paper 26.2+ 或 Folia 26.1.2+（MC 26.x） |
| Java | 25 |
| 跨服代理 | Velocity 4.2+（仅跨服模式需要） |
| Redis | 6.2+（仅跨服模式需要） |
| 客户端 | 原生对话框需 MC 1.21.6+，旧版客户端请关闭 `dialogs.enabled`（自动回退聊天菜单） |

首次启动会自动从 Maven Central 下载 SQLite / HikariCP / Jedis 运行库，需要服务器能访问外网。

## 安装

### 单服务器（默认，零依赖）

1. 把 `MikuTP-Paper-1.1.0.jar` 放入 `plugins/`，重启。
2. 完成。数据存储在 `plugins/MikuTP/data.db`（SQLite），无需 Redis、无需 MySQL。

### 跨服网络（Velocity）

1. 每台后端服务器安装 `MikuTP-Paper`，代理安装 `MikuTP-Velocity`。
2. 部署一台 Redis（建议设密码、仅监听内网）。
3. 每台后端编辑 `plugins/MikuTP/config.yml`：
   ```yaml
   sync:
     mode: REDIS          # 开启跨服同步
     redis:
       host: 你的Redis地址
       port: 6379
       password: "你的密码"
   cross_server:
     server_id: survival  # 必须与 velocity.toml 中的服务器名完全一致
   ```
4. 依次重启全部后端。此后家、玩家档案、屏蔽列表自动全网同步；`/tpa`、`/home`、`/back`、`/otp` 全部支持跨服。

数据安全：本地 SQLite 是唯一事实源，Redis 只承担同步。Redis 短暂宕机不影响本地功能，恢复后自动补同步，不会丢数据。

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
| `/tpblock`、`/tpunblock` | 永久屏蔽 / 解除（/tpignore） | mikutp.tpa |
| `/wild`（/rtp） | 随机传送 | mikutp.wild |
| `/back` | 返回上次传送位置 | mikutp.back |
| `/dback` | 返回死亡地点 | mikutp.dback |
| `/otp <玩家>` | 强制传送到玩家身边 | mikutp.otp |
| `/otph <玩家>`、`/otph all [子服]` | 强制拉取玩家 / 全部玩家 | mikutp.otp |
| `/outtp <玩家>` | 去玩家最后下线的位置 | mikutp.otp |
| `/mtp reload \| resync \| permissions \| info` | 管理命令（别名 /mikutp） | mikutp.admin |

所有命令不带参数时都会弹出对应的对话框菜单（可在配置中关闭）。

## 权限

全部节点在 `paper-plugin.yml` 中声明，服主可直接输入 `/mtp permissions` 查看完整清单后在 LuckPerms 中配置。默认值：基础功能全员可用；`mikutp.warp.manage`、`mikutp.otp`、`mikutp.admin` 仅 OP；`mikutp.bypass.warmup`、`mikutp.bypass.cooldown`、`mikutp.homes.unlimited` 默认无人拥有（适合做会员特权）。

## PlaceholderAPI 占位符

`%mikutp_homes_used%`、`%mikutp_homes_max%`、`%mikutp_homes_left%`、`%mikutp_warps%`、`%mikutp_tpa_enabled%`、`%mikutp_cooldown_home%`、`%mikutp_cooldown_warp%`、`%mikutp_cooldown_tpa%`、`%mikutp_cooldown_wild%`、`%mikutp_cooldown_back%`、`%mikutp_server_id%`

## 常见问题

**玩家收不到对话框？**
客户端版本低于 1.21.6（例如经 ViaVersion 进来的老版本客户端）无法渲染 Dialog。把 `dialogs.enabled` 改为 `false` 即可整体回退为可点击的聊天菜单。

**Folia 26.1.2 提示 api-version 过高拒绝加载？**
把 `paper-plugin.yml` 里的 `api-version: '26.2'` 改为 `'26.1'` 即可。

**Redis 宕机会有什么影响？**
本地功能（家、地标、单服传送、随机传送）完全正常；跨服传送暂停，Redis 恢复后插件自动补发积压的同步事件，无需人工干预。

**跨服请求/传送有延迟？**
事件经 Redis 推送，通常毫秒级到达；跨服传送还包含可配置的预热倒计时与服务器切换时间，属于正常现象。

## 技术亮点

- 原生 Dialog 菜单 + 可点击聊天菜单双通道，所有文案可自定义
- 完全兼容 Folia：全程区域化调度器 + `teleportAsync`，无主线程假设
- 本地 SQLite 为唯一事实源，Redis Stream + 消费组做增量同步，本地 outbox 保证 Redis 宕机期间事件不丢
- 零打包依赖：运行库全部由服务端按需自动下载
