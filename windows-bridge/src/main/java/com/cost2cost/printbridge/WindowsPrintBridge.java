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
    private final int paperWidthMm;
    private final int timeoutMs;
    private final Charset charset;
    private final String token;

    WindowsPrintBridge(Map<String, String> options) throws IOException {
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
        paperWidthMm = parseInt(options, "paper-width", 80, 58, 80);
        if (paperWidthMm != 58 && paperWidthMm != 80) {
            throw new IllegalArgumentException("--paper-width must be 58 or 80");
        }
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
        } else if ("GET".equalsIgnoreCase(method) && "/preview".equals(path)) {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                            + "connect-src 'self'; frame-src 'self'");
            send(exchange, 200, previewPage());
        } else if ("GET".equalsIgnoreCase(method) && "/status".equals(path)) {
            send(exchange, 200, "{\"bridgeRunning\":true,\"printerConfigured\":"
                    + ("usb".equals(connectionType) ? !printerName.isBlank() : !printerIp.isBlank() && printerPort > 0)
                    + ",\"connectionType\":" + quote(connectionType)
                    + ",\"printerName\":" + quote("usb".equals(connectionType) ? printerName : "")
                    + ",\"printerIp\":" + quote(printerIp)
                    + ",\"printerPort\":" + printerPort
                    + ",\"printerReachable\":" + isPrinterReachable() + "}");
        } else if ("POST".equalsIgnoreCase(method) && "/preview".equals(path)) {
            handlePreview(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/print".equals(path)) {
            handlePrint(exchange);
        } else {
            send(exchange, 404, "{\"success\":false,\"message\":\"Not found\"}");
        }
    }

    private void handlePreview(HttpExchange exchange) throws IOException {
        if (!token.isEmpty() && !("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, errorJson("Unauthorized", "A valid bearer token is required"));
            return;
        }

        try {
            byte[] body = readLimited(exchange.getRequestBody());
            if (body.length == 0) {
                send(exchange, 400, errorJson("Invalid preview request", "Empty request body"));
                return;
            }

            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map)) {
                send(exchange, 400, errorJson("Invalid preview request", "Expected a JSON object"));
                return;
            }
            Object contentValue = ((Map<?, ?>) parsed).get("content");
            if (!(contentValue instanceof List) || ((List<?>) contentValue).isEmpty()) {
                send(exchange, 400, errorJson("Preview unavailable", "Missing content array"));
                return;
            }

            buildReceipt((List<?>) contentValue);
            String html = buildPreviewHtml((List<?>) contentValue, paperWidthMm);
            send(exchange, 200, "{\"success\":true,\"previewHtml\":" + quote(html) + "}");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, errorJson("Invalid preview request", e.getMessage()));
        } catch (RequestTooLargeException e) {
            send(exchange, 413, errorJson("Request too large", e.getMessage()));
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

    byte[] buildReceipt(List<?> content) {
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
                case "separator":
                    writeSeparator(output);
                    break;
                case "item":
                    writeItem(output, item);
                    break;
                case "order":
                case "header":
                    writeOrderHeader(output, item);
                    break;
                case "subtotal":
                case "total":
                    writeSummary(output, item, "total".equals(type));
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
        return output.toByteArray();
    }

    static String buildPreviewHtml(List<?> content, int paperWidthMm) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "style-src 'unsafe-inline'; img-src data:\"><style>"
                + "*{box-sizing:border-box}html,body{margin:0;background:#e8e8e8;color:#111;"
                + "font:12px/1.2 Arial,sans-serif}.receipt{width:" + paperWidthMm
                + "mm;margin:12px auto;padding:3mm;background:#fff;box-shadow:0 2px 12px #0003;"
                + "overflow-wrap:anywhere}.row{white-space:pre-wrap;min-height:1.15em}"
                + ".center{text-align:center}.right{text-align:right}.bold{font-weight:bold}"
                + ".small{font-size:9px;line-height:1.05}.large{font-size:1.25em;line-height:1.15}"
                + ".larger{font-size:1.4em;line-height:1.15}.separator{border-top:1px dashed #666;margin:2px 0}"
                + ".columns{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:6px;align-items:baseline}"
                + ".left{overflow-wrap:anywhere}.amount{text-align:right;white-space:nowrap;font-weight:bold}"
                + ".details{font-size:11px;line-height:1.15}.mrp{font-size:9px;line-height:1.05}"
                + ".feed{height:2mm}.cut{border-top:1px dashed #888;margin:2mm 0}"
                + "@media print{html,body{background:#fff}.receipt{margin:0;box-shadow:none;width:"
                + paperWidthMm + "mm}}"
                + "</style></head><body><main class=\"receipt\">");
        for (Object value : content) {
            Map<?, ?> item = (Map<?, ?>) value;
            String type = stringValue(item.get("type"), "text").toLowerCase(Locale.ROOT);
            switch (type) {
                case "text":
                    appendPreviewText(html, stringValue(item.get("text"), ""),
                            stringValue(item.get("align"), "left"),
                            booleanValue(item.get("bold"), false), intValue(item.get("size"), 1));
                    break;
                case "line":
                case "separator":
                    html.append("<div class=\"separator\" aria-label=\"section separator\"></div>");
                    break;
                case "order":
                case "header":
                    appendPreviewOrderHeader(html, item);
                    break;
                case "subtotal":
                case "total":
                    appendPreviewSummary(html, item, "total".equals(type));
                    break;
                case "item":
                    appendPreviewItem(html, item);
                    break;
                case "feed":
                    int lines = Math.max(1, Math.min(100, intValue(item.get("lines"), 1)));
                    for (int i = 0; i < lines; i++) {
                        html.append("<div class=\"feed\" aria-hidden=\"true\"></div>");
                    }
                    break;
                case "cut":
                    html.append("<div class=\"cut\" aria-label=\"cut command\"></div>");
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported print command: " + type);
            }
        }
        for (int i = 0; i < 3; i++) {
            html.append("<div class=\"feed\" aria-hidden=\"true\"></div>");
        }
        return html.append("</main></body></html>").toString();
    }

    private void writeItem(ByteArrayOutputStream output, Map<?, ?> item) {
        String name = stringValue(item.get("name"), "");
        int qty = intValue(item.get("qty"), 1);
        double unitPrice = doubleValue(item.get("price"), 0);
        double amount = doubleValue(item.get("total"), doubleValue(item.get("amount"), 0));
        writeText(output, name, "left", false, 1, false);
        writeColumns(output, (qty > 0 ? qty + " x " : "") + money(unitPrice), money(amount), false);
        if (item.get("mrp") instanceof Number || item.get("mrp") instanceof String) {
            write(output, new byte[] { ESC, 'M', 1 });
            writeText(output, "(MRP " + money(doubleValue(item.get("mrp"), 0)) + ")",
                    "right", false, 1, false);
            write(output, new byte[] { ESC, 'M', 0 });
        }
        if (item.get("savings") instanceof Number || item.get("savings") instanceof String) {
            write(output, new byte[] { ESC, 'M', 1 });
            writeText(output, "(Saved " + money(doubleValue(item.get("savings"), 0)) + ")",
                    "right", false, 1, false);
            write(output, new byte[] { ESC, 'M', 0 });
        } else if (item.get("offer") instanceof Number || item.get("offer") instanceof String) {
            write(output, new byte[] { ESC, 'M', 1 });
            writeText(output, "(Offer " + money(doubleValue(item.get("offer"), 0)) + ")",
                    "right", false, 1, false);
            write(output, new byte[] { ESC, 'M', 0 });
        }
    }

    private void writeOrderHeader(ByteArrayOutputStream output, Map<?, ?> item) {
        String title = stringValue(item.get("title"), "ORDER RECEIPT");
        writeText(output, title, "center", true, 1, false);
        Object orderNo = item.containsKey("orderNo") ? item.get("orderNo") : item.get("orderId");
        if (orderNo != null) {
            writeText(output, "Order #" + orderNo, "right", true, 1, false);
        }
        Object time = item.containsKey("time") ? item.get("time") : item.get("createdAt");
        if (time != null) {
            writeText(output, stringValue(time, ""), "right", false, 1, false);
        }
        writeSeparator(output);
    }

    private void writeSummary(ByteArrayOutputStream output, Map<?, ?> item, boolean emphasize) {
        String label = stringValue(item.get("label"), emphasize ? "Total" : "Subtotal");
        double amount = doubleValue(item.get("amount"), doubleValue(item.get("total"), 0));
        writeColumns(output, label, money(amount), emphasize);
    }

    private void writeColumns(ByteArrayOutputStream output, String left, String right, boolean bold) {
        int columns = paperWidthMm == 58 ? 32 : 48;
        if (right.length() >= columns) {
            right = right.substring(right.length() - columns + 1);
        }
        int leftWidth = Math.max(0, columns - right.length());
        if (left.length() > leftWidth) {
            left = left.substring(0, leftWidth);
        }
        writeText(output, left + " ".repeat(Math.max(1, columns - left.length() - right.length())) + right,
                "left", bold, 1, false);
    }

    private void writeSeparator(ByteArrayOutputStream output) {
        writeText(output, "-".repeat(paperWidthMm == 58 ? 32 : 48), "left", false, 1, false);
    }

    private static String money(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static void appendPreviewOrderHeader(StringBuilder html, Map<?, ?> item) {
        appendPreviewText(html, stringValue(item.get("title"), "ORDER RECEIPT"), "center", true, 1);
        Object orderNo = item.containsKey("orderNo") ? item.get("orderNo") : item.get("orderId");
        if (orderNo != null) {
            appendPreviewText(html, "Order #" + orderNo, "right", true, 1);
        }
        Object time = item.containsKey("time") ? item.get("time") : item.get("createdAt");
        if (time != null) {
            appendPreviewText(html, stringValue(time, ""), "right", false, 1);
        }
        html.append("<div class=\"separator\" aria-label=\"section separator\"></div>");
    }

    private static void appendPreviewItem(StringBuilder html, Map<?, ?> item) {
        appendPreviewText(html, stringValue(item.get("name"), ""), "left", false, 1);
        int qty = intValue(item.get("qty"), 1);
        double unitPrice = doubleValue(item.get("price"), 0);
        double amount = doubleValue(item.get("total"), doubleValue(item.get("amount"), 0));
        html.append("<div class=\"columns details\"><span class=\"left\">")
                .append(escapeHtml((qty > 0 ? qty + " x " : "") + money(unitPrice)))
                .append("</span><span class=\"amount\">").append(escapeHtml(money(amount)))
                .append("</span></div>");
        if (item.get("mrp") instanceof Number || item.get("mrp") instanceof String) {
            html.append("<div class=\"row right small\">(MRP ")
                    .append(escapeHtml(money(doubleValue(item.get("mrp"), 0)))).append(")</div>");
        }
        if (item.get("savings") instanceof Number || item.get("savings") instanceof String) {
            html.append("<div class=\"row right small\">(Saved ")
                    .append(escapeHtml(money(doubleValue(item.get("savings"), 0)))).append(")</div>");
        } else if (item.get("offer") instanceof Number || item.get("offer") instanceof String) {
            html.append("<div class=\"row right small\">(Offer ")
                    .append(escapeHtml(money(doubleValue(item.get("offer"), 0)))).append(")</div>");
        }
    }

    private static void appendPreviewSummary(StringBuilder html, Map<?, ?> item, boolean emphasize) {
        String label = stringValue(item.get("label"), emphasize ? "Total" : "Subtotal");
        double amount = doubleValue(item.get("amount"), doubleValue(item.get("total"), 0));
        html.append("<div class=\"columns")
                .append(emphasize ? " bold" : "")
                .append("\"><span class=\"left\">").append(escapeHtml(label))
                .append("</span><span class=\"amount\">").append(escapeHtml(money(amount)))
                .append("</span></div>");
    }
    private static void appendPreviewText(StringBuilder html, String text, String align, boolean bold, int size) {
        if (text.isEmpty()) {
            return;
        }
        String alignment = "center".equalsIgnoreCase(align) ? "center"
                : "right".equalsIgnoreCase(align) ? "right" : "left";
        String sizeClass = size == 2 ? " large" : size >= 3 ? " larger" : "";
        html.append("<div class=\"row ").append(alignment);
        if (bold) {
            html.append(" bold");
        }
        html.append(sizeClass).append("\">").append(escapeHtml(text)).append("</div>");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String previewPage() {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Cost2Cost Print Preview</title><style>"
                + "*{box-sizing:border-box}body{margin:0;background:#f2f4f7;color:#182230;"
                + "font:14px/1.5 system-ui,sans-serif}.layout{max-width:1100px;margin:32px auto;padding:0 20px}"
                + "h1{font-size:24px;margin:0 0 8px}.note{background:#fff8db;border:1px solid #f1d978;"
                + "border-radius:8px;padding:12px 16px;margin:16px 0}main{display:grid;"
                + "grid-template-columns:minmax(320px,1fr) minmax(280px,420px);gap:20px}"
                + "textarea{width:100%;height:430px;padding:12px;border:1px solid #bac4d0;"
                + "border-radius:8px;font:12px/1.45 Consolas,monospace}button{padding:10px 16px;"
                + "border:0;border-radius:6px;background:#1459c7;color:white;font-weight:600;cursor:pointer}"
                + "#status{min-height:24px;margin:8px 0}iframe{width:100%;height:580px;border:1px solid #ccd3dc;"
                + "background:#e8e8e8;border-radius:8px}@media(max-width:760px){.layout{margin:16px auto}"
                + "main{grid-template-columns:1fr}textarea{height:280px}}</style></head><body>"
                + "<div class=\"layout\"><h1>Cost2Cost Print Preview</h1>"
                + "<div class=\"note\"><strong>Preview only:</strong> this page does not send anything to a printer. "
                + "It simulates the bridge's text-command layout; font rendering and paper feed may differ on the device. "
                + "For a faithful visual check, use a frontend-rendered raster payload.</div><main><section>"
                + "<label for=\"payload\">Paste the same JSON receipt payload you would send to <code>/print</code>.</label>"
                + "<p><textarea id=\"payload\" spellcheck=\"false\">{&quot;content&quot;:["
                + "{&quot;type&quot;:&quot;order&quot;,&quot;orderNo&quot;:&quot;CC-1042&quot;,&quot;time&quot;:&quot;30 Oct 2026 10:15 AM&quot;},"
                + "{&quot;type&quot;:&quot;item&quot;,&quot;name&quot;:&quot;Product A - long product description&quot;,&quot;qty&quot;:2,&quot;price&quot;:100,&quot;total&quot;:200,&quot;mrp&quot;:250,&quot;savings&quot;:50},"
                + "{&quot;type&quot;:&quot;item&quot;,&quot;name&quot;:&quot;Product B&quot;,&quot;qty&quot;:1,&quot;price&quot;:75,&quot;total&quot;:75,&quot;mrp&quot;:90},"
                + "{&quot;type&quot;:&quot;separator&quot;},"
                + "{&quot;type&quot;:&quot;subtotal&quot;,&quot;amount&quot;:275},"
                + "{&quot;type&quot;:&quot;total&quot;,&quot;amount&quot;:275}]}</textarea></p>"
                + "<label>Bearer token (only if configured): <input id=\"token\" type=\"password\" autocomplete=\"off\"></label>"
                + "<p><button id=\"preview\" type=\"button\">Show preview (no printing)</button></p>"
                + "<div id=\"status\" role=\"status\" aria-live=\"polite\"></div></section>"
                + "<iframe id=\"receipt\" title=\"Simulated receipt preview\" sandbox=\"\"></iframe>"
                + "</main></div><script>"
                + "const button=document.getElementById('preview'),status=document.getElementById('status');"
                + "button.addEventListener('click',async()=>{status.textContent='Rendering preview…';"
                + "try{const payload=JSON.parse(document.getElementById('payload').value);"
                + "const headers={'Content-Type':'application/json'};const token=document.getElementById('token').value;"
                + "if(token)headers.Authorization='Bearer '+token;"
                + "const response=await fetch('/preview',{method:'POST',headers,body:JSON.stringify(payload)});"
                + "const result=await response.json();if(!response.ok||!result.success)throw new Error(result.error||result.message||'Preview failed');"
                + "document.getElementById('receipt').srcdoc=result.previewHtml;"
                + "status.textContent='Preview only — no printer job was sent.';"
                + "}catch(error){status.textContent=error.message;}});</script></body></html>";
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
        private static final long serialVersionUID = 1L;

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
