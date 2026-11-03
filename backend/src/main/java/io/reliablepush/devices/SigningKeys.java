package io.reliablepush.devices;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.apps.ReliablePushProperties.SigningKey;
import io.reliablepush.shared.config.SecretRefs;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
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
 * The device-token signing keys (spec 5): P-256 key pairs loaded once at startup from {@code env:NAME} references,
 * identified by {@code kid}. Each key is configured as a PEM pair: the private key as PKCS#8 ({@code BEGIN PRIVATE KEY})
 * and the public key as SPKI ({@code BEGIN PUBLIC KEY}); both are parsed with the JDK and must be P-256 and match each
 * other. {@link #current()} signs new tokens; {@link #byKid(String)} serves verification, so tokens signed with a
 * previous key stay valid while that key is still configured (rotation). A key is never taken from a token. Key material
 * never appears in {@code toString} or error messages.
 *
 * <p>With no keys configured the application still starts ({@link #isConfigured()} is false) but cannot issue tokens.
 */
@Component
public class SigningKeys {

    private static final String PROPERTY = "reliable-push.device-token.signing-keys";

    /** The PEM texts of one key pair. {@code toString} never prints them. */
    public record PemPair(String privateKeyPem, String publicKeyPem) {
        @Override
        public String toString() {
            return "PemPair[REDACTED]";
        }
    }

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

    private SigningKeys(String currentKid, Map<String, PemPair> pemByKid) {
        Map<String, Key> parsed = new LinkedHashMap<>();
        pemByKid.forEach((kid, pems) -> parsed.put(kid, parse(kid, pems)));
        if (!parsed.isEmpty() && (currentKid == null || !parsed.containsKey(currentKid))) {
            throw new IllegalStateException(
                    "reliable-push.device-token.current-kid must be the kid of one of the configured signing-keys");
        }
        this.keys = Map.copyOf(parsed);
        this.current = parsed.isEmpty() ? null : parsed.get(currentKid);
    }

    /** Builds the keys from PEM text directly (tests, tools). An empty map means "not configured". */
    public static SigningKeys fromPem(String currentKid, Map<String, PemPair> pemByKid) {
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

    private static Map<String, PemPair> resolvePems(ReliablePushProperties properties, Environment environment) {
        Map<String, PemPair> pems = new LinkedHashMap<>();
        for (SigningKey configured : properties.deviceToken().signingKeys()) {
            String where = PROPERTY + "[" + configured.kid() + "]";
            pems.put(configured.kid(), new PemPair(
                    SecretRefs.resolve(environment, where + ".private-key-pem-ref", configured.privateKeyPemRef()).value(),
                    SecretRefs.resolve(environment, where + ".public-key-pem-ref", configured.publicKeyPemRef()).value()));
        }
        return pems;
    }

    /**
     * Parses and cross-checks one key pair with the JDK only. Failures carry no cause and no detail: either could quote
     * key material.
     */
    private static Key parse(String kid, PemPair pems) {
        String where = PROPERTY + "[" + kid + "]";
        ECPrivateKey privateKey;
        ECPublicKey publicKey;
        try {
            PrivateKey decodedPrivate = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(pemBody(pems.privateKeyPem(), "PRIVATE KEY")));
            privateKey = decodedPrivate instanceof ECPrivateKey ec ? ec : null;
        } catch (GeneralSecurityException | RuntimeException e) {
            privateKey = null;
        }
        if (privateKey == null) {
            throw new IllegalStateException(where + ".private-key-pem-ref is not a valid PKCS#8 PEM EC private key");
        }
        try {
            PublicKey decodedPublic = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(pemBody(pems.publicKeyPem(), "PUBLIC KEY")));
            publicKey = decodedPublic instanceof ECPublicKey ec ? ec : null;
        } catch (GeneralSecurityException | RuntimeException e) {
            publicKey = null;
        }
        if (publicKey == null) {
            throw new IllegalStateException(where + ".public-key-pem-ref is not a valid SPKI PEM EC public key");
        }
        if (!isP256(privateKey.getParams()) || !isP256(publicKey.getParams())) {
            throw new IllegalStateException(where + " must be a P-256 (secp256r1) key pair: ES256 requires P-256");
        }
        try {
            requireMatchingPair(privateKey, publicKey);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException(where + " public key does not match its private key");
        }
        return new Key(kid, privateKey, publicKey);
    }

    /** Decodes the single PEM block with the given label; anything else (none, several, bad base64) throws. */
    private static byte[] pemBody(String pem, String label) {
        Pattern block = Pattern.compile("-----BEGIN " + label + "-----([A-Za-z0-9+/=\\s]*)-----END " + label + "-----");
        Matcher matcher = block.matcher(pem.replace("\\n", "\n"));
        if (!matcher.find()) {
            throw new IllegalArgumentException();
        }
        byte[] der = Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
        if (matcher.find()) {
            throw new IllegalArgumentException(); // more than one block in one value: ambiguous
        }
        return der;
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

    /** Sign/verify round trip: proves the two configured PEMs are one key pair before the key is ever used. */
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
