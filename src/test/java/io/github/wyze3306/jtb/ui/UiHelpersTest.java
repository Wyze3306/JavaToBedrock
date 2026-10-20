package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.text.BedrockText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.protocol.bedrock.data.payload.boss.BossBarColor;
import org.cloudburstmc.protocol.bedrock.data.payload.boss.BossBarOverlay;
import org.cloudburstmc.protocol.bedrock.data.payload.map.MapDecoration;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarDivision;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapIcon;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapIconType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiHelpersTest {

    private static final Function<String, Component> LITERAL = BedrockText::legacyToComponent;

    @Test
    void mapColorsKeepExactPaletteColours() {
        for (int id = 4; id < 248; id++) {
            int rgb = MapColors.rgb(id);
            int abgr = 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
            assertEquals(rgb, MapColors.rgb(MapColors.fromBedrock(abgr) & 0xFF), "colour id " + id);
        }
        assertEquals(0, MapColors.fromBedrock(0x00FFFFFF));
        // Pure red is base 4 (fire), full shade: id 18.
        assertEquals(18, MapColors.fromBedrock(0xFF0000FF) & 0xFF);
        // A colour off the palette lands on a close one.
        int near = MapColors.rgb(MapColors.fromBedrock(0xFF0101FE) & 0xFF);
        assertEquals(0xFF0000, near);
    }

    @Test
    void mapDecorationsBecomeIcons() {
        MapIcon[] icons = MapState.icons(List.of(new MapDecoration(MapDecoration.Type.MARKER_WHITE, 9, 250, 10, "Moi", 0),
                new MapDecoration(MapDecoration.Type.NO_DRAW, 0, 0, 0, "", 0),
                new MapDecoration(MapDecoration.Type.WITCH_HUT, 0, 0, 0, "", 0)));
        assertEquals(2, icons.length);
        assertEquals(MapIconType.PLAYER, icons[0].getIconType());
        assertEquals(-6, icons[0].getCenterX());
        assertEquals(10, icons[0].getCenterZ());
        assertEquals(9, icons[0].getIconRotation());
        assertEquals(MapIconType.SWAMP_HUT, icons[1].getIconType());
        assertNull(icons[1].getDisplayName());
    }

    @Test
    void scoreboardSlotsAndAscendingOrder() {
        assertEquals(ScoreboardPosition.SIDEBAR, ScoreboardState.position("sidebar"));
        assertEquals(ScoreboardPosition.PLAYER_LIST, ScoreboardState.position("list"));
        assertEquals(ScoreboardPosition.BELOW_NAME, ScoreboardState.position("belowname"));
        assertNull(ScoreboardState.position("unknown"));
        assertEquals(-5, ScoreboardState.negate(5));
        assertEquals(Integer.MAX_VALUE, ScoreboardState.negate(Integer.MIN_VALUE));
    }

    @Test
    void bossBarStyles() {
        BossEventPacket packet = new BossEventPacket();
        packet.setColor(BossBarColor.BLUE);
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.BossBarColor.CYAN, BossBarState.color(packet));
        packet.setColor(BossBarColor.REBECCA_PURPLE);
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.BossBarColor.PURPLE, BossBarState.color(packet));
        packet.setColor(BossBarColor.WHITE);
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.BossBarColor.WHITE, BossBarState.color(packet));
        assertEquals(BossBarDivision.NOTCHES_12, BossBarState.division(BossBarOverlay.NOTCHED_12));
        assertEquals(BossBarDivision.NONE, BossBarState.division(null));
    }

    @Test
    void rawTextIsRendered() {
        Component component = RawText.toComponent("{\"rawtext\":[{\"text\":\"§aHello \"},{\"translate\":\"key.%s\",\"with\":[\"x\"]},"
                + "{\"selector\":\"@p\"}]}", LITERAL, (key, with) -> Component.text(key + with));
        assertEquals("Hello key.%s[x]@p", PlainTextComponentSerializer.plainText().serialize(component));
        Component nested = RawText.toComponent("{\"rawtext\":[{\"translate\":\"k\",\"with\":{\"rawtext\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}]}",
                LITERAL, (key, with) -> Component.text(key + with));
        assertEquals("k[a, b]", PlainTextComponentSerializer.plainText().serialize(nested));
        assertEquals("{not json", PlainTextComponentSerializer.plainText().serialize(
                RawText.toComponent("{not json", LITERAL, (key, with) -> Component.empty())));
        assertNull(RawText.parseRawText("plain"));
    }
}
