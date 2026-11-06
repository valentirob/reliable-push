package io.reliablepush.devices.identity;

/** The user a credential proved. {@code toString} never prints the user id (raw user ids are not logged). */
public record VerifiedIdentity(String userId) {

    @Override
    public String toString() {
        return "VerifiedIdentity[REDACTED]";
    }
}
