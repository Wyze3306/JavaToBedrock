package io.github.wyze3306.jtb.resourcepack;

import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.text.BedrockLang;
import io.github.wyze3306.jtb.text.BedrockText;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The converted pack applied to this session and what it provides.
 */
public final class ResourcePackState implements SessionState {

    public static final StateKey<ResourcePackState> KEY = StateKey.of("resource-pack", ResourcePackState::new);

    private final ProxySession session;
    private @Nullable PackConversion conversion;

    private ResourcePackState(ProxySession session) {
        this.session = session;
    }

    /** Sends the pack to the Java client and publishes its artifacts to the session. */
    void apply(PackConversion conversion) {
        this.conversion = conversion;
        for (Map.Entry<String, Map<String, String>> language : conversion.artifacts().languages().entrySet()) {
            BedrockLang.forCode(language.getKey()).addPackEntries(language.getValue());
        }
        var config = this.session.config().resourcePacks();
        String url = this.session.proxy().service(JavaPackService.class).url(this.session, conversion.pack());
        this.session.get(JavaConfiguration.KEY).pushResourcePack(conversion.pack().id(), url, conversion.pack().sha1(),
                config.required(), BedrockText.legacyToComponent(config.prompt()));
    }

    public @Nullable ConversionArtifacts artifacts() {
        return this.conversion == null ? null : this.conversion.artifacts();
    }
}
