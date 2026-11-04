package io.reliablepush.harness.logs;

import io.reliablepush.harness.config.HarnessProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Follows the backend's ECS JSON log files ({@code backend-*.json}, one per backend container) and keeps the most
 * recent entries in memory. Runs on its own single thread so a slow tail never delays other scheduled work.
 *
 * <p>Ordering: entries are kept in arrival order (the order this tail read them), not sorted by timestamp; with
 * several files, lines from one poll are grouped per file. Lines that are not valid JSON objects, and the first 64 KB
 * of oversize lines, are not queryable but are retained (last {@value #MAX_UNPARSED}) so {@link #findSecrets} still
 * scans them.
 */
@Component
public class BackendLogTail {

    static final int MAX_ENTRIES = 20_000;
    static final int MAX_UNPARSED = 2_000;
    static final int MAX_LINE_BYTES = 64 * 1024;
    private static final int MIN_SECRET_LENGTH = 8;
    private static final Logger LOG = LogManager.getLogger(BackendLogTail.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private record Entry(Map<String, Object> fields, String raw) {}

    /** Read position of one file: consumed offset, buffered partial line, and oversize-skip state. */
    private static final class FileState {
        long offset;
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        boolean skipping;
    }

    private final Path dir;
    private final Duration interval;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Path, FileState> files = new HashMap<>();
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Deque<String> unparsed = new ArrayDeque<>();
    private final Set<String> warned = new HashSet<>();
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong oversize = new AtomicLong();
    private volatile boolean missingLogged;
    private ScheduledExecutorService executor;

    @Autowired
    public BackendLogTail(HarnessProperties properties, @Value("${harness.log-poll-interval:500ms}") Duration interval) {
        this(properties.backendLogDir(), interval);
    }

    public BackendLogTail(Path dir, Duration interval) {
        this.dir = Objects.requireNonNull(dir);
        this.interval = interval;
    }

    @PostConstruct
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().daemon().name("backend-log-tail").unstarted(r));
        executor.scheduleWithFixedDelay(this::safePoll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void safePoll() {
        try {
            poll();
        } catch (Throwable e) {
            if (warned.add(e.getClass().getName())) {
                LOG.warn("backend log poll failed: {}", e.toString());
            }
        }
    }

    /** One pass over the directory; public so tests (and nothing else) can drive it deterministically. */
    public void poll() {
        List<Path> current = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "backend-*.json")) {
                stream.forEach(current::add);
            } catch (IOException e) {
                LOG.warn("cannot list backend log directory: {}", e.toString());
            }
        } else if (!missingLogged) {
            missingLogged = true;
            LOG.info("backend log directory {} does not exist yet", dir);
        }
        Set<Path> seen = new HashSet<>(current);
        files.keySet().removeIf(p -> !seen.contains(p));
        for (Path file : current) {
            try {
                readNew(file, files.computeIfAbsent(file, p -> new FileState()));
            } catch (IOException e) {
                LOG.debug("cannot read {}: {}", file.getFileName(), e.toString());
            }
        }
    }

    private void readNew(Path file, FileState state) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < state.offset) {
                state.offset = 0;
                state.partial.reset();
                state.skipping = false;
            }
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            while (state.offset < size) {
                buf.clear();
                int n = ch.read(buf, state.offset);
                if (n <= 0) {
                    break;
                }
                state.offset += n;
                for (int i = 0; i < n; i++) {
                    consume(state, buf.get(i));
                }
            }
        }
    }

    private void consume(FileState state, byte b) {
        if (b == '\n') {
            if (state.skipping) {
                state.skipping = false;
            } else {
                String line = state.partial.toString(StandardCharsets.UTF_8);
                parse(line);
            }
            state.partial.reset();
        } else if (!state.skipping) {
            if (state.partial.size() >= MAX_LINE_BYTES) {
                state.skipping = true;
                keepUnparsed(state.partial.toString(StandardCharsets.UTF_8));
                state.partial.reset();
                oversize.incrementAndGet();
            } else {
                state.partial.write(b);
            }
        }
    }

    private void parse(String line) {
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isBlank()) {
            return;
        }
        Map<String, Object> fields;
        try {
            fields = json.readValue(line, MAP);
        } catch (RuntimeException e) {
            fields = null;
        }
        if (fields == null) {
            malformed.incrementAndGet();
            keepUnparsed(line);
            return;
        }
        synchronized (entries) {
            entries.addLast(new Entry(fields, line));
            if (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
        }
    }

    private void keepUnparsed(String raw) {
        synchronized (entries) {
            unparsed.addLast(raw);
            if (unparsed.size() > MAX_UNPARSED) {
                unparsed.removeFirst();
            }
        }
    }

    /** Matching entries, newest first. */
    public List<Map<String, Object>> query(LogQuery q) {
        int limit = q.effectiveLimit();
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (entries) {
            var it = entries.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                Map<String, Object> f = it.next().fields();
                if (matches(f, "trace.id", q.traceId())
                        && matches(f, "rp.tracking_id", q.trackingId())
                        && matches(f, "event.action", q.eventAction())) {
                    out.add(java.util.Collections.unmodifiableMap(f));
                }
            }
        }
        return out;
    }

    private static boolean matches(Map<String, Object> f, String key, String wanted) {
        return wanted == null || Objects.equals(wanted, f.get(key));
    }

    /** Secrets (length at least 8) present in any retained raw line, each masked to its first 4 characters. */
    public List<String> findSecrets(Collection<String> secrets) {
        List<String> candidates = secrets.stream()
                .filter(s -> s != null && s.length() >= MIN_SECRET_LENGTH)
                .distinct()
                .toList();
        List<String> found = new ArrayList<>();
        synchronized (entries) {
            for (String secret : candidates) {
                boolean hit = entries.stream().anyMatch(e -> e.raw().contains(secret))
                        || unparsed.stream().anyMatch(raw -> raw.contains(secret));
                if (hit) {
                    found.add(secret.substring(0, 4) + "…");
                }
            }
        }
        return found;
    }

    public long malformedCount() {
        return malformed.get();
    }

    public long oversizeCount() {
        return oversize.get();
    }
}
