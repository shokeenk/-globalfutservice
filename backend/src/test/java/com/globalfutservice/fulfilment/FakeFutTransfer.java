package com.globalfutservice.fulfilment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * FUT Transfer, played by the JDK's own HTTP server on localhost.
 *
 * <p>Tests never reach the real API and never place a real order. Each path answers from
 * a script -- a status, a body, and optionally a delay long enough to make the client time
 * out -- and every request is recorded, so a test can say exactly how many times
 * {@code /orderAPI} was called and what it was sent.
 *
 * <p>The canned bodies are the vendor's own examples from its Postman collection.
 */
final class FakeFutTransfer implements AutoCloseable {

    /** The collection's "Submit Order" success example. */
    static final String ORDER_ACCEPTED = "{\"orderID\":\"123e4567-e89b-12d3-a456-426614174000\"}";
    static final String VENDOR_ID = "123e4567-e89b-12d3-a456-426614174000";

    /** The "Submit Order" success shape, with an id of the test's own choosing. */
    static String accepted(String vendorOrderId) {
        return "{\"orderID\":\"" + vendorOrderId + "\"}";
    }

    /** The collection's "Query Order Status" example, with our reference and amount. */
    static String status(String ref, long amountOrderedK) {
        return """
                {"status":"partlyDelivered","accountCheck":"finished","accountCheckLong":"finished",
                 "economyState":"transfersInProgress","economyStateLong":"Transfers in Progress",
                 "amountOrdered":%d,"amount":107,"coinsUsed":135040,"externalOrderID":"%s",
                 "toPay":0,"sellerReceives":0,"coinsCustomerAccount":1010825,"wasAborted":0,
                 "knownClub":"My FUT Club","cached":0,"isMotherID":0,
                 "ba1":"99887766","ba2":"55443322"}
                """.formatted(amountOrderedK, ref);
    }

    record Reply(int status, String body, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, 0);
        }

        static Reply of(int status, String body) {
            return new Reply(status, body, 0);
        }

        static Reply slow(long delayMillis) {
            return new Reply(200, ORDER_ACCEPTED, delayMillis);
        }
    }

    record Request(String path, JsonNode body) {
    }

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Function<JsonNode, Reply>> scripts = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    FakeFutTransfer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body;
            try {
                body = mapper.readTree(raw);
            } catch (Exception e) {
                body = null;
            }
            requests.add(new Request(path, body));
            Reply reply = scripts.getOrDefault(path, b -> Reply.of(404, "notFound")).apply(body);
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (IOException e) {
                // The client gave up (a timeout test); nothing to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    FakeFutTransfer on(String path, Reply reply) {
        scripts.put(path, b -> reply);
        return this;
    }

    FakeFutTransfer on(String path, Function<JsonNode, Reply> script) {
        scripts.put(path, script);
        return this;
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    long calls(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
