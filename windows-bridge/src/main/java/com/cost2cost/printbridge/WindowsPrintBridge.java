package com.cost2cost.printbridge;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public final class WindowsPrintBridge {
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private static final byte LF = 0x0A;

    private final String bindHost;
    private final int bridgePort;
    private final String connectionType;
    private final String printerName;
    private final Path spoolerScript;
    private final String printerIp;
    private final int printerPort;
    private final int timeoutMs;
    private final Charset charset;
    private final String token;

    private WindowsPrintBridge(Map<String, String> options) throws IOException {
        bindHost = options.getOrDefault("host", "127.0.0.1");
        bridgePort = parseInt(options, "bridge-port", 8765, 1, 65535);
        connectionType = options.getOrDefault("connection", "ethernet").toLowerCase(Locale.ROOT);
        if (!"ethernet".equals(connectionType) && !"usb".equals(connectionType)) {
            throw new IllegalArgumentException("--connection must be ethernet or usb");
        }
        printerName = options.getOrDefault("printer-name", "");
        spoolerScript = Paths.get(options.getOrDefault("spooler-script", "scripts\\print-raw.ps1"))
                .toAbsolutePath().normalize();
        if ("usb".equals(connectionType)) {
            if (printerName.isBlank()) {
                throw new IllegalArgumentException("--printer-name is required for USB connection mode");
            }
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
                throw new IllegalArgumentException("USB printer-queue mode requires Windows");
            }
            if (!Files.isRegularFile(spoolerScript)) {
                throw new IllegalArgumentException("Windows spooler helper not found: " + spoolerScript);
            }
        }
        printerIp = options.getOrDefault("printer-ip", "192.168.1.87");
        printerPort = parseInt(options, "printer-port", 9100, 1, 65535);
        timeoutMs = parseInt(options, "timeout", 5000, 1, 120000);
        token = options.getOrDefault("token", "");
        if (!InetAddress.getByName(bindHost).isLoopbackAddress() && token.isEmpty()) {
            throw new IllegalArgumentException("A bearer token is required when binding beyond loopback");
        }
        charset = resolveCharset(options.getOrDefault("encoding", "UTF-8"));
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> options = parseOptions(args);
        WindowsPrintBridge bridge = new WindowsPrintBridge(options);
        HttpServer server = HttpServer.create(new InetSocketAddress(bridge.bindHost, bridge.bridgePort), 0);
        server.createContext("/", bridge::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.printf("Cost2Cost Windows Print Bridge listening at http://%s:%d%n",
                bridge.bindHost, bridge.bridgePort);
        if ("usb".equals(bridge.connectionType)) {
            System.out.printf("USB printer queue: %s%n", bridge.printerName);
        } else {
            System.out.printf("Network printer: %s:%d (timeout %d ms)%n",
                    bridge.printerIp, bridge.printerPort, bridge.timeoutMs);
        }
        new CountDownLatch(1).await();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        addCorsHeaders(exchange.getResponseHeaders(), exchange.getRequestHeaders().getFirst("Origin"));

        if ("OPTIONS".equalsIgnoreCase(method)) {
            send(exchange, 200, "");
        } else if ("GET".equalsIgnoreCase(method) && "/status".equals(path)) {
            send(exchange, 200, "{\"bridgeRunning\":true,\"printerConfigured\":"
                    + ("usb".equals(connectionType) ? !printerName.isBlank() : !printerIp.isBlank() && printerPort > 0)
                    + ",\"connectionType\":" + quote(connectionType)
                    + ",\"printerName\":" + quote("usb".equals(connectionType) ? printerName : "")
                    + ",\"printerIp\":" + quote(printerIp)
                    + ",\"printerPort\":" + printerPort
                    + ",\"printerReachable\":" + isPrinterReachable() + "}");
        } else if ("POST".equalsIgnoreCase(method) && "/print".equals(path)) {
            handlePrint(exchange);
        } else {
            send(exchange, 404, "{\"success\":false,\"message\":\"Not found\"}");
        }
    }

    private void handlePrint(HttpExchange exchange) throws IOException {
        if (!token.isEmpty() && !("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, "{\"success\":false,\"message\":\"Unauthorized\"}");
            return;
        }

        try {
            byte[] body = readLimited(exchange.getRequestBody());
            if (body.length == 0) {
                send(exchange, 400, "{\"success\":false,\"message\":\"Empty request body\"}");
                return;
            }

            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map)) {
                send(exchange, 400, errorJson("Invalid JSON", "Expected a JSON object"));
                return;
            }
            Object contentValue = ((Map<?, ?>) parsed).get("content");
            if (!(contentValue instanceof List) || ((List<?>) contentValue).isEmpty()) {
                send(exchange, 400, errorJson("Invalid print request", "Missing content array"));
                return;
            }

            byte[] receipt = buildReceipt((List<?>) contentValue);
            sendToPrinter(receipt);
            send(exchange, 200, "{\"success\":true,\"message\":\"Printed successfully\"}");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, errorJson("Invalid print request", e.getMessage()));
        } catch (RequestTooLargeException e) {
            send(exchange, 413, errorJson("Request too large", e.getMessage()));
        } catch (IOException e) {
            send(exchange, 500, errorJson("Unable to connect to printer", e.getMessage()));
        }
    }

    private boolean isPrinterReachable() {
        if ("usb".equals(connectionType)) {
            try {
                runSpooler(true, null);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(printerIp, printerPort), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void sendToPrinter(byte[] bytes) throws IOException {
        if ("usb".equals(connectionType)) {
            runSpooler(false, bytes);
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(printerIp, printerPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }
    }

    private void runSpooler(boolean checkOnly, byte[] bytes) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(spoolerScript.toString());
        command.add("-PrinterName");
        command.add(printerName);
        if (checkOnly) {
            command.add("-CheckOnly");
        }

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!checkOnly) {
            try (var input = process.getOutputStream()) {
                input.write(java.util.Base64.getEncoder().encode(bytes));
                input.write('\n');
            }
        } else {
            process.getOutputStream().close();
        }

        boolean finished;
        try {
            finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while communicating with the Windows printer spooler", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out communicating with the Windows printer spooler");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException(output.isEmpty() ? "Windows printer spooler rejected the job" : output);
        }
    }

    private byte[] buildReceipt(List<?> content) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(output, new byte[] { ESC, '@', ESC, 'x', 1, ESC, 'r', 0 });

        for (Object value : content) {
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("Each content item must be an object");
            }
            Map<?, ?> item = (Map<?, ?>) value;
            String type = stringValue(item.get("type"), "text").toLowerCase(Locale.ROOT);
            switch (type) {
                case "text":
                    writeText(output, stringValue(item.get("text"), ""),
                            stringValue(item.get("align"), "left"),
                            booleanValue(item.get("bold"), false),
                            intValue(item.get("size"), 1),
                            booleanValue(item.get("underline"), false));
                    break;
                case "line":
                    write(output, new byte[] { ESC, 'd', 3 });
                    break;
                case "item":
                    String name = stringValue(item.get("name"), "");
                    int qty = intValue(item.get("qty"), 1);
                    double price = doubleValue(item.get("price"), 0);
                    double total = doubleValue(item.get("total"), 0);
                    writeText(output, name + (qty > 0 ? " x" + qty : ""), "left", false, 1, false);
                    writeText(output, String.format(Locale.US, "%.2f  %.2f", price, total),
                            "right", false, 1, false);
                    break;
                case "feed":
                    int lines = Math.max(1, Math.min(100, intValue(item.get("lines"), 1)));
                    for (int i = 0; i < lines; i++) {
                        write(output, new byte[] { ESC, 'J', 0x18 });
                    }
                    break;
                case "cut":
                    write(output, new byte[] { GS, 'V', 0x41, 0x00 });
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported print command: " + type);
            }
        }
        for (int i = 0; i < 3; i++) {
            write(output, new byte[] { ESC, 'J', 0x18 });
        }
        return output.toByteArray();
    }

    private void writeText(ByteArrayOutputStream output, String text, String align,
                           boolean bold, int size, boolean underline) {
        if (text.isEmpty()) {
            return;
        }
        int alignment = "center".equalsIgnoreCase(align) ? 1 : "right".equalsIgnoreCase(align) ? 2 : 0;
        int textSize = size == 2 ? 0x10 : size >= 3 ? 0x11 : 0;
        write(output, new byte[] { ESC, 'a', (byte) alignment });
        write(output, new byte[] { ESC, 'E', (byte) (bold ? 1 : 0) });
        write(output, new byte[] { ESC, '-', (byte) (underline ? 1 : 0) });
        write(output, new byte[] { GS, '!', (byte) textSize });
        write(output, text.getBytes(charset));
        write(output, new byte[] { LF, ESC, 'E', 0, ESC, '-', 0, GS, '!', 0 });
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException("Request body exceeds 1 MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void addCorsHeaders(Headers headers, String origin) {
        if ("https://cost2cost.in".equals(origin) || isLocalhostOrigin(origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
        }
        headers.set("Vary", "Origin");
        headers.set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Origin");
        headers.set("Access-Control-Allow-Credentials", "true");
        headers.set("Content-Type", "application/json; charset=utf-8");
    }

    private static boolean isLocalhostOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getRawPath() != null
                    && uri.getRawPath().isEmpty()
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static String errorJson(String message, String error) {
        return "{\"success\":false,\"message\":" + quote(message)
                + ",\"error\":" + quote(error == null ? "Unknown error" : error) + "}";
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static Charset resolveCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported encoding: " + name, e);
        }
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int parseInt(Map<String, String> options, String key, int fallback, int min, int max) {
        String value = options.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("--" + key + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a number", e);
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --option value, got: " + argument);
            }
            options.put(argument.substring(2), args[++i]);
        }
        return options;
    }

    private static void write(ByteArrayOutputStream output, byte[] bytes) {
        output.write(bytes, 0, bytes.length);
    }

    private static final class RequestTooLargeException extends IOException {
        RequestTooLargeException(String message) {
            super(message);
        }
    }

    private static final class JsonParser {
        private final String source;
        private int index;

        JsonParser(String source) {
            this.source = source;
        }

        Object parse() {
            Object value = readValue();
            skipWhitespace();
            if (index != source.length()) {
                fail("Unexpected trailing content");
            }
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (index >= source.length()) {
                return fail("Unexpected end of JSON");
            }
            char current = source.charAt(index);
            if (current == '{') return readObject();
            if (current == '[') return readArray();
            if (current == '"') return readString();
            if (current == 't' || current == 'f') return readBoolean();
            if (current == 'n') return readNull();
            if (current == '-' || Character.isDigit(current)) return readNumber();
            return fail("Unexpected character at position " + index);
        }

        private Map<String, Object> readObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (take('}')) return object;
            while (true) {
                skipWhitespace();
                if (index >= source.length() || source.charAt(index) != '"') {
                    return fail("Expected object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                object.put(key, readValue());
                skipWhitespace();
                if (take('}')) return object;
                expect(',');
            }
        }

        private List<Object> readArray() {
            List<Object> array = new ArrayList<>();
            index++;
            skipWhitespace();
            if (take(']')) return array;
            while (true) {
                array.add(readValue());
                skipWhitespace();
                if (take(']')) return array;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < source.length()) {
                char current = source.charAt(index++);
                if (current == '"') return result.toString();
                if (current == '\\') {
                    if (index >= source.length()) return fail("Incomplete escape sequence");
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"': result.append('"'); break;
                        case '\\': result.append('\\'); break;
                        case '/': result.append('/'); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u':
                            if (index + 4 > source.length()) return fail("Incomplete Unicode escape");
                            try {
                                result.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                            } catch (NumberFormatException e) {
                                return fail("Invalid Unicode escape");
                            }
                            index += 4;
                            break;
                        default: return fail("Invalid escape sequence");
                    }
                } else {
                    if (current < 0x20) return fail("Control character in string");
                    result.append(current);
                }
            }
            return fail("Unterminated string");
        }

        private Object readBoolean() {
            if (source.startsWith("true", index)) {
                index += 4;
                return true;
            }
            if (source.startsWith("false", index)) {
                index += 5;
                return false;
            }
            return fail("Invalid boolean");
        }

        private Object readNull() {
            if (!source.startsWith("null", index)) return fail("Invalid null");
            index += 4;
            return null;
        }

        private Number readNumber() {
            int start = index;
            if (take('-') && index >= source.length()) return fail("Invalid number");
            if (take('0')) {
                if (index < source.length() && Character.isDigit(source.charAt(index))) {
                    return fail("Leading zero in number");
                }
            } else {
                readDigits();
            }
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                readDigits();
            }
            if (take('e') || take('E')) {
                decimal = true;
                if (!take('+')) take('-');
                readDigits();
            }
            String number = source.substring(start, index);
            try {
                return decimal ? Double.parseDouble(number) : Long.parseLong(number);
            } catch (NumberFormatException e) {
                return fail("Invalid number");
            }
        }

        private void readDigits() {
            int start = index;
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            if (start == index) fail("Expected digit");
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!take(expected)) fail("Expected '" + expected + "'");
        }

        private boolean take(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private <T> T fail(String message) {
            throw new IllegalArgumentException(message);
        }
    }
}
