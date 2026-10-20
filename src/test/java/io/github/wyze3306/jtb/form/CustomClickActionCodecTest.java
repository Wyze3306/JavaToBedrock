package io.github.wyze3306.jtb.form;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Dialog clicks as the client writes them (MCProtocolLib's writer, used by Geyser against vanilla servers). */
class CustomClickActionCodecTest {

    @Test
    void decodesWhatTheClientWrites() {
        NbtMap payload = NbtMap.builder().putInt("button_id", 2).putString("1", "x".repeat(300)).putFloat("3", 7.5f).build();
        ServerboundCustomClickActionPacket decoded = roundTrip(new ServerboundCustomClickActionPacket(Key.key("jtb", "form/4"), payload));
        assertEquals(Key.key("jtb", "form/4"), decoded.getId());
        assertEquals(payload, decoded.getPayload());
    }

    @Test
    void decodesAMissingPayload() {
        assertNull(roundTrip(new ServerboundCustomClickActionPacket(Key.key("jtb", "server_settings"), null)).getPayload());
    }

    @Test
    void garbageBecomesNoPayloadAndStaysInBounds() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeBytes(new byte[]{3, 10, (byte) 0xFF, 0x7F, 0x42});
            assertNull(CustomClickActionCodec.readPayload(buffer.readSlice(3)));
            assertEquals(2, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static ServerboundCustomClickActionPacket roundTrip(ServerboundCustomClickActionPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.serialize(buffer);
            buffer.writeByte(0x55); // the next packet's data must not be touched
            ServerboundCustomClickActionPacket decoded = new CustomClickActionCodec().deserialize(buffer, null);
            assertEquals(1, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
