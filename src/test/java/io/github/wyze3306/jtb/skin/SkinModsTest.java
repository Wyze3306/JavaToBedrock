package io.github.wyze3306.jtb.skin;

import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SkinImage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinModsTest {

    private static final UUID PLAYER = UUID.fromString("3f2c9c4e-1b7a-4c8e-9f00-2d1e5a6b7c8d");

    private static SerializedSkin skin(int width, int height) {
        SerializedSkin skin = new SerializedSkin();
        skin.setImageData(new SkinImage(width, height, new byte[width * height * 4]));
        skin.setResourcePatch("{\"geometry\":{\"default\":\"geometry.custom\"}}");
        skin.setGeometryData("{\"format_version\":\"1.12.0\"}");
        skin.setCapeImageData(new SkinImage(64, 32, new byte[64 * 32 * 4]));
        skin.setCapeID("cape-id");
        return skin;
    }

    private static String readString(DataInputStream in) throws Exception {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    @Test
    void viaBedrockUtilityLayout() throws Exception {
        List<byte[]> payloads = SkinMods.viaBedrockUtility(PLAYER, skin(128, 128));
        assertEquals(3, payloads.size());
        DataInputStream info = new DataInputStream(new ByteArrayInputStream(payloads.get(0)));
        assertEquals(4, info.readInt());
        assertEquals(PLAYER, new UUID(info.readLong(), info.readLong()));
        assertEquals(128, info.readInt());
        assertEquals(128, info.readInt());
        assertEquals("{\"geometry\":{\"default\":\"geometry.custom\"}}", readString(info));
        assertTrue(info.readBoolean());
        assertEquals("{\"format_version\":\"1.12.0\"}", readString(info));
        assertEquals(1, info.readInt());
        assertEquals(0, info.available());

        DataInputStream data = new DataInputStream(new ByteArrayInputStream(payloads.get(1)));
        assertEquals(5, data.readInt());
        assertEquals(PLAYER, new UUID(data.readLong(), data.readLong()));
        assertEquals(0, data.readInt());
        assertEquals(128 * 128 * 4, data.available());

        DataInputStream cape = new DataInputStream(new ByteArrayInputStream(payloads.get(2)));
        assertEquals(3, cape.readInt());
        assertEquals(PLAYER, new UUID(cape.readLong(), cape.readLong()));
        assertEquals(64, cape.readInt());
        assertEquals(32, cape.readInt());
        assertEquals("cape-id", readString(cape));
        assertEquals(64 * 32 * 4, cape.readInt());
        assertEquals(64 * 32 * 4, cape.available());
    }

    @Test
    void bedrockSkinUtilityLayout() throws Exception {
        List<byte[]> payloads = SkinMods.bedrockSkinUtility(PLAYER, skin(64, 64));
        DataInputStream info = new DataInputStream(new ByteArrayInputStream(payloads.get(0)));
        assertEquals(1, info.readInt());
        assertEquals(1, info.readInt());
        assertEquals(PLAYER, new UUID(info.readLong(), info.readLong()));
        assertEquals(64, info.readInt());
        assertEquals(64, info.readInt());
        assertTrue(info.readBoolean());
        assertEquals("{\"format_version\":\"1.12.0\"}", readString(info));
        assertEquals("{\"geometry\":{\"default\":\"geometry.custom\"}}", readString(info));
        assertEquals(1, info.readInt());
        DataInputStream cape = new DataInputStream(new ByteArrayInputStream(payloads.get(2)));
        assertEquals(0, cape.readInt());
        assertEquals(1, cape.readInt());
    }

    @Test
    void personaSkinsAreNotSent() {
        SerializedSkin persona = skin(64, 64);
        persona.setPersona(true);
        assertTrue(SkinMods.viaBedrockUtility(PLAYER, persona).isEmpty());
        assertTrue(SkinMods.bedrockSkinUtility(PLAYER, persona).isEmpty());
    }
}
