# 更新日志 / Changelog

本项目的版本号独立计数，从 `0.1.0` 重新开始，不再沿用上游 MusicHud 的 `1.2.x` 序列。
Version numbering is independent of upstream and restarted at `0.1.0`.

---

## 0.1.4 — 2026-10-24

### 修复：BiomeBeats / Lost Beats 的音乐又遮不住了（0.1.3 的相对回归）

**症状.** 0.1.1 里能压住的 BiomeBeats（含 Lost Cities 等环境/条件音乐）在 0.1.2 / 0.1.3 里又能自己响起来，MusicHud 播放时两路音乐同时出声。

**根因.** 0.1.3 的钩子全部挂在上层入口：`MusicManager.play(...)` 与 `JavaStreamPlayer.play(...)` / `openPlay(...)`。但播放器状态机里有一条**不经过这两个入口的起声路径**：

```
FadeInState.<init>(MusicTrack track)
    if (!Objects.equals(track, ctx.getCurrentSong())) { ctx.stopAndResetGain(); ctx.openPlay(track); }
    else                                        { ctx.getPlayer().resume(); }   // ← 绕过 openPlay
```

`ctx.getPlayer()` 是 biomebeats 内置的 goxr3plus `StreamPlayer`，而 `StreamPlayer.resume()` 是**该类仅有的两个会 `start()` 底层 `SourceDataLine` 的方法之一**（另一个是 `play()`）。换句话说：只要状态机认为"还是在放同一首、只是从淡出里恢复"，它就绕过了我们全部的钩子，直接让声卡继续出声。0.1.1 之所以"看起来能用"，是因为它的 `BiomeBeatsTickMixin` 在 `BiomeBeatsCommon.tick()` 的 `HEAD` 处整个 `ci.cancel()`，连带把这台状态机一起冻住了 —— 代价是条件音乐播完之后再也恢复不了（0.1.3 修的就是这个）。所以这是**用覆盖范围换来了正确性**留下的口子。

**修复.** 新增 `BiomeBeatsStreamDeviceMixin`，直接挂在真正持有 `SourceDataLine` 的类上：

```java
@Pseudo @Mixin(targets = "io.github.maki99999.biomebeats.com.goxr3plus.streamplayer.stream.StreamPlayer",
               priority = 2000, remap = false)
public class BiomeBeatsStreamDeviceMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void music_hud$suppressStreamPlayerPlay(CallbackInfo ci) {
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ExternalMusicSuppressor.markBiomeBeatsStopPending();
            ci.cancel();
        }
    }

    @Inject(method = "resume", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void music_hud$suppressStreamPlayerResume(CallbackInfoReturnable<Boolean> cir) {
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ExternalMusicSuppressor.markBiomeBeatsStopPending();
            cir.setReturnValue(false);
        }
    }
}
```

`play()` 与 `resume()` 是该类**全部**的起声入口（`open(...)` 只准备流、`pause()` / `stop()` 只会停），所以钩在设备层再也没有调用方能绕过去 —— 这也是为什么它比在状态机各子类上逐个补钩更可靠。`resume()` 返回 `false` 与它原生"当前不在暂停态"的返回值语义一致，而唯一的调用方 `FadeInState` 会把返回值 `pop` 掉、不看它，因此不改变任何上层控制流。两个注入都用 `require = 0`：第三方 mod 若改了方法名，降级为"抑制失效"，而不是启动崩溃。

`MusicHudMixinPlugin` 的存在性探测同步从 3 个类扩到 4 个（加入 `StreamPlayer`），仍用 `ClassLoader.getResource` 而不是 `Class.forName`。

### 修复：播放列表有时显示、有时整块消失

**症状.** 打开主界面，队列有时在、有时整块不见；重连服务器或改配置之后更容易复现。

**根因（两个叠加）.**

1. **监听器每刷新一次就多注册一份.** `HomeView.refresh()` 在每次重连、每次改配置时都会被 `MainFragment.refresh()` 调用，而它在方法体里 **new 了一批 lambda** 再注册进 `MusicService` 的监听器集合。这些集合是 `ConcurrentHashMap.newKeySet()` —— **按引用去重**，所以每次刷新都真的多出一份副本；旧副本从未被移除（只有最后注册的那份会在 detach 时被摘掉）。于是队列推送一次，就会往列表里 append 多行重复项。
2. **提前 return 的分支把视图留在了死容器上.** `refresh()` 在"未连接且未开启 isolated 模式"时，`removeAllViews()` 之后就 `return`，但字段 `playQueueListView` 仍然指向那个**已经被移出视图树**的 `LinearLayout`。此后任何一次队列推送都会往这个不可见的容器里加行，`checkQueue` 也会把 `visibility` 设到不在树上的视图 —— 这就是"有时显示、有时不显示"。

