package io.github.wyze3306.jtb.item;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.mapping.MappingData;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test support: a {@link ProxySession} without network (Java packets are recorded, Bedrock packets are
 * dropped) whose item registry knows every vanilla Bedrock item of the mapping data, plus helpers to
 * build Bedrock stacks.
 */
public final class TestItems {

    private static final EventLoop LOOP = new DefaultEventLoop();

    /** A session and the Java packets sent to its client. */
    public record TestSession(ProxySession session, List<Packet> javaPackets) {

        public ItemDefinition definition(String identifier) {
            ItemDefinition definition = this.session.get(ItemRegistry.KEY).byIdentifier(identifier);
            if (definition == null) {
                throw new IllegalArgumentException("Unknown item " + identifier);
            }
            return definition;
        }

        public ItemData item(String identifier, int count) {
            return ItemData.builder().definition(definition(identifier)).count(count).build();
        }

        public ItemData item(String identifier, int damage, int count, NbtMap tag) {
            return ItemData.builder().definition(definition(identifier)).damage(damage).count(count).tag(tag).build();
        }

        /** The Java packets of a type sent so far. */
        public <P extends Packet> List<P> sent(Class<P> type) {
            List<P> result = new ArrayList<>();
            for (Packet packet : this.javaPackets) {
                if (type.isInstance(packet)) {
                    result.add(type.cast(packet));
                }
            }
            return result;
        }
    }

    private TestItems() {
    }

    /**
     * A new session in the game phase, with an item registry made of every Bedrock identifier the
     * mapping data knows (runtime ids in that order) and the given extra definitions.
     */
    public static TestSession session(ItemDefinition... extra) {
        MappingData.load();
        List<Packet> packets = new CopyOnWriteArrayList<>();
        Session java = (Session) Proxy.newProxyInstance(TestItems.class.getClassLoader(), new Class<?>[]{Session.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("send") && args != null && args.length > 0 && args[0] instanceof Packet packet) {
                        packets.add(packet);
                        return null;
                    }
                    if (method.getName().equals("isConnected")) {
                        return true;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class || type == long.class || type == short.class || type == byte.class) {
                        return 0;
                    }
                    if (method.getName().equals("toString")) {
                        return "TestJavaSession";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    return null;
                });
        JavaToBedrock proxy = new JavaToBedrock(Path.of("build", "tmp", "test-data"), ProxyConfig.defaults());
        ProxySession session = new ProxySession(proxy, java, new GameProfile(UUID.randomUUID(), "Tester"), LOOP);
        try {
            Field phase = ProxySession.class.getDeclaredField("javaPhase");
            phase.setAccessible(true);
            phase.set(session, ProxySession.JavaPhase.GAME);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        List<ItemDefinition> definitions = new ArrayList<>(vanillaDefinitions());
        definitions.addAll(List.of(extra));
        session.get(ItemRegistry.KEY).load(definitions, ItemRegistry.buildCodecRegistry(definitions));
        return new TestSession(session, packets);
    }

    /** Every Bedrock identifier of the mapping data, as definitions with sequential runtime ids. */
    public static List<ItemDefinition> vanillaDefinitions() {
        ItemMappings mappings = ItemMappings.get();
        Set<String> identifiers = new LinkedHashSet<>();
        for (int id = 1; id < mappings.javaItemCount(); id++) {
            for (ItemMappings.BedrockVariant variant : mappings.bedrockVariants(id)) {
                identifiers.add(variant.identifier());
            }
        }
        List<ItemDefinition> definitions = new ArrayList<>(identifiers.size());
        int runtimeId = 1;
        for (String identifier : identifiers) {
            definitions.add(new SimpleItemDefinition(identifier, runtimeId++, ItemVersion.LEGACY, false, NbtMap.EMPTY));
        }
        return definitions;
    }

    /** A component-based (custom) item definition. */
    public static ItemDefinition custom(String identifier, int runtimeId, NbtMap componentData) {
        return new SimpleItemDefinition(identifier, runtimeId, ItemVersion.DATA_DRIVEN, true, componentData);
    }
}
