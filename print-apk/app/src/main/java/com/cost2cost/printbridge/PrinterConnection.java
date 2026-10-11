package com.cost2cost.printbridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class PrinterConnection {
    private static String formatPrintTimestamp(Context context) {
        return android.text.format.DateFormat.getTimeFormat(context).format(System.currentTimeMillis());
    }

    public static PrintResult printTest(Context context, PrinterSettings settings) {
        try {
            byte[] bytes = new EscPosPrinter(settings.getEncoding(), parsePaperWidth(settings.getPaperWidth()))
                    .buildTestPrint(settings.getPrinterName(), settings.getPrinterIp(), settings.getPrinterPort());

            if (settings.isUsbConnection()) {
                UsbPrinterTransport transport = new UsbPrinterTransport(context, settings);
                if (!transport.connect()) {
                    return new PrintResult(false, "USB PRINT FAILED", transport.getLastError());
                }
                boolean ok = transport.print(bytes);
                transport.disconnect();
                if (ok) {
                    settings.setLastPrintStatus("USB SUCCESS - " + formatPrintTimestamp(context));
                    return new PrintResult(true, "USB PRINT SUCCESS", "");
                }
                return new PrintResult(false, "USB PRINT FAILED", transport.getLastError());
            }

            PrintResult result = sendBytes(settings.getPrinterIp(), settings.getPrinterPort(), settings.getTimeoutMs(), bytes);
            if (result.success) {
                settings.setLastPrintStatus("SUCCESS - " + formatPrintTimestamp(context));
            }
            return result;
        } catch (Exception e) {
            return new PrintResult(false, settings.isUsbConnection() ? "USB PRINT FAILED" : "Print failed",
                    e.getMessage() == null ? "Unknown error" : e.getMessage());
        }
    }

    public static PrintResult printJsonReceipt(Context context, PrinterSettings settings, JSONObject request) {
        try {
            JSONArray content = request.optJSONArray("content");
            if (content == null || content.length() == 0) {
                return new PrintResult(false, "Invalid print request", "Missing content array");
            }

            byte[] bytes = new EscPosPrinter(settings.getEncoding(), parsePaperWidth(settings.getPaperWidth()))
                    .buildReceipt(content);

            if (settings.isUsbConnection()) {
                UsbPrinterTransport transport = new UsbPrinterTransport(context, settings);
                if (!transport.connect()) {
                    return new PrintResult(false, "Unable to connect to USB printer", transport.getLastError());
                }
                boolean ok = transport.print(bytes);
                transport.disconnect();
                if (!ok) {
                    return new PrintResult(false, "Unable to print via USB", transport.getLastError());
                }
                settings.setLastPrintStatus("USB SUCCESS - " + formatPrintTimestamp(context));
                return new PrintResult(true, "Printed successfully", "");
            }

            PrintResult result = sendBytes(settings.getPrinterIp(), settings.getPrinterPort(), settings.getTimeoutMs(), bytes);
            if (result.success) {
                settings.setLastPrintStatus("SUCCESS - " + formatPrintTimestamp(context));
            }
            return result;
        } catch (JSONException e) {
            return new PrintResult(false, "Invalid JSON", e.getMessage() == null ? "Malformed receipt" : e.getMessage());
        } catch (Exception e) {
            return new PrintResult(false, "Unable to connect to printer", e.getMessage() == null ? "Unknown error" : e.getMessage());
        }
    }

    public static boolean isReachable(PrinterSettings settings) {
        if (settings.isUsbConnection()) {
            return new UsbPrinterTransport(null, settings).isConnected();
        }
        if (!settings.isPrinterConfigured()) {
            return false;
        }
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(settings.getPrinterIp(), settings.getPrinterPort()), settings.getTimeoutMs());
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static PrintResult sendBytes(String ip, int port, int timeoutMs, byte[] payload) {
        if (ip == null || ip.trim().isEmpty()) {
            return new PrintResult(false, "Invalid Printer IP", "Printer IP address is required.");
        }
        String targetIp = ip.trim();
        try {
            Socket socket = new Socket();
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(targetIp, port), timeoutMs);

            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            socket.close();
            return new PrintResult(true, "Printed successfully", "");
        } catch (SocketTimeoutException e) {
            return new PrintResult(false, "Connection timed out", "Printer not reachable at " + targetIp + ":" + port + " (Timeout " + timeoutMs + "ms)");
        } catch (IOException e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new PrintResult(false, "Unable to connect to printer", detail + " (" + targetIp + ":" + port + ")");
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            return new PrintResult(false, "Print failed", detail);
        }
    }

    private static int parsePaperWidth(String paperWidth) {
        if (paperWidth == null) {
            return 80;
        }
        String text = paperWidth.replace("mm", "").trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    public static class PrintResult {
        public final boolean success;
        public final String message;
        public final String error;

        public PrintResult(boolean success, String message, String error) {
            this.success = success;
            this.message = message;
            this.error = error;
        }
    }
}
