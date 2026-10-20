package io.github.wyze3306.jtb.auth;

import org.cloudburstmc.protocol.bedrock.data.auth.PlayerAuthenticationType;
import org.jspecify.annotations.Nullable;

import java.security.KeyPair;
import java.util.List;
import java.util.UUID;

/**
 * What the Bedrock {@code LoginPacket} needs from an authentication provider.
 *
 * @param keyPair     the identity key pair; it signs the client data and drives the key exchange
 * @param type        how the identity was authenticated
 * @param chain       certificate chain (offline/legacy form), empty when a token is used
 * @param token       authentication token (new login form), null when a chain is used
 * @param displayName the Bedrock display name the server will see
 * @param identity    the identity UUID the server will see
 * @param xuid        the Xbox user id, empty when not authenticated with Xbox Live
 */
public record LoginData(KeyPair keyPair, PlayerAuthenticationType type, List<String> chain, @Nullable String token,
                        String displayName, UUID identity, String xuid) {
}
