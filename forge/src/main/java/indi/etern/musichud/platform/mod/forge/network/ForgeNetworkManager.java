package indi.etern.musichud.platform.mod.forge.network;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.network.*;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.network.payloads.IPayload;
import indi.etern.musichud.network.payloads.S2CPayload;
import indi.etern.musichud.network.vanillaUtils.VanillaPlayerProxy;
import indi.etern.musichud.platform.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forge 1.20.1 replaces NeoForge's {@code RegisterPayloadHandlersEvent} + {@code StreamCodec}
 * with a single {@link SimpleChannel}. Every payload class gets a stable numeric message id
 * assigned in registration order; the shared {@link ByteBufCodec} performs the (de)serialization
 * because {@code FriendlyByteBuf} extends Netty's {@code ByteBuf}.
 */
@SuppressWarnings("unused")
public class ForgeNetworkManager implements INetworkRegister, IServerNetworkService {
    private static volatile ForgeNetworkManager instance;
    private static final String PROTOCOL_VERSION = "1";
    private static final AtomicInteger NEXT_MESSAGE_ID = new AtomicInteger(0);

    private final Environment.Side side = MusicHud.getCurrentEnvironment().getSide();
    /**
     * Classes already handed to {@link SimpleChannel#messageBuilder}. Forge keys its codec on the
     * message class, so a payload that is missing here cannot be encoded on either side.
     */
    private final Set<Class<?>> registeredClasses = new LinkedHashSet<>();
    private SimpleChannel channel;

    public static ForgeNetworkManager getInstance() {
        if (instance == null) {
            synchronized (ForgeNetworkManager.class) {
                if (instance == null) {
                    instance = new ForgeNetworkManager();
                }
            }
        }
        return instance;
    }

    private SimpleChannel channel() {
        if (channel == null) {
            synchronized (this) {
                if (channel == null) {
                    channel = NetworkRegistry.newSimpleChannel(
                            MusicHud.location("main"),
                            () -> PROTOCOL_VERSION,
                            PROTOCOL_VERSION::equals,
                            PROTOCOL_VERSION::equals
                    );
                }
            }
        }
        return channel;
    }

    /**
     * Ensures the channel exists. Payload registration itself is eager (see
     * {@link #registerC2SPayload}/{@link #registerS2CPayload}); this stays because the loader
     * calls it during mod construction and it must remain safe to call at any time, any number
     * of times.
     */
    public synchronized void init() {
        channel();
    }

    @SuppressWarnings("unchecked")
    private <T extends IPayload> void registerPayloadInternal(RegistrationInfo<T> info, int id) {
        SimpleChannel channel = channel();
        ByteBufCodec<T> codec = info.codec();
        // Keep the message class typed as Class<T> so SimpleChannel.MessageBuilder infers T
        // for the encoder/decoder/consumer lambdas (a raw Class erases them to Object).
        Class<T> messageClass = (Class<T>) info.clazz;

        if (C2SPayload.class.isAssignableFrom(info.clazz)) {
            NetworkReceiver<T> receiver = (NetworkReceiver<T>) info.serverReceiver();
            channel.messageBuilder(messageClass, id, NetworkDirection.PLAY_TO_SERVER)
                    .encoder((msg, buf) -> codec.encode(buf, msg))
                    .decoder(codec::decode)
                    .consumerMainThread((msg, ctxSupplier) -> {
                        var ctx = ctxSupplier.get();
                        ctx.setPacketHandled(true);
                        if (receiver != null) {
                            receiver.receive(msg, VanillaPlayerProxy.ofPlayer(ctx.getSender()));
                        } else {
                            MusicHud.LOGGER.error("No server receiver for client payload {}", info.clazz.getName());
                        }
                    })
                    .add();
        } else if (S2CPayload.class.isAssignableFrom(info.clazz)) {
            NetworkReceiver<T> receiver = (NetworkReceiver<T>) info.clientReceiver();
            channel.messageBuilder(messageClass, id, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder((msg, buf) -> codec.encode(buf, msg))
                    .decoder(codec::decode)
                    .consumerMainThread((msg, ctxSupplier) -> {
                        var ctx = ctxSupplier.get();
                        ctx.setPacketHandled(true);
                        if (receiver != null) {
                            var sender = ctx.getSender();
                            // On the client the sending player is unavailable from the context, so the
                            // local player proxy is resolved here instead.
                            receiver.receive(msg, sender != null
                                    ? VanillaPlayerProxy.ofPlayer(sender)
                                    : VanillaPlayerProxy.ofLocalPlayer());
                        } else {
                            MusicHud.LOGGER.error("No client receiver for server payload {}", info.clazz.getName());
                        }
                    })
                    .add();
        }
    }

    @Override
    public <T extends IPayload> void registerC2SPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> serverReceiver
    ) {
        registerPayload(clazz, codec, null, serverReceiver);
    }

