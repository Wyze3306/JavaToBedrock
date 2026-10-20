package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.block.BlockData;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.GlobalPalette;
import org.geysermc.mcprotocollib.protocol.data.game.level.HeightmapTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLightUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks what the world subsystem sends: chunks the Java codec can decode, with terrain around the
 * player, valid biomes, heightmaps and computed light; block and light updates only for chunks the
 * client has; and, when the proxy forwards commands (needs {@code Tester<N>} in the server's
 * ops.txt), that {@code /setblock} changes show up as block updates with light, and with the states
 * Java derives from neighbours (redstone wire connections, snowy grass).
 */
public final class WorldScenario implements E2eScenario {

    private static final int OVERWORLD_SECTIONS = 24;
    private static final int MIN_Y = -64;
    private static final int BIOME_REGISTRY_SIZE = 67;

    private final Map<Long, ChunkSection[]> chunks = new ConcurrentHashMap<>();
    private final AtomicInteger decodeFailures = new AtomicInteger();
    private final AtomicInteger badBiomes = new AtomicInteger();
    private final AtomicInteger missingHeightmaps = new AtomicInteger();
    private final AtomicInteger updatesForUnknownChunks = new AtomicInteger();
    private final AtomicInteger lightUpdatesForUnknownChunks = new AtomicInteger();
    private final AtomicInteger chunksWithSkyGradient = new AtomicInteger();
    private final AtomicInteger chunksWithBlockLight = new AtomicInteger();
    private final AtomicInteger chunksWithoutSky = new AtomicInteger();
    private final AtomicInteger nonEmptyChunks = new AtomicInteger();
    private final AtomicInteger batches = new AtomicInteger();
    private final AtomicBoolean centerBeforeChunks = new AtomicBoolean();
    private final AtomicBoolean anyChunk = new AtomicBoolean();
    private final AtomicBoolean commands = new AtomicBoolean();
    private final Map<Long, Integer> lastBlockUpdates = new ConcurrentHashMap<>();
    private final AtomicBoolean glowstoneLight = new AtomicBoolean();
    /** False while every chunk came with sky light 15 everywhere: the proxy runs with {@code lighting: full-bright}. */
    private final AtomicBoolean computedLight = new AtomicBoolean();
    private int baseX;
    private int baseY;
    private int baseZ;

