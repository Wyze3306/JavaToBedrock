package io.github.wyze3306.jtb.skin;

import org.cloudburstmc.protocol.bedrock.data.payload.skin.ArmSizeType;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SkinImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignedSkinsTest {

    private static SerializedSkin skin(int width, int height, int bytes) {
        SerializedSkin skin = new SerializedSkin();
        byte[] rgba = new byte[bytes];
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = (byte) (i * 31);
        }
        skin.setImageData(new SkinImage(width, height, rgba));
        skin.setResourcePatch("{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}");
        skin.setArmSize(ArmSizeType.WIDE);
        return skin;
    }

    @Test
    void acceptsOnlySkinsJavaCanDraw() {
        assertNotNull(SignedSkins.Skin.of(skin(64, 64, 64 * 64 * 4)));
        assertNotNull(SignedSkins.Skin.of(skin(64, 32, 64 * 32 * 4)));
        assertNotNull(SignedSkins.Skin.of(skin(128, 128, 128 * 128 * 4)));
        assertNull(SignedSkins.Skin.of(skin(256, 256, 256 * 256 * 4)));
        assertNull(SignedSkins.Skin.of(skin(64, 64, 100)));
        SerializedSkin persona = skin(64, 64, 64 * 64 * 4);
        persona.setPersona(true);
        assertNull(SignedSkins.Skin.of(persona));
        assertNull(SignedSkins.Skin.of(null));
    }

    @Test
    void detectsSlimArms() {
        SerializedSkin slimPatch = skin(64, 64, 64 * 64 * 4);
        slimPatch.setResourcePatch("{\"geometry\":{\"default\":\"geometry.humanoid.customSlim\"}}");
        assertTrue(SignedSkins.Skin.of(slimPatch).slim());
        SerializedSkin slimArms = skin(64, 64, 64 * 64 * 4);
        slimArms.setArmSize(ArmSizeType.SLIM);
        assertTrue(SignedSkins.Skin.of(slimArms).slim());
    }

    @Test
    void hashMatchesTheSkinTheProxySent() {
        SerializedSkin relayed = skin(64, 64, 64 * 64 * 4);
        SkinImage image = relayed.getImageData();
        assertEquals(SignedSkins.hash(64, 64, image.getImageBytes()), SignedSkins.Skin.of(relayed).hash());
        // Same pixels, other dimensions: another skin.
        assertNotEquals(SignedSkins.hash(64, 64, new byte[16]), SignedSkins.hash(32, 128, new byte[16]));
    }

    @Test
    void hdSkinsAreScaledToJavaSize() throws Exception {
        SignedSkins.Skin hd = SignedSkins.Skin.of(skin(128, 128, 128 * 128 * 4));
        BufferedImage png = ImageIO.read(new ByteArrayInputStream(SignedSkins.javaPng(hd)));
        assertEquals(64, png.getWidth());
        assertEquals(64, png.getHeight());
        BufferedImage source = Images.fromRgba(hd.rgba(), 128, 128);
        assertEquals(source.getRGB(10, 6), png.getRGB(5, 3));

        SignedSkins.Skin classic = SignedSkins.Skin.of(skin(64, 64, 64 * 64 * 4));
        BufferedImage same = ImageIO.read(new ByteArrayInputStream(SignedSkins.javaPng(classic)));
        assertArrayEquals(classic.rgba(), Images.toRgba(same));
    }
}
