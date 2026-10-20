package io.github.wyze3306.jtb.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.network.bedrock.BedrockConnector;
import io.github.wyze3306.jtb.session.ProxySession;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.bedrock.model.MinecraftMultiplayerToken;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.util.holder.listener.BasicChangeListener;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.auth.PlayerAuthenticationType;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Real Xbox Live identity for each Java player: the first time, the player signs in to their
 * Microsoft account with a device code shown in a Java dialog while the client waits in the
 * configuration screen; the tokens are then cached per Java account and refreshed silently, so the
 * next logins are immediate. Works with any Bedrock server, including those with Xbox authentication.
 * <p>
 * Token files ({@code auth/microsoft/<java uuid>.json}) are credentials: they are written owner-only.
 */
public final class MicrosoftAuthProvider implements AuthProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftAuthProvider.class);

    private final JavaToBedrock proxy;
    private final Path directory;
    private final HttpClient http = MinecraftAuth.createHttpClient();

    public MicrosoftAuthProvider(JavaToBedrock proxy) {
        this.proxy = proxy;
        this.directory = proxy.dataDirectory().resolve("auth").resolve("microsoft");
    }

    @Override
    public CompletableFuture<LoginData> login(ProxySession session) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BedrockAuthManager auth = manager(session);
                MinecraftMultiplayerToken token = auth.getMinecraftMultiplayerToken().getUpToDate();
                return new LoginData(auth.getSessionKeyPair(), PlayerAuthenticationType.FULL, List.of(), token.getToken(),
                        token.getDisplayName(), token.getUuid(), token.getXuid());
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, this.proxy.workers());
    }

    private BedrockAuthManager manager(ProxySession session) throws Exception {
        UUID javaId = session.getProfile().getId();
        Path file = this.directory.resolve(javaId + ".json");
        String gameVersion = BedrockConnector.defaultCodec().getMinecraftVersion();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                BedrockAuthManager auth = BedrockAuthManager.fromJson(this.http, gameVersion, json);
                watch(auth, file);
                auth.getMinecraftMultiplayerToken().refreshIfExpired();
                return auth;
            } catch (Exception e) {
                LOGGER.warn("[{}] Saved Microsoft session unusable ({}), asking to sign in again", session.getName(), e.toString());
            }
        }
        Consumer<MsaDeviceCode> showCode = code -> session.execute(() -> showDeviceCode(session, code));
        BedrockAuthManager auth = BedrockAuthManager.create(this.http, gameVersion).login(DeviceCodeMsaAuthService::new, showCode);
        session.execute(() -> session.sendJavaDirect(new ClientboundClearDialogPacket()));
        save(auth, file);
        watch(auth, file);
        LOGGER.info("[{}] Signed in to Microsoft as {}", session.getName(),
                auth.getMinecraftMultiplayerToken().getUpToDate().getDisplayName());
        return auth;
    }

    private void watch(BedrockAuthManager auth, Path file) {
        auth.getChangeListeners().add((BasicChangeListener) () -> save(auth, file));
    }

    private void save(BedrockAuthManager auth, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "session", ".tmp");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX file system (Windows): the directory permissions apply.
            }
            Files.writeString(temp, BedrockAuthManager.toJson(auth).toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not save the Microsoft session {}", file, e);
        }
    }

    /**
     * Shows the sign-in code in a Java dialog (the client is still in the configuration phase, where
     * dialogs are allowed) with a button opening Microsoft's page with the code filled in.
     */
    private static void showDeviceCode(ProxySession session, MsaDeviceCode code) {
        LOGGER.info("[{}] Microsoft sign-in: open {} and enter the code {}", session.getName(), code.getVerificationUri(), code.getUserCode());
        NbtMap body = NbtMap.builder()
                .putString("type", "minecraft:plain_message")
                .putString("contents", "Ce serveur Bedrock demande un compte Microsoft.\n\n"
                        + "Ouvrez " + code.getVerificationUri() + " et saisissez le code :\n\n"
                        + code.getUserCode() + "\n\n"
                        + "La connexion continue automatiquement ensuite. Elle sera mémorisée pour les prochaines fois.")
                .putInt("width", 300)
                .build();
        NbtMap action = NbtMap.builder()
                .putString("label", "Ouvrir la page de connexion")
                .putCompound("action", NbtMap.builder()
                        .putString("type", "minecraft:open_url")
                        .putString("url", code.getDirectVerificationUri())
                        .build())
                .build();
        NbtMap dialog = NbtMap.builder()
                .putString("type", "minecraft:notice")
                .putString("title", "Connexion Microsoft / Xbox")
                .putList("body", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND, body))
                .putCompound("action", action)
                .putBoolean("can_close_with_escape", false)
                .build();
        session.sendJavaDirect(new ClientboundShowDialogConfigurationPacket(dialog));
    }
}
