> ## ⚠️ This is a FORK — read this first / 请注意：这是移植分支
>
> **This repository is NOT the original MusicHud.** It is a community backport of the original mod to **Minecraft 1.20.1 (Forge + Fabric)**, maintained specifically for the **DeceasedCraft (亡者世界)** modpack. The upstream project targets 1.21.x / NeoForge and is maintained by its original author.
>
> - **Upstream / 原作者仓库:** https://github.com/Ephern/MusicHud — [Modrinth](https://modrinth.com/mod/music-hud) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/music-hud)
> - **This fork / 本分支:** https://github.com/Qy14172945/MusicHud (branch `1.20.1-forge`)
>
> 🚫 **DO NOT report any bug, crash or compatibility issue of THIS build to the original author.** Anything you hit here is a backport artifact, not an upstream defect. Report it in this fork's [Issues](https://github.com/Qy14172945/MusicHud/issues) instead.
>
> 🚫 **本版本出现的任何 bug 都请勿向原作者反馈。** 本分支是 1.20.1 移植版，出现的问题基本来自移植与适配，与原作者无关。请到[本仓库的 Issues](https://github.com/Qy14172945/MusicHud/issues) 反馈。
>
> **Scope of this backport / 本移植分支的定位**
> - Targets **Minecraft 1.20.1 only** (Forge 47.1.37 + Fabric 0.16.10). It does **not** work on 1.21.x, and the 1.21.x branches of this repo are not what gets built here.
> - Tuned and tested against the **DeceasedCraft (亡者世界)** modpack; that is the primary support target. Other packs are "best effort".
> - Requires **ModernUI 3.12.0 for 1.20.1** — do not mix it with the 1.21.x ModernUI builds.
> - Version numbering continues from upstream (`1.2.15.1` → `1.2.16`). A `1.2.16` from this fork only describes this backport branch and has no relation to any identically numbered upstream release.

![Banner](
https://cdn.modrinth.com/data/7Rnb6oJr/images/20ba5cbcaf71e2ab70436963776e5f801fea44a7.png)
![Home View](https://cdn.modrinth.com/data/7Rnb6oJr/images/b1ab5a9d276489d9f2ef4ecce3fd318e58290553.png)

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Etern-34520/MusicHud)

#### A GUI-based, all situations (singleplayer/multiplayers) music mod/plugin designed with zero modifications to game mechanics

> Due to service scope provided by NetEase, this mod might not work well outside China

## Related Links
> The Modrinth and CurseForge pages below are the **original author's** and describe the upstream 1.21.x project, not this 1.20.1 backport.

[ModRinth](https://modrinth.com/mod/music-hud)

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/music-hud)

[Third-party Bukkit plugin 1](https://github.com/Shiroiame-Kusu/MusicHud-Bukkit) (1.0.5 stable -)

[Third-party Bukkit plugin 2](https://github.com/MOPELotus/MusicHud-Paper) (1.1.4 hotfix +, now partly merged into main repository)

> Note: this backport only builds the **mod edition (Fabric + Forge)**. The plugin editions are not part of this branch and are not built from here.

## Prerequisites
- **Minecraft 1.20.1** (this backport's only target)
- ModernUI **3.12.0 for 1.20.1** (only client)
- Forge Config API Port (only fabric)
- ~~Architectury API~~ (Mod edition, 1.2.0 and below)

## Features
- With graceful GUI, providing an in-game operation interface and a easily configurable HUD.
- Can read NetEase Cloud Music account playlists for convenient song requests.
- The server does not retain user data; it is only temporarily stored during login.
- Streamed playback with no redundant client-side caching.
- Staggered lyrics auto scroll inspired by Apple Music
- HUD dynamic fluid background
- Windows SMTC / Linux MPRIS (have not fully tested yet) support

## Functions
- Search for musics, playlists, albums and artists.
- Log in to NetEase Cloud Music account via QR code or SMS code to explore subscribed playlists, albums and artists.
- Connected mode: Synchronized server-wide play queue.
- Isolated mode: Do all works on client, enjoy music yourself.
- Configure idle playback sources for automatic random song switching when music player is idle.
- Display lyrics, requesting player (or avatar) in the HUD and user interface.

## Compatibility
- Currently specially supports to mute Reactive Music when MusicHUD is playing

## TO-DO List
- Customizable HUD layout [target 1.3.0/1.4.0]
- More music platform support (QQ Music, Kugou Music) [target 2.0.0]

## Usage

### Client
First of all, place the prerequisite mods and the MusicHud jar file into the mods folder.

#### Single-player or LAN Multi-player Host
All you need is to configure or deploy an API server.

#### Multiplayer (join Server or LAN Multi-player)
##### Connected mode
You don't need to configure or deploy an API server, but the server or LAN multiplayer host needs to configure or deploy MusicHUD and API server.

##### Isolated mode
All you need is to configure or deploy an API server.

### Server
All you need is to configure or deploy an API server.

### How to Deploy an API Server
There are 2 methods to deploy.
#### Deploy bound with mod (recommend for client)
> This method allows MusicHUD to manage lifecycle of api server.
>
> Due to some limitations, API Server may not be auto-closed when game exit abnormal (e.g., due to a crash)

##### Auto Deploy
You can find "Download API..." button in setting page, which will open a download guide, and download NetEase Cloud Music Enhanced from [a trusted repository](https://github.com/MOPELotus/api-enhanced) which automatically releases artifact powered by GitHub Actions to bypass login restrictions of accessing artifacts from GitHub Actions, with optional GH Proxy speedup

##### Manual Deploy
1. **Login to GitHub (Important, otherwise can NOT download artifacts)** and go to https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/actions/workflows/build-dev.yml
2. Click latest action runs in type "Build Artifacts" and download platform binary artifacts matches to your pc/server platform
3. Uncompress the binary executable file from ZIP file to ...
   - Mod edition: `{corepath}/music-hud/` and rename it to `api` (`api.exe` on Windows)
   - Plugin edition: `{corepath}/plugins/MusicHud/` and rename it to `api` (`api.exe` on Windows)
   - Or place it anywhere and modify config in game or option `serverApiBinaryExecutablePath` (absolute or relative path) in config file `/config/music_hud-common.toml`.

#### Deploy separately (recommend for server)
1. Deploy NetEase Cloud Music API Enhanced (https://github.com/neteasecloudmusicapienhanced/api-enhanced).
2. If not using the default port (3000) of NCM API Enhanced or deploying on another server, modify the `serverApiBaseUrl` property in the config file `/config/music_hud-server.toml`.

---
## CN version description

> ## ⚠️ 请注意：这是移植分支，不是原版
>
> **本仓库不是原作。** 它是 MusicHUD 的社区**低版本移植版**，把原 mod 移植回 **Minecraft 1.20.1（Forge + Fabric）**，并针对 **亡者世界（DeceasedCraft）** 整合包做了优化与适配。上游项目面向 1.21.x / NeoForge，由原作者维护。
>
> - **原作者仓库：** https://github.com/Ephern/MusicHud — [Modrinth](https://modrinth.com/mod/music-hud) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/music-hud)
> - **本移植仓库：** https://github.com/Qy14172945/MusicHud （分支 `1.20.1-forge`）
>
> 🚫 **本版本出现的任何 bug 都请不要到原作者处反馈。** 这里遇到的问题基本来自移植适配本身，与原作无关；请到[本仓库的 Issues](https://github.com/Qy14172945/MusicHud/issues) 反馈，并附上崩溃日志 / `latest.log`。
>
> **本移植分支的定位**
> - **仅支持 Minecraft 1.20.1**（Forge 47.1.37 + Fabric 0.16.10），在 1.21.x 上无法运行；本仓库的 1.21.x 分支不参与本版本构建。
> - 主要面向并针对 **亡者世界（DeceasedCraft）** 整合包调优与测试，其它整合包属于"尽力兼容"。
> - 需要 **1.20.1 版的 ModernUI 3.12.0**，不要与 1.21.x 的 ModernUI 混用。
> - 版本号沿用上游序列（`1.2.15.1` → `1.2.16`），但本移植版的 `1.2.16` 只代表本分支，与上游任何同号版本无关。

#### 图形化的全场景（单人/多人游戏）音乐模组/插件，设计上不修改任何游戏机制

## 相关链接
> 下面的 MC 百科 / 第三方插件链接指向的是**原作相关页面**，介绍的是上游 1.21.x 项目，不是本 1.20.1 移植版。

[MC 百科](https://www.mcmod.cn/class/23688.html)

[第三方 Bukkit 插件实现 1](https://github.com/Shiroiame-Kusu/MusicHud-Bukkit) (1.0.5 stable -)

[第三方 Bukkit 插件实现 2](https://github.com/MOPELotus/MusicHud-Paper) (1.1.4 hotfix +，目前已部分合并至主仓库)

> 注意：本移植版只构建 **mod 版（Fabric + Forge）**，插件版不属于本分支，也不会在这里构建。

## 前置依赖
- **Minecraft 1.20.1**（本移植版唯一目标）
- ModernUI **1.20.1 版 3.12.0**（仅客户端）
- Forge Config API Port （仅 Fabric）
- ~~Architectury API~~ （非插件版，1.2.0 以及更低版本）

## 特点
- 具有优雅的 GUI，提供游戏内操作界面，以及易配置的 HUD
- 可读取网易云账户歌单，方便点歌
- 服务端不保留用户数据，仅在登录时暂存
- 流式播放，无赘余的客户端缓存
- 受 Apple Music 启发的歌词交错滚动与逐字歌词
- HUD 动态流体背景
- Windows SMTC / Linux MPRIS（尚未充分测试） 支持

## 功能
- 搜索音乐、歌单、专辑和歌手
- 通过二维码或短信验证码登录网易云账户，查看收藏的歌单、专辑和歌手
- 连接模式：全服同步播放队列
- 隔离模式：在客户端上独立享受音乐
- 配置空闲播放源，播放器空闲时随机切歌
- 在 HUD 和用户界面中展示歌词，点歌玩家（或头像）

## 兼容
- 目前特殊支持在 MusicHUD 播放时静音 Reactive Music

## ~~大饼~~ 待办清单
- 可自定义的 HUD 布局 [目标 1.3.0/1.4.0]
- 更多音乐平台支持(QQ音乐, 酷狗音乐) [目标 2.0.0]

## 使用

### 客户端
首先在 mods 文件夹中放入前置 mod 和 MusicHUD 的 jar 文件

#### 单人模式 或 局域网联机主机
只需要配置/部署 API 服务器

#### 加入服务器 或 加入局域网世界
##### 连接模式
不需要配置/部署 API 服务器

但是需要服务器/局域网主机配置/部署 MusicHUD 和 API 服务器

##### 隔离模式
只需要配置/部署 API 服务器

### 服务端
只需要部署 API 服务器

### 如何部署 API 服务器
两种方法
#### 绑定在 mod 中（推荐客户端使用）
> 这个方法会让 MusicHUD 管理 API 服务器生命周期
>
> 由于一些限制，在游戏非正常退出时（如崩溃）无法自动结束进程

##### 自动部署（客户端，1.2.15+）
在设置页面中的 API 状态一栏中可找到“下载 API...”按钮，可打开一个下载指引，会从一个通过 GitHub Actions 自动发布 release 的[可信的分支仓库](https://github.com/MOPELotus/api-enhanced)下载 NetEase Cloud Music Enhanced 以绕过访问 GitHub Actions 工件的登录限制，可选使用 GH Proxy 加速下载

##### 手动部署
1. **登录到 GitHub (重要，否则无法下载工件)** 并跳转到 https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/actions/workflows/build-dev.yml
2. 在 actions 类型中选择 "Build Artifacts" 并进入最新的构建中根据你的平台下载对应的二进制构建产物
3. 解压下载得到的压缩包并将其中的二进制可执行文件放置到...
   - mod 版：{核心目录}/music-hud/ 并重命名为 `api` (对于 Windows 为 `api.exe`)
   - 插件版: {核心目录}/plugins/MusicHud/api(.exe)
   - 或者放在任意处并在游戏内或配置文件`/config/music_hud-common.toml`中修改选项 `serverApiBinaryExecutablePath` 对应(绝对/相对)目录

#### 独立部署（推荐服务端使用）
1. 部署 Netease Cloud Music API Enhanced (https://github.com/neteasecloudmusicapienhanced/api-enhanced)
2. 如果不使用 NCM API Enhanced 的默认端口 ( 3000 ) 或在其他服务器上部署，需要修改配置文件`/config/music_hud-server.toml`的 `serverApiBaseUrl` 属性