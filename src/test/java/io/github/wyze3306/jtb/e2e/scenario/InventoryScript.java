package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import io.github.wyze3306.jtb.item.ItemMappings;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateRecipesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base of the inventory scenarios: a list of scripted steps run one after the other, a copy of what
 * the Java client was told about its screens (contents, cursor, properties, open screen), and helpers
 * to click, run commands, use blocks and ask the server how many items the player has
 * ({@code /clear <player> <item> 0 0}). The tester must be an operator.
 */
abstract class InventoryScript implements E2eScenario {

    private static final Pattern COUNT = Pattern.compile("(\\d+)");

    /** One scripted step: an action, then how many ticks to wait before the next one. */
    private record Step(String name, int delay, Runnable action) {
    }

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;
    private int nextStepTick = 60;

    private final Map<Integer, ItemStack[]> containers = new HashMap<>();
    private final Map<Integer, Integer> stateIds = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> properties = new HashMap<>();
    protected volatile ItemStack cursor;
    protected volatile int openId = -1;
    protected volatile ContainerType openType;
    protected volatile String openTitle = "";
    protected volatile int playerContents;
    protected volatile int slotsBeforeContent;
    protected volatile ClientboundUpdateRecipesPacket recipes;

    protected ClientContext context;
    protected String name = "Tester";
    protected Vector3i feet;
    private int sequence;
    private int chatMark;

    protected static int id(String path) {
        int id = ItemMappings.get().javaId(path);
        if (id <= 0) {
            throw new IllegalArgumentException("Unknown Java item " + path);
        }
        return id;
    }

    /** Adds the scenario's steps. */
    protected abstract void script();

    // ------------------------------------------------------------------ packets (network thread)

