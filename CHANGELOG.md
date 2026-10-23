# 更新日志 / Changelog

本项目的版本号独立计数，从 `0.1.0` 重新开始，不再沿用上游 MusicHud 的 `1.2.x` 序列。
Version numbering is independent of upstream and restarted at `0.1.0`.

---

## 0.1.3 — 2026-10-23

### 修复：BiomeBeats / Lost Beats 的条件音乐仍能绕过抑制，MusicHud 播放时两首歌同时响

**症状.** 装了 BiomeBeats 的整合包里，MusicHud 正在播放时，BiomeBeats 的环境/条件音乐依然会自己响起来，两路音乐同时出声。0.1.2 已经加了 BiomeBeats 的抑制钩子，但那版钩错了位置。

**根因.** 0.1.2 的 `BiomeBeatsMusicMixin` 只在 `MusicManager.play(MusicTrack)` 上取消调用。但那个方法的调用者只有"外部点名要播某一首"的场景（配置界面预览、手动选歌）。真正让环境音乐自动播起来的是另一条路径：

```
MusicManager.playNext(boolean)          ← 条件变化时自己挑下一首
  └─ offset 149: JavaStreamPlayer.play(MusicTrack)   ← 直接调，完全绕过 MusicManager.play
       └─ JavaStreamPlayer.openPlay(MusicTrack)      ← 真正喂给 StreamPlayer 并起声的地方
            └─ StreamPlayer.open(...).play()         ← JavaSound，从不经过 SoundEngine
```

`playNext` 是被 Lost Beats 的 `SmartMusicManager` 通过 `setCurrentMusicTracks(...)` 驱动的（`setCurrentMusicTracks` 在偏移 56 处调 `playNext`），也就是说**条件音乐自动切歌时，`MusicManager.play` 一次都不会被调用**，原有的 HEAD 取消点根本不会触发。

**修复.** 新增 `BiomeBeatsStreamPlayerMixin`，把钩子下沉到 `io.github.maki99999.biomebeats.music.statemachine.JavaStreamPlayer` —— 这是该模组所有播放入口的唯一汇合点：`MusicManager.play`、`playNext`、状态机（`IdleState` / `FadeInState` / `MusicState` / `WaitingBreakState` / `PausedState`）以及 Lost Beats 的过渡系统最终都落在这里。

```java
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.music.statemachine.JavaStreamPlayer",
       priority = 2000, remap = false)
public class BiomeBeatsStreamPlayerMixin {
    @Inject(method = "play",     at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    @Inject(method = "openPlay", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
}
```

两个注入点都保留，理由不同：`play` 覆盖"点名要播某首"、`openPlay` 覆盖"状态机自己起声"。任一被未来版本改名时 `require = 0` 让它降级成"抑制失效"，而不是让整合包启动崩溃。

**同时修掉的隐患.** `ExternalMusicSuppressor.stopExternalMusic()` 原先在 `biomeBeatsMusicManager == null` 时直接 return。而当首播走的是 `playNext`（而非 `play`）时，从未有人把 manager 实例传进来，导致"停掉正在响的那首"这一半静默失效。现在增加了 `resolveBiomeBeatsMusicManager()` 回退：读该模组的 `Constants.MUSIC_MANAGER` 公有静态字段，失败则一次性缓存 `biomeBeatsManagerResolveUnavailable` 不再重试。

**行为保持不变.** 阻断仍然是**条件式**的：只有 `VanillaMusicSuppressor.shouldSuppress()`（即 MusicHud 正在播放/缓冲/重试）时才取消，MusicHud 一停，BiomeBeats 的下一次 `play`/`openPlay` 照常执行，条件音乐逻辑自行恢复，状态机没有被禁用或跳过。

---

## 0.1.2 — 2026-10-23

本版本文档只记录**修复**：三处问题都表现为"游戏启动后某个看似无关的位置报错"，根因却都在模组的 mixin 层，因此下面把机理写清楚，方便以后排查同类症状。

### 修复 1：mixin 插件用 `Class.forName` 探测可选模组，导致他人的 mixin 静默失效

**症状.** 玩家在装有 BiomeBeats 2.1.1 + Lost Beats 1.1.0 的整合包中，进入游戏后每 tick 崩溃一次：

```
java.lang.ClassCastException: class io.github.maki99999.biomebeats.music.MusicManager
  cannot be cast to class com.bawnorton.lost_beats.client.mixin.accessor.MusicManagerAccessor
    at com.bawnorton.lost_beats.client.music.SmartMusicManager.isWaiting(SmartMusicManager.java:176)
```

崩溃报告把嫌疑指向 biomebeats 与 lost_beats，日志里没有任何一行提到 MusicHud —— 但问题确实由 MusicHud 引入。

**根因.** `MusicHudMixinPlugin.shouldApplyMixin` 需要判断可选兼容目标是否存在，原先的实现是：

