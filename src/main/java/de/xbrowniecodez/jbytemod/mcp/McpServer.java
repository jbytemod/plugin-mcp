package de.xbrowniecodez.jbytemod.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.xbrowniecodez.jbytemod.plugin.PluginContext;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class McpServer implements Closeable {
    private static final String MODERN_PROTOCOL = "2026-07-28";
    private static final String LEGACY_PROTOCOL = "2025-11-25";
    private static final Set<String> LEGACY_PROTOCOLS = Set.of(
            "2024-11-05", "2025-03-26", "2025-06-18", LEGACY_PROTOCOL);
    private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private final HttpServer httpServer;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final McpTools tools;
    private final String endpoint;
    private volatile boolean running;

    McpServer(PluginContext context, int port) throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
        this.httpServer = HttpServer.create(address, 0);
        this.httpServer.createContext("/mcp", this::handle);
        this.httpServer.setExecutor(executor);
        this.tools = new McpTools(context);
        this.endpoint = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp";
    }

    void start() {
        httpServer.start();
        running = true;
    }

    boolean isRunning() {
        return running;
    }

    String getEndpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        httpServer.stop(0);
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!isLocalRequest(exchange)) {
                sendText(exchange, 403, "Local connections only");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            if (!isJson(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                sendText(exchange, 415, "Content-Type must be application/json");
                return;
            }

            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                sendText(exchange, 413, "Request is too large");
                return;
            }

            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            } catch (RuntimeException exception) {
                sendJson(exchange, 400, error(null, -32700, "Parse error"));
                return;
            }
            if (!parsed.isJsonObject()) {
                sendJson(exchange, 400, error(null, -32600, "Batch requests are not supported"));
                return;
            }

            JsonObject request = parsed.getAsJsonObject();
            JsonElement id = request.get("id");
            String method = string(request, "method");
            if (!"2.0".equals(string(request, "jsonrpc")) || method == null) {
                sendJson(exchange, 400, error(id, -32600, "Invalid request"));
                return;
            }

            boolean modern = isModernRequest(exchange, request);
            if (modern && !validModernHeaders(exchange, request, method)) {
                sendJson(exchange, 400, error(id, -32020, "MCP routing headers do not match the request"));
                return;
            }
            if (id == null || id.isJsonNull()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            JsonObject result;
            int status = 200;
            try {
                result = dispatch(request, method, modern);
            } catch (UnknownMethodException exception) {
                status = modern ? 404 : 200;
                sendJson(exchange, status, error(id, -32601, "Method not found: " + method));
                return;
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 200, error(id, -32602, exception.getMessage()));
                return;
            } catch (Throwable throwable) {
                sendJson(exchange, 200, error(id, -32603, throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName() : throwable.getMessage()));
                return;
            }

            if (modern) {
                result.addProperty("resultType", "complete");
                addServerInfo(result);
            }
            sendJson(exchange, status, response(id, result));
        }
    }

    private JsonObject dispatch(JsonObject request, String method, boolean modern) throws Exception {
        JsonObject params = object(request, "params");
        return switch (method) {
            case "server/discover" -> discover();
            case "initialize" -> initialize(params);
            case "ping" -> new JsonObject();
            case "tools/list" -> tools.list(modern);
            case "tools/call" -> tools.call(params);
            default -> throw new UnknownMethodException();
        };
    }

    private JsonObject discover() {
        JsonObject result = new JsonObject();
        result.add("supportedVersions", GSON.toJsonTree(new String[]{MODERN_PROTOCOL}));
        result.add("capabilities", capabilities());
        result.addProperty("instructions", "Inspect the archive currently open in JByteMod and navigate its UI. Class names use JVM internal names such as java/lang/String.");
        result.addProperty("ttlMs", 60_000);
        result.addProperty("cacheScope", "private");
        return result;
    }

    private JsonObject initialize(JsonObject params) {
        String requested = string(params, "protocolVersion");
        String selected = LEGACY_PROTOCOLS.contains(requested) ? requested : LEGACY_PROTOCOL;
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", selected);
        result.add("capabilities", capabilities());
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "jbytemod");
        serverInfo.addProperty("version", tools.getVersion());
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", "Inspect the archive currently open in JByteMod and navigate its UI. Class names use JVM internal names such as java/lang/String.");
        return result;
    }

    private JsonObject capabilities() {
        JsonObject capabilities = new JsonObject();
        JsonObject toolCapabilities = new JsonObject();
        toolCapabilities.addProperty("listChanged", false);
        capabilities.add("tools", toolCapabilities);
        return capabilities;
    }

    private void addServerInfo(JsonObject result) {
        JsonObject meta = result.has("_meta") && result.get("_meta").isJsonObject()
                ? result.getAsJsonObject("_meta") : new JsonObject();
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "jbytemod");
        serverInfo.addProperty("version", tools.getVersion());
        meta.add("io.modelcontextprotocol/serverInfo", serverInfo);
        result.add("_meta", meta);
    }

    private boolean isModernRequest(HttpExchange exchange, JsonObject request) {
        String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (MODERN_PROTOCOL.equals(version)) {
            return true;
        }
        JsonObject params = object(request, "params");
        JsonObject meta = object(params, "_meta");
        return MODERN_PROTOCOL.equals(string(meta, "io.modelcontextprotocol/protocolVersion"));
    }

    private boolean validModernHeaders(HttpExchange exchange, JsonObject request, String method) {
        Headers headers = exchange.getRequestHeaders();
        if (!MODERN_PROTOCOL.equals(headers.getFirst("MCP-Protocol-Version"))
                || !method.equals(headers.getFirst("Mcp-Method"))) {
            return false;
        }
        if (!"tools/call".equals(method)) {
            return true;
        }
        JsonObject params = object(request, "params");
        return java.util.Objects.equals(string(params, "name"), headers.getFirst("Mcp-Name"));
    }

    private boolean isLocalRequest(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null
                || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            return false;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!(normalizedHost.equals("localhost") || normalizedHost.startsWith("localhost:")
                || normalizedHost.equals("127.0.0.1") || normalizedHost.startsWith("127.0.0.1:")
                || normalizedHost.equals("[::1]") || normalizedHost.startsWith("[::1]:"))) {
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        try {
            String originHost = URI.create(origin).getHost();
            return "localhost".equalsIgnoreCase(originHost) || "127.0.0.1".equals(originHost)
                    || "::1".equals(originHost);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private static JsonObject response(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id.deepCopy());
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        response.add("error", error);
        return response;
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
            return new JsonObject();
        }
        return object.getAsJsonObject(name);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class UnknownMethodException extends Exception {
    }
}
