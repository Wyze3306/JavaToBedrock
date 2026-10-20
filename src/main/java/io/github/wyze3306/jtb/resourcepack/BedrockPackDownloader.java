package io.github.wyze3306.jtb.resourcepack;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackResponse;
import org.cloudburstmc.protocol.bedrock.data.payload.pack.PackInfoData;
import org.cloudburstmc.protocol.bedrock.data.payload.pack.PackInstanceId;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.jspecify.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bedrock side of resource packs: downloads every pack the server offers (from the local cache,
 * the server's CDN URL, or chunk by chunk over the protocol), then hands the final pack stack to
 * {@link ResourcePackState} for conversion while the Java client waits in its configuration phase.
 * <p>
 * Chunks are requested with a small window so a pack downloads at full speed without flooding the
 * server. Downloaded packs are verified (SHA-256 from {@code ResourcePackDataInfo}) and cached on
 * disk by id and version, so the next player and the next start reuse them.
 */
public final class BedrockPackDownloader implements SessionState {

    public static final StateKey<BedrockPackDownloader> KEY = StateKey.of("bedrock-pack-downloader", BedrockPackDownloader::new);

    private static final int REQUEST_WINDOW = 4;

    private final ProxySession session;
    /** Packs offered by the server, by id. */
    private final Map<UUID, PackInfoData> offered = new LinkedHashMap<>();
    /** Pack bytes once available (cache, CDN or protocol). */
    private final Map<UUID, BedrockPack> packs = new HashMap<>();
    private final Map<UUID, Download> downloads = new LinkedHashMap<>();
    private int pendingCdn;
    private boolean finishedSent;

    private BedrockPackDownloader(ProxySession session) {
        this.session = session;
    }

    private static final class Download {
        final ResourcePackDataInfoPacket info;
        final byte[] data;
        final boolean[] received;
        int nextRequest;
        int receivedCount;

        Download(ResourcePackDataInfoPacket info) {
            this.info = info;
            this.data = new byte[(int) info.getFileSize()];
            this.received = new boolean[chunkCount(info)];
        }
    }

    /**
     * Number of chunks of a pack. The announced count is ignored by the Bedrock client, which derives
     * it from the file and chunk sizes (ViaBedrock does the same); a server announcing another count
     * would otherwise leave the download hanging or request chunks that do not exist.
     */
    static int chunkCount(ResourcePackDataInfoPacket info) {
        if (info.getChunkSize() > 0) {
            return (int) ((info.getFileSize() + info.getChunkSize() - 1) / info.getChunkSize());
        }
        return (int) info.getNumberOfChunks();
    }

    public void handle(BedrockPacket packet) {
        if (packet instanceof ResourcePacksInfoPacket info) {
            onInfo(info);
        } else if (packet instanceof ResourcePackDataInfoPacket dataInfo) {
            onDataInfo(dataInfo);
        } else if (packet instanceof ResourcePackChunkDataPacket chunk) {
            onChunk(chunk);
        } else if (packet instanceof ResourcePackStackPacket stack) {
            onStack(stack);
        }
    }

    // ------------------------------------------------------------------ info

    private void onInfo(ResourcePacksInfoPacket info) {
        boolean convert = this.session.config().resourcePacks().enabled();
        PackCache cache = this.session.proxy().service(JavaPackService.class).cache();
        List<String> toDownload = new ArrayList<>();
        for (PackInfoData pack : info.getResourcePacks()) {
            UUID id = pack.getPackIdVersion().getPackUUID();
            String version = pack.getPackIdVersion().getPackVersion();
            this.offered.put(id, pack);
            if (!convert) {
                continue;
            }
            byte[] cached = cache.readBedrockPack(id, version, pack.getPackSize());
            if (cached != null) {
                this.packs.put(id, new BedrockPack(id, version, pack.getContentKey(), pack.getContentIdentity(),
                        pack.getSubpackName(), cached));
            } else if (pack.getCdnUrl() != null && !pack.getCdnUrl().isEmpty()) {
                downloadFromCdn(pack);
            } else {
                toDownload.add(id + "_" + version);
            }
        }
        this.session.logger().info("[{}] Bedrock server offers {} resource pack(s) ({} cached, {} to download)",
                this.session.getName(), this.offered.size(), this.packs.size(), toDownload.size() + this.pendingCdn);

        if (toDownload.isEmpty()) {
            if (this.pendingCdn == 0) {
                sendFinished();
            }
            return;
        }
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setResponse(ResourcePackResponse.DOWNLOADING);
        response.getDownloadingPacks().addAll(toDownload);
        this.session.sendBedrockImmediately(response);
    }

    private void downloadFromCdn(PackInfoData pack) {
        this.pendingCdn++;
        UUID id = pack.getPackIdVersion().getPackUUID();
        String version = pack.getPackIdVersion().getPackVersion();
        PackCache cache = this.session.proxy().service(JavaPackService.class).cache();
        cache.downloadCdn(pack.getCdnUrl()).whenComplete((bytes, error) -> this.session.execute(() -> {
            this.pendingCdn--;
            if (error != null || bytes == null) {
                this.session.logger().warn("[{}] Could not download pack {} from its CDN: {}", this.session.getName(), id,
                        error == null ? "empty" : error.toString());
            } else {
                cache.writeBedrockPack(id, version, bytes);
                this.packs.put(id, new BedrockPack(id, version, pack.getContentKey(), pack.getContentIdentity(),
                        pack.getSubpackName(), bytes));
            }
            maybeFinish();
        }));
    }

    // ------------------------------------------------------------------ protocol download

    private void onDataInfo(ResourcePackDataInfoPacket info) {
        if (info.getFileSize() <= 0 || info.getFileSize() > Integer.MAX_VALUE - 16 || chunkCount(info) <= 0) {
            this.session.logger().warn("[{}] Ignoring pack {} with invalid size", this.session.getName(), info.getPackId());
            return;
        }
        Download download = new Download(info);
        this.downloads.put(info.getPackId(), download);
        for (int i = 0; i < REQUEST_WINDOW; i++) {
            requestNext(download);
        }
    }

    private void requestNext(Download download) {
        if (download.nextRequest >= download.received.length) {
            return;
        }
        ResourcePackChunkRequestPacket request = new ResourcePackChunkRequestPacket();
        request.setPackId(download.info.getPackId());
        request.setPackVersion(download.info.getPackVersion());
        request.setChunk(download.nextRequest++);
        this.session.sendBedrockImmediately(request);
    }

    private void onChunk(ResourcePackChunkDataPacket chunk) {
        Download download = this.downloads.get(chunk.getPackId());
        if (download == null || chunk.getChunkID() < 0 || chunk.getChunkID() >= download.received.length
                || download.received[chunk.getChunkID()]) {
            return;
        }
        int length = chunk.getChunkData().readableBytes();
        long offset = chunk.getByteOffset();
        if (offset < 0 || offset + length > download.data.length) {
            this.session.logger().warn("[{}] Pack {} chunk {} out of bounds", this.session.getName(), chunk.getPackId(),
                    chunk.getChunkID());
            return;
        }
        chunk.getChunkData().getBytes(chunk.getChunkData().readerIndex(), download.data, (int) offset, length);
        download.received[chunk.getChunkID()] = true;
        download.receivedCount++;
        requestNext(download);
        if (download.receivedCount == download.received.length) {
            completeDownload(download);
        }
    }

    private void completeDownload(Download download) {
        UUID id = download.info.getPackId();
        this.downloads.remove(id);
        byte[] hash = download.info.getFileHash();
        if (hash != null && hash.length == 32 && !Arrays.equals(hash, sha256(download.data))) {
            this.session.logger().warn("[{}] Pack {} failed its hash check, skipping it", this.session.getName(), id);
        } else {
            PackInfoData info = this.offered.get(id);
            String version = download.info.getPackVersion();
            this.session.proxy().service(JavaPackService.class).cache().writeBedrockPack(id, version, download.data);
            this.packs.put(id, new BedrockPack(id, version, info == null ? "" : info.getContentKey(),
                    info == null ? "" : info.getContentIdentity(), info == null ? "" : info.getSubpackName(), download.data));
        }
        maybeFinish();
    }

    private void maybeFinish() {
        if (this.downloads.isEmpty() && this.pendingCdn == 0) {
            sendFinished();
        }
    }

    private void sendFinished() {
        if (this.finishedSent) {
            return;
        }
        this.finishedSent = true;
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setResponse(ResourcePackResponse.DOWNLOADING_FINISHED);
        this.session.sendBedrockImmediately(response);
    }

    // ------------------------------------------------------------------ stack

    private void onStack(ResourcePackStackPacket stack) {
        List<BedrockPack> ordered = new ArrayList<>();
        for (PackInstanceId instance : stack.getTexturePackList()) {
            BedrockPack pack = findPack(instance.getPackID());
            if (pack != null) {
                ordered.add(pack.withSubpack(instance.getSubPackName()));
            }
        }
        // Packs that were offered but not listed in the stack still apply, below the listed ones, in
        // the order the server offered them (behaviour packs are skipped by the converter).
        for (UUID id : this.offered.keySet()) {
            BedrockPack pack = this.packs.get(id);
            if (pack != null && ordered.stream().noneMatch(p -> p.id().equals(id))) {
                ordered.add(pack);
            }
        }

        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setResponse(ResourcePackResponse.RESOURCE_PACK_STACK_FINISHED);
        this.session.sendBedrockImmediately(response);

        this.session.get(ResourcePackState.KEY).onStack(ordered);
    }

    private @Nullable BedrockPack findPack(String id) {
        try {
            return this.packs.get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Packs downloaded for this session, by id. */
    public Map<UUID, BedrockPack> packs() {
        return this.packs;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
