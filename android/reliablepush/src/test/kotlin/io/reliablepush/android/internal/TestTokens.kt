package io.reliablepush.android.internal

import java.util.Base64

/** Builds an unsigned JWT-shaped string carrying `iat`/`exp` (seconds), enough for [DeviceToken.parse]. */
internal fun fakeJwt(iat: Long, exp: Long): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    fun part(s: String) = enc.encodeToString(s.toByteArray())
    return part("""{"alg":"ES256"}""") + "." + part("""{"iss":"rp","sub":"u","did":"d","ver":1,"iat":$iat,"exp":$exp}""") + ".c2ln"
}
