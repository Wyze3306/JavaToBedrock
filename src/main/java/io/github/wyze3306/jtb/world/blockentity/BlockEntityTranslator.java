package io.github.wyze3306.jtb.world.blockentity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.block.BedrockBlockPalette;
import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock block entities (chunk payloads, {@code BlockActorDataPacket}) to what the Java client
 * needs: the block entity NBT its renderers read, and the Java block state for blocks whose look
 * Bedrock keeps in the block entity (bed and banner colours, skull rotation, flower pot content,
 * lectern book, double chests). Stateless and thread safe.
 * <p>
 * Ported in part from ViaBedrock's block entity rewriters (GPL-3.0).
 */
public final class BlockEntityTranslator {

    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    /** Text colours Bedrock signs get from each dye (black is the default text), same order as {@link #DYES}. */
    private static final int[] DYE_SIGN_RGB = {0xF0F0F0, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x000000};
    /** Patterns of the ominous banner (Bedrock {@code Type: 1} carries no pattern list). */
    private static final String[][] OMINOUS = {{"rhombus", "cyan"}, {"stripe_bottom", "light_gray"}, {"stripe_center", "gray"},
            {"border", "light_gray"}, {"stripe_middle", "black"}, {"half_horizontal", "light_gray"}, {"circle", "light_gray"},
            {"border", "black"}};
    /** Bedrock entity identifiers that differ in Java (spawner display). */
    private static final Map<String, String> ENTITY_IDS = Map.of(
            "minecraft:zombie_pigman", "minecraft:zombified_piglin",
            "minecraft:evocation_illager", "minecraft:evoker",
            "minecraft:villager_v2", "minecraft:villager",
            "minecraft:zombie_villager_v2", "minecraft:zombie_villager",
            "minecraft:ender_crystal", "minecraft:end_crystal",
            "minecraft:snow_golem", "minecraft:snow_golem",
            "minecraft:npc", "minecraft:villager");

    private static final Map<String, String> BANNER_PATTERNS = loadPatterns();

    private BlockEntityTranslator() {
    }

