package io.github.wyze3306.jtb.translator;

import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps packet classes to their translator. Exactly one translator per packet class: registering a
 * second one is a programming error. Lookups are by exact runtime class (packets are final
 * classes), which keeps dispatch to a single identity hash lookup.
 * <p>
 * The registry is filled once at startup and is read-only afterwards, so lookups are safe from any
 * thread.
 */
public final class TranslatorRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslatorRegistry.class);

    private final Map<Class<?>, BedrockTranslator<?>> bedrock = new IdentityHashMap<>();
    private final Map<Class<?>, JavaTranslator<?>> java = new IdentityHashMap<>();
    private boolean frozen;

    public <P extends BedrockPacket> void bedrock(Class<P> type, BedrockTranslator<? super P> translator) {
        checkNotFrozen();
        if (this.bedrock.putIfAbsent(type, translator) != null) {
            throw new IllegalStateException("Duplicate Bedrock translator for " + type.getName());
        }
    }

    public <P extends Packet> void java(Class<P> type, JavaTranslator<? super P> translator) {
        checkNotFrozen();
        if (this.java.putIfAbsent(type, translator) != null) {
            throw new IllegalStateException("Duplicate Java translator for " + type.getName());
        }
    }

    public boolean hasBedrock(Class<? extends BedrockPacket> type) {
        return this.bedrock.containsKey(type);
    }

    public boolean hasJava(Class<? extends Packet> type) {
        return this.java.containsKey(type);
    }

    /**
     * Translates a Bedrock packet. Must be called on the session event loop.
     *
     * @return {@code false} when no translator is registered for this packet class
     */
    @SuppressWarnings("unchecked")
    public <P extends BedrockPacket> boolean translateBedrock(ProxySession session, P packet) {
        BedrockTranslator<P> translator = (BedrockTranslator<P>) this.bedrock.get(packet.getClass());
        if (translator == null) {
            return false;
        }
        try {
            translator.translate(session, packet);
        } catch (Throwable t) {
            LOGGER.error("[{}] Failed to translate Bedrock packet {}", session.getName(), packet.getClass().getSimpleName(), t);
        }
        return true;
    }

    /**
     * Translates a Java packet. Must be called on the session event loop.
     *
     * @return {@code false} when no translator is registered for this packet class
     */
    @SuppressWarnings("unchecked")
    public <P extends Packet> boolean translateJava(ProxySession session, P packet) {
        JavaTranslator<P> translator = (JavaTranslator<P>) this.java.get(packet.getClass());
        if (translator == null) {
            return false;
        }
        try {
            translator.translate(session, packet);
        } catch (Throwable t) {
            LOGGER.error("[{}] Failed to translate Java packet {}", session.getName(), packet.getClass().getSimpleName(), t);
        }
        return true;
    }

    public int bedrockCount() {
        return this.bedrock.size();
    }

    public int javaCount() {
        return this.java.size();
    }

    void freeze() {
        this.frozen = true;
    }

    private void checkNotFrozen() {
        if (this.frozen) {
            throw new IllegalStateException("Translator registry is frozen");
        }
    }
}
