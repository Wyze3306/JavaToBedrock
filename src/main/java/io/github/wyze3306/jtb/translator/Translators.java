package io.github.wyze3306.jtb.translator;

import io.github.wyze3306.jtb.chat.ChatModule;
import io.github.wyze3306.jtb.entity.EntityModule;
import io.github.wyze3306.jtb.form.FormModule;
import io.github.wyze3306.jtb.inventory.InventoryModule;
import io.github.wyze3306.jtb.player.PlayerModule;
import io.github.wyze3306.jtb.sound.SoundModule;
import io.github.wyze3306.jtb.ui.UiModule;
import io.github.wyze3306.jtb.world.WorldModule;

import java.util.List;

/**
 * The list of all translator modules. Each subsystem contributes one module; the order only
 * matters for readability since a packet class can only be claimed by a single translator.
 */
public final class Translators {

    private static final List<TranslatorModule> MODULES = List.of(
            new WorldModule(),
            new PlayerModule(),
            new EntityModule(),
            new InventoryModule(),
            new ChatModule(),
            new FormModule(),
            new UiModule(),
            new SoundModule()
    );

    private Translators() {
    }

    public static TranslatorRegistry createRegistry() {
        TranslatorRegistry registry = new TranslatorRegistry();
        for (TranslatorModule module : MODULES) {
            module.register(registry);
        }
        registry.freeze();
        return registry;
    }
}
