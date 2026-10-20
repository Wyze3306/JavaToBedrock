package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The plugin channels the Java client listens on, as announced by its mods with
 * {@code minecraft:register} (Fabric and NeoForge do it during the configuration phase). Lets other
 * subsystems talk to client mods such as ViaBedrockUtility or BedrockSkinUtility only when they are
 * installed.
 */
public final class JavaChannels implements SessionState {

    public static final StateKey<JavaChannels> KEY = StateKey.of("java-channels", JavaChannels::new);

    /** Registered by ViaBedrockUtility, which waits for a confirmation on {@link #VIABEDROCKUTILITY}. */
    public static final String VIABEDROCKUTILITY_CONFIRM = "viabedrockutility:confirm";
    public static final String VIABEDROCKUTILITY = "viabedrockutility:data";
    public static final String BEDROCKSKINUTILITY = "bedrockskin:data";

    private static final Key REGISTER = Key.key("minecraft:register");
    private static final Key UNREGISTER = Key.key("minecraft:unregister");

    private final ProxySession session;
    private final Set<String> channels = new HashSet<>();

    private JavaChannels(ProxySession session) {
        this.session = session;
    }

    /**
     * Handles a {@code minecraft:register} / {@code minecraft:unregister} payload.
     *
     * @return whether the payload was one of them
     */
    public boolean onCustomPayload(ServerboundCustomPayloadPacket packet) {
        Key channel = packet.getChannel();
        boolean register = channel.equals(REGISTER);
        if (!register && !channel.equals(UNREGISTER)) {
            return false;
        }
        for (String name : new String(packet.getData(), StandardCharsets.UTF_8).split("\0")) {
            if (name.isEmpty()) {
                continue;
            }
            if (!register) {
                this.channels.remove(name);
            } else if (this.channels.add(name) && name.equals(VIABEDROCKUTILITY_CONFIRM)) {
                // ViaBedrockUtility payload type 0 (CONFIRM): the mod then accepts skins and models.
                this.session.sendJavaDirect(new ClientboundCustomPayloadPacket(Key.key(VIABEDROCKUTILITY), new byte[4]));
            }
        }
        return true;
    }

    public boolean has(String channel) {
        return this.channels.contains(channel);
    }

    @Override
    public boolean survivesServerSwitch() {
        return true;
    }
}
