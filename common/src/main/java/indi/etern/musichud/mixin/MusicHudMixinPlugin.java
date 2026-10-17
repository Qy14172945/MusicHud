package indi.etern.musichud.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MusicHudMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("MusicHud/MixinPlugin");

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("ReactiveMusicCompatMixin")) {
            return isClassPresent("circuitlord.reactivemusic.ReactiveMusic");
        }
        if (mixinClassName.contains("Blaze3DAudioLibraryInitMixin")) {
            // `@Redirect` is exclusive: only one mixin may own a call site, everyone else is
            // skipped, and a skipped redirect still counts as (0/1) succeeded -- so the loser's
            // `injectors.defaultRequire = 1` turns the collision into a fatal
            //   InjectionError: Critical injection failure: Redirector injected(JLjava/nio/IntBuffer;)J
            // that crashes the game while SoundEngine is constructed. Sound Physics Remastered's
            // LibraryMixin redirects this exact ALC10.alcCreateContext call, and both mods default
            // to priority 1000, so "music_hud" sorting before "sound_physics_remastered" made US
            // the winner and killed the launch. A dedicated audio mod owns the OpenAL context when
            // it is installed, so defer to it instead of fighting over the same instruction.
            boolean soundPhysics = isClassPresent("com.sonicether.soundphysics.SoundPhysicsMod");
            LOGGER.info("3D audio (HRTF) context fix: {}", soundPhysics
                    ? "skipped, Sound Physics Remastered already redirects alcCreateContext"
                    : "enabled");
            return !soundPhysics;
        }
        if (mixinClassName.contains("BiomeBeats")) {
            // BiomeBeats is the mod that plays situational music through its own audio stack in
            // most modpacks. Its two hooks are skipped entirely when it is absent, so MusicHud
            // loads on every pack (the @Pseudo targets alone would be skipped too, but an explicit
            // decision keeps the "required" mixin config from ever treating it as a failure).
            boolean present = isClassPresent("io.github.maki99999.biomebeats.BiomeBeatsCommon")
                    && isClassPresent("io.github.maki99999.biomebeats.music.MusicManager");
            // Logged because "the game still plays its own music" is otherwise indistinguishable
            // from "the companion mod was not detected on this launcher's class loader".
            LOGGER.info("BiomeBeats music suppression: {}", present
                    ? "hooks enabled"
                    : "skipped, BiomeBeats not found on the class path");
            return present;
        }
        return true;
    }

    /**
     * Whether an optional companion mod is on the classpath.
     *
     * <p>The context loader is tried first because it is the transforming loader that actually sees
     * every mod jar; the plugin's own loader is the fallback. A loader that cannot be queried at all
     * is reported as "not present" so an unreadable companion degrades to "feature off" rather than
     * to a hard mixin failure.
     */
    private static boolean isClassPresent(String className) {
        return tryLoad(className, Thread.currentThread().getContextClassLoader())
                || tryLoad(className, MusicHudMixinPlugin.class.getClassLoader());
    }

    private static boolean tryLoad(String className, ClassLoader classLoader) {
        if (classLoader == null) {
            return false;
        }
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
