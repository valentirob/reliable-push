package io.reliablepush.payload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadExceptionTest {

    @Test
    void codesAreTheEightDocumentedOnes() {
        assertThat(PayloadException.Code.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "INVALID_TRACKING_ID", "RESERVED_KEY", "APNS_NO_ALERT", "APNS_TOO_LARGE", "FCM_NON_STRING_DATA",
                "FCM_UNSUPPORTED_NOTIFICATION_FIELD", "FCM_TOO_LARGE", "INVALID_JSON");
    }

    @Test
    void carriesCodeAndMessage() {
        PayloadException e = new PayloadException(PayloadException.Code.RESERVED_KEY, "reserved");
        assertThat(e.code()).isEqualTo(PayloadException.Code.RESERVED_KEY);
        assertThat(e).hasMessage("reserved").isInstanceOf(IllegalArgumentException.class);
    }
}
