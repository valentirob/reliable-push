package io.reliablepush.shared.security;

/** The authenticated sender app of a request; set by {@link SenderApiKeyFilter} under {@link #ATTR}. */
public record SenderPrincipal(String appId) {

    /** Request attribute holding the {@link SenderPrincipal}. */
    public static final String ATTR = "rp.sender_principal";
}
