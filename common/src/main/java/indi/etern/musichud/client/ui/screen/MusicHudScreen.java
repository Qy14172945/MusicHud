package indi.etern.musichud.client.ui.screen;

/*
 * Modified from Modern UI
 */

/*
 * Modern UI.
 * Copyright (C) 2026 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.Color;
import icyllis.modernui.mc.*;
import icyllis.modernui.view.KeyEvent;
import indi.etern.musichud.interfaces.ClientConfig;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Base screen that provides ModernUI.
 *
 * @see MenuScreen
 * @since 3.13
 */
@SuppressWarnings("UnstableApiUsage")
public class MusicHudScreen extends Screen implements MuiScreen {
    private final UIManager mHost;
    @Nullable
    private final Screen mPrevious;
    private final Fragment mFragment;
    @Nullable
    private final ScreenCallback mCallback;

    private final static int FADE_IN_DURATION_MILLIS = 200;
    @Getter
    @Setter
    private static double darken = ClientConfig.getInstance().getMainScreenAdditionalBackgroundDarken();

    MusicHudScreen(Fragment fragment,
                   @Nullable ScreenCallback callback, @Nullable Screen previous,
                   @NonNull Component title) {
        super(title);
        mHost = UIManager.getInstance();
        mPrevious = previous;
        mFragment = Objects.requireNonNull(fragment);
        mCallback = callback != null ? callback :
                fragment instanceof ScreenCallback cbk ? cbk : DefaultCallback.INSTANCE;
    }

    /**
     * Stands in for the {@code null} callback this screen used to pass, so that the one hook
     * this mod needs to override - {@link ScreenCallback#shouldBlurBackground()} - can reach
     * Modern UI's {@code BlurHandler}.
     * <p>
     * Every other method reproduces what a {@code null} callback did, which is why overriding
     * them is not optional: a {@code null} callback makes {@code UIManager} treat a
     * non-menu screen as "Escape is the only back key" and makes this screen a pause screen,
     * whereas the interface's own defaults would also accept the inventory key. Getting that
     * wrong would turn E into "close the music screen".
     */
    private static final class DefaultCallback implements ScreenCallback {
        static final DefaultCallback INSTANCE = new DefaultCallback();

        @Override
        public boolean isBackKey(int keyCode, @NonNull KeyEvent event) {
            // Matches UIManager's null-callback branch for MuiScreens that are not menus.
            return keyCode == KeyEvent.KEY_ESCAPE;
        }

        @Override
        public boolean hasDefaultBackground() {
            return true;
        }

        @Override
        public boolean isPauseScreen() {
            return true;
        }

        @Override
        public boolean shouldBlurBackground() {
            // Modern UI reads this from BlurHandler.blur(), which only runs on a screen change,
            // so a toggle takes effect the next time the screen is opened. Reading the config
            // live (instead of caching it in a field) at least avoids a stale value if the
            // screen is reopened after the setting changed.
            return ClientConfig.getInstance().getMainScreenBlurBackground();
        }
    }

    /*@Override
    public void init(@Nonnull Minecraft minecraft, int width, int height) {
        this.minecraft = minecraft;
        this.width = width;
        this.height = height;
    }*/

    public static MusicHudScreen createScreen(@NonNull Fragment fragment,
                                              @Nullable ScreenCallback callback,
                                              @Nullable Screen previousScreen,
                                              @Nullable CharSequence title) {
        return new MusicHudScreen(fragment, callback, previousScreen, Component.literal(title == null ? "" : title.toString()));
    }

    @Override
    protected void init() {
        super.init();
        mHost.initScreen(this);
    }

    @Override
    public void resize(@NonNull Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
    }

    @Override
    public void render(@NonNull GuiGraphics gr, int mouseX, int mouseY, float deltaTick) {
        // Minecraft 1.20.1 has no Screen.renderBackground(GuiGraphics, int, int, float) hook,
        // so the background pass is folded into render() (as Modern UI's own 1.20.1
        // forge SimpleScreen does).
        ScreenCallback callback = getCallback();
        if (callback == null || callback.hasDefaultBackground()) {
            BlurHandler.INSTANCE.drawScreenBackground(gr, 0, 0, this.width, this.height);
            float progress = Math.clamp((float) MuiModApi.getElapsedTime() / FADE_IN_DURATION_MILLIS, 0, 1);
            int darkenAlpha = (int) (progress * darken * 255);
            if (darkenAlpha > 0) {
                //additional darken; skipped entirely at alpha 0 so a fill quad is not
                //emitted into the deferred GuiGraphics buffer for nothing
                gr.fill(0, 0, this.width, this.height, Color.argb(darkenAlpha, 0, 0, 0));
            }
        }
        super.render(gr, mouseX, mouseY, deltaTick);
        // UIManager.render() below takes over the GL pipeline with raw GL calls and
        // submits its own geometry directly, and it force-resets the Blaze3D state it
        // relies on. Anything of ours still queued in the shared GuiGraphics buffer
        // would therefore be flushed at a later, framebuffer-inconsistent moment
        // (either at the end of the frame, on top of the UI, or at the start of the
        // next frame's blur pass, underneath it) - which is exactly the world
        // background flicker plus stale pixel ghosting seen while the mouse moves and
        // the UI surface is re-recorded. Commit our own geometry first so the order is
        // strictly world -> blur -> darken -> UI, as Modern UI's own SimpleScreen does
        // (BlurHandler.drawScreenBackground flushes internally, the darken quad did not).
        gr.flush();
        mHost.render(gr, mouseX, mouseY, deltaTick);
    }

    @Override
    public void removed() {
        super.removed();
        mHost.removed(this);
    }

    @Override
    public boolean isPauseScreen() {
        ScreenCallback callback = getCallback();
        return callback == null || callback.isPauseScreen();
    }

    @NonNull
    @Override
    public Screen self() {
        return this;
    }

    @NonNull
    @Override
    public Fragment getFragment() {
        return mFragment;
    }

    @Nullable
    @Override
    public ScreenCallback getCallback() {
        return mCallback;
    }

    @Nullable
    @Override
    public Screen getPreviousScreen() {
        return mPrevious;
    }

    @Override
    public boolean isMenuScreen() {
        return false;
    }

    // IMPL - GuiEventListener

    @Override
    public void onBackPressed() {
        mHost.getOnBackPressedDispatcher().onBackPressed();
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        mHost.onHoverMove(true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int mouseButton) {
        super.mouseReleased(mouseX, mouseY, mouseButton);
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int mouseButton, double deltaX, double deltaY) {
        super.mouseDragged(mouseX, mouseY, mouseButton, deltaX, deltaY);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // Minecraft 1.20.1 reports a single combined scroll delta. Modern UI's own
        // MixinMouseHandler already forwards raw scroll to MuiModApi (whose UIManager
        // hook is private here), so the screen only consumes the event.
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() != null && getFocused().keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        mHost.onKeyPress(keyCode, scanCode, modifiers);
        return false;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (getFocused() != null && getFocused().keyReleased(keyCode, scanCode, modifiers)) {
            return true;
        }
        mHost.onKeyRelease(keyCode, scanCode, modifiers);
        return false;
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (getFocused() != null && getFocused().charTyped(ch, modifiers)) {
            return true;
        }
        return mHost.onCharTyped(ch);
    }
}
