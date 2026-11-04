package io.reliablepush.harness.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ConsoleStreamTest {

    final ConsoleStream stream = new ConsoleStream();

    @AfterEach
    void close() {
        stream.shutdown();
    }

    @Test
    void the21stClientGets503() {
        for (int i = 0; i < ConsoleStream.MAX_CLIENTS; i++) {
            stream.stream();
        }

        assertThat(stream.clientCount()).isEqualTo(20);
        assertThatThrownBy(stream::stream).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void publishingWithoutClientsIsANoOp() {
        stream.publishTimeline(UUID.randomUUID(), new TimelineEntry(java.time.Instant.now(), "registered", Map.of()));

        assertThat(stream.clientCount()).isZero();
        assertThat(stream.dropped()).isZero();
    }
}
