package io.github.wyze3306.jtb.block;

import org.cloudburstmc.nbt.NbtMap;

/**
 * One look of custom block states that got no carrier: the Java client has an invisible block there
 * ({@code barrier}, or {@code structure_void} without collision) and the world subsystem shows the
 * model with an {@code item_display} entity in the block (item model
 * {@code JavaAssetNames.blockDisplayModel(id)} of the converted pack).
 *
 * @param id         display number, dense from 0 in allocation order
 * @param identifier the custom block
 * @param states     one state with this look (the first allocated)
 * @param definition the block definition NBT
 * @param rotationX  model rotation around X in degrees (0, 90, 180, 270), like a blockstate variant's {@code x}
 * @param rotationY  model rotation around Y in degrees, like a blockstate variant's {@code y}
 */
public record CustomBlockDisplay(int id, String identifier, NbtMap states, NbtMap definition, int rotationX, int rotationY) {
}
