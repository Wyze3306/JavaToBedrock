package io.github.wyze3306.jtb.network.java;

import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;

/**
 * Values chosen so that what MCProtocolLib 26.3 writes is what the vanilla 26.3 client reads, where the
 * two disagree.
 */
public final class JavaWire {

    /**
     * {@code PlayerSpawnInfo.previousGamemode} for "none". The vanilla client reads that field as an
     * optional VarInt (0 = none, n + 1 = mode n) while MCProtocolLib writes a byte (-1 = none): the
     * {@code 0xFF} of a null value is an unfinished VarInt to the client, which then reads one byte too
     * many and fails to decode the login and respawn packets. MCProtocolLib writes survival as 0, which
     * the client reads as none.
     */
    public static final GameMode NO_PREVIOUS_GAME_MODE = GameMode.SURVIVAL;

    private JavaWire() {
    }
}
