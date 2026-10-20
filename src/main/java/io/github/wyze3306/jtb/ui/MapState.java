package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.data.payload.map.MapDecoration;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundMapItemDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.MapInfoRequestPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapData;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapIcon;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapIconType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock maps on Java. Bedrock map ids are longs (the {@code map_uuid} of the item); Java map ids
 * are small ints, allocated here per session. {@link #javaMapId(long)} is the contract for the
 * inventory subsystem: a Java {@code filled_map} built from a Bedrock map item must carry
 * {@code map_id = javaMapId(bedrockId)}; the first call also asks the server for the map content
 * ({@code MapInfoRequest}, what a Bedrock client sends for a map it has no data for).
 * <p>
 * {@code ClientboundMapItemData}: pixels (ABGR) are converted to the Java palette
 * ({@link MapColors}), decorations to Java map icons. Known limitation: MCProtocolLib cannot encode
 * an empty-but-present icon list, so when every decoration disappears the old icons stay until the
 * next non-empty list.
 */
public final class MapState implements SessionState {

    public static final StateKey<MapState> KEY = StateKey.of("ui-maps", MapState::new);

    private final ProxySession session;
    private final Long2IntMap javaIds = new Long2IntOpenHashMap();
    private int nextId;

    private MapState(ProxySession session) {
        this.session = session;
        this.javaIds.defaultReturnValue(-1);
    }

    /**
     * The Java map id of a Bedrock map, allocated on first use; the first use also requests the map
     * from the server.
     */
    public int javaMapId(long bedrockMapId) {
        int id = this.javaIds.get(bedrockMapId);
        if (id < 0) {
            id = this.nextId++;
            this.javaIds.put(bedrockMapId, id);
            if (bedrockMapId > 0) {
                MapInfoRequestPacket request = new MapInfoRequestPacket();
                request.setMapUniqueID(bedrockMapId);
                this.session.sendBedrock(request);
            }
        }
        return id;
    }

    public void onMapData(ClientboundMapItemDataPacket packet) {
        int id = this.javaIds.get(packet.getMapID());
        if (id < 0) {
            id = this.nextId++;
            this.javaIds.put(packet.getMapID(), id);
        }
        byte scale = (byte) (packet.getScale() == null ? 0 : Math.max(0, Math.min(4, packet.getScale())));
        MapIcon[] icons = icons(packet.getDecorations());
        MapData data = null;
        IntList pixels = packet.getPixels();
        Integer width = packet.getWidth();
        Integer height = packet.getHeight();
        if (pixels != null && width != null && height != null && width > 0 && height > 0) {
            int columns = Math.min(128, width);
            int rows = Math.min(128, height);
            int startX = packet.getStartX() == null ? 0 : Math.max(0, Math.min(127, packet.getStartX()));
            int startY = packet.getStartY() == null ? 0 : Math.max(0, Math.min(127, packet.getStartY()));
            columns = Math.min(columns, 128 - startX);
            rows = Math.min(rows, 128 - startY);
            if (pixels.size() >= width * height) {
                byte[] colors = new byte[columns * rows];
                for (int y = 0; y < rows; y++) {
                    int row = y * width;
                    for (int x = 0; x < columns; x++) {
                        colors[y * columns + x] = MapColors.fromBedrock(pixels.getInt(row + x));
                    }
                }
                data = new MapData(columns, rows, startX, startY, colors);
            }
        }
        if (data == null && icons.length == 0 && packet.getDecorations() == null) {
            // Nothing the Java client can use (e.g. tracked entity list only).
            return;
        }
        this.session.sendJava(new org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundMapItemDataPacket(
                id, scale, packet.isLocked(), icons, data));
    }

    static MapIcon[] icons(@Nullable List<MapDecoration> decorations) {
        if (decorations == null || decorations.isEmpty()) {
            return new MapIcon[0];
        }
        List<MapIcon> icons = new ArrayList<>(decorations.size());
        for (MapDecoration decoration : decorations) {
            MapIconType type = iconType(decoration.getImageType());
            if (type == null) {
                continue;
            }
            String label = decoration.getLabel();
            icons.add(new MapIcon((byte) decoration.getX(), (byte) decoration.getY(), type, decoration.getRotation() & 0x0F,
                    label == null || label.isEmpty() ? null : Component.text(label)));
        }
        return icons.toArray(new MapIcon[0]);
    }

    static @Nullable MapIconType iconType(MapDecoration.@Nullable Type type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case MARKER_WHITE -> MapIconType.PLAYER;
            case MARKER_GREEN -> MapIconType.FRAME;
            case MARKER_RED -> MapIconType.RED_MARKER;
            case MARKER_BLUE, MARKER_TEAL -> MapIconType.BLUE_MARKER;
            case MARKER_PINK, MARKER_ORANGE, MARKER_YELLOW -> MapIconType.PLAYER;
            case X_WHITE -> MapIconType.TARGET_X;
            case TRIANGLE_RED, TRIANGLE_GREEN, MARKER_SIGN -> MapIconType.TARGET_POINT;
            case SQUARE_WHITE -> MapIconType.PLAYER_OFF_MAP;
            case SMALL_SQUARE_WHITE -> MapIconType.PLAYER_OFF_LIMITS;
            case MANSION -> MapIconType.MANSION;
            case MONUMENT -> MapIconType.MONUMENT;
            case VILLAGE_DESERT -> MapIconType.VILLAGE_DESERT;
            case VILLAGE_PLAINS -> MapIconType.VILLAGE_PLAINS;
            case VILLAGE_SAVANNA -> MapIconType.VILLAGE_SAVANNA;
            case VILLAGE_SNOWY -> MapIconType.VILLAGE_SNOWY;
            case VILLAGE_TAIGA -> MapIconType.VILLAGE_TAIGA;
            case JUNGLE_TEMPLE -> MapIconType.JUNGLE_TEMPLE;
            case WITCH_HUT -> MapIconType.SWAMP_HUT;
            case TRIAL_CHAMBERS -> MapIconType.TRIAL_CHAMBERS;
            case ABANDONED_CAMP -> MapIconType.ABANDONED_CAMP;
            case BURIED_ANCIENT_CITY -> MapIconType.ANCIENT_CITY;
            case BURIED_MINESHAFT -> MapIconType.MINESHAFT;
            case DESERT_PYRAMID -> MapIconType.DESERT_PYRAMID;
            case WARM_OCEAN_RUINS -> MapIconType.OCEAN_RUIN_WARM;
            case NO_DRAW, COUNT -> null;
        };
    }
}