    private static Map<String, String> loadPatterns() {
        try (InputStream in = BlockEntityTranslator.class.getClassLoader().getResourceAsStream("mappings/world/block_entities.json")) {
            if (in == null) {
                throw new IOException("mappings/world/block_entities.json missing from the jar");
            }
            JsonObject patterns = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("banner_patterns");
            Map<String, String> map = new HashMap<>();
            patterns.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
            return Map.copyOf(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Block position packed within a chunk column: {@code x | z << 4 | (y - minY) << 8}. */
    public static int packLocal(int x, int y, int z, int minY) {
        return (x & 15) | ((z & 15) << 4) | ((y - minY) << 8);
    }

    /**
     * The Java state to show at a block whose Bedrock block entity is {@code nbt}.
     *
     * @param base the state mapped from the Bedrock block layers alone
     * @param x    absolute block x (double chests compare it with the partner position)
     * @param z    absolute block z
     */
    public static int javaState(int base, NbtMap nbt, int x, int z, BlockMapper mapper) {
        String block = JavaBlockStates.blockName(JavaBlockStates.blockId(base));
        if (block.endsWith("_bed")) {
            return JavaBlockStates.withBlock(base, dye(nbt.getByte("color", (byte) 14)) + "_bed");
        }
        if (block.endsWith("_banner")) {
            String color = dye(15 - (nbt.getInt("Base", 0) & 15));
            if (nbt.getInt("Type", 0) == 1) {
                color = "white";
            }
            return JavaBlockStates.withBlock(base, color + (block.endsWith("_wall_banner") ? "_wall_banner" : "_banner"));
        }
        if (JavaBlockStates.property(base, "rotation") != null && JavaBlockStates.blockEntityType(base) == BlockEntityType.SKULL) {
            int rotation;
            if (nbt.containsKey("Rot")) {
                rotation = nbt.getByte("Rot") & 15;
            } else {
                rotation = Math.floorMod(Math.round(nbt.getFloat("Rotation", 0f) / 22.5f), 16);
            }
            return JavaBlockStates.withProperty(base, "rotation", Integer.toString(rotation));
        }
        if (block.equals("flower_pot") || block.startsWith("potted_")) {
            NbtMap plant = nbt.getCompound("PlantBlock", null);
            int potted = -1;
            if (plant != null && plant.containsKey("name")) {
                int runtimeId = mapper.runtimeIdOf(plant.getString("name"), plant.getCompound("states", NbtMap.EMPTY));
                potted = BedrockBlockPalette.pottedState(mapper.toJava(runtimeId));
            }
            return potted >= 0 ? potted : JavaBlockStates.defaultState("flower_pot");
        }
        if (block.equals("lectern")) {
            boolean book = nbt.getBoolean("hasBook", false) || nbt.containsKey("book");
            return JavaBlockStates.withProperty(base, "has_book", Boolean.toString(book));
        }
        if (JavaBlockStates.property(base, "type") != null
                && (block.equals("chest") || block.equals("trapped_chest") || block.endsWith("copper_chest"))) {
            return JavaBlockStates.withProperty(base, "type", chestType(base, nbt, x, z));
        }
        return base;
    }

    /** {@code left}/{@code right} when the Bedrock chest is paired beside its facing, else {@code single}. */
    private static String chestType(int state, NbtMap nbt, int x, int z) {
        if (!nbt.containsKey("pairx") || !nbt.containsKey("pairz")) {
            return "single";
        }
        int dx = nbt.getInt("pairx") - x;
        int dz = nbt.getInt("pairz") - z;
        String facing = JavaBlockStates.property(state, "facing");
        // Clockwise of the facing direction: north -> east -> south -> west.
        int[] clockwise = switch (facing == null ? "north" : facing) {
            case "east" -> new int[]{0, 1};
            case "south" -> new int[]{-1, 0};
            case "west" -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
        if (dx == clockwise[0] && dz == clockwise[1]) {
            return "left";
        }
        if (dx == -clockwise[0] && dz == -clockwise[1]) {
            return "right";
        }
        return "single";
    }

    /**
     * The Java block entity NBT for a block in {@code javaState}, from its Bedrock block entity (null
     * when Bedrock sent none). Never null: Java renderers only need the type for most blocks.
     */
    public static NbtMap toJava(BlockEntityType type, int javaState, @Nullable NbtMap bedrock) {
        if (bedrock == null) {
            return NbtMap.EMPTY;
        }
        return switch (type) {
            case SIGN, HANGING_SIGN -> sign(bedrock);
            case BANNER -> banner(bedrock);
            case MOB_SPAWNER -> spawner(bedrock);
            case DECORATED_POT -> decoratedPot(bedrock);
            default -> named(bedrock);
        };
    }

    private static NbtMap named(NbtMap bedrock) {
        String name = bedrock.getString("CustomName", "");
        return name.isEmpty() ? NbtMap.EMPTY : NbtMap.builder().putString("CustomName", name).build();
    }

    private static NbtMap sign(NbtMap bedrock) {
        NbtMap front = bedrock.getCompound("FrontText", null);
        if (front == null) {
            // Signs saved before 1.20 keep one text at the root.
            front = NbtMap.builder().putString("Text", bedrock.getString("Text", ""))
                    .putInt("SignTextColor", bedrock.getInt("SignTextColor", 0xFF000000))
                    .putBoolean("IgnoreLighting", bedrock.getBoolean("IgnoreLighting", false)).build();
        }
        return NbtMap.builder()
                .putCompound("front_text", signText(front))
                .putCompound("back_text", signText(bedrock.getCompound("BackText", NbtMap.EMPTY)))
                .putBoolean("is_waxed", bedrock.getBoolean("IsWaxed", false))
                .build();
    }

    private static NbtMap signText(NbtMap text) {
        String[] lines = text.getString("Text", "").split("\n", -1);
        List<String> messages = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            messages.add(i < lines.length ? lines[i] : "");
        }
        int argb = text.getInt("SignTextColor", 0xFF000000);
        return NbtMap.builder()
                .putList("messages", NbtType.STRING, messages)
                .putString("color", nearestDye(argb & 0xFFFFFF))
                .putBoolean("has_glowing_text", text.getBoolean("IgnoreLighting", false) && !text.getBoolean("HideGlowOutline", false))
                .build();
    }

    private static String nearestDye(int rgb) {
        int best = 15;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < DYE_SIGN_RGB.length; i++) {
            int c = DYE_SIGN_RGB[i];
            long dr = ((c >> 16) & 255) - ((rgb >> 16) & 255);
            long dg = ((c >> 8) & 255) - ((rgb >> 8) & 255);
            long db = (c & 255) - (rgb & 255);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return DYES[best];
    }

    private static NbtMap banner(NbtMap bedrock) {
        List<NbtMap> patterns = new ArrayList<>();
        if (bedrock.getInt("Type", 0) == 1) {
            for (String[] pattern : OMINOUS) {
                patterns.add(NbtMap.builder().putString("pattern", "minecraft:" + pattern[0]).putString("color", pattern[1]).build());
            }
        } else {
            for (NbtMap pattern : bedrock.getList("Patterns", NbtType.COMPOUND, List.of())) {
                String id = BANNER_PATTERNS.get(pattern.getString("Pattern", ""));
                if (id != null) {
                    patterns.add(NbtMap.builder().putString("pattern", id)
                            .putString("color", dye(15 - (pattern.getInt("Color", 0) & 15))).build());
                }
            }
        }
        NbtMapBuilder out = NbtMap.builder();
        if (!patterns.isEmpty()) {
            out.putList("patterns", NbtType.COMPOUND, patterns);
        }
        return out.build();
    }

    private static NbtMap spawner(NbtMap bedrock) {
        String id = bedrock.getString("EntityIdentifier", "");
        if (id.isEmpty()) {
            return NbtMap.EMPTY;
        }
        id = ENTITY_IDS.getOrDefault(id, id);
        return NbtMap.builder().putCompound("SpawnData", NbtMap.builder()
                .putCompound("entity", NbtMap.builder().putString("id", id).build()).build()).build();
    }

    private static NbtMap decoratedPot(NbtMap bedrock) {
        List<String> sherds = bedrock.getList("sherds", NbtType.STRING, List.of());
        if (sherds.isEmpty()) {
            return NbtMap.EMPTY;
        }
        List<String> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String sherd = i < sherds.size() ? sherds.get(i) : "";
            out.add(sherd.isEmpty() ? "minecraft:brick" : sherd);
        }
        return NbtMap.builder().putList("sherds", NbtType.STRING, out).build();
    }

    private static String dye(int javaIndex) {
        return DYES[Math.floorMod(javaIndex, 16)];
    }

    /** Bedrock sign NBT with one side's text replaced by Java's four edited lines (for {@code BlockActorDataPacket}). */
    public static NbtMap signUpdate(@Nullable NbtMap stored, int x, int y, int z, boolean hanging, boolean front, List<String> lines) {
        int last = lines.size();
        while (last > 0 && lines.get(last - 1).isEmpty()) {
            last--;
        }
        String text = String.join("\n", lines.subList(0, last));
        NbtMap base = stored == null ? NbtMap.EMPTY : stored;
        NbtMapBuilder out = base.toBuilder()
                .putString("id", hanging ? "HangingSign" : "Sign")
                .putInt("x", x)
                .putInt("y", y)
                .putInt("z", z);
        String side = front ? "FrontText" : "BackText";
        String other = front ? "BackText" : "FrontText";
        out.putCompound(side, base.getCompound(side, NbtMap.EMPTY).toBuilder().putString("Text", text).build());
        if (!base.containsKey(other)) {
            out.putCompound(other, NbtMap.builder().putString("Text", "").build());
        }
        return out.build();
    }
}
