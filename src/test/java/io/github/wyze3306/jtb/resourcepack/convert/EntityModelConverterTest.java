package io.github.wyze3306.jtb.resourcepack.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityModelConverterTest {

    @Test
    void onlyRealVanillaEntitiesKeepTheirJavaLook() {
        assertTrue(EntityModelConverter.isVanilla("minecraft:zombie"));
        assertTrue(EntityModelConverter.isVanilla("minecraft:npc"));
        // Servers declare their own entities in the minecraft namespace too (Linesia's NPCs).
        assertFalse(EntityModelConverter.isVanilla("minecraft:npc_sylven"));
        assertFalse(EntityModelConverter.isVanilla("linesia:airdrop"));
    }
}
