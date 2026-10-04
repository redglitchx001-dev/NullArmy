package redglitchx.nullarmy.plugin.selftest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 for the self test: a skin proxy and an
 * OpenAI-compatible endpoint, with canned answers. No dependency beyond the JDK,
 * bound to the loopback interface only, and stopped at the end of the test.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class StubHttp implements AutoCloseable {

    private static final class Answer {
        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final ServerSocket socket;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastBody = "";
    private volatile boolean running = true;

    private StubHttp(ServerSocket socket) {
        this.socket = socket;
        Thread thread = new Thread(this::serve, "NullArmy-selftest-http");
        thread.setDaemon(true);
        thread.start();
    }

    static StubHttp start() throws IOException {
        ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(500);
        return new StubHttp(socket);
    }

    /** Answers every request whose path starts with {@code prefix}. */
    void respond(String prefix, int status, String body) {
        answers.put(prefix, new Answer(status, body == null ? "" : body));
    }

    String url(String path) {
        return "http://127.0.0.1:" + socket.getLocalPort() + path;
    }

    int requests() { return requests.get(); }

    String lastBody() { return lastBody; }

    private void serve() {
        while (running) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(3000);
                handle(client);
            } catch (java.net.SocketTimeoutException idle) {
                // poll the running flag
            } catch (Throwable ignored) {
                // one bad connection must not stop the stub
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        String head = readHead(in);
        if (head == null) {
            return;
        }
        String[] lines = head.split("\r\n");
        String[] requestLine = lines[0].split(" ");
        String path = requestLine.length > 1 ? requestLine[1] : "/";
        int length = 0;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    length = 0;
                }
            }
        }
        byte[] body = in.readNBytes(Math.max(0, Math.min(length, 1 << 20)));
        lastBody = new String(body, StandardCharsets.UTF_8);
        requests.incrementAndGet();
        Answer answer = null;
        int best = -1;
        for (Map.Entry<String, Answer> entry : answers.entrySet()) {
            if (path.startsWith(entry.getKey()) && entry.getKey().length() > best) {
                best = entry.getKey().length();
                answer = entry.getValue();
            }
        }
        if (answer == null) {
            answer = new Answer(404, "{\"error\":\"not found\"}");
        }
        byte[] payload = answer.body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + answer.status + " Stub\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            if ((matched == 0 || matched == 2) && b == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                matched++;
                if (matched == 4) {
                    return buffer.toString(StandardCharsets.US_ASCII.name());
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
            if (buffer.size() > 65536) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
