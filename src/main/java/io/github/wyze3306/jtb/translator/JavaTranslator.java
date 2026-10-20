package io.github.wyze3306.jtb.translator;

import io.github.wyze3306.jtb.session.ProxySession;
import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * Translates one kind of packet received from the Java client into Bedrock packets sent to the
 * server (and/or local state changes).
 * <p>
 * Always invoked on the session event loop.
 *
 * @param <P> the Java packet type
 */
@FunctionalInterface
public interface JavaTranslator<P extends Packet> {

    void translate(ProxySession session, P packet);
}
