package io.github.wyze3306.jtb.translator;

import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * Translates one kind of packet received from the Bedrock server into whatever the Java client
 * must see (Java packets, session state changes...).
 * <p>
 * Always invoked on the session event loop, so implementations may freely read and mutate session
 * state without synchronisation.
 *
 * @param <P> the Bedrock packet type
 */
@FunctionalInterface
public interface BedrockTranslator<P extends BedrockPacket> {

    void translate(ProxySession session, P packet);
}
