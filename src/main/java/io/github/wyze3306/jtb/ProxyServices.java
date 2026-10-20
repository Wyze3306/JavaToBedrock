package io.github.wyze3306.jtb;

import io.github.wyze3306.jtb.resourcepack.JavaPackService;

import java.util.List;

/**
 * The list of proxy-wide services, started in order after the mappings and translators are loaded.
 */
final class ProxyServices {

    private ProxyServices() {
    }

    static List<ProxyService> create(JavaToBedrock proxy) {
        return List.of(
                new JavaPackService(proxy)
        );
    }
}
