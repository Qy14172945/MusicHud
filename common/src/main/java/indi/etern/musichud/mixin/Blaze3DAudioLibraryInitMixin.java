package indi.etern.musichud.mixin;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

@Mixin(Library.class)
public class Blaze3DAudioLibraryInitMixin {

    @Unique
    private boolean music_hud$hrtfEnabled;

    // `require = 0` on both redirects is deliberate. They take over an instruction that other
    // audio mods also redirect (Sound Physics Remastered does, see MusicHudMixinPlugin), and a
    // redirect that loses that conflict is reported as a failed injection -- with the config's
    // default `require = 1` that is a fatal MixinTransformerError which kills the launch. Losing
    // the conflict is survivable here (the other mod then owns the OpenAL context), so the
    // injections must degrade to "not applied" instead of to a crash.
    @Redirect(method = "init", require = 0, at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Library;setHrtf(Z)V"
    ))
    private void skipSetHrtf(Library self, boolean enable) {
        music_hud$hrtfEnabled = enable;  // 捕获 combined = ALC_SOFT_HRTF && directionalAudio
        // 不执行原 setHrtf
    }

    @Redirect(
            method = "init",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"
            )
    )
    private long injectHrtfAttributes(long device, IntBuffer oldAttrs) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attrs = stack.callocInt(11);
            int numHrtf = ALC10.alcGetInteger(device, 6548);
            if (numHrtf > 0) {
                attrs.put(6546).put(music_hud$hrtfEnabled ? 1 : 0);  // 开了传 1，关了传 0 —— 都要传！
                attrs.put(6550).put(0);
            }
            attrs.put(6554).put(1).put(0).flip();
            return ALC10.alcCreateContext(device, attrs);
        }
    }
}
