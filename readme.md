> ## ⚠️ This is an INDEPENDENT project — read this first / 请注意：这是独立项目
>
> **This repository is NOT the original MusicHud.** It started as a community backport of the original mod to **Minecraft 1.20.1 (Forge + Fabric)**, and is now developed **independently** — it no longer tracks the upstream project, and its version numbering was restarted at `0.1.0`. The upstream project targets 1.21.x / NeoForge and is maintained by its original author.
>
> - **Upstream / 原作者仓库:** https://github.com/Ephern/MusicHud — [Modrinth](https://modrinth.com/mod/music-hud) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/music-hud)
> - **This project / 本项目:** https://github.com/Qy14172945/MusicHud (branch `1.20.1-forge`)
>
> 🚫 **DO NOT report any bug, crash or compatibility issue of THIS build to the original author, and do not open pull requests against the upstream repository.** This is a separate, independent codebase now; upstream is only referenced for attribution. Report anything you hit in this project's [Issues](https://github.com/Qy14172945/MusicHud/issues) instead.
>
> 🚫 **本版本出现的任何 bug 都请勿向原作者反馈，也请不要向上游仓库提交 PR。** 本仓库现已是独立维护的代码库，上游仅作为出处说明。请到[本仓库的 Issues](https://github.com/Qy14172945/MusicHud/issues) 反馈。
>
> **Scope of this project / 本项目的定位**
> - Targets **Minecraft 1.20.1 only** (Forge 47.1.37 + Fabric 0.16.10). It does **not** work on 1.21.x, and the 1.21.x branches of this repo are not what gets built here.
> - Tuned and tested against the **DeceasedCraft (亡者世界)** modpack; that is the primary support target. Other packs are "best effort".
> - Requires **ModernUI 3.12.0 for 1.20.1** — do not mix it with the 1.21.x ModernUI builds.
> - **Version numbering is independent of upstream** and restarted at `0.1.0`. A `0.1.x` / `0.x` release from this project corresponds to no upstream version at all; the last build that still carried an upstream number was `1.2.16`.

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

## Changelog / 更新日志

See [CHANGELOG.md](CHANGELOG.md) for what changed in each release, including the root cause of every fix.

各版本的改动与修复原因见 [CHANGELOG.md](CHANGELOG.md)（中文，含根因说明）。

Latest release: **0.1.2** — see [Releases](https://github.com/Qy14172945/MusicHud/releases).

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
- Customizable HUD layout
- More music platform support (QQ Music, Kugou Music)

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

#### QQ Music API (optional, separate from the NetEase API)
QQ Music support needs **its own** API server, which is a **separate Node.js process** and is **not** managed by MusicHUD - you have to run it yourself (there is no prebuilt binary).

> Note: this backport adds the QQ Music provider. It is not a drop-in for the NetEase endpoints - login is QR-code only, and there is no per-word lyric, no scrobble and no search-by-content-type support.

- **Repository / self-hosting instructions:** https://github.com/sansenjian/qq-music-api
- Requirements: **Node.js >= 20.17** (or Docker).
- Default port: **3200** (`http://localhost:3200`).
- If you change the port or deploy it on another machine, modify the `qqApiBaseUrl` property in `/config/music_hud-server.toml` (or set it in the in-game config screen).

---
## CN version description

> ## ⚠️ 请注意：这是独立项目，不是原版
>
> **本仓库不是原作。** 它最初是 MusicHUD 的社区**低版本移植版**，把原 mod 移植回 **Minecraft 1.20.1（Forge + Fabric）**；现在已经**独立发展**，不再跟随上游，版本号也从 `0.1.0` 重新独立计数。上游项目面向 1.21.x / NeoForge，由原作者维护。
>
> - **原作者仓库：** https://github.com/Ephern/MusicHud — [Modrinth](https://modrinth.com/mod/music-hud) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/music-hud)
> - **本项目仓库：** https://github.com/Qy14172945/MusicHud （分支 `1.20.1-forge`）
>
> 🚫 **本版本出现的任何 bug 都请不要到原作者处反馈，也请不要向上游仓库提交 PR。** 本仓库现已是独立维护的代码库，上游仅作为出处说明；请到[本仓库的 Issues](https://github.com/Qy14172945/MusicHud/issues) 反馈，并附上崩溃日志 / `latest.log`。
>
> **本项目的定位**
> - **仅支持 Minecraft 1.20.1**（Forge 47.1.37 + Fabric 0.16.10），在 1.21.x 上无法运行；本仓库的 1.21.x 分支不参与本版本构建。
> - 主要面向并针对 **亡者世界（DeceasedCraft）** 整合包调优与测试，其它整合包属于"尽力兼容"。
> - 需要 **1.20.1 版的 ModernUI 3.12.0**，不要与 1.21.x 的 ModernUI 混用。
> - **版本号独立计数**，从 `0.1.0` 重新开始，与上游版本序列没有任何对应关系；最后一个仍沿用上游编号的构建是 `1.2.16`。

#### 图形化的全场景（单人/多人游戏）音乐模组/插件，设计上不修改任何游戏机制

## 相关链接
> 下面的 MC 百科 / 第三方插件链接指向的是**原作相关页面**，介绍的是上游 1.21.x 项目，不是本 1.20.1 移植版。

[MC 百科](https://www.mcmod.cn/class/23688.html)

[第三方 Bukkit 插件实现 1](https://github.com/Shiroiame-Kusu/MusicHud-Bukkit) (1.0.5 stable -)

[第三方 Bukkit 插件实现 2](https://github.com/MOPELotus/MusicHud-Paper) (1.1.4 hotfix +，目前已部分合并至主仓库)

> 注意：本移植版只构建 **mod 版（Fabric + Forge）**，插件版不属于本分支，也不会在这里构建。

## 更新日志

各版本的改动与每项修复的根因见 [CHANGELOG.md](CHANGELOG.md)。

最新版本：**0.1.2**，见 [Releases](https://github.com/Qy14172945/MusicHud/releases)。

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
- 可自定义的 HUD 布局
- 更多音乐平台支持(QQ音乐, 酷狗音乐)

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

##### 自动部署（客户端）
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

#### QQ 音乐 API（可选，与网易云 API 相互独立）
QQ 音乐支持需要**另外单独**部署一个 API 服务器，它是一个**独立的 Node.js 进程**，**不由 MusicHUD 托管**，需要你自行运行（官方没有提供预编译二进制）。

> 注意：QQ 音乐是移植版新增的音乐平台支持，它的接口与网易云并不通用 —— 只支持扫码登录，且没有逐字歌词、没有 scrobble（听歌记录上报）、搜索也没有按内容类型过滤的参数。

- **仓库地址 / 自建说明：** https://github.com/sansenjian/qq-music-api
- 环境要求：**Node.js >= 20.17**（或使用 Docker）
- 默认端口：**3200**（`http://localhost:3200`）
- 如果改过端口或部署在其它机器上，需要修改 `/config/music_hud-server.toml` 中的 `qqApiBaseUrl` 属性（也可以在游戏内配置界面里改）