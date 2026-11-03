package io.reliablepush.support;

import io.reliablepush.devices.SigningKeys;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Generates throwaway EC key pairs (PEM) for tests; nothing here is ever a real key and nothing is committed. */
public final class TestKeys {

    private static final Base64.Encoder PEM_BASE64 = Base64.getMimeEncoder(64, new byte[] {'\n'});

    private TestKeys() {}

    /** A fresh P-256 key pair as PEMs: private PKCS#8 ({@code BEGIN PRIVATE KEY}), public SPKI ({@code BEGIN PUBLIC KEY}). */
    public static SigningKeys.PemPair newEcPemPair() {
        return pemPair(generate("secp256r1"));
    }

    /** A fresh P-384 pair, to test that non-P-256 keys are rejected. */
    public static SigningKeys.PemPair newP384PemPair() {
        return pemPair(generate("secp384r1"));
    }

    /** The PEM pair of an existing key pair. */
    public static SigningKeys.PemPair pemPair(KeyPair pair) {
        return new SigningKeys.PemPair(
                pem("PRIVATE KEY", pair.getPrivate().getEncoded()), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }

    /** The PEM text of a public key (SPKI). */
    public static String publicPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    /** Parses a PKCS#8 PEM produced by {@link #pemPair(KeyPair)} back into a JDK key (test helper, independent of production code). */
    public static ECPrivateKey privateKey(String pkcs8Pem) {
        try {
            String base64 = pkcs8Pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh P-256 key pair. */
    public static KeyPair newEcKeyPair() {
        return generate("secp256r1");
    }

    private static KeyPair generate(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + PEM_BASE64.encodeToString(der) + "\n-----END " + label + "-----\n";
    }
}
