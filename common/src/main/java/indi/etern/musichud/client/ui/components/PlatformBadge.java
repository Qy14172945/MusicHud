package indi.etern.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.TextView;
import indi.etern.musichud.client.ui.Theme;
import indi.etern.musichud.server.api.ApiProvider;
import lombok.Getter;

/**
 * Small chip that names the music platform an item came from.
 *
 * <p>MusicHud can serve NetEase Cloud Music and QQ Music <em>at the same time</em> (each with its own
 * server address, its own login and its own search), so a song, album, artist or playlist shown in
 * the UI is otherwise ambiguous: two rows can look identical while only one of them can actually be
 * played, because the play URL is fetched from that row's own provider. The badge makes the source
 * explicit instead of leaving it implicit in the values the client happens to send.
 *
 * <p>The label reuses the already-localised {@code music_hud.apiProvider.<NAME>} names via
 * {@link ApiProvider#toString()} (the client-side branch of that method returns the translated
 * display name), so no extra translation keys are needed.
 */
public class PlatformBadge extends TextView {
    /** NetEase Cloud Music brand red. */
    private static final int COLOR_NCM = 0xFFC20C0C;
    /** QQ Music brand green. */
    private static final int COLOR_QQ = 0xFF31C27C;

    @Getter
    private ApiProvider provider = ApiProvider.NCM;

    public PlatformBadge(Context context) {
        super(context);
        setTextSize(Theme.TEXT_SIZE_SMALL);
        setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        setSingleLine(true);
        setGravity(Gravity.CENTER);
        setPadding(dp(4), dp(1), dp(4), dp(1));
    }

    public void setProvider(ApiProvider provider) {
        ApiProvider resolved = provider == null ? ApiProvider.NCM : provider;
        this.provider = resolved;

        ShapeDrawable background = new ShapeDrawable();
        background.setCornerRadius(dp(3));
        background.setColor(resolved == ApiProvider.QQ ? COLOR_QQ : COLOR_NCM);
        setBackground(background);

        setText(resolved.toString());
        setContentDescription(resolved.toString());
    }
}
