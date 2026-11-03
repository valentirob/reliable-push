package io.reliablepush.devices;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.apps.ReliablePushProperties.SigningKey;
import io.reliablepush.shared.config.SecretRefs;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The device-token signing keys (spec 5): P-256 private keys loaded once at startup from {@code env:NAME} references,
 * identified by {@code kid}. {@link #current()} signs new tokens; {@link #byKid(String)} serves verification, so tokens
 * signed with a previous key stay valid while that key is still configured (rotation). Public keys are derived from the
 * private ones; a key is never taken from a token. Key material never appears in {@code toString} or error messages.
 *
 * <p>With no keys configured the application still starts ({@link #isConfigured()} is false) but cannot issue tokens.
 */
@Component
public class SigningKeys {

    private static final String PROPERTY = "reliable-push.device-token.signing-keys";
    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN (PRIVATE KEY|EC PRIVATE KEY)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");

    /** One signing key pair. {@code toString} shows the kid only. */
    public record Key(String kid, ECPrivateKey privateKey, ECPublicKey publicKey) {
        @Override
        public String toString() {
            return "Key[kid=" + kid + "]";
        }
    }

    private final Map<String, Key> keys;
    private final Key current;

    @Autowired
    public SigningKeys(ReliablePushProperties properties, Environment environment) {
        this(properties.deviceToken().currentKid(), resolvePems(properties, environment));
    }

    private SigningKeys(String currentKid, Map<String, String> pemByKid) {
        Map<String, Key> parsed = new LinkedHashMap<>();
        pemByKid.forEach((kid, pem) -> parsed.put(kid, parse(kid, pem)));
        if (!parsed.isEmpty() && (currentKid == null || !parsed.containsKey(currentKid))) {
            throw new IllegalStateException(
                    "reliable-push.device-token.current-kid must be the kid of one of the configured signing-keys");
        }
        this.keys = Map.copyOf(parsed);
        this.current = parsed.isEmpty() ? null : parsed.get(currentKid);
    }

    /** Builds the keys from PEM text directly (tests, tools). An empty map means "not configured". */
    public static SigningKeys fromPem(String currentKid, Map<String, String> pemByKid) {
        return new SigningKeys(currentKid, pemByKid);
    }

    public boolean isConfigured() {
        return current != null;
    }

    /** The key that signs new tokens. */
    public Key current() {
        if (current == null) {
            throw new IllegalStateException("device-token signing keys are not configured");
        }
        return current;
    }

    /** Looks a verification key up by the {@code kid} of a token header; empty if it is not (or no longer) configured. */
    public Optional<Key> byKid(String kid) {
        return Optional.ofNullable(kid).map(keys::get);
    }

    @Override
    public String toString() {
        return "SigningKeys[kids=" + keys.keySet() + ", current=" + (current == null ? null : current.kid()) + "]";
    }

    private static Map<String, String> resolvePems(ReliablePushProperties properties, Environment environment) {
        Map<String, String> pems = new LinkedHashMap<>();
        for (SigningKey configured : properties.deviceToken().signingKeys()) {
            pems.put(configured.kid(), SecretRefs.resolve(environment,
                    PROPERTY + "[" + configured.kid() + "].private-key-pem-ref", configured.privateKeyPemRef()).value());
        }
        return pems;
    }

    /**
     * Parses a PKCS#8 ({@code BEGIN PRIVATE KEY}) or SEC1 ({@code BEGIN EC PRIVATE KEY}) P-256 key with the JDK only
     * (nimbus's PEM reader needs BouncyCastle) and derives the public key. Failures carry no cause and no detail: either
     * could quote key material.
     */
    private static Key parse(String kid, String pem) {
        String where = PROPERTY + "[" + kid + "]";
        String invalid = where + " is not a valid PEM-encoded EC private key (PKCS#8 or SEC1)";
        ECPrivateKey privateKey;
        try {
            privateKey = readPrivateKey(pem);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException(invalid);
        }
        if (privateKey == null) {
            throw new IllegalStateException(invalid);
        }
        if (!isP256(privateKey.getParams())) {
            throw new IllegalStateException(where + " must be a P-256 (secp256r1) key: ES256 requires P-256");
        }
        try {
            ECPublicKey publicKey = derivePublic(privateKey);
            requireMatchingPair(privateKey, publicKey);
            return new Key(kid, privateKey, publicKey);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException(invalid);
        }
    }

    private static ECPrivateKey readPrivateKey(String pem) throws GeneralSecurityException {
        Matcher matcher = PEM_BLOCK.matcher(pem.replace("\\n", "\n"));
        if (!matcher.find()) {
            return null;
        }
        String label = matcher.group(1);
        byte[] der = Base64.getDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
        if (matcher.find()) {
            return null; // more than one private key in one value: ambiguous
        }
        byte[] pkcs8 = label.equals("EC PRIVATE KEY") ? sec1ToPkcs8(der) : der;
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        return key instanceof ECPrivateKey ec ? ec : null;
    }

    /**
     * Wraps a SEC1 {@code ECPrivateKey} (RFC 5915: version, private scalar, [0] curve OID, optional [1] public key) into
     * a PKCS#8 structure. The curve OID must be present and P-256, otherwise the wrapped key would claim a curve it is
     * not on.
     */
    private static byte[] sec1ToPkcs8(byte[] sec1) {
        Der.Reader seq = new Der.Reader(sec1).sequence();
        if (seq.integer().intValueExact() != 1) {
            throw new IllegalArgumentException();
        }
        seq.octetString();
        byte[] params = seq.contextParameters(0);
        if (!java.util.Arrays.equals(params, Der.OID_P256)) {
            throw new IllegalArgumentException();
        }
        byte[] algorithm = Der.sequence(Der.concat(Der.OID_EC_PUBLIC_KEY, Der.OID_P256));
        return Der.sequence(Der.concat(new byte[] {0x02, 0x01, 0x00}, algorithm, Der.octetString(sec1)));
    }

    private static boolean isP256(ECParameterSpec params) {
        ECParameterSpec p256 = p256Parameters();
        return params.getCurve().equals(p256.getCurve())
                && params.getGenerator().equals(p256.getGenerator())
                && params.getOrder().equals(p256.getOrder())
                && params.getCofactor() == p256.getCofactor();
    }

    private static ECParameterSpec p256Parameters() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the JDK does not support secp256r1");
        }
    }

    /** Public key = d * G, by double-and-add on the affine curve. Runs once per key at startup. */
    private static ECPublicKey derivePublic(ECPrivateKey privateKey) throws GeneralSecurityException {
        ECParameterSpec params = privateKey.getParams();
        BigInteger d = privateKey.getS();
        if (d.signum() <= 0 || d.compareTo(params.getOrder()) >= 0) {
            throw new InvalidKeyException();
        }
        BigInteger p = ((ECFieldFp) params.getCurve().getField()).getP();
        BigInteger a = params.getCurve().getA();
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = params.getGenerator();
        for (int i = 0; i < d.bitLength(); i++) {
            if (d.testBit(i)) {
                result = add(result, addend, a, p);
            }
            addend = add(addend, addend, a, p);
        }
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(result, params));
    }

    private static ECPoint add(ECPoint p1, ECPoint p2, BigInteger a, BigInteger p) {
        if (p1.equals(ECPoint.POINT_INFINITY)) {
            return p2;
        }
        if (p2.equals(ECPoint.POINT_INFINITY)) {
            return p1;
        }
        BigInteger lambda;
        if (p1.getAffineX().equals(p2.getAffineX())) {
            if (p1.getAffineY().add(p2.getAffineY()).mod(p).signum() == 0) {
                return ECPoint.POINT_INFINITY;
            }
            BigInteger x = p1.getAffineX();
            lambda = x.multiply(x).multiply(BigInteger.valueOf(3)).add(a)
                    .multiply(p1.getAffineY().shiftLeft(1).modInverse(p)).mod(p);
        } else {
            lambda = p2.getAffineY().subtract(p1.getAffineY())
                    .multiply(p2.getAffineX().subtract(p1.getAffineX()).modInverse(p)).mod(p);
        }
        BigInteger x3 = lambda.multiply(lambda).subtract(p1.getAffineX()).subtract(p2.getAffineX()).mod(p);
        BigInteger y3 = lambda.multiply(p1.getAffineX().subtract(x3)).subtract(p1.getAffineY()).mod(p);
        return new ECPoint(x3, y3);
    }

    /** Sign/verify round trip: proves the derived public key belongs to the private key before it is ever used. */
    private static void requireMatchingPair(ECPrivateKey privateKey, ECPublicKey publicKey) throws GeneralSecurityException {
        byte[] probe = {1, 2, 3};
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(probe);
        if (!verifier.verify(signature)) {
            throw new InvalidKeyException();
        }
    }
}
