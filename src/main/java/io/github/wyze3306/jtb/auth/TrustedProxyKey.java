package io.github.wyze3306.jtb.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The proxy's long-term EC P-384 key used in {@code trusted} mode. Stored as two PEM blocks
 * (PKCS#8 private key + X.509 public key) and generated on first start.
 */
public final class TrustedProxyKey {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrustedProxyKey.class);
    private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private TrustedProxyKey() {
    }

    public static KeyPair loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        if (Files.isRegularFile(file)) {
            return read(Files.readString(file, StandardCharsets.US_ASCII));
        }
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        String pem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()) + pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        LOGGER.info("Generated a new trusted proxy key in {}", file);
        return keyPair;
    }

    static KeyPair read(String text) throws GeneralSecurityException {
        PrivateKey privateKey = null;
        PublicKey publicKey = null;
        KeyFactory factory = KeyFactory.getInstance("EC");
        Matcher matcher = PEM.matcher(text);
        while (matcher.find()) {
            byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
            switch (matcher.group(1)) {
                case "PRIVATE KEY" -> privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
                case "PUBLIC KEY" -> publicKey = factory.generatePublic(new X509EncodedKeySpec(der));
                default -> {
                }
            }
        }
        if (privateKey == null || publicKey == null) {
            throw new GeneralSecurityException("The trusted key file must contain a PRIVATE KEY and a PUBLIC KEY block");
        }
        return new KeyPair(publicKey, privateKey);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
