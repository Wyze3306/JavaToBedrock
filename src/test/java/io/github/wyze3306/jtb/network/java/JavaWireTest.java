package io.github.wyze3306.jtb.network.java;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaWireTest {

    @Test
    void noPreviousGameModeIsTheVanillaEmptyOptionalVarInt() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            MinecraftTypes.writePlayerSpawnInfo(buffer, new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 0L,
                    GameMode.CREATIVE, JavaWire.NO_PREVIOUS_GAME_MODE, false, false, null, 0, 63));
            MinecraftTypes.readVarInt(buffer);           // dimension type
            MinecraftTypes.readResourceLocation(buffer); // dimension
            buffer.readLong();                           // hashed seed
            assertEquals(1, MinecraftTypes.readVarInt(buffer), "game mode: VarInt id");
            // Vanilla 26.3: GameType.OPTIONAL_STREAM_CODEC = OPTIONAL_VAR_INT, 0 = none.
            assertEquals(0, MinecraftTypes.readVarInt(buffer), "previous game mode: none");
            assertEquals(5, buffer.readableBytes(), "debug, flat, no death location, portal cooldown, sea level");
        } finally {
            buffer.release();
        }
    }
}
