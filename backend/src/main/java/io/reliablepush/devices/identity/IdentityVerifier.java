package io.reliablepush.devices.identity;

/**
 * Proves which user registers a device (spec 5). The userId always comes from a verified credential, never from the
 * client's request body.
 */
public interface IdentityVerifier {

    /**
     * @param credential the ID token (OIDC) or signed grant, as sent by the client
     * @throws IdentityRejectedException with a reason code if the credential does not prove an identity
     */
    VerifiedIdentity verify(String credential) throws IdentityRejectedException;
}
