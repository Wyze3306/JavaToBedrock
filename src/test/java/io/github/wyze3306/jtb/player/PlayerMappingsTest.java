package io.github.wyze3306.jtb.player;

import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMappingsTest {

    @Test
    void bedrockEffectIds() {
        assertEquals(Effect.SPEED, EffectMappings.toJava(1));
        assertEquals(Effect.POISON, EffectMappings.toJava(25), "fatal poison has no Java counterpart");
        assertEquals(Effect.HERO_OF_THE_VILLAGE, EffectMappings.toJava(29));
        assertEquals(Effect.TRIAL_OMEN, EffectMappings.toJava(31));
        assertEquals(Effect.WIND_CHARGED, EffectMappings.toJava(32));
        assertEquals(Effect.RAID_OMEN, EffectMappings.toJava(36));
        assertEquals(Effect.BREATH_OF_THE_NAUTILUS, EffectMappings.toJava(37));
        assertNull(EffectMappings.toJava(0));
        assertNull(EffectMappings.toJava(500));
    }

    @Test
    void instantBreakStates() {
        // Java 26.3 state ids: 0 air, 1 stone, 30 oak sapling (stage 0).
        assertFalse(BlockBreaking.InstantBreak.isInstant(0));
        assertFalse(BlockBreaking.InstantBreak.isInstant(1));
        assertTrue(BlockBreaking.InstantBreak.isInstant(30));
    }

    @Test
    void experienceTotals() {
        assertEquals(0, LocalStats.totalExperience(0, 0));
        assertEquals(7, LocalStats.totalExperience(1, 0));
        assertEquals(352, LocalStats.totalExperience(16, 0));
        assertEquals(1395, LocalStats.totalExperience(30, 0));
        assertEquals(1507, LocalStats.totalExperience(31, 0));
        assertEquals(4, LocalStats.totalExperience(0, 0.5f));
    }

    @Test
    void cooldownGroups() {
        assertEquals(Key.key("minecraft:ender_pearl"), StatusTranslators.cooldownGroup("minecraft:ender_pearl"));
        assertEquals(Key.key("minecraft:shield"), StatusTranslators.cooldownGroup("shield"));
        assertEquals(Key.key("custom:my_item"), StatusTranslators.cooldownGroup("Custom:My Item"));
        assertNull(StatusTranslators.cooldownGroup(""));
    }

    @Test
    void gameModes() {
        for (GameMode mode : GameMode.values()) {
            assertEquals(mode, GameModes.toJava(GameModes.toBedrock(mode)));
        }
        assertEquals(GameMode.SURVIVAL, GameModes.toJava(null));
    }

    @Test
    void yawWrapping() {
        assertEquals(0, AuthInputBuilder.wrapDegrees(720), 1e-4);
        assertEquals(-90, AuthInputBuilder.wrapDegrees(270), 1e-4);
        assertEquals(179.5f, AuthInputBuilder.wrapDegrees(-180.5f), 1e-4);
    }
}
