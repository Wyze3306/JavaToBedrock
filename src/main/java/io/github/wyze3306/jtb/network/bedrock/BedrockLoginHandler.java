package io.github.wyze3306.jtb.network.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.auth.BedrockCrypto;
import io.github.wyze3306.jtb.auth.ClientDataBuilder;
import io.github.wyze3306.jtb.auth.LoginData;
import io.github.wyze3306.jtb.resourcepack.BedrockPackDownloader;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.skin.BedrockSkinData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.jose4j.jws.JsonWebSignature;

import javax.crypto.SecretKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * The Bedrock login sequence, from network settings up to {@code StartGame}:
 * <pre>
 * -> RequestNetworkSettings      <- NetworkSettings (compression)
 * -> Login (identity + client data)
 * <- ServerToClientHandshake     -> ClientToServerHandshake (encryption on)
 * <- PlayStatus LOGIN_SUCCESS    -> ClientCacheStatus(false)
 * <- ResourcePacksInfo ... ResourcePackStack (see {@link BedrockPackDownloader})
 * <- StartGame                   (hand over to the translators)
 * </pre>
 * Runs on the session event loop.
 */
final class BedrockLoginHandler {

    enum Phase {
        NETWORK_SETTINGS, LOGIN, ENCRYPTION, RESOURCE_PACKS, GAME
    }

    private final ProxySession session;
    private final BedrockClientSession bedrock;
    private final BedrockCodec codec;
    private final LoginData login;
    private final BedrockSkinData skin;
    private Phase phase = Phase.NETWORK_SETTINGS;

    BedrockLoginHandler(ProxySession session, BedrockClientSession bedrock, BedrockCodec codec, LoginData login,
                        BedrockSkinData skin) {
        this.session = session;
        this.bedrock = bedrock;
        this.codec = codec;
        this.login = login;
        this.skin = skin;
    }

    Phase phase() {
        return this.phase;
    }

    /**
     * @return true when the packet belonged to the login sequence and was consumed
     */
    boolean handle(BedrockPacket packet) {
        if (this.phase == Phase.GAME) {
            return false;
        }
        if (packet instanceof NetworkSettingsPacket settings) {
            onNetworkSettings(settings);
            return true;
        }
        if (packet instanceof ServerToClientHandshakePacket handshake) {
            onHandshake(handshake);
            return true;
        }
        if (packet instanceof PlayStatusPacket status) {
            return onPlayStatus(status);
        }
        if (packet instanceof ResourcePacksInfoPacket || packet instanceof ResourcePackDataInfoPacket
                || packet instanceof ResourcePackChunkDataPacket || packet instanceof ResourcePackStackPacket) {
            this.phase = Phase.RESOURCE_PACKS;
            this.session.get(BedrockPackDownloader.KEY).handle(packet);
            return true;
        }
        if (packet instanceof StartGamePacket) {
            this.phase = Phase.GAME;
            return false;
        }
        return false;
    }

    private void onNetworkSettings(NetworkSettingsPacket settings) {
        this.bedrock.setCompression(settings.getCompressionAlgorithm());
        this.phase = Phase.LOGIN;
        sendLogin(this.login, this.skin);
    }

    private void sendLogin(LoginData loginData, BedrockSkinData skin) {
        if (!this.bedrock.isConnected()) {
            return;
        }
        try {
            var config = this.session.config().bedrock();
            String clientJwt = ClientDataBuilder.build(this.session, loginData.keyPair(), skin,
                    this.codec.getMinecraftVersion(), config.address() + ":" + config.port());
            LoginPacket packet = new LoginPacket();
            packet.setClientNetworkVersion(this.codec.getProtocolVersion());
            packet.setAuthenticationType(loginData.type());
            if (loginData.token() != null) {
                packet.setToken(loginData.token());
                packet.setChain(null);
            } else {
                packet.getChain().addAll(loginData.chain());
            }
            packet.setClientJwt(clientJwt);
            this.phase = Phase.ENCRYPTION;
            this.bedrock.sendPacketImmediately(packet);
        } catch (Exception e) {
            this.session.logger().error("[{}] Could not build the Bedrock login", this.session.getName(), e);
            this.session.disconnect("Erreur interne pendant la connexion Bedrock");
        }
    }

    private void onHandshake(ServerToClientHandshakePacket packet) {
        LoginData loginData = this.login;
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(packet.getHandshakeWebToken());
            PublicKey serverKey = BedrockCrypto.parseKey(jws.getHeader("x5u"));
            jws.setKey(serverKey);
            if (!jws.verifySignature()) {
                throw new IllegalStateException("Invalid server handshake signature");
            }
            JsonObject payload = JsonParser.parseString(jws.getUnverifiedPayload()).getAsJsonObject();
            byte[] salt = Base64.getDecoder().decode(payload.get("salt").getAsString());
            SecretKey key = BedrockCrypto.sessionKey(loginData.keyPair().getPrivate(), serverKey, salt);
            BedrockCrypto.enableEncryption(this.bedrock, key);
            this.bedrock.sendPacketImmediately(new ClientToServerHandshakePacket());
        } catch (Exception e) {
            this.session.logger().error("[{}] Bedrock encryption handshake failed", this.session.getName(), e);
            this.session.disconnect("Échec du chiffrement avec le serveur Bedrock");
        }
    }

    private boolean onPlayStatus(PlayStatusPacket packet) {
        switch (packet.getStatus()) {
            case LOGIN_SUCCESS -> {
                ClientCacheStatusPacket cache = new ClientCacheStatusPacket();
                cache.setCacheSupported(false);
                this.bedrock.sendPacketImmediately(cache);
                this.session.logger().info("[{}] Logged in to the Bedrock server as {}", this.session.getName(),
                        this.session.getBedrockName());
                return true;
            }
            case PLAYER_SPAWN -> {
                return false;
            }
            default -> {
                this.session.disconnect(LoginMessages.playStatus(packet.getStatus()));
                return true;
            }
        }
    }
}
