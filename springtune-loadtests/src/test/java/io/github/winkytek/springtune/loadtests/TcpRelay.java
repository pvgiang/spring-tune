package io.github.winkytek.springtune.loadtests;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-JVM TCP relay between a pool and PostgreSQL that can <b>stop forwarding</b> one connection, as a half-open
 * TCP connection does (a firewall dropping state, a failed-over peer): once armed, the first connection whose
 * client sends a message containing {@code trigger} is frozen. Its bytes are neither forwarded nor answered; the
 * relay keeps both sockets open, so the client sees no error, only silence. The client's own close is still
 * noticed (the relay keeps reading and discarding what the client sends) and recorded, so a test can tell whether
 * and when the pool gave the connection up. Connections opened afterwards are forwarded normally.
 */
final class TcpRelay implements AutoCloseable {

    /** One relayed connection; {@code frozenAt} and {@code clientClosedAt} are set at most once. */
    static final class Link {
        final int id;
        volatile Instant frozenAt;
        volatile Instant clientClosedAt;
        final Socket client;
        final Socket upstream;

        Link(int id, Socket client, Socket upstream) {
            this.id = id;
            this.client = client;
            this.upstream = upstream;
        }

        boolean frozen() {
            return frozenAt != null;
        }

        @Override
        public String toString() {
            return "link#" + id + (frozenAt != null ? " frozen at " + frozenAt : "")
                    + (clientClosedAt != null ? " closed by the client at " + clientClosedAt : "");
        }
    }

    private final ServerSocket server;
    private final String upstreamHost;
    private final int upstreamPort;
    private final List<Link> links = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile String trigger;
    private volatile boolean closed;

    TcpRelay(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread acceptor = new Thread(this::accept, "tcp-relay-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Freezes the next connection whose client sends {@code text} (one connection, then disarms itself). */
    void stopForwardingOn(String text) {
        trigger = text;
    }

    /** Disarms the trigger: new and unfrozen connections are forwarded. A frozen connection stays frozen. */
    void resume() {
        trigger = null;
    }

    List<Link> frozen() {
        return links.stream().filter(Link::frozen).toList();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket(upstreamHost, upstreamPort);
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                Link link = new Link(ids.incrementAndGet(), client, upstream);
                links.add(link);
                pump(link, true);
                pump(link, false);
            } catch (IOException ex) {
                if (!closed) {
                    System.out.println("[loadtests] relay accept failed: " + ex);
                }
            }
        }
    }

    private void pump(Link link, boolean fromClient) {
        Thread thread = new Thread(() -> {
            Socket in = fromClient ? link.client : link.upstream;
            Socket out = fromClient ? link.upstream : link.client;
            byte[] buffer = new byte[16384];
            String tail = "";
            try (InputStream input = in.getInputStream()) {
                OutputStream output = out.getOutputStream();
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    if (fromClient && !link.frozen()) {
                        String armed = trigger;
                        String seen = tail + new String(buffer, 0, n, StandardCharsets.ISO_8859_1);
                        if (armed != null && seen.contains(armed)) {
                            trigger = null;
                            link.frozenAt = Instant.now();
                            System.out.println("[loadtests] relay: " + link);
                        }
                        tail = seen.substring(Math.max(0, seen.length() - 256));
                    }
                    if (link.frozen()) {
                        continue; // silence: read and drop, answer nothing
                    }
                    output.write(buffer, 0, n);
                    output.flush();
                }
            } catch (IOException ex) {
                // a closed socket ends the pump
            }
            if (fromClient) {
                if (link.frozen() && link.clientClosedAt == null) {
                    link.clientClosedAt = Instant.now();
                    System.out.println("[loadtests] relay: " + link);
                }
                closeQuietly(link.upstream);
                closeQuietly(link.client);
            } else if (!link.frozen()) {
                closeQuietly(link.client);
            }
        }, "tcp-relay-" + link.id + (fromClient ? "-up" : "-down"));
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly2(server);
        for (Link link : links) {
            closeQuietly(link.client);
            closeQuietly(link.upstream);
        }
    }

    private static void closeQuietly2(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
