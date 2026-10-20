package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.block.BlockData;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moves the player around with commands (needs {@code Tester<N>} in ops.txt and a build that forwards
 * commands): a far teleport (old chunks forgotten, cache centre follows, new chunks arrive), then the
 * nether and back (Java respawns into the right dimension type, nether chunks have no sky light).
 */
public final class WorldTravelScenario implements E2eScenario {

    private static final int FAR = 4000;

    private final AtomicBoolean commands = new AtomicBoolean();
    private final AtomicInteger phase = new AtomicInteger();
    private final AtomicInteger forgotten = new AtomicInteger();
    private final AtomicInteger farChunks = new AtomicInteger();
    private final AtomicBoolean farCentre = new AtomicBoolean();
    private final AtomicInteger netherChunks = new AtomicInteger();
    private final AtomicInteger netherChunksWithSky = new AtomicInteger();
    private final AtomicInteger netherDecodeFailures = new AtomicInteger();
    private final AtomicBoolean netherRespawn = new AtomicBoolean();
    private final AtomicBoolean netherPosition = new AtomicBoolean();
    private final AtomicBoolean overworldRespawn = new AtomicBoolean();
    private final AtomicInteger overworldChunksAfter = new AtomicInteger();

    public WorldTravelScenario() {
        BlockData.load();
    }

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        int phase = this.phase.get();
        if (packet instanceof ClientboundCommandsPacket) {
            this.commands.set(true);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket && phase == 1) {
            this.forgotten.incrementAndGet();
        } else if (packet instanceof ClientboundSetChunkCacheCenterPacket centre && phase == 1
                && Math.abs(centre.getChunkX() - (FAR >> 4)) <= 2 && Math.abs(centre.getChunkZ() - (FAR >> 4)) <= 2) {
            this.farCentre.set(true);
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            String world = respawn.getCommonPlayerSpawnInfo().getWorldName().asString();
            if (phase == 2 && world.endsWith("the_nether")) {
                this.netherRespawn.set(true);
            } else if (phase == 3 && world.endsWith("overworld")) {
                this.overworldRespawn.set(true);
            }
        } else if (packet instanceof ClientboundPlayerPositionPacket && phase == 2 && this.netherRespawn.get()) {
            this.netherPosition.set(true);
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            if (phase == 1 && Math.abs(chunk.getX() - (FAR >> 4)) <= 10 && Math.abs(chunk.getZ() - (FAR >> 4)) <= 10) {
                this.farChunks.incrementAndGet();
            } else if (phase == 2 && this.netherRespawn.get()) {
                this.netherChunks.incrementAndGet();
                if (!chunk.getLightData().getSkyYMask().isEmpty()) {
                    this.netherChunksWithSky.incrementAndGet();
                }
                ByteBuf buf = Unpooled.wrappedBuffer(chunk.getChunkData());
                try {
                    for (int i = 0; i < 16; i++) {
                        MinecraftTypes.readChunkSection(buf, JavaBlockStates.count(), 67);
                    }
                    if (buf.isReadable()) {
                        this.netherDecodeFailures.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    this.netherDecodeFailures.incrementAndGet();
                }
            } else if (phase == 3 && this.overworldRespawn.get()) {
                this.overworldChunksAfter.incrementAndGet();
            }
        }
    }

    @Override
    public void tick(ClientContext context, int tick) {
        if (!this.commands.get()) {
            return;
        }
        switch (tick) {
            case 60 -> {
                this.phase.set(1);
                context.send(new ServerboundChatCommandPacket("tp @s " + FAR + " 120 " + FAR));
            }
            case 260 -> {
                this.phase.set(2);
                context.send(new ServerboundChatCommandPacket("world tp world_the_nether"));
            }
            case 460 -> {
                this.phase.set(3);
                context.send(new ServerboundChatCommandPacket("world tp world"));
            }
            default -> {
            }
        }
    }

    @Override
    public void finish(ClientContext context) {
        if (!this.commands.get()) {
            context.log("travel checks skipped: this build does not forward commands");
            return;
        }
        context.log(String.format("travel: %d forgotten, %d far chunks, %d nether chunks (%d with sky), %d overworld chunks after",
                this.forgotten.get(), this.farChunks.get(), this.netherChunks.get(), this.netherChunksWithSky.get(),
                this.overworldChunksAfter.get()));
        context.check("far teleport: old chunks forgotten", this.forgotten.get() > 50);
        context.check("far teleport: cache centre follows", this.farCentre.get());
        context.check("far teleport: new chunks", this.farChunks.get() > 50);
        context.check("nether: Java respawn into the nether", this.netherRespawn.get());
        context.check("nether: position after the respawn", this.netherPosition.get());
        context.check("nether: chunks arrive", this.netherChunks.get() > 50);
        context.check("nether: 16 sections per chunk decode", this.netherDecodeFailures.get() == 0);
        context.check("nether: no sky light", this.netherChunksWithSky.get() == 0);
        context.check("back: Java respawn into the overworld", this.overworldRespawn.get());
        context.check("back: overworld chunks arrive", this.overworldChunksAfter.get() > 50);
    }
}
