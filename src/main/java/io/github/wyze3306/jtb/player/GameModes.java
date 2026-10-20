package io.github.wyze3306.jtb.player;

import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;

/**
 * Bedrock game types to Java game modes. The "viewer" types are Bedrock's spectating variants of
 * survival/creative; DEFAULT means "the world's default" and is resolved by the caller.
 */
public final class GameModes {

    private GameModes() {
    }

    public static GameMode toJava(GameType type) {
        if (type == null) {
            return GameMode.SURVIVAL;
        }
        return switch (type) {
            case CREATIVE -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SPECTATOR, SURVIVAL_VIEWER, CREATIVE_VIEWER -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
    }

    public static GameType toBedrock(GameMode mode) {
        return switch (mode) {
            case CREATIVE -> GameType.CREATIVE;
            case ADVENTURE -> GameType.ADVENTURE;
            case SPECTATOR -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
        };
    }
}
