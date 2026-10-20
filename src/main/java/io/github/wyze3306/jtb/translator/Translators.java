package io.github.wyze3306.jtb.translator;

import java.util.List;

/**
 * The list of all translator modules. Each subsystem contributes one module; the order only
 * matters for readability since a packet class can only be claimed by a single translator.
 */
public final class Translators {

    private static final List<TranslatorModule> MODULES = List.of(
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
