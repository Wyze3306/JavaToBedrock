package io.github.wyze3306.jtb.auth;

import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.codec.FrameIdCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionEncoder;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Bedrock login cryptography (ES384 identity keys, ECDH key exchange, AES stream ciphers).
 * <p>
 * The protocol library has equivalents in {@code EncryptionUtils}, but that class contacts Mojang's
 * discovery service when it is loaded and fails to load when the service is unreachable (offline
 * LAN servers, firewalled hosts). The proxy never needs that service, so it uses this class instead
 * and wires the library's cipher handlers itself.
 */
public final class BedrockCrypto {

    public static final String ALGORITHM = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BedrockCrypto() {
    }

    /** A fresh EC P-384 key pair, as Bedrock clients use for their identity. */
    public static KeyPair createKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC P-384 is not available in this JVM", e);
        }
    }

    /** Base64 of the X.509 encoding, the form Bedrock puts in {@code x5u} and {@code identityPublicKey}. */
    public static String encodeKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static ECPublicKey parseKey(String base64) throws GeneralSecurityException {
        byte[] encoded = Base64.getDecoder().decode(base64.trim());
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /**
     * Signs {@code payload} (JSON) as an ES384 JWS whose {@code x5u} header carries the public key.
     */
    public static String sign(KeyPair keyPair, String payload) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(ALGORITHM);
        jws.setHeader("x5u", encodeKey(keyPair.getPublic()));
        jws.setPayload(payload);
        jws.setKey(keyPair.getPrivate());
        return jws.getCompactSerialization();
    }

    /**
     * Derives the session key from the server handshake: SHA-256(salt || ECDH(local, server)).
     */
    public static SecretKey sessionKey(PrivateKey localPrivate, PublicKey serverPublic, byte[] salt) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(localPrivate);
        agreement.doPhase(serverPublic, true);
        byte[] shared = agreement.generateSecret();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        digest.update(shared);
        return new SecretKeySpec(digest.digest(), "AES");
    }

    /**
     * Turns on packet encryption, exactly like {@code BedrockPeer#enableEncryption} but without
     * touching {@code EncryptionUtils}. Protocol 428+ uses AES-CTR, older versions AES-CFB8.
     */
    public static void enableEncryption(BedrockSession session, SecretKey key) throws GeneralSecurityException {
        boolean ctr = session.getCodec().getProtocolVersion() >= 428;
        var pipeline = session.getPeer().getChannel().pipeline();
        if (pipeline.get(BedrockEncryptionEncoder.class) != null) {
            throw new IllegalStateException("Encryption is already enabled");
        }
        pipeline.addAfter(FrameIdCodec.NAME, BedrockEncryptionEncoder.NAME, new BedrockEncryptionEncoder(key, cipher(ctr, true, key)));
        pipeline.addAfter(FrameIdCodec.NAME, BedrockEncryptionDecoder.NAME, new BedrockEncryptionDecoder(key, cipher(ctr, false, key)));
    }

    private static Cipher cipher(boolean ctr, boolean encrypt, SecretKey key) throws GeneralSecurityException {
        byte[] iv;
        String transformation;
        if (ctr) {
            iv = new byte[16];
            System.arraycopy(key.getEncoded(), 0, iv, 0, 12);
            iv[15] = 2;
            transformation = "AES/CTR/NoPadding";
        } else {
            iv = Arrays.copyOf(key.getEncoded(), 16);
            transformation = "AES/CFB8/NoPadding";
        }
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        return cipher;
    }

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
