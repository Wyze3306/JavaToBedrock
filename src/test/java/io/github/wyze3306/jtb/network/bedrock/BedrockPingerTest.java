package io.github.wyze3306.jtb.network.bedrock;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockPingerTest {

    private static byte[] png(int size) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void serverIconsAreScaledTo64() throws Exception {
        byte[] big = png(192);
        BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(BedrockPinger.serverIcon(big)));
        assertEquals(64, scaled.getWidth());
        assertEquals(64, scaled.getHeight());
        byte[] exact = png(64);
        assertArrayEquals(exact, BedrockPinger.serverIcon(exact), "a 64 x 64 PNG is kept as it is");
    }
}