    @Override
    public synchronized void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundContainerSetContentPacket content) {
            if (content.getContainerId() == 0) {
                this.playerContents++;
            }
            this.containers.put(content.getContainerId(), Arrays.copyOf(content.getItems(), content.getItems().length));
            this.stateIds.put(content.getContainerId(), content.getStateId());
            this.cursor = content.getCarriedItem();
        } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            if (slot.getContainerId() == -1 && slot.getSlot() == -1) {
                this.cursor = slot.getItem();
                return;
            }
            ItemStack[] items = this.containers.get(slot.getContainerId());
            if (items == null && slot.getContainerId() == 0) {
                this.slotsBeforeContent++;
                items = new ItemStack[46];
                this.containers.put(0, items);
            }
            if (items != null && slot.getSlot() >= 0 && slot.getSlot() < items.length) {
                items[slot.getSlot()] = slot.getItem();
            }
            this.stateIds.put(slot.getContainerId(), slot.getStateId());
        } else if (packet instanceof ClientboundSetCursorItemPacket cursorPacket) {
            this.cursor = cursorPacket.getContents();
        } else if (packet instanceof ClientboundContainerSetDataPacket data) {
            this.properties.computeIfAbsent(data.getContainerId(), k -> new HashMap<>()).put(data.getRawProperty(), data.getValue());
        } else if (packet instanceof ClientboundOpenScreenPacket open) {
            this.openId = open.getContainerId();
            this.openType = open.getType();
            this.openTitle = plain(open.getTitle());
            this.properties.remove(open.getContainerId());
            context.log("Open screen " + open.getContainerId() + " " + open.getType() + " '" + this.openTitle + "'");
        } else if (packet instanceof ClientboundContainerClosePacket close) {
            if (close.getContainerId() == this.openId) {
                this.openId = -1;
            }
        } else if (packet instanceof ClientboundUpdateRecipesPacket update) {
            this.recipes = update;
        }
    }

    // ------------------------------------------------------------------ steps

    @Override
    public void onJoined(ClientContext context) {
        this.context = context;
        String property = System.getProperty("name");
        if (property != null) {
            this.name = property;
        }
        this.feet = Vector3i.from(Math.floor(context.x()), Math.floor(context.y()), Math.floor(context.z()));
        step("room", 30, () -> {
            // Room to breathe: the spawn point may be inside the terrain (no chunks without the world module).
            command("fill " + (this.feet.getX() - 4) + " " + this.feet.getY() + " " + (this.feet.getZ() - 4) + " "
                    + (this.feet.getX() + 4) + " " + (this.feet.getY() + 2) + " " + (this.feet.getZ() + 4) + " air");
            // And a floor: anvils fall and grindstones break without support.
            command("fill " + (this.feet.getX() - 4) + " " + (this.feet.getY() - 1) + " " + (this.feet.getZ() - 4) + " "
                    + (this.feet.getX() + 4) + " " + (this.feet.getY() - 1) + " " + (this.feet.getZ() + 4) + " stone");
            command("gamemode survival " + this.name);
            command("clear " + this.name);
        });
        script();
    }

    @Override
    public void tick(ClientContext context, int tick) {
        context.send(ServerboundClientTickEndPacket.INSTANCE);
        if (tick >= this.nextStepTick && this.stepIndex < this.steps.size()) {
            Step step = this.steps.get(this.stepIndex++);
            try {
                step.action().run();
            } catch (RuntimeException e) {
                check("step '" + step.name() + "' ran (" + e + ")", false);
            }
            this.nextStepTick = tick + step.delay();
        }
    }

    @Override
    public void finish(ClientContext context) {
        context.check("every inventory step ran (" + this.stepIndex + "/" + this.steps.size() + ")", this.stepIndex == this.steps.size());
        dump(0);
    }

    protected void step(String name, int delay, Runnable action) {
        this.steps.add(new Step(name, delay, action));
    }

    /** Asks the server how many {@code item} the player has, and checks the answer. */
    protected void serverCount(String item, int expected) {
        step("count " + item, 30, () -> {
            this.chatMark = this.context.chat().size();
            command("clear " + this.name + " " + item + " 0 0");
        });
        step("check count " + item, 1, () -> {
            List<String> lines = this.context.chat().subList(this.chatMark, this.context.chat().size());
            int count = expected == 0 ? -1 : -2;
            for (String line : lines) {
                if (line.contains(this.name)) {
                    Matcher matcher = COUNT.matcher(line.substring(line.indexOf(this.name) + this.name.length()));
                    count = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
                }
            }
            this.context.log("Server count of " + item + ": " + lines);
            check("server has " + expected + " " + item, count == expected || (expected == 0 && count <= 0));
        });
    }

    protected void command(String command) {
        this.context.send(new ServerboundChatCommandPacket(command));
    }

    protected void setblock(Vector3i block, String type) {
        command("setblock " + block.getX() + " " + block.getY() + " " + block.getZ() + " " + type);
    }

    protected void use(Vector3i block) {
        this.context.send(new ServerboundUseItemOnPacket(block, Direction.UP, Hand.MAIN_HAND, 0.5f, 1f, 0.5f, false, false,
                ++this.sequence));
    }

    /** Closes the shown screen (the player inventory when no other is open), like the Java client does locally. */
    protected void close() {
        int id = this.openId > 0 ? this.openId : 0;
        this.openId = -1;
        this.context.send(new ServerboundContainerClosePacket(id));
    }

    protected synchronized void click(int containerId, int slot, ContainerActionType type, ContainerAction action) {
        int stateId = this.stateIds.getOrDefault(containerId, 0);
        this.context.send(new ServerboundContainerClickPacket(containerId, stateId, slot, type, action, null,
                new Int2ObjectOpenHashMap<>()));
    }

    protected void check(String name, boolean ok) {
        this.context.check(name, ok);
        if (!ok) {
            dump(this.openId > 0 ? this.openId : 0);
        }
    }

    // ------------------------------------------------------------------ what Java was told

    protected synchronized ItemStack item(int containerId, int slot) {
        ItemStack[] items = this.containers.get(containerId);
        return items == null || slot < 0 || slot >= items.length ? null : items[slot];
    }

    protected synchronized int size(int containerId) {
        ItemStack[] items = this.containers.get(containerId);
        return items == null ? -1 : items.length;
    }

    protected synchronized int property(int containerId, int property) {
        Map<Integer, Integer> values = this.properties.get(containerId);
        Integer value = values == null ? null : values.get(property);
        return value == null ? Integer.MIN_VALUE : value;
    }

    protected boolean is(int containerId, int slot, int id, int amount) {
        ItemStack item = item(containerId, slot);
        return item != null && item.getId() == id && item.getAmount() == amount;
    }

    protected boolean empty(int containerId, int slot) {
        ItemStack item = item(containerId, slot);
        return item == null || item.getAmount() <= 0;
    }

    protected boolean cursorEmpty() {
        ItemStack item = this.cursor;
        return item == null || item.getAmount() <= 0;
    }

    protected static int count(ItemStack item, int id) {
        return item != null && item.getId() == id ? item.getAmount() : 0;
    }

    protected int total(int containerId, int from, int to, int id) {
        int total = 0;
        for (int slot = from; slot < to; slot++) {
            total += count(item(containerId, slot), id);
        }
        return total;
    }

    protected int find(int containerId, int from, int to, int id) {
        for (int slot = from; slot < to; slot++) {
            if (count(item(containerId, slot), id) > 0) {
                return slot;
            }
        }
        return -1;
    }

    /** First empty slot in the range, or -1. */
    protected int free(int containerId, int from, int to) {
        for (int slot = from; slot < to; slot++) {
            if (empty(containerId, slot)) {
                return slot;
            }
        }
        return -1;
    }

    protected static String customName(ItemStack item) {
        if (item == null || item.getDataComponentsPatch() == null) {
            return null;
        }
        Component name = item.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_NAME);
        return name == null ? null : plain(name);
    }

    protected static boolean enchanted(ItemStack item) {
        if (item == null || item.getDataComponentsPatch() == null) {
            return false;
        }
        var enchantments = item.getDataComponentsPatch().get(DataComponentTypes.ENCHANTMENTS);
        return enchantments != null && !enchantments.getEnchantments().isEmpty();
    }

    protected static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    protected synchronized void dump(int containerId) {
        ItemStack[] items = this.containers.get(containerId);
        StringBuilder text = new StringBuilder("Container ").append(containerId).append(':');
        if (items != null) {
            for (int slot = 0; slot < items.length; slot++) {
                if (items[slot] != null && items[slot].getAmount() > 0) {
                    text.append(' ').append(slot).append('=').append(describe(items[slot]));
                }
            }
        }
        ItemStack carried = this.cursor;
        if (carried != null && carried.getAmount() > 0) {
            text.append(" cursor=").append(describe(carried));
        }
        Map<Integer, Integer> values = this.properties.get(containerId);
        if (values != null && !values.isEmpty()) {
            text.append(" properties=").append(values);
        }
        this.context.log(text.toString());
    }

    private static String describe(ItemStack item) {
        String name = customName(item);
        return ItemMappings.get().javaItem(item.getId()).key().value() + 'x' + item.getAmount()
                + (name != null ? "[" + name + "]" : "") + (enchanted(item) ? "[ench]" : "");
    }
}
