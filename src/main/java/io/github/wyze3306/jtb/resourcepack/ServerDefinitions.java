package io.github.wyze3306.jtb.resourcepack;

import org.cloudburstmc.nbt.NbtMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the second conversion phase needs from the server besides its packs: the custom items of
 * its item registry and the custom block states with their allocated Java carrier states.
 *
 * @param items  custom (non-{@code minecraft}) items with their component NBT, sorted by identifier
 * @param blocks custom block states, sorted by carrier block then carrier state
 */
public record ServerDefinitions(List<CustomItem> items, List<CustomBlockCarrier> blocks) {

    public static final ServerDefinitions EMPTY = new ServerDefinitions(List.of(), List.of());

    /**
     * @param components the item's {@code componentData} from {@code ItemRegistryPacket}
     *                   ({@code {components: {item_properties: {minecraft:icon: ...}, ...}}})
     */
    public record CustomItem(String identifier, NbtMap components) {
    }

    public ServerDefinitions {
        items = items.stream().sorted(Comparator.comparing(CustomItem::identifier)).toList();
        blocks = blocks.stream().sorted(Comparator.comparing(CustomBlockCarrier::javaBlock)
                .thenComparing(carrier -> new TreeMap<>(carrier.javaProperties()).toString())).toList();
    }

    /** Hash of everything the conversion reads from these definitions (part of the cache key). */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CustomItem item : this.items) {
                update(digest, "item|" + item.identifier() + "|" + item.components());
            }
            for (CustomBlockCarrier block : this.blocks) {
                update(digest, "block|" + block.bedrockIdentifier() + "|" + block.bedrockStates() + "|" + block.definition()
                        + "|" + block.javaBlock() + "|" + new TreeMap<>(block.javaProperties()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /** True when there is nothing for the second phase to convert. */
    public boolean isEmpty() {
        return this.items.isEmpty() && this.blocks.isEmpty();
    }

    static String key(Map<String, String> properties) {
        return new TreeMap<>(properties).toString();
    }
}
