# Music HUD - Agent Guide

Multi-loader Minecraft mod (Fabric + Forge via Architectury) for **MC 1.20.1 / Java 21**. A GUI-based full-server song request system with a pluggable music backend: **NetEase Cloud Music (NCM)** and **QQ Music**.

> Branch note: this is the `1.20.1-forge` branch, and it is the **only** branch built from this working tree. The repo also carries 1.21.x branches with a different module layout (they add `core/` + `processor/`, and target NeoForge/Paper). Do not carry their build commands over.
>
> Project note: this repository is an **independent** project (a 1.20.1 backport that diverged from the original MusicHud). It has one remote, `origin` = `Qy14172945/MusicHud`; the original author's repository is **not** a remote and must never receive issues or PRs. Version numbering restarted at **`0.1.0`** and no longer follows upstream's `1.2.x` sequence — `gradle.properties` `mod_version` and `Version.current` are bumped together.

## Build & Run

```bash
# Build a loader (produces shadowed fat jars):
./gradlew fabric:build          # build/libs/music_hud-fabric-<version>+1.20.1.jar
./gradlew forge:build           # build/libs/music_hud-forge-<version>+1.20.1.jar

# Run (Architectury Loom dev runs):
./gradlew fabric:runClient      # / fabric:runServer
./gradlew forge:runClient       # / forge:runServer
```

There is **no `neoforge` or `paper` module on this branch**. `settings.gradle` includes only `common`, `fabric`, `forge`; the adapters for the other loaders were removed together with the `NEOFORGE`/`PAPER` constants on `Environment.Platform` (they exist only on the 1.21.x branches).

### Build environment quirks (this machine)

- **Use a workspace-local Gradle home with `--no-watch-fs`**:
  ```powershell
  cd <repo>
  $env:GRADLE_USER_HOME = "<repo>\.gradle-home"
  .\gradlew.bat --no-daemon --console=plain --no-watch-fs --continue <tasks>
  ```
  `--no-watch-fs` is required, otherwise the daemon dies with `NativeException: Couldn't open current thread, error = 5`.
- Gradle 9.6.0, **JDK 21 only** (no JDK 17 installed).
- The `Fuzs Mod Resources` repository URL in the root `build.gradle` points at a `ghproxy.net` mirror of `raw.githubusercontent.com`; the direct URL `Connection reset`s and aborts *all* dependency resolution.
- **Never write `.java` with PowerShell `Set-Content` / `Out-File -Encoding utf8`** — they emit a UTF-8 BOM, and javac then reports `错误: 非法字符: '\ufeff'` on line 1 plus a cascade of bogus "class expected" errors across the file. Use the editor tools, or `[IO.File]::WriteAllText($p, $src, (New-Object Text.UTF8Encoding($false)))`.

## Critical Quirks

