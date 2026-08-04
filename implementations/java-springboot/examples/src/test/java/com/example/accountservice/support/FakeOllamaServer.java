package com.example.accountservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * An in-process fake of the one Ollama endpoint every LLM Technical Service in this codebase calls:
 * {@code POST /api/chat}, non-streaming (see {@code
 * account/infrastructure/NlTransactionQueryTranslatorImpl.java}/{@code
 * NlTransactionAnswerComposerImpl.java}/{@code TransactionAutoCategorizerImpl.java} and {@code
 * payment/infrastructure/RefundReasonClassifierImpl.java}). An e2e test starts one instance in a
 * static field and points {@code llm.ollama-base-url} at {@link #baseUrl()} via
 * {@code @DynamicPropertySource}, so each service's real HTTP request → response-parse path runs in
 * the e2e loop without a live model.
 *
 * <p>Routing is stateless and purely content-based — the system message identifies WHICH service is
 * calling (each service builds a distinctive system prompt), and recognizable markers in the user
 * message pick WHAT deterministic content to answer with — so there is no per-test mutable state to
 * reset between tests. Built on the JDK's own {@code com.sun.net.httpserver.HttpServer} (a single
 * POST endpoint needs no extra test dependency).
 */
public final class FakeOllamaServer {

    /**
     * Makes the fake answer 500 whenever it appears anywhere in a request's user message. Tests
     * that need to cover an LLM Technical Service's graceful-fallback path embed it in the natural
     * input that reaches the prompt (question/merchantName/reason), so the forced outage is scoped
     * to exactly that request — every other request in the suite keeps getting deterministic
     * successful responses.
     */
    public static final String FORCE_LLM_FAILURE_MARKER = "force-llm-500";

    /**
     * Marks answers produced by the fake composer branch below — asserting on it proves the {@code
     * /transactions/ask} answer really came through the LLM request/parse path (the composer's own
     * fallback answer starts with "Found ... matching transaction(s)" instead).
     */
    public static final String FAKE_ANSWER_PREFIX = "FAKE-OLLAMA GROUNDED ANSWER:\n";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;

    public FakeOllamaServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the fake Ollama server", e);
        }
        server.createContext("/api/chat", this::handleChat);
        server.start();
    }

    /**
     * The base URL to bind to {@code llm.ollama-base-url} — the services append {@code /api/chat}
     * themselves.
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Stop from {@code @AfterAll} — the JDK server's dispatcher thread is non-daemon, so leaving it
     * running would keep the Gradle test-worker JVM alive after the suite finishes.
     */
    public void stop() {
        server.stop(0);
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "only POST /api/chat is supported");
                return;
            }

            Map<?, ?> request =
                    objectMapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            String systemContent = messageContent(request, "system");
            String userContent = messageContent(request, "user");

            if (userContent.contains(FORCE_LLM_FAILURE_MARKER)) {
                respond(exchange, 500, "forced failure for fallback-path coverage");
                return;
            }

            String content = route(systemContent, userContent);
            if (content == null) {
                // An unrecognized system prompt means a new LLM Technical Service was added
                // without teaching this fake about it — fail loudly (the caller's fallback
                // assertion will surface it).
                respond(exchange, 500, "fake Ollama does not recognize this system prompt");
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(
                    exchange,
                    200,
                    objectMapper.writeValueAsString(
                            Map.of("message", Map.of("role", "assistant", "content", content))));
        } finally {
            exchange.close();
        }
    }

    private static String messageContent(Map<?, ?> request, String role) {
        if (!(request.get("messages") instanceof List<?> messages)) {
            return "";
        }
        return messages.stream()
                .filter(m -> m instanceof Map<?, ?> message && role.equals(message.get("role")))
                .map(m -> ((Map<?, ?>) m).get("content"))
                .filter(content -> content instanceof String)
                .map(String.class::cast)
                .findFirst()
                .orElse("");
    }

    /**
     * Picks the deterministic response content for one chat request. Each branch matches a
     * distinctive phrase from the real system prompt built by one Impl, and returns content in
     * exactly the shape that service parses (structured JSON for the
     * translator/categorizer/classifier, free-form prose for the composer). Returns null for an
     * unrecognized system prompt.
     */
    private static String route(String systemContent, String userContent) {
        String loweredUser = userContent.toLowerCase(Locale.ROOT);

        // NlTransactionQueryTranslatorImpl — buildSystemPrompt.
        if (systemContent.contains("translate a user's natural-language question")) {
            return loweredUser.contains("deposit")
                    ? "{\"type\":\"DEPOSIT\",\"fromDate\":\"\",\"toDate\":\"\"}"
                    : "{\"type\":\"ANY\",\"fromDate\":\"\",\"toDate\":\"\"}";
        }

        // NlTransactionAnswerComposerImpl — SYSTEM_PROMPT. Echo the grounding data back so the
        // caller can assert the retrieved transactions really reached the prompt (and
        // non-matching ones did not).
        if (systemContent.contains(
                "answer a user's question about their own bank account transactions")) {
            int transactionsBlock = userContent.indexOf("Transactions:\n");
            return FAKE_ANSWER_PREFIX
                    + (transactionsBlock >= 0
                            ? userContent.substring(transactionsBlock + "Transactions:\n".length())
                            : userContent);
        }

        // TransactionAutoCategorizerImpl — buildSystemPrompt. User content is
        // "Merchant: <name>\nAmount: <n>".
        if (systemContent.contains("classify a bank withdrawal")) {
            return userContent.contains("Starbucks")
                    ? "{\"category\":\"FOOD\"}"
                    : "{\"category\":\"OTHER\"}";
        }

        // RefundReasonClassifierImpl — buildSystemPrompt. User content is the refund's stated
        // reason verbatim.
        if (systemContent.contains("classify a customer's stated refund reason")) {
            if (loweredUser.contains("arrived broken")) {
                return "{\"category\":\"DEFECTIVE_PRODUCT\"}";
            }
            if (loweredUser.contains("changed my mind")) {
                return "{\"category\":\"CHANGED_MIND\"}";
            }
            return "{\"category\":\"OTHER\"}";
        }

        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