```java
private static boolean isClassPresent(String className) {
    tryLoad(className, Thread.currentThread().getContextClassLoader())
        || tryLoad(className, MusicHudMixinPlugin.class.getClassLoader());
}

private static boolean tryLoad(String className, ClassLoader classLoader) {
    Class.forName(className, false, classLoader);   // 缺陷在这里
    return true;
}
```

`Class.forName(name, false, loader)` 的 `false` 只跳过**静态初始化**，类依然会被 define 并 link。

关键在于这段代码运行在 Mixin 的 **prepare 阶段**（`MixinProcessor.prepareConfigs` → `MixinInfo.shouldApplyMixin`），此时被 define 的类**不会被 transformer 处理**。于是 `io.github.maki99999.biomebeats.music.MusicManager` 在 Lost Beats 的 `MusicManagerAccessor` 有机会应用之前就已经定型，之后针对它的**所有** mixin（不止 MusicHud 的，也包括 Lost Beats 自己的）全部静默跳过。Lost Beats 随后对 `Constants.MUSIC_MANAGER` 做 `(MusicManagerAccessor)` 强转，拿到的是没有该接口的原始类，于是每 tick 抛 CCE。

**为什么难查.** 没有任何一条日志指向 MusicHud：Mixin 不会报"此 mixin 被跳过"，栈帧落在完全无关的第三方模组里，崩溃报告 `Suspected Mods` 也只列 biomebeats 和 lost_beats。

**修复.** 探测改为读资源、不定义任何类：

```java
private static boolean isClassPresent(String className) {
    String resource = className.replace('.', '/') + ".class";
    return hasResource(resource, Thread.currentThread().getContextClassLoader())
        || hasResource(resource, MusicHudMixinPlugin.class.getClassLoader());
}

private static boolean hasResource(String resource, ClassLoader classLoader) {
    if (classLoader == null) {
        return false;
    }
    try {
        return classLoader.getResource(resource) != null;
    } catch (Throwable e) {
        return false;
    }
}
```

`ClassLoader.getResource` 只读字节，不 define、不 link，因此不会干扰任何其它模组的转换。

**顺带修正同类隐患.** 同一函数里对 `circuitlord.reactivemusic.ReactiveMusic`、`com.sonicether.soundphysics.SoundPhysicsMod` 的探测也走这条路径，一并修好。

**验证.** 编译后反编译打包产物 `MusicHudMixinPlugin.class`，确认常量池与指令中**再无 `Class.forName`**。

---

### 修复 2：压制第三方音乐模组时把它的整个 client tick 取消掉，导致播完无法恢复

**症状.** MusicHud 播放时 biomebeats 的群系/条件音乐确实被静音了 —— 但 MusicHud 停止后，整合包自己的音乐**不会自动回来**，必须重启游戏。

**根因.** `BiomeBeatsTickMixin` 原先这样写：

```java
@Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
private static void music_hud$suppressBiomeBeatsTick(CallbackInfo ci) {
    if (!VanillaMusicSuppressor.shouldSuppress()) {
        return;
    }
    ExternalMusicSuppressor.stopExternalMusic();
    ci.cancel();          // 整个 tick 被吃掉
}
```

`BiomeBeatsCommon.tick()` 不只是"决定放什么音乐"，它还是这些逻辑的唯一驱动点：

- 条件状态机 `ConditionManager.tick()`；
- 自己的按键检测（打开配置界面 / 调试 HUD）；
- 遍历 `Constants.TICK_LISTENERS` —— **Lost Beats 的条件音乐系统正注册在这个集合里**。

`ci.cancel()` 把这些全部冻结。状态机停在 MusicHud 抢占的那一刻，播放结束后也没有任何 tick 来推进它，所以原版逻辑再也回不来。

**修复.** 按职责拆成两个都带条件的钩子：

| 钩子 | 位置 | 作用 |
| --- | --- | --- |
| `BiomeBeatsMusicMixin` | `MusicManager.play(...)` 的 `HEAD`，`cancellable` | 拦在"声音还没提交到音频设备"的唯一时点。`shouldSuppress()` 为 false 时完全不干预 —— 模组从未被禁用 |
| `BiomeBeatsTickMixin` | `BiomeBeatsCommon.tick()` 的 `@At("RETURN")`，**不取消** | 对方的 tick（含 Lost Beats 的监听器）先完整跑完、自行决定该放什么，之后我们只把已经响起来的那个停掉 |

因为没有任何逻辑被跳过，MusicHud 一停，下一 tick 就没有东西可压，条件音乐逻辑自行恢复。

**注入顺序.** 两个 mixin 都标了 `priority = 2000`，即排在 biomebeats 与 Lost Beats 的默认优先级（1000）**之后**应用，而不是与它们争抢同一个方法的改写权。

注意方向容易写反：Mixin **优先级越低越先应用**。
`MixinInfo.compareTo` 返回 `this.priority - other.priority`（升序），`MixinApplicatorStandard.apply(SortedSet)` 用 `iterator()` 升序遍历后依次应用 —— 所以"排在它们之后"要的是**大于** 1000 的值。

