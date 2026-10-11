package com.cost2cost.printbridge;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class BridgeHttpServer extends NanoHTTPD {
    private static final String TAG = "BridgeHttpServer";

    private final PrinterSettings settings;
    private final Context context;

    public BridgeHttpServer(String host, int port, PrinterSettings settings, Context context) {
        super(host, port);
        this.settings = settings;
        this.context = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        String uri = session.getUri();
        String origin = session.getHeaders().get("origin");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            return withCorsHeaders(session, Response.Status.OK, "");
        }

        if ("GET".equalsIgnoreCase(method) && "/status".equals(uri)) {
            return handleStatusRequest(session);
        }

        if ("POST".equalsIgnoreCase(method) && "/print".equals(uri)) {
            return handlePrintRequest(session);
        }

        return withCorsHeaders(session, Response.Status.NOT_FOUND, "{\"success\":false,\"message\":\"Not found\"}");
    }

    private Response handleStatusRequest(IHTTPSession session) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("bridgeRunning", true);
            payload.put("printerConfigured", settings.isPrinterConfigured());
            payload.put("printerIp", settings.getPrinterIp());
            payload.put("printerPort", settings.getPrinterPort());
            payload.put("printerReachable", PrinterConnection.isReachable(settings));
            return withCorsHeaders(session, Response.Status.OK, payload.toString());
        } catch (Exception e) {
            return withCorsHeaders(session, Response.Status.INTERNAL_ERROR, "{\"success\":false,\"message\":\"Status unavailable\"}");
        }
    }

    private Response handlePrintRequest(IHTTPSession session) {
        if (settings.getAllowLan()) {
            String authHeader = session.getHeaders().get("authorization");
            if (TextUtils.isEmpty(authHeader) || !authHeader.equals("Bearer " + settings.getApiToken())) {
                return withCorsHeaders(session, Response.Status.UNAUTHORIZED, "{\"success\":false,\"message\":\"Unauthorized\"}");
            }
        }

        try {
            String requestBody = readBody(session);
            if (TextUtils.isEmpty(requestBody)) {
                return withCorsHeaders(session, Response.Status.BAD_REQUEST, "{\"success\":false,\"message\":\"Empty request body\"}");
            }

            JSONObject request = new JSONObject(requestBody);
            PrinterConnection.PrintResult result = PrinterConnection.printJsonReceipt(context, settings, request);
            if (!result.success) {
                JSONObject error = new JSONObject();
                error.put("success", false);
                error.put("message", result.message);
                error.put("error", result.error);
                return withCorsHeaders(session, Response.Status.INTERNAL_ERROR, error.toString());
            }

            JSONObject success = new JSONObject();
            success.put("success", true);
            success.put("message", result.message);
            return withCorsHeaders(session, Response.Status.OK, success.toString());
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            try {
                error.put("success", false);
                error.put("message", "Invalid JSON");
                error.put("error", e.getMessage() == null ? "Malformed request" : e.getMessage());
            } catch (Exception ignored) {
            }
            return withCorsHeaders(session, Response.Status.BAD_REQUEST, error.toString());
        }
    }

    private String readBody(IHTTPSession session) throws IOException {
        Map<String, String> headers = session.getHeaders();
        String contentLength = headers.get("content-length");
        int length = contentLength == null ? 0 : Integer.parseInt(contentLength);
        if (length <= 0) {
            return "";
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream inputStream = new BufferedInputStream(session.getInputStream(), length);
        byte[] chunk = new byte[1024];
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() >= length) {
                break;
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private Response withCorsHeaders(IHTTPSession session, Response.Status status, String body) {
        String origin = session.getHeaders().get("origin");
        String allowedOrigin = getAllowedOrigin(origin);
        Response response = newFixedLengthResponse(status, "application/json", body);
        response.addHeader("Access-Control-Allow-Origin", allowedOrigin);
        response.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Origin");
        response.addHeader("Access-Control-Allow-Credentials", "true");
        return response;
    }

    private String getAllowedOrigin(String origin) {
        if ("https://cost2cost.in".equals(origin) || "http://localhost".equals(origin) || "http://127.0.0.1".equals(origin)) {
            return origin;
        }
        return "https://cost2cost.in";
    }
}
