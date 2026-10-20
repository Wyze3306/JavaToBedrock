package io.github.wyze3306.jtb.inventory;

import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotInfo;

/**
 * A Bedrock slot: where it is mirrored ({@code container[index]}) and how item stack requests name it
 * ({@code name} + network slot).
 */
record SlotRef(BedrockContainer container, int index, ContainerEnumName name, int netSlot) {

    /**
     * Request slot data. {@code fullContainerName} is mandatory for PowerNukkitX; its dynamic id is only
     * used for bundles.
     */
    ItemStackRequestSlotInfo info(int stackNetworkId) {
        return new ItemStackRequestSlotInfo(this.name, this.netSlot, stackNetworkId, new FullContainerName(this.name, null));
    }

    boolean sameSlot(SlotRef other) {
        return this.container == other.container && this.index == other.index;
    }
}
