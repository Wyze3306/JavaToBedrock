package io.github.wyze3306.jtb.skin;

import io.github.wyze3306.jtb.network.java.JavaChannels;
import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SkinImage;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Sends full Bedrock skins (custom geometry, HD textures, capes) to Java clients running
 * ViaBedrockUtility or BedrockSkinUtility, which draw them like a Bedrock client does. Vanilla
 * clients rely on {@link SignedSkins} instead.
 * <p>
 * Wire format ported from ViaBedrock ({@code api/modinterface}, GPL-3.0), which these mods are
 * written against: big-endian ints, booleans as one byte, strings as an int length followed by UTF-8
 * bytes, and the image as raw RGBA split in chunks below the 1 MiB payload limit. ViaBedrock writes
 * the string length in UTF-16 units; the byte length is written here, which is the same for the ASCII
 * texts skins carry and correct for a reader taking the length in bytes.
 */
public final class SkinMods {

    private static final int VIABEDROCKUTILITY_CAPE = 3;
    private static final int VIABEDROCKUTILITY_SKIN_INFORMATION = 4;
    private static final int VIABEDROCKUTILITY_SKIN_DATA = 5;
    private static final int BEDROCKSKIN_CAPE = 0;
    private static final int BEDROCKSKIN_SKIN_INFORMATION = 1;
    private static final int BEDROCKSKIN_SKIN_DATA = 2;
    private static final int BEDROCKSKIN_VERSION = 1;
    private static final int MAX_CHUNK = 1048576 - 24;

    private SkinMods() {
    }

    /**
     * Sends {@code skin} for the player {@code uuid} if the client has one of the mods; does nothing
     * otherwise, or for persona skins (their pieces are not part of the payload).
     *
     * @return whether a mod received the skin
     */
    public static boolean send(ProxySession session, UUID uuid, SerializedSkin skin) {
        JavaChannels channels = session.get(JavaChannels.KEY);
        boolean viaBedrock = channels.has(JavaChannels.VIABEDROCKUTILITY);
        if (!viaBedrock && !channels.has(JavaChannels.BEDROCKSKINUTILITY)) {
            return false;
        }
        List<byte[]> payloads = viaBedrock ? viaBedrockUtility(uuid, skin) : bedrockSkinUtility(uuid, skin);
        if (payloads.isEmpty()) {
            return false;
        }
        Key channel = Key.key(viaBedrock ? JavaChannels.VIABEDROCKUTILITY : JavaChannels.BEDROCKSKINUTILITY);
        for (byte[] payload : payloads) {
            session.sendJava(new ClientboundCustomPayloadPacket(channel, payload));
        }
        return true;
    }

    static List<byte[]> viaBedrockUtility(UUID uuid, SerializedSkin skin) {
        SkinImage image = drawable(skin);
        if (image == null) {
            return List.of();
        }
        String geometry = skin.getGeometryData();
        boolean hasGeometry = hasGeometry(geometry);
        int chunks = chunkCount(image);
        List<byte[]> payloads = new ArrayList<>(chunks + 2);
        payloads.add(write(out -> {
            out.writeInt(VIABEDROCKUTILITY_SKIN_INFORMATION);
            writeUuid(out, uuid);
            out.writeInt(image.getWidth());
            out.writeInt(image.getHeight());
            writeString(out, nullToEmpty(skin.getResourcePatch()));
            out.writeBoolean(hasGeometry);
            if (hasGeometry) {
                writeString(out, geometry);
            }
            out.writeInt(chunks);
        }));
        addChunks(payloads, VIABEDROCKUTILITY_SKIN_DATA, uuid, image, chunks);
        SkinImage cape = skin.getCapeImageData();
        if (hasPixels(cape)) {
            payloads.add(write(out -> {
                out.writeInt(VIABEDROCKUTILITY_CAPE);
                writeUuid(out, uuid);
                writeCape(out, skin, cape);
            }));
        }
        return payloads;
    }

    static List<byte[]> bedrockSkinUtility(UUID uuid, SerializedSkin skin) {
        SkinImage image = drawable(skin);
        if (image == null) {
            return List.of();
        }
        String geometry = skin.getGeometryData();
        boolean hasGeometry = hasGeometry(geometry);
        int chunks = chunkCount(image);
        List<byte[]> payloads = new ArrayList<>(chunks + 2);
        payloads.add(write(out -> {
            out.writeInt(BEDROCKSKIN_SKIN_INFORMATION);
            out.writeInt(BEDROCKSKIN_VERSION);
            writeUuid(out, uuid);
            out.writeInt(image.getWidth());
            out.writeInt(image.getHeight());
            out.writeBoolean(hasGeometry);
            if (hasGeometry) {
                writeString(out, geometry);
                writeString(out, nullToEmpty(skin.getResourcePatch()));
            }
            out.writeInt(chunks);
        }));
        addChunks(payloads, BEDROCKSKIN_SKIN_DATA, uuid, image, chunks);
        SkinImage cape = skin.getCapeImageData();
        if (hasPixels(cape)) {
            payloads.add(write(out -> {
                out.writeInt(BEDROCKSKIN_CAPE);
                out.writeInt(BEDROCKSKIN_VERSION);
                writeUuid(out, uuid);
                writeCape(out, skin, cape);
            }));
        }
        return payloads;
    }

    private static SkinImage drawable(SerializedSkin skin) {
        if (skin == null || skin.isPersona() || !hasPixels(skin.getImageData())) {
            return null;
        }
        return skin.getImageData();
    }

    private static boolean hasPixels(SkinImage image) {
        return image != null && image.getWidth() > 0 && image.getHeight() > 0 && image.getImageBytes() != null
                && image.getImageBytes().length == image.getWidth() * image.getHeight() * 4;
    }

    private static boolean hasGeometry(String geometry) {
        return geometry != null && !geometry.isEmpty() && !geometry.toLowerCase(Locale.ROOT).equals("null");
    }

    private static int chunkCount(SkinImage image) {
        return Math.max(1, (image.getImageBytes().length + MAX_CHUNK - 1) / MAX_CHUNK);
    }

    private static void addChunks(List<byte[]> payloads, int type, UUID uuid, SkinImage image, int chunks) {
        byte[] pixels = image.getImageBytes();
        for (int i = 0; i < chunks; i++) {
            int index = i;
            byte[] chunk = chunks == 1 ? pixels : Arrays.copyOfRange(pixels, i * MAX_CHUNK, Math.min((i + 1) * MAX_CHUNK, pixels.length));
            payloads.add(write(out -> {
                out.writeInt(type);
                writeUuid(out, uuid);
                out.writeInt(index);
                out.write(chunk);
            }));
        }
    }

    private static void writeCape(DataOutputStream out, SerializedSkin skin, SkinImage cape) throws IOException {
        out.writeInt(cape.getWidth());
        out.writeInt(cape.getHeight());
        writeString(out, nullToEmpty(skin.getCapeID()));
        out.writeInt(cape.getImageBytes().length);
        out.write(cape.getImageBytes());
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