**修复.** 把监听器从方法体内的临时 lambda 提升为 **instance 字段**，并新增 `detachListeners()` 统一负责"反注册全部监听器 + 把引用视图的字段全部置 null"：

- `refresh()` 一进来先 `detachListeners()`，再 `removeAllViews()`，然后才重建并重新注册 —— 重复刷新不再堆积副本。
- `detachListeners()` 同时清空 `playQueueListView` / `queueTitle` / `nextToPlayItem` / `nextToPlayTitle` / 四个 idle-play-source 视图字段 / `staggeredLyricScrollView`，让后续任何迟到的回调都拿不到已经死掉的容器。
- `checkQueue` / `checkNextToPlay` / `checkIdlePlaySources` 全部补上 null 守卫 —— 因为它们会被 `MuiModApi.postToUiThread(...)` 调度，可能在 detach 之后才执行。
- `attachStateListener` 也提升为字段，注册前先 `removeOnAttachStateChangeListener(...)`，保证 detach 回调不会重复累积。

### 修复：队列已经切歌了，播放列表还显示旧歌；点"移除"没反应

**根因（两个独立缺陷）.**

1. **`MusicService.refreshQueue` 的增量算法在"一次移除多个头部元素"时算错.** 原实现在 `ArrayDeque` 上**持着迭代器的同时**用 `removeIf` 修改同一个 deque。`ArrayDeque.removeIf` 不会抛 `ConcurrentModificationException`（它走的是自身的批量删除），但迭代器游标会**静默错位**。用一份忠实复刻的脚本实测：

   ```
   [A,B,C] 与 [C] reconcile（连续两次跳过）  期望 [C]，实际得到 [B]   ← WRONG
   [A,B,C] 与 [B,C]（跳过一首）              得到 [B,C]            ← OK
   ```

   一旦算错，列表与服务端**整局**都对不上，后续所有增量都在错误基线上叠加。

2. **"整表重建"根本没有触发点.** `MusicService` 提供了 `musicQueueRefreshListeners`（每次 reconcile 后用权威队列回调一次），但**全项目零消费者** —— 没有任何 UI 注册它。因此当增量回调不足以表达真实变化（重新排序、后缀整体替换）时，UI 没有任何纠错机会。

**修复.**

- `refreshQueue` 改为**公共前缀 + 后缀整体重建**：先逐位比较求出与服务端队列的公共前缀长度，再从尾向前把本地多出来的元素逐个移除（同时记下 `removedIndexes`，**尾先收集**，这样 UI 按索引删除时不会发生索引偏移），最后把服务端新增的后缀整体追加。
- 派发 refresh 时使用**快照**（`new ArrayDeque<>(musicQueue)`）而不是直接遍历共享 deque —— 监听器会 `postToUiThread`，异步执行时队列可能已被下一次 reconcile 改动。
- `HomeView` 注册 `musicQueueRefreshListeners`，收到就 `rebuildMusicQueue(...)`：`removeAllViews()` + 逐项重建 + `checkQueue(...)`。从此列表有权威重建路径，增量错了也能在下一帧自愈。
- "移除"改成**按曲目定位**而不是盲信 `removeIndex`：`removeMusicQueueItem(musicDetail, removeIndex)` 先遍历 `MusicListItem.getMusicDetail()` 找同曲项删除，索引只作为"同一首入队两次时删哪一个"的提示，找不到匹配才回退按索引删。原因同样是那条回调走 `postToUiThread`，等它执行时列表可能已经被整表重建过，旧索引会删错行。

### 兼容性

`leastCapable` 保持 `0.1.0` 不变 —— 这是补丁版本，0.1.4 客户端仍需能连 0.1.0 服务端（反之亦然）。`compatibleWith()` 只拒绝低于该下限的版本，抬高它会让本来能用的连接失效。

新增的 4 个 BiomeBeats 相关 mixin 全部 `require = 0` + `@Pseudo`，缺少目标 mod 时直接跳过，不影响纯原版或其他整合包。

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
