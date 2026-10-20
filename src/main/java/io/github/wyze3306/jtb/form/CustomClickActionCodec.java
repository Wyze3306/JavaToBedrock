package io.github.wyze3306.jtb.form;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.network.codec.PacketDefinition;
import org.geysermc.mcprotocollib.network.codec.PacketSerializer;
import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

/**
 * Decoder for {@code ServerboundCustomClickAction}, replacing MCProtocolLib's.
 * <p>
 * The client writes {@code ResourceLocation id, VarInt length, optional tag} where the optional tag
 * is a tag type byte ({@code 0} = no payload) followed by the tag (at most 65536 bytes, depth 16).
 * MCProtocolLib's writer matches that (Geyser sends it to vanilla servers), but its reader expects an
 * extra boolean before the type byte, so every payload a real client sends decodes as garbage and
 * the connection is dropped. This reader parses the vanilla layout, bounded by the length prefix;
 * an unreadable or non-compound payload becomes "no payload" instead of killing the connection.
 */
final class CustomClickActionCodec implements PacketSerializer<ServerboundCustomClickActionPacket> {

    private static final int MAX_PAYLOAD = 65536;
    private static final int MAX_DEPTH = 16;
    private static boolean installed;

    CustomClickActionCodec() {
    }

    /** Installs the decoder in the configuration and game packet registries (once, at startup). */
    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        CustomClickActionCodec codec = new CustomClickActionCodec();
        for (ProtocolState state : new ProtocolState[]{ProtocolState.CONFIGURATION, ProtocolState.GAME}) {
            PacketRegistry registry = MinecraftCodec.CODEC.getCodec(state);
            int id = registry.getServerboundId(ServerboundCustomClickActionPacket.class);
            registry.registerServerbound(id, ServerboundCustomClickActionPacket.class, codec);
        }
    }

    @Override
    public void serialize(ByteBuf buf, ServerboundCustomClickActionPacket packet) {
        packet.serialize(buf);
    }

    @Override
    public ServerboundCustomClickActionPacket deserialize(ByteBuf buf, PacketDefinition<ServerboundCustomClickActionPacket> definition) {
        Key id = MinecraftTypes.readResourceLocation(buf);
        int length = MinecraftTypes.readVarInt(buf);
        if (length < 0 || length > MAX_PAYLOAD || length > buf.readableBytes()) {
            throw new IllegalArgumentException("Invalid custom click action payload length " + length);
        }
        return new ServerboundCustomClickActionPacket(id, readPayload(buf.readSlice(length)));
    }

    static @Nullable NbtMap readPayload(ByteBuf payload) {
        if (!payload.isReadable()) {
            return null;
        }
        int type = payload.readUnsignedByte();
        if (type == 0) {
            return null;
        }
        try {
            Object tag = new NBTInputStream(new ByteBufInputStream(payload)).readValue(NbtType.byId(type), MAX_DEPTH);
            return tag instanceof NbtMap map ? map : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