    public WorldScenario() {
        BlockData.load();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundSetChunkCacheCenterPacket && !this.anyChunk.get()) {
            this.centerBeforeChunks.set(true);
        } else if (packet instanceof ClientboundChunkBatchStartPacket) {
            this.batches.incrementAndGet();
        } else if (packet instanceof ClientboundCommandsPacket) {
            this.commands.set(true);
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            this.anyChunk.set(true);
            onChunk(chunk);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            this.chunks.remove(chunkKey(forget.getX(), forget.getZ()));
        } else if (packet instanceof ClientboundBlockUpdatePacket update) {
            apply(update.getEntry());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            for (BlockChangeEntry entry : update.getEntries()) {
                apply(entry);
            }
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            if (!this.chunks.containsKey(chunkKey(light.getX(), light.getZ()))) {
                this.lightUpdatesForUnknownChunks.incrementAndGet();
            }
            if (glowstoneLit(light)) {
                this.glowstoneLight.set(true);
            }
        }
    }

    private void onChunk(ClientboundLevelChunkWithLightPacket packet) {
        ByteBuf buf = Unpooled.wrappedBuffer(packet.getChunkData());
        ChunkSection[] sections = new ChunkSection[OVERWORLD_SECTIONS];
        try {
            boolean nonEmpty = false;
            for (int i = 0; i < OVERWORLD_SECTIONS; i++) {
                sections[i] = MinecraftTypes.readChunkSection(buf, JavaBlockStates.count(), BIOME_REGISTRY_SIZE);
                nonEmpty |= sections[i].getBlockCount() > 0;
                if (!biomesValid(sections[i].getBiomeData())) {
                    this.badBiomes.incrementAndGet();
                }
            }
            if (buf.isReadable()) {
                this.decodeFailures.incrementAndGet();
            }
            if (nonEmpty) {
                this.nonEmptyChunks.incrementAndGet();
            }
        } catch (RuntimeException e) {
            this.decodeFailures.incrementAndGet();
            return;
        }
        if (!packet.getHeightMaps().containsKey(HeightmapTypes.WORLD_SURFACE) || !packet.getHeightMaps().containsKey(HeightmapTypes.MOTION_BLOCKING)) {
            this.missingHeightmaps.incrementAndGet();
        }
        LightUpdateData light = packet.getLightData();
        if (light.getSkyYMask().cardinality() + light.getEmptySkyYMask().cardinality() != OVERWORLD_SECTIONS + 2) {
            this.chunksWithoutSky.incrementAndGet();
        }
        if (hasLevelBetween(light.getSkyUpdates())) {
            this.chunksWithSkyGradient.incrementAndGet();
        }
        if (!allFull(light.getSkyUpdates()) || !light.getBlockUpdates().isEmpty()) {
            this.computedLight.set(true);
        }
        if (hasLevelAbove(light.getBlockUpdates(), 0)) {
            this.chunksWithBlockLight.incrementAndGet();
        }
        this.chunks.put(chunkKey(packet.getX(), packet.getZ()), sections);
    }

    private static boolean biomesValid(DataPalette biomes) {
        if (biomes.getPalette() instanceof GlobalPalette) {
            for (int value : biomes.getStorage().toIntArray()) {
                if (value < 0 || value >= BIOME_REGISTRY_SIZE) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 0; i < biomes.getPalette().size(); i++) {
            int id = biomes.getPalette().idToState(i);
            if (id < 0 || id >= BIOME_REGISTRY_SIZE) {
                return false;
            }
        }
        return true;
    }

    /** Whether some light array has a value strictly between 0 and 15 (light spread, not only fills). */
    private static boolean hasLevelBetween(java.util.List<byte[]> arrays) {
        for (byte[] array : arrays) {
            for (byte b : array) {
                int low = b & 15;
                int high = (b >> 4) & 15;
                if (low > 0 && low < 15 || high > 0 && high < 15) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean allFull(java.util.List<byte[]> arrays) {
        for (byte[] array : arrays) {
            for (byte b : array) {
                if (b != (byte) 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasLevelAbove(java.util.List<byte[]> arrays, int level) {
        for (byte[] array : arrays) {
            for (byte b : array) {
                if ((b & 15) > level || ((b >> 4) & 15) > level) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a light update gives block light 15 at the glowstone placed by the scenario. */
    private boolean glowstoneLit(ClientboundLightUpdatePacket light) {
        int gx = this.baseX + 2;
        int gy = this.baseY + 1;
        int gz = this.baseZ;
        if (!this.commands.get() || light.getX() != gx >> 4 || light.getZ() != gz >> 4) {
            return false;
        }
        int section = ((gy - MIN_Y) >> 4) + 1;
        java.util.BitSet mask = light.getLightData().getBlockYMask();
        int array = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1), array++) {
            if (bit == section) {
                byte[] data = light.getLightData().getBlockUpdates().get(array);
                int index = ((gy & 15) << 8) | ((gz & 15) << 4) | (gx & 15);
                return ((data[index >> 1] >> ((index & 1) << 2)) & 15) == 15;
            }
        }
        return false;
    }

    private void apply(BlockChangeEntry entry) {
        int x = entry.getPosition().getX();
        int y = entry.getPosition().getY();
        int z = entry.getPosition().getZ();
        ChunkSection[] sections = this.chunks.get(chunkKey(x >> 4, z >> 4));
        if (sections == null) {
            this.updatesForUnknownChunks.incrementAndGet();
            return;
        }
        int index = (y - MIN_Y) >> 4;
        if (index >= 0 && index < sections.length) {
            sections[index].setBlock(x & 15, y & 15, z & 15, entry.getBlock());
        }
        this.lastBlockUpdates.put(blockKey(x, y, z), entry.getBlock());
    }

    private int blockAt(int x, int y, int z) {
        ChunkSection[] sections = this.chunks.get(chunkKey(x >> 4, z >> 4));
        int index = (y - MIN_Y) >> 4;
        if (sections == null || index < 0 || index >= sections.length) {
            return -1;
        }
        return sections[index].getBlock(x & 15, y & 15, z & 15);
    }

    @Override
    public void tick(ClientContext context, int tick) {
        if (tick == 80) {
            this.baseX = (int) Math.floor(context.x());
            this.baseY = (int) Math.floor(context.y());
            this.baseZ = (int) Math.floor(context.z());
            if (this.commands.get()) {
                context.send(new ServerboundChatCommandPacket("setblock " + this.baseX + " " + (this.baseY - 1) + " " + this.baseZ + " gold_block"));
            }
        } else if (tick == 110 && this.commands.get()) {
            context.send(new ServerboundChatCommandPacket("setblock " + (this.baseX + 2) + " " + (this.baseY + 1) + " " + this.baseZ + " glowstone"));
        } else if (tick == 140 && this.commands.get()) {
            context.send(new ServerboundChatCommandPacket("setblock " + (this.baseX + 2) + " " + (this.baseY + 1) + " " + this.baseZ + " air"));
        } else if (tick == 160 && this.commands.get()) {
            context.send(new ServerboundChatCommandPacket("setblock " + (this.baseX - 2) + " " + (this.baseY - 1) + " " + this.baseZ + " stone"));
        } else if (tick == 165 && this.commands.get()) {
            context.send(new ServerboundChatCommandPacket("setblock " + (this.baseX - 2) + " " + this.baseY + " " + this.baseZ + " redstone_wire"));
        } else if (tick == 170 && this.commands.get()) {
            context.send(new ServerboundChatCommandPacket("setblock " + (this.baseX - 2) + " " + (this.baseY - 1) + " " + (this.baseZ - 2) + " grass_block"));
        } else if (tick == 175 && this.commands.get()) {
            context.send(new ServerboundChatCommandPacket("setblock " + (this.baseX - 2) + " " + this.baseY + " " + (this.baseZ - 2) + " snow_layer"));
        }
    }

    @Override
    public void finish(ClientContext context) {
        int x = (int) Math.floor(context.x());
        int y = (int) Math.floor(context.y());
        int z = (int) Math.floor(context.z());
        context.log(String.format("world: %d chunks kept, %d non-empty, %d with sky gradient, %d with block light, %d batches",
                this.chunks.size(), this.nonEmptyChunks.get(), this.chunksWithSkyGradient.get(), this.chunksWithBlockLight.get(),
                this.batches.get()));
        context.check("received at least 50 chunks", context.count("ClientboundLevelChunkWithLightPacket") >= 50);
        context.check("chunks sent in batches", this.batches.get() > 0
                && context.count("ClientboundChunkBatchFinishedPacket") == this.batches.get());
        context.check("chunk cache centre sent before the first chunk", this.centerBeforeChunks.get());
        context.check("every chunk decodes with the Java codec", this.decodeFailures.get() == 0);
        context.check("chunk around the player received", this.chunks.containsKey(chunkKey(x >> 4, z >> 4)));
        boolean ground = false;
        for (int dy = 1; dy <= 4 && !ground; dy++) {
            int state = blockAt(x, y - dy, z);
            ground = state > 0 && !JavaBlockStates.isAir(state);
        }
        context.check("solid block under the player", ground);
        context.check("terrain has blocks", this.nonEmptyChunks.get() >= 50);
        context.check("biome ids are valid", this.badBiomes.get() == 0);
        context.check("heightmaps present", this.missingHeightmaps.get() == 0);
        context.check("sky light covers every section", this.chunksWithoutSky.get() == 0);
        if (this.computedLight.get()) {
            context.check("sky light is computed (levels between 0 and 15)", this.chunksWithSkyGradient.get() > 10);
        } else {
            context.log("full-bright lighting: every chunk has sky light 15 everywhere");
        }
        context.check("block updates only for chunks the client has", this.updatesForUnknownChunks.get() == 0);
        context.check("light updates only for chunks the client has", this.lightUpdatesForUnknownChunks.get() == 0);
        context.check("time received", context.last(ClientboundSetTimePacket.class) != null);
        if (this.commands.get()) {
            int gold = JavaBlockStates.parse("minecraft:gold_block");
            int glowstone = JavaBlockStates.parse("minecraft:glowstone");
            context.check("setblock gold_block shown", Integer.valueOf(gold).equals(
                    this.lastBlockUpdates.get(blockKey(this.baseX, this.baseY - 1, this.baseZ))));
            context.check("setblock glowstone then air shown", blockAt(this.baseX + 2, this.baseY + 1, this.baseZ) == JavaBlockStates.AIR
                    && this.lastBlockUpdates.containsKey(blockKey(this.baseX + 2, this.baseY + 1, this.baseZ)) && glowstone > 0);
            if (this.computedLight.get()) {
                context.check("glowstone lit its block (light update with block light 15)", this.glowstoneLight.get());
            }
            int wire = blockAt(this.baseX - 2, this.baseY, this.baseZ);
            context.log("wire shown as " + (wire < 0 ? "nothing" : JavaBlockStates.stateString(wire)));
            context.check("redstone wire shows connections (not a dot)", wire > 0
                    && JavaBlockStates.blockName(JavaBlockStates.blockId(wire)).equals("redstone_wire")
                    && !"none".equals(JavaBlockStates.property(wire, "north")));
            int grass = blockAt(this.baseX - 2, this.baseY - 1, this.baseZ - 2);
            context.log("grass under snow shown as " + (grass < 0 ? "nothing" : JavaBlockStates.stateString(grass)));
            context.check("grass under snow is snowy", grass == JavaBlockStates.parse("grass_block[snowy=true]"));
        } else {
            context.log("setblock checks skipped: this build does not forward commands");
        }
    }
}