**验证.** 反编译打包产物：`BiomeBeatsTickMixin.class` 常量池含 `tick` + `RETURN` + `Integer 2000` 且**无 `cancellable`**；`BiomeBeatsMusicMixin.class` 含 `play` + `HEAD` + `cancellable` + `Integer 2000`。

---

### 修复 3：工程文档记录两条踩坑结论

`AGENTS.md` 补充上述两条结论（mixin priority 方向、`Class.forName` 探测陷阱），并让根目录的调试用崩溃报告（`crash-*.txt`）不再出现在 `git status` 里（`.gitignore` 新增 `crash-*.txt`）。

---

### 兼容性

- 版本号独立计数，`0.1.2` 仍兼容 `0.1.0` 及以上的服务端（`leastCapable` 保持 `0.1.0` 不变 —— 补丁版本不应抬高协议下限，否则新版客户端会拒连仍是旧版的服务端）。
- Forge 客户端与服务端**必须同版本**，否则服务端握手会返回 `Connecting denied`（`accepted = false`）。升级客户端时请一并替换服务端 jar。

---

## 0.1.1 — 2026-10-18

### 修复：多人游戏中打开主界面时整个世界背景整片闪烁并留下鼠标残影

**症状.** 打开 Music HUD 界面后，只要移动鼠标，后面的游戏画面就一闪一闪，还会在原鼠标位置短暂出现指针残影。单人存档不明显，多人服务器上极其明显。

**根因.** Modern UI 打开界面时会对**主渲染目标**跑一次高斯模糊后处理：

```
pass1: minecraft:main -> swap   (BlurDir = [1.0, 0.0])
pass2: swap -> minecraft:main   (BlurDir = [0.0, 1.0])
```

单人世界里打开界面游戏会暂停，主渲染目标静止，读写同一个 buffer 也看不出问题；多人世界里世界仍在持续渲染，这个后处理每帧都在读写一个**正在被绘制**的目标，于是整屏闪烁。

Modern UI 为此提供了按界面关闭的正式钩子 `ScreenCallback.shouldBlurBackground()`（由 `BlurHandler.blur()` 读取），但本项目 `MusicHudScreen` 创建时传的 `callback = null`，走的永远是默认值 `true`。

**修复.** 给 `MusicHudScreen` 补一个 `DefaultCallback`，精确复现原先 `callback == null` 的全部行为，只额外覆盖 `shouldBlurBackground()`，让它读取新配置项 `mainScreenBlurBackground`（默认 **false**）：

- 新增配置项 `mainScreenBlurBackground`（中/英/繁中/粤语四套语言文件均已补齐）；
- 界面设置页新增"主界面背景模糊"开关，想保留模糊效果的玩家可以自行打开；
- 不修改 Modern UI 自己的 `blurEffect` 配置，其它模组的界面不受影响。

**附带说明.** 关掉 Modern UI 全局模糊（`config/ModernUI/client.toml` 里 `blurEffect = false`）也能消除该闪烁 —— 本修复只是让"仅限本模组的界面"生效，无需牺牲全局效果。

---

## 0.1.0 — 2026-10-18

首个独立版本。Minecraft 1.20.1 移植版正式从上游 MusicHud 的 `1.2.x` 序列中分离出来。

### 新增 / 改进

- 完成 1.20.1（Fabric + Forge）移植；
- HUD 渲染的 GL 状态保存/恢复，以及 UBO binding point 分配（改从 `GL_MAX_UNIFORM_BUFFER_BINDINGS - 1` 向下分配，避开其它所有向上增长的使用者）；
- 清理死代码与构建脚本冗余配置；
- QQ 音乐 provider（独立 Node.js 进程 `@sansenjian/qq-music-api`，默认端口 3200）。

### 修复

- `MainTest` 误用 1.20.4+ 才有的 `RegistryFriendlyByteBuf`（测试默认关闭，此前一直未暴露）；
- 死亡后音乐音量骤降到极低、切歌才恢复 —— 音源增益只有一个写入点且从不读回实际 `AL_GAIN`，被外部改动后永不纠正。改为双缓存（目标值 / 实际写入值）+ 每 tick 校验 `AL_GAIN`、`AL_SOURCE_RELATIVE`、`AL_ROLLOFF_FACTOR` 与被外部挂上的 `AL_DIRECT_FILTER`，漂移即自愈；
- 单人歌单被带到多人服务器 —— `MusicPlayerServerService` 是进程级单例但集成服务器每次新建，队列从未随服务器停止清空；`MusicService.initialSyncReceived` 一旦置 true 永不复位。现在服务端停止时 `reset()`，且每次外部连接前重置同步标志；
- 部分曲目永久停在加载中 —— 上一次修复引入的 `AL_INVALID_ENUM` 回归（OpenAL Soft 允许**写** `AL_DIRECT_FILTER` 但禁止**读**，探测读回污染了错误队列）。
