package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.block.PassThroughBlockDefinitions;
import io.github.wyze3306.jtb.item.ItemRegistry;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.common.NamedDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.List;

/**
 * Definition registries of the protocol codec. They must be up to date before the next packet is
 * decoded, and the library decodes the following packets of a batch right after the handler of the
 * current one returns - so these updates run synchronously on the Netty thread, before the packet is
 * handed to the session loop.
 */
final class CodecRegistries {

    private CodecRegistries() {
    }

    /** Called once the codec is set (setting the codec recreates the helper). */
    static void install(BedrockSession session) {
        BedrockCodecHelper helper = session.getPeer().getCodecHelper();
        // Servers send lists far above the library's defensive defaults (item registry, crafting data);
        // the server is the trusted side here.
        helper.setEncodingSettings(EncodingSettings.UNLIMITED);
        helper.setBlockDefinitions(new PassThroughBlockDefinitions());
        helper.setItemDefinitions(ItemRegistry.initialCodecRegistry());
        helper.setCameraPresetDefinitions(SimpleDefinitionRegistry.<NamedDefinition>builder().build());
    }

    /**
     * Updates the registries a packet defines. Runs on the Netty thread.
     *
     * @return the item registry built for an {@link ItemRegistryPacket}, else null
     */
    static DefinitionRegistry<ItemDefinition> onDecoded(BedrockSession session, BedrockPacket packet) {
        if (packet instanceof ItemRegistryPacket items) {
            DefinitionRegistry<ItemDefinition> registry = ItemRegistry.buildCodecRegistry(items.getItemData());
            session.getPeer().getCodecHelper().setItemDefinitions(registry);
            return registry;
        }
        if (packet instanceof CameraPresetsPacket cameras) {
            List<CameraPreset> presets = cameras.getCameraPresets();
            SimpleDefinitionRegistry.Builder<NamedDefinition> builder = SimpleDefinitionRegistry.builder();
            for (int i = 0; i < presets.size(); i++) {
                builder.add(new SimpleNamedDefinition(presets.get(i).getName(), i));
            }
            session.getPeer().getCodecHelper().setCameraPresetDefinitions(builder.build());
        }
        return null;
    }
}
