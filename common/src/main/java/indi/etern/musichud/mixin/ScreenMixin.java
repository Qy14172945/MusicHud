package indi.etern.musichud.mixin;

import indi.etern.musichud.client.ui.hud.HudRendererManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "render",
            at = @At("RETURN"))
    private void music_hud$onRender(GuiGraphics guiGraphics, int i, int j, float f, CallbackInfo ci) {
        // ServerReconfigScreen does not exist in 1.20.1 (added with the 1.20.2 configuration phase),
        // so only the level-loading screen needs the HUD drawn on top of it here.
        //noinspection ConstantValue (incorrect warning)
        if (((Object) this instanceof ReceivingLevelScreen)) {
            HudRendererManager.getInstance().renderFrame(guiGraphics, f);
        }
    }
}
