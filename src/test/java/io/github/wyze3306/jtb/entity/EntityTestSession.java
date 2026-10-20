package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.mapping.MappingData;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.channel.DefaultEventLoop;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A real {@link ProxySession} in the game phase whose Java side records the packets sent to it,
 * without any network: translators run on the session loop as in the proxy.
 */
final class EntityTestSession implements AutoCloseable {

    static final UUID PROFILE_UUID = UUID.fromString("00000000-0000-3000-8000-000000000001");

    private final DefaultEventLoop loop = new DefaultEventLoop();
    private final List<Packet> sent = new CopyOnWriteArrayList<>();
    final ProxySession session;

    EntityTestSession(Path dataDirectory) {
        MappingData.load();
        EntityDefinitions.load();
        Session java = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "send" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Packet packet) {
                            this.sent.add(packet);
                        }
                        yield null;
                    }
                    case "toString" -> "TestJavaSession";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        this.session = new ProxySession(new JavaToBedrock(dataDirectory, ProxyConfig.defaults()), java,
                new GameProfile(PROFILE_UUID, "Tester"), this.loop);
        run(this.session::enterGame);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        } else if (type == int.class || type == short.class || type == byte.class || type == char.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0f;
        } else if (type == double.class) {
            return 0d;
        }
        return null;
    }

    /** Runs {@code task} on the session loop and waits for it. */
    void run(Runnable task) {
        try {
            this.loop.submit(task).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Task failed", e);
        }
    }

    EntityTracker tracker() {
        return this.session.get(EntityTracker.KEY);
    }

    /** Packets of {@code type} sent to the Java client so far. */
    <T extends Packet> List<T> sent(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Packet packet : this.sent) {
            if (type.isInstance(packet)) {
                out.add(type.cast(packet));
            }
        }
        return out;
    }

    List<Packet> all() {
        return List.copyOf(this.sent);
    }

    void clear() {
        this.sent.clear();
    }

    /** Every entity data entry sent for {@code javaId}, the last value of each index. */
    Map<Integer, Object> data(int javaId) {
        Map<Integer, Object> values = new TreeMap<>();
        for (ClientboundSetEntityDataPacket packet : sent(ClientboundSetEntityDataPacket.class)) {
            if (packet.getEntityId() == javaId) {
                for (EntityMetadata<?, ?> entry : packet.getMetadata()) {
                    values.put(entry.getId(), entry.getValue());
                }
            }
        }
        return values;
    }

    @Override
    public void close() {
        this.loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
}
