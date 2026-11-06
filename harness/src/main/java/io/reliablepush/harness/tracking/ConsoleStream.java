package io.reliablepush.harness.tracking;

import io.reliablepush.harness.receiver.ReceivedWebhook;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /console/api/stream}: Server-Sent Events for the web console. Publishing never blocks the caller: every
 * tab has its own bounded queue drained by its own virtual thread; a tab that cannot keep up is dropped. At most
 * {@value #MAX_CLIENTS} tabs at once (the next gets 503). A comment line is sent every 15 s as heartbeat.
 */
@RestController
public class ConsoleStream implements SmartLifecycle {

    static final int MAX_CLIENTS = 20;
    static final int CLIENT_QUEUE = 1_000;
    private static final Logger LOG = LogManager.getLogger(ConsoleStream.class);
    private static final Object HEARTBEAT = new Object();

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();

    private final class Client {
        final SseEmitter emitter = new SseEmitter(0L);
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(CLIENT_QUEUE);
        volatile Thread thread;

        void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Object item = queue.take();
                    if (item == HEARTBEAT) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } else {
                        emitter.send(SseEmitter.event().data(item));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                // client went away (or an unexpected failure): never leave a zombie client behind
                remove(this);
                emitter.completeWithError(e);
            }
        }
    }

    @GetMapping(value = "/console/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Client c = new Client();
        synchronized (clients) {
            if (clients.size() >= MAX_CLIENTS) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "too many console streams");
            }
            clients.add(c);
        }
        Runnable gone = () -> remove(c);
        c.emitter.onCompletion(gone);
        c.emitter.onTimeout(gone);
        c.emitter.onError(e -> gone.run());
        c.queue.add(HEARTBEAT); // flushes the headers so the client sees the stream is open
        c.thread = Thread.ofVirtual().name("console-stream").start(c::run);
        return c.emitter;
    }

    public int clientCount() {
        return clients.size();
    }

    public long dropped() {
        return dropped.get();
    }

    void publishTimeline(UUID trackingId, TimelineEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "timeline");
        m.put("trackingId", trackingId.toString());
        m.put("entry", entry.toJson());
        broadcast(m);
    }

    void publishWebhook(ReceivedWebhook w) {
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("callId", w.callId() == null ? null : w.callId().toString());
        hook.put("appId", w.appId());
        hook.put("attempt", w.attempt());
        hook.put("reason", w.reason());
        hook.put("signatureValid", w.signatureValid());
        hook.put("receivedAt", w.receivedAt() == null ? null : w.receivedAt().toString());
        hook.put("respondedAt", w.respondedAt() == null ? null : w.respondedAt().toString());
        hook.put("respondedStatus", w.respondedStatus());
        hook.put("pending", w.isPending());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "webhook");
        m.put("trackingId", w.trackingId() == null ? null : w.trackingId().toString());
        m.put("webhook", hook);
        broadcast(m);
    }

    @Scheduled(fixedRate = 15_000, initialDelay = 15_000)
    void heartbeat() {
        for (Client c : clients) {
            offer(c, HEARTBEAT);
        }
    }

    private void broadcast(Map<String, Object> payload) {
        if (clients.isEmpty()) {
            return;
        }
        String data = json.writeValueAsString(payload);
        for (Client c : clients) {
            offer(c, data);
        }
    }

    private void offer(Client c, Object item) {
        if (!c.queue.offer(item)) {
            dropped.incrementAndGet();
            LOG.warn("console stream client too slow, dropping it");
            remove(c);
            c.emitter.complete();
        }
    }

    private void remove(Client c) {
        clients.remove(c);
        Thread t = c.thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private volatile boolean running;

    @Override
    public void start() {
        running = true;
    }

    /** Stops before the web server's graceful shutdown, which would otherwise wait forever for the open streams. */
    @Override
    public void stop() {
        running = false;
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    void shutdown() {
        for (Client c : clients) {
            remove(c);
            c.emitter.complete();
        }
    }
}
