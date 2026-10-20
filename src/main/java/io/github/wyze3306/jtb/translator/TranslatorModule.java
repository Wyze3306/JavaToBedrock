package io.github.wyze3306.jtb.translator;

/**
 * A group of translators belonging to one subsystem (world, entities, inventory...).
 * Modules are listed in {@link Translators}.
 */
@FunctionalInterface
public interface TranslatorModule {

    void register(TranslatorRegistry registry);
}
