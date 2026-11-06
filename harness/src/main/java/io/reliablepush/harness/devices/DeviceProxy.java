package io.reliablepush.harness.devices;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A plain TCP proxy for real devices (the iOS Simulator has no airplane mode): clients connect to this port and the bytes
 * are relayed to the backend. {@link #setOnline(boolean) Offline} closes every open connection and accepts-then-closes new
 * ones, so a client sees a transport error and never an HTTP status. Blocking sockets on virtual threads; the relay uses
 * fixed 8 KiB buffers, upstream connects time out, at most {@link #MAX_CONNECTIONS} connections are open. Bytes are never
 * logged.
 */
public final class DeviceProxy implements AutoCloseable {

    static final int MAX_CONNECTIONS = 256;
    private int maxConnections = MAX_CONNECTIONS;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int IDLE_TIMEOUT_MS = 60_000;
    private static final long WARN_EVERY_MS = 10_000;
    private static final int BUFFER = 8 * 1024;
    private static final Logger LOG = LogManager.getLogger(DeviceProxy.class);

    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final boolean enabled;
    private final Set<Connection> open = ConcurrentHashMap.newKeySet();
    private volatile boolean online = true;
    private volatile ServerSocket server;
    private final java.util.concurrent.atomic.AtomicLong lastWarn = new java.util.concurrent.atomic.AtomicLong();

    public DeviceProxy(int listenPort, String targetHost, int targetPort) {
        this(listenPort, targetHost, targetPort, true);
    }

    private DeviceProxy(int listenPort, String targetHost, int targetPort, boolean enabled) {
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.enabled = enabled;
    }

    /** A proxy without a target: it never listens, but still reports and remembers the online switch. */
    public static DeviceProxy disabled() {
        return new DeviceProxy(0, "", 0, false);
    }

    /** Test hook: lower the connection cap. */
    DeviceProxy withMaxConnections(int max) {
        this.maxConnections = max;
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Currently open relayed connections (tests, diagnostics). */
    int openConnections() {
        return open.size();
    }

    public boolean online() {
        return online;
    }

    /** The bound listen port (0 when not listening). */
    public int port() {
        ServerSocket s = server;
        return s == null ? 0 : s.getLocalPort();
    }

    public synchronized void start() throws IOException {
        if (!enabled || server != null) {
            return;
        }
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), listenPort), 128);
        server = s;
        Thread.ofVirtual().name("device-proxy-accept").start(() -> acceptLoop(s));
        LOG.info("device proxy listening on port {} -> {}:{}", s.getLocalPort(), targetHost, targetPort);
    }

    /** Offline closes every open connection now and makes new ones close at once; online forwards again. */
    public void setOnline(boolean value) {
        online = value;
        if (!value) {
            open.forEach(Connection::close);
        }
    }

    @Override
    public synchronized void close() {
        online = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        open.forEach(Connection::close);
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                Thread.ofVirtual().name("device-proxy-conn").start(() -> handle(client));
            } catch (IOException e) {
                if (!s.isClosed()) {
                    warnLimited("device proxy accept failed: " + e.getClass().getSimpleName());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** At most one WARN per 10 s (the first one always logs); never carries payload bytes. */
    private void warnLimited(String message) {
        long now = System.nanoTime() / 1_000_000;
        long last = lastWarn.get();
        if ((last == 0 || now - last >= WARN_EVERY_MS) && lastWarn.compareAndSet(last, Math.max(now, 1))) {
            LOG.warn(message);
        }
    }

    private void handle(Socket client) {
        Connection c = new Connection(client);
        // register first, then look at the switch: setOnline(false) sets the flag before it sweeps the registry
        open.add(c);
        if (!online) {
            c.close();
            return;
        }
        if (open.size() > maxConnections) {
            warnLimited("device proxy connection cap (" + maxConnections + ") reached, refusing connections");
            c.close();
            return;
        }
        try {
            client.setSoTimeout(IDLE_TIMEOUT_MS);
            Socket upstream = new Socket();
            c.upstream = upstream;
            upstream.setSoTimeout(IDLE_TIMEOUT_MS);
            upstream.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS);
            if (!online) {
                c.close();
                return;
            }
            Thread.ofVirtual().name("device-proxy-up").start(() -> c.pump(client, upstream));
            c.pump(upstream, client);
        } catch (IOException e) {
            c.close();
        }
    }

    private final class Connection {
        private final Socket client;
        volatile Socket upstream;
        private int finished;

        Connection(Socket client) {
            this.client = client;
        }

        /** Copies one direction until EOF or error; the connection closes when both directions are done. */
        void pump(Socket from, Socket to) {
            byte[] buf = new byte[BUFFER];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                to.shutdownOutput();
            } catch (IOException e) {
                close();
                return;
            }
            boolean done;
            synchronized (this) {
                done = ++finished == 2;
            }
            if (done) {
                close();
            }
        }

        void close() {
            open.remove(this);
            quietly(client);
            quietly(upstream);
        }

        private static void quietly(Socket s) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }
}
