package io.reliablepush.support;

import java.math.BigInteger;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Generates throwaway EC private keys (PEM) for tests; nothing here is ever a real key and nothing is committed. */
public final class TestKeys {

    private static final byte[] OID_P256 = {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07};
    private static final Base64.Encoder PEM_BASE64 = Base64.getMimeEncoder(64, new byte[] {'\n'});

    private TestKeys() {}

    /** A fresh P-256 private key as PKCS#8 PEM ({@code BEGIN PRIVATE KEY}). */
    public static String newEcPem() {
        return pkcs8Pem(generate("secp256r1"));
    }

    /** A fresh private key on another curve (P-384), to test that non-P-256 keys are rejected. */
    public static String newP384Pem() {
        return pkcs8Pem(generate("secp384r1"));
    }

    /** A fresh P-256 private key in SEC1 form ({@code BEGIN EC PRIVATE KEY}, with curve parameters). */
    public static String newSec1Pem() {
        ECPrivateKey key = (ECPrivateKey) generate("secp256r1").getPrivate();
        byte[] d = toFixedLength(key.getS(), 32);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] {0x02, 0x01, 0x01}); // version 1
        body.writeBytes(new byte[] {0x04, 0x20}); // OCTET STRING, 32 bytes
        body.writeBytes(d);
        body.writeBytes(new byte[] {(byte) 0xA0, (byte) OID_P256.length}); // [0] parameters
        body.writeBytes(OID_P256);
        ByteArrayOutputStream der = new ByteArrayOutputStream();
        der.write(0x30);
        der.write(body.size());
        der.writeBytes(body.toByteArray());
        return pem("EC PRIVATE KEY", der.toByteArray());
    }

    /** Parses a PKCS#8 PEM produced by {@link #newEcPem()} back into a JDK key (test helper, independent of production code). */
    public static ECPrivateKey privateKey(String pkcs8Pem) {
        try {
            String base64 = pkcs8Pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh P-256 key pair; its PEM is {@link #pkcs8Pem(KeyPair)}. */
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

    public static String pkcs8Pem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + PEM_BASE64.encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, out, length - copy, copy);
        return out;
    }
}
