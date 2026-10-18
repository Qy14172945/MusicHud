package indi.etern.musichud;

import indi.etern.musichud.beans.api.SearchType;
import indi.etern.musichud.beans.music.MusicDetail;
import indi.etern.musichud.beans.music.MusicResourceInfo;
import indi.etern.musichud.beans.music.Playlist;
import indi.etern.musichud.beans.music.Quality;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.IMusicApiService;
import indi.etern.musichud.server.api.impl.ncm.MusicApiService;
import indi.etern.musichud.utils.JsonUtil;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static indi.etern.musichud.MusicHud.getLogger;

public class MainTest {
    private static final Logger LOGGER = getLogger(MainTest.class);
    private final IMusicApiService musicApiService = IMusicApiService.getInstance(ApiProvider.NCM);

    @SneakyThrows
    @Test
    public void testSearch() {
        LOGGER.info("test search");
        List<MusicDetail> searchResult = musicApiService.search("Hideaway Feint", 0, 50, SearchType.MUSIC, response -> JsonUtil.gson.fromJson(response, MusicApiService.SearchMusicResponseBody.class)).result().musicDetails();
        assert !searchResult.isEmpty();
        LOGGER.info(JsonUtil.gson.toJson(searchResult));
    }

    @SneakyThrows
    @Test
    public void testGetDetail() {
        LOGGER.info("test get detail");
        List<MusicDetail> detailByIds = musicApiService.getMusicDetailByIds(List.of(1827011682L), null);
        assert !detailByIds.isEmpty();
        LOGGER.info(JsonUtil.gson.toJson(detailByIds));
        LOGGER.info("test get resource detail");
        MusicResourceInfo resourceInfo = musicApiService.getResourceInfo(detailByIds.getFirst(), Quality.LOSSLESS, null);
        LOGGER.info(JsonUtil.gson.toJson(resourceInfo));
    }

    @SneakyThrows
    @Test
    public void testPlaylistDetail() {
        LOGGER.info("test get playlist detail");
        Playlist detailByIds = musicApiService.getPlaylistDetail(975427390, null);
        LOGGER.info(JsonUtil.gson.toJson(detailByIds));
        // RegistryFriendlyByteBuf does not exist in 1.20.1 - it was added in 1.20.4 - so the
        // codec is exercised against a plain FriendlyByteBuf (which only extends ByteBuf).
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        Playlist.CODEC.encode(buf, detailByIds);
    }

    @SneakyThrows
    @Test
    public void testVersion() {
        // leastCapable is 0.1.0 in this project (numbering restarted away from upstream's
        // 1.2.x sequence), so anything at or above 0.1.0 - including these - must stay
        // compatible.
        assert Version.compatibleWith(new Version(0, 1, 0, Version.BuildType.Alpha));
        assert Version.compatibleWith(new Version(0, 1, 0, Version.BuildType.Stable));
        // 0.1.1 is a patch release and did not raise leastCapable, so a 0.1.0 peer stays usable.
        assert Version.compatibleWith(new Version(0, 1, 1, Version.BuildType.Stable));
        assert Version.compatibleWith(Version.current);
        assert Version.compatibleWith(new Version(1, 2, 16, Version.BuildType.Stable));
        assert !Version.compatibleWith(new Version(0, 0, 9, Version.BuildType.Stable));
    }
}