- **Tests are DISABLED by default** (`enabled = false`, `common/build.gradle:49`). To run: edit the file and flip it to `true` — there is no Gradle property to override this at runtime. (Two test classes: `common/src/test/java/indi/etern/musichud/MainTest.java` and `.../server/api/ApiServerFetcherTest.java`; they call the live NCM API and may fail offline.)
- **CI builds both jars but runs no tests.** `.github/workflows/gradle.yml` runs `:fabric:build :forge:build` on JDK 21 for pushes/PRs to `1.20.1-forge` and uploads the two remapped jars; its `dependency-submission` job feeds Dependabot. There is no linter, no formatter and no typechecker. The workflow sets `MUSICHUD_FUZS_REPO` to the canonical Fuzs URL, because `build.gradle` defaults that repository to a `ghproxy.net` mirror for the author's network (raw.githubusercontent.com connection-resets there).
- **Build scripts are Groovy DSL** (`.gradle`), not Kotlin (`.gradle.kts`).
- **`mods.toml` dependency schema is version-specific.** Forge 1.20.1 reads `mandatory = true` and has **no `type` key** (`ModInfo$ModVersion` throws `InvalidModFileException: Missing required field mandatory in dependency`, which cascades into the misleading `Failed to find system mod: minecraft`). `type = "required"` is the NeoForge 1.21 schema — do not copy it here.
- **`music_hud.mixins.json` `compatibilityLevel` must be ≤ `JAVA_18`.** Forge 1.20.1 forces `dev.architectury:mixin-patched:0.8.5.12`, whose `MixinEnvironment.CompatibilityLevel` enum has no `JAVA_21` constant → `MixinInitialisationError: ... specifies compatibility level JAVA_21 which is not recognised`. The file uses `JAVA_17` (conservative). This is independent of `options.release = 21`: the mod's own classes are still compiled at class-file version 65.
- **Forge finds mixins through the `MixinConfigs` manifest attribute, NOT through `mods.toml`.** Another Forge-only silent failure: the `[[mixins]]` table in `mods.toml` is a **NeoForge** addition — the 1.20.x mods.toml schema has no such key — so declaring the config only there registers nothing and every mixin in `music_hud.mixins.json` stays silently unapplied, with no error anywhere. That is why this mod's music suppression, level-screen HUD hook and ModernUI span fix all worked on Fabric (`fabric.mod.json` has a top-level `"mixins": [...]`) and did nothing on Forge. The fix is `tasks.withType(Jar) { manifest { attributes 'MixinConfigs': 'music_hud.mixins.json' } }` in `forge/build.gradle`. Conventions in the field: of 460 jars in the reference pack, 272 declare `MixinConfigs` in the manifest and only 4 use the mods.toml table. Two consequences: (1) `:forge:runClient` can never surface this — the dev classpath has no manifest, so **mixins are only exercised by a packaged jar**; (2) since the config is `required: true` with `injectors.defaultRequire = 1`, turning it on for the first time can crash the launch, so verify every `@Inject`/`@Overwrite` target with `javap` against the real jars first (`minecraft-merged-srg.jar` for vanilla, the dependency jar for library mixins, plus `@Pseudo`+plugin guards for optional compat targets).
- **Enabling a mixin config for the first time also enables every collision it was hiding.** As long as Forge never registered `music_hud.mixins.json`, no mixin was conflict-checked, so the first launch after the `MixinConfigs` fix crashed with `MixinTransformerError ... InjectionError: Critical injection failure: Redirector injected(JLjava/nio/IntBuffer;)J in sound_physics_remastered.mixins.json:LibraryMixin from mod (unknown) failed injection check, (0/1) succeeded`. `@Redirect` is **exclusive** — exactly one mixin owns a call site, everyone else is skipped, and a skipped redirect still counts as a *failed* injection, so the loser's `injectors.defaultRequire = 1` makes the collision fatal. Here `Blaze3DAudioLibraryInitMixin` and Sound Physics Remastered both redirect `ALC10.alcCreateContext` inside `com.mojang.blaze3d.audio.Library.init`; both default to priority 1000, so the tie broke on name order ("music_hud" < "sound_physics_remastered"), we won, and the pack could not launch. Two defences now: `MusicHudMixinPlugin.shouldApplyMixin` stands our mixin down when `com.sonicether.soundphysics.SoundPhysicsMod` is loadable (a dedicated audio mod owns the OpenAL context), and both redirects carry `require = 0` so an unknown collision degrades to "not applied" instead of to a crash. Prefer `@Inject` (additive, cannot collide) over `@Redirect`/`@Overwrite`, and before shipping any new redirect, grep the pack's `*.mixins.json` files for the same call site.
- **Forge `SimpleChannel` payload registration must be EAGER per payload — never batched behind a one-shot latch.** `IndexedMessageCodec.build()` resolves the encoder from a **per-channel** `Map<Class<?>, MessageHandler<?>> types` that is only populated by `SimpleChannel.messageBuilder(...).add()`. A `ForgeNetworkManager` that queued registrations in `pendingRegistrations` and drained them from `init()` guarded by `if (registered) return; registered = true;` registers only the FIRST payload — every later one is still cached in `INetworkRegister.codecInfoMap`, so `sendToServer` passes its "was never registered" guard, reaches the channel, logs `[SIMPLENET]: Received invalid message <payload> on channel music_hud:main` (that string is from the **encode** side, not decode — a decode miss logs `Received invalid discriminator byte` instead) and throws `IllegalArgumentException: Invalid message ...` on the caller's thread. Symptom in game: single player works (its `IClientNetworkService.sendToServer` short-circuits to the in-process receiver) while multiplayer "connect does nothing". Fix: register inside `registerC2SPayload`/`registerS2CPayload` directly, idempotent per class via a `Set<Class<?>>` — see `forge/src/main/java/indi/etern/musichud/platform/mod/forge/network/ForgeNetworkManager.java`.
- **`MinecraftForge.EVENT_BUS.addListener(...)` does NOT de-duplicate.** `EventBus` → `ListenerList$ListenerListInst.register` is a bare `ArrayList.add` with no `contains`/`equals` check. Registering from a `ModConfigEvent.Loading` handler is therefore a double-registration trap: `onConfigLoaded` fires **once per config spec** (this mod registers both `ModConfigType.COMMON` and `CLIENT`, so the "both configured" branch runs twice — visible in the log as two `Starting explicit auto-registration in environment: CLIENT` blocks), and a fresh `CommonInitializer::onRenderGui` method reference is a new listener object each time → `HudRendererManager.renderFrame` runs 2×/frame and the semi-transparent HUD double-blends. Guard any game-bus registration with a static boolean latch, and prefer registering render listeners from `FMLClientSetupEvent` or the mod constructor.
- **Keep client-only lambdas out of `IEventBus.register(this)` classes.** Forge's EventBus reflects over *all* declared methods, so a lambda in the mod's main class compiles to a synthetic method typed `net.minecraft.client.Minecraft` and trips `RuntimeDistCleaner`: `Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER`, failing mod construction. Client-only event registration lives in `forge/src/main/java/indi/etern/musichud/forge/ConfigScreenFactory.java`.
- **Never let Shadow merge `Multi-Release: true` into the fat jar's manifest.** `slf4j-api` declares `Multi-Release: true` (it ships `META-INF/versions/9/module-info.class`). Shadow copies that attribute while `exclude 'org/slf4j/**'` drops the versioned entry, producing a jar that *claims* multi-release but has no `META-INF/versions` directory. Forge's `SecureJar` then executes (bytecode of `cpw.mods.jarhandling.impl.Jar.<init>`: `isMultiRelease = Boolean.parseBoolean(manifest.getValue("Multi-Release"))`; if true → `Files.walk(root.resolve("META-INF/versions"))`) and dies **before any mod loads**, with a stack that never names this mod:

  ```
  Exception in thread "main" java.io.UncheckedIOException: cpw.mods.niofs.union.UnionFileSystem$NoSuchFileException: /META-INF/versions
      at cpw.mods.securejarhandler/cpw.mods.jarhandling.impl.Jar.<init>(Jar.java:138)
      at ...ModDirTransformerDiscoverer.visitFile(ModDirTransformerDiscoverer.java:76)
      at ...ModDirTransformerDiscoverer.scan(ModDirTransformerDiscoverer.java:65)
  ```

  Both `fabric/build.gradle` and `forge/build.gradle` therefore carry `exclude group: 'org.slf4j', module: 'slf4j-api'` on the `shadowBundle` configuration (the loaders provide slf4j). **This only manifests with the packaged jar — `:forge:runServer` uses `build/classes` on the dev classpath, so dev runs never cover it.** After changing bundling, verify the shipped jar directly: open it and assert no `Multi-Release` manifest attribute (or that `META-INF/versions/` actually exists).
- **Forge 1.20.1 splits JDK module resolution per jar, and JarJar-nested jars count.** Every mod jar on the mods folder becomes a JPMS automatic module, and **JarJar extracts nested `META-INF/jarjar/*.jar` files onto the module layer too**. A pack that already ships a library we `shadowBundle` therefore aborts the launch before any mod loads:
  ```
  java.lang.module.ResolutionException: Modules music_hud and jlayer export package javazoom.jl.converter to module forge
  ```
  This really happened with a pack whose speaker mod bundles `jlayer-1.0.1.4.jar` via JarJar. `jlayer` (the MP3 decoder) is relocated to `indi.etern.musichud.shaded.javazoom` in **both** `fabric/build.gradle` and `forge/build.gradle`, so our copy can never collide with, or shadow, another mod's. `org.jflac` / `org.freedesktop.dbus` / `javazoom` still appear verbatim in dev-staging runs because the dev classpath adds the standalone jars next to our bundled copies — that specific error is a **dev-staging artifact**, but the JarJar nesting above is not, so after changing bundling always re-scan a real pack.
  - **`relocate` does not rewrite `META-INF/services/*`, and that alone keeps the split alive.** After relocating `javazoom`, the SPI file `META-INF/services/javazoom.jl.player.AudioDeviceFactory` kept its old name and contents (`javazoom.jl.player.JavaSoundAudioDeviceFactory`). JPMS derives a `provides` directive from every `META-INF/services/*` entry, so module `music_hud` still claimed package `javazoom.jl.player` and hit the *same* `ResolutionException` one package over — and the jar could not even derive a module descriptor (`FindException` ⟵ `InvalidModuleDescriptorException: Provider class javazoom.jl.player.JavaSoundAudioDeviceFactory not in JAR file`). Fixed with `exclude 'META-INF/services/javazoom.jl.player.AudioDeviceFactory'` (the `player` package is unused — only `javazoom.jl.decoder` is, via `MP3StreamDecoder`). **Whenever you relocate a dependency, also relocate or drop its `META-INF/services` files.** Verify with `java.lang.module.ModuleFinder.of(jar).findAll()` and confirm the descriptor's package list has no relocated group.
  - Audit recipe (catches nested jars, which a plain top-level scan misses): for each of our exported packages, descend 2 levels into every other mod's `META-INF/jarjar/*.jar` and record every package directory. Any package we export that another mod also provides is a launch-time `ResolutionException` waiting to happen. After the relocation, MusicHud exports 81 packages and conflicts with none of the 459 pack jars.

## Architecture

```
common/          — all shared code (network, UI, HUD rendering, logic, data models, mixins)
fabric/          — Fabric loader adapter (shadow-jars common)
forge/           — Forge 1.20.1 adapter (shadow-jars common)  [new on this branch]
```

`common` is the only place 1.20.1-vs-1.21 API drift is centralized where possible; platform modules provide the loader bindings. `build.gradle` applies Loom to every subproject (`common`, `fabric`, `forge`).

## Key Patterns

- **Service Locator** (not DI): `Environment.Platform` holds 8 `Supplier`s per platform and `load()` resolves them via `Class.forName()` + `findStatic("getInstance")`. Interfaces: `ServerConfig`, `ClientConfig`, `INetworkRegister`, `IServerNetworkService`, `IClientNetworkService`, `IServerEventService`, `IClientEventService`, `IKeyRegistryService`. `Environment.Platform.FORGE` is the new enum constant on this branch; on Forge, one `ForgeNetworkManager` serves both `INetworkRegister` and `IServerNetworkService`.
- **Auto-Registration**: `RegistrationManager` loads classes by string array, instantiates `Register` implementors. `@RegisterMark` marks registered classes. Called via `performCommonAutoRegistration()` / `performSideAutoRegistration()`. On a healthy server start you should see `Starting explicit auto-registration ... Registering 44 common registries` + `Registering 3 server registries`.
- **Custom Network Protocol**: payload classes implement `IPayload` (**not** `CustomPacketPayload` — that type does not exist in 1.20.1). A platform-neutral `PayloadType<T extends IPayload(ResourceLocation id)` record is the channel key; Fabric maps it to a `ResourceLocation` channel, Forge to a `SimpleChannel` message id. Both feed the shared `ByteBufCodec` directly (`FriendlyByteBuf extends ByteBuf`).
- **Music provider abstraction**: `ApiProvider { NCM, QQ }` + `MusicIdRegistry`. Protocols address tracks/albums/artists/playlists by numeric `long` id, but **QQ only accepts MID strings**, so `MusicIdRegistry` remembers `long → (provider, externalId)` and `QqMusicApiService` mints deterministic FNV-1a ids when QQ supplies no numeric id. Unknown ids resolve to `NONE`/empty rather than throwing. See `server/api/impl/qq/`.
- **Virtual Threads**: `MusicHud.EXECUTOR = Executors.newVirtualThreadPerTaskExecutor()`. Used for audio decoding, API server management, network I/O — this is why the project targets Java 21.
- **Mixin**: 8 client mixins + 1 plugin — `BiomeBeatsMusicMixin`, `BiomeBeatsTickMixin`, `Blaze3DAudioLibraryInitMixin`, `MusicManagerMixin`, `ReactiveMusicCompatMixin`, `ScreenMixin`, `SoundEngineMixin`, `SpanSetMixin`, plus `MusicHudMixinPlugin`. Config: `common/src/main/resources/music_hud.mixins.json` (registered on Fabric via `fabric.mod.json`, on Forge via the `MixinConfigs` manifest attribute — see Critical Quirks). `BiomeBeatsMusicMixin`/`BiomeBeatsTickMixin`/`ReactiveMusicCompatMixin` are `@Pseudo` optional-compat mixins enabled by `MusicHudMixinPlugin` only when the target mod is present.
- **Config**: Forge Config API Port v2 on Fabric (`fuzs.forgeconfigapiport.api.config.v2.*`) / native `ForgeConfigSpec` on Forge.

## Frameworks & Dependencies

- **ModernUI** (`icyllis.modernui:*:3.12.0`) — GUI framework, **not** vanilla MC widgets. Resolved from `https://maven.izzel.io/releases`; the group is `icyllis.modernui`, **not** `dev.icyllis`.
- **Lombok** heavily used — annotation processing already configured.
- **JLayer** (MP3) + **JFLAC** (FLAC) — audio decoders, shaded into output jars.
- **Mojang mappings** — `loom.officialMojangMappings()`.
- Key versions live in `gradle.properties`: MC `1.20.1`, Forge `47.1.37`, ModernUI `3.12.0`, Fabric loader `0.16.10`, Fabric API `0.92.12+1.20.1`, FCAP `8.0.3`, Cloth Config `11.1.136`, Mod Menu `7.2.2`, JUnit `5.10.2`.

## External Dependencies

- **NCM API Enhanced** — external process (`api`/`api.exe`) managed by `ApiServerManager`. Lifecycle: auto-start on launch, manages process I/O via virtual threads. Deploy to `{corepath}/music-hud/`. Default base URL `http://localhost:3000` (`serverApiBaseUrl`).
- **QQ Music API** — a **separate** Node.js process, [`@sansenjian/qq-music-api`](https://sansenjian.github.io/qq-music-api/) (MIT), default port **3200**, configured via `qqApiBaseUrl`. There is no prebuilt binary, so `ApiServerManager` does not manage it — it must be run by the user (Node ≥ 20.17 / Docker). It is *not* a drop-in for the NetEase endpoints. Login is QR-only; it has no per-word lyrics, no scrobble, and no search-by-content-type parameter.

## Testing

- JUnit Jupiter `5.10.2`.
- Tests call the live NCM API (network-dependent, may fail offline) and **must be enabled manually** (see Critical Quirks).

## Misc

- Languages: `en_us`, `zh_cn`, `zh_hk`, `zh_tw` — `common/src/main/resources/assets/music_hud/lang/*.json`.
- Custom GL shaders (`.vsh`/`.fsh`) for album cover, progress bar, fluid background. The HUD renders via **direct LWJGL GL calls** (`HudShaderManager` compiles programs, `HudRenderContext` uploads UBOs and draws) — it does **not** use 1.21's `RenderPipeline`/`GuiRenderer`/`RenderPass` types, none of which exist in 1.20.1.

## HUD Rendering Architecture Notes

These are hard-won and still accurate for the shared `common/` rendering code.

- **z-clip**: `ProjMat` ortho(n=1000, f=21000) requires z ∈ [-21000,-1000]; z=0 clips everything. `ModelViewMat.translate(0,0,-1000)`.
- **JOML `get(ByteBuffer)` is broken at runtime**: `mat.get(buffer)` / `vec.get(buffer)` don't advance the buffer position. Use manual `putFloat()` / `mat.get(float[])` in `Std140BufferWriter`.
- **UBO layout is column-major**: row-major float writes into `putMat4f` produce garbage.
- **UBO size 0**: `putVec4(Vector4f)` failed silently → `glBindBufferRange(...,0)` → `GL_INVALID_VALUE`. Use `putVec4(float,float,float,float)`.
- **GL state leak**: save/restore program, texture, blend func and depth test around each draw, or MC text/blit rendering breaks.
- **Shader cache key must be `vsh|fsh`**, not the vertex path alone, or fragment changes never recompile.
- **Blit UV double-division**: `HudRenderContext.blit` passes raw texels; `GuiGraphics.blit` divides by texWidth internally.
- **`getPixelRGBA` returns `0xAABBGGRR`** (ABGR byte order), not `0xAARRGGBB`.
- UBO block bindings: MHBasePosition(2), MHAlbumPosition(2), MHProgressPosition(2), MHNowPlayingThemeColor(3), MHProgressStyle(3), MHDynamicStatus(4). All shaders follow `ProjMat * ModelViewMat * Position`.
- `ByteBuffer` allocations are cached per UBO name to avoid per-frame allocation.
- `BufferUploader.draw(rendered)` uploads + draws without touching the shader — safe alongside a custom `glUseProgram`. Call `builder.endOrDiscardIfEmpty()` and null-check before drawing.
- 1.20.1 `BufferBuilder` has **no** `addVertex(Matrix4f,...)` and **no** `buildOrThrow()`; use `builder.begin(Mode, Format)`, `builder.vertex(Matrix4f,x,y,z).color(-1).endVertex()`, `builder.endOrDiscardIfEmpty()`.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues on `Qy14172945/MusicHud`, this project's only remote (`origin`). See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles use their default names (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), plus the `wayfinder:*` family. See `docs/agents/triage-labels.md`.

### Domain docs

single-context — one root `GLOSSARY.md` plus `docs/adr/`; the loader modules share one vocabulary and get no per-module glossary. See `docs/agents/domain.md`.