    @Override
    public <T extends IPayload> void registerS2CPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    ) {
        registerPayload(clazz, codec, clientReceiver, null);
    }

    /**
     * Registers a payload class with the channel exactly once, at the moment it is declared.
     *
     * <p>Forge's {@code IndexedMessageCodec} resolves the encoder from a per-channel
     * {@code Map<Class<?>, MessageHandler<?>>} that only {@code messageBuilder(...).add()} populates.
     * Deferring that call behind a one-shot "already initialised" latch meant every payload after
     * the first stayed absent from that map: {@code sendToServer} still succeeded its
     * {@code codecInfoMap} guard and then threw
     * {@code IllegalArgumentException: Invalid message ...} from
     * {@code IndexedMessageCodec.build}, which is the
     * {@code Received invalid message ... ConnectRequest} line seen on multiplayer join.
     */
    private synchronized <T extends IPayload> void registerPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver,
            NetworkReceiver<T> serverReceiver
    ) {
        getMetaDataOrNew(clazz, serverReceiver != null ? serverReceiver : clientReceiver);
        cacheCodec(clazz, codec);
        if (!registeredClasses.add(clazz)) {
            return;
        }
        registerPayloadInternal(new RegistrationInfo<>(clazz, codec, clientReceiver, serverReceiver),
                NEXT_MESSAGE_ID.getAndIncrement());
    }

    @Override
    public <T extends IPayload> void autoRegisterPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientOrServerReceiver
    ) {
        if (S2CPayload.class.isAssignableFrom(clazz)) {
            registerS2CPayload(clazz, codec, clientOrServerReceiver);
        } else if (C2SPayload.class.isAssignableFrom(clazz)) {
            registerC2SPayload(clazz, codec, clientOrServerReceiver);
        } else {
            throw new IllegalArgumentException("Payload class must implements S2CPayload or C2SPayload");
        }
    }

    @Override
    public void sendToNetworkPlayer(IPlayerClient playerClient, S2CPayload payload) {
        if (playerClient instanceof VanillaPlayerProxy player && player.getPlayer() instanceof ServerPlayer serverPlayer) {
            var info = INetworkRegister.getCodecInfo((Class<? extends IPayload>) payload.getClass());
            if (info == null) {
                MusicHud.LOGGER.error("S2C payload {} was never registered", payload.getClass().getName());
                return;
            }
            try {
                channel().send(PacketDistributor.PLAYER.with(() -> serverPlayer), payload);
            } catch (Throwable t) {
                // A send failure must never escape into the caller: server sends can run on a
                // player's packet thread, and an escaping throwable there disconnects the session.
                MusicHud.LOGGER.error("Failed to send S2C payload {}", payload.getClass().getName(), t);
            }
        }
    }

    /**
     * Sends a C2S payload over the channel. Used by {@code ForgeClientNetworkService}.
     */
    public void sendToServer(C2SPayload payload) {
        var info = INetworkRegister.getCodecInfo((Class<? extends IPayload>) payload.getClass());
        if (info == null) {
            MusicHud.LOGGER.error("C2S payload {} was never registered", payload.getClass().getName());
            return;
        }
        try {
            ResourceLocation ignored = info.type().id();
            channel().sendToServer(payload);
        } catch (Throwable t) {
            // Encoding happens eagerly here, on whatever thread the caller used (button clicks and
            // the client-join handler are virtual threads, but this is also reachable from the UI
            // thread). Contain the failure so a broken/absent payload cannot surface as a crash or
            // as an exception escaping into the render path.
            MusicHud.LOGGER.error("Failed to send C2S payload {}", payload.getClass().getName(), t);
        }
    }

    public SimpleChannel getChannel() {
        return channel();
    }

    public String getProtocolVersion() {
        // Must report the same value the channel handshake advertises, otherwise a client and
        // server built from the same sources would still consider each other incompatible.
        return PROTOCOL_VERSION;
    }

    private record RegistrationInfo<T extends IPayload>(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver,
            NetworkReceiver<T> serverReceiver
    ) {
    }
}
