package jtb.e2e;

import org.powernukkitx.item.Item;
import org.powernukkitx.item.customitem.CustomItem;
import org.powernukkitx.item.customitem.CustomItemDefinition;
import org.powernukkitx.item.utils.ItemArmorType;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.registry.RegisterException;
import org.powernukkitx.registry.Registries;

/**
 * PowerNukkitX test plugin registering two custom items whose textures come from the e2e resource
 * pack written by {@code TestPacks}: {@code jtb:ruby} (icon {@code jtb_ruby}, hand equipped) and
 * {@code jtb:ruby_helmet} (same icon, worn on the head; its attachable is in the pack).
 * Build: see {@code ResourcePackScenario}.
 */
public final class JtbE2eItems extends PluginBase {

    @Override
    public void onLoad() {
        try {
            Registries.ITEM.registerCustomItem(this, Ruby.class, RubyHelmet.class);
        } catch (RegisterException e) {
            getLogger().error("Could not register the JavaToBedrock test items", e);
        }
    }

    public static final class Ruby extends Item implements CustomItem {
        public Ruby() {
            super("jtb:ruby");
        }

        @Override
        public CustomItemDefinition getDefinition() {
            return CustomItemDefinition.simpleBuilder(this).texture("jtb_ruby").handEquipped(true).build();
        }
    }

    public static final class RubyHelmet extends Item implements CustomItem {
        public RubyHelmet() {
            super("jtb:ruby_helmet");
        }

        @Override
        public CustomItemDefinition getDefinition() {
            return CustomItemDefinition.simpleBuilder(this).texture("jtb_ruby").wearable(ItemArmorType.HEAD, 2)
                    .maxStackSize(1).build();
        }
    }
}
