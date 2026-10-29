package com.cost2cost.printbridge;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class EscPosPrinter {
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private static final byte LF = 0x0A;

    private final ByteArrayOutputStream output;
    private final Charset charset;
    private final int paperWidthMm;

    public EscPosPrinter(String encoding, int paperWidthMm) {
        this.output = new ByteArrayOutputStream();
        this.paperWidthMm = Math.max(1, paperWidthMm);
        this.charset = resolveCharset(encoding);
    }

    public void initialize() {
        write(new byte[] { ESC, '@' });
        write(new byte[] { ESC, 'x', 1 });
        write(new byte[] { ESC, 'r', 0 });
    }

    public void setBold(boolean bold) {
        write(new byte[] { ESC, 'E', (byte) (bold ? 1 : 0) });
    }

    public void setUnderline(boolean underline) {
        write(new byte[] { ESC, '-', (byte) (underline ? 1 : 0) });
    }

    public void setAlignment(String align) {
        byte alignment = 0;
        if ("center".equalsIgnoreCase(align)) {
            alignment = 1;
        } else if ("right".equalsIgnoreCase(align)) {
            alignment = 2;
        }
        write(new byte[] { ESC, 'a', alignment });
    }

    public void setTextSize(int size) {
        int value = 0;
        if (size == 2) {
            value = 0x10;
        } else if (size == 3) {
            value = 0x11;
        } else if (size >= 4) {
            value = 0x11;
        }
        write(new byte[] { GS, '!', (byte) value });
    }

    public void writeText(String text) {
        writeText(text, "left", false, 1, false);
    }

    public void writeText(String text, String align, boolean bold, int size, boolean underline) {
        if (TextUtils.isEmpty(text)) {
            return;
        }

        setAlignment(align);
        setBold(bold);
        setUnderline(underline);
        setTextSize(size);
        write(toBytes(text));
        write(new byte[] { LF });
        setBold(false);
        setUnderline(false);
        setTextSize(1);
    }

    public void lineSeparator() {
        writeText(repeat('-', getCharacterWidth()), "left", false, 1, false);
    }

    public void feed(int lines) {
        for (int i = 0; i < Math.max(1, lines); i++) {
            write(new byte[] { ESC, 'J', 0x18 });
        }
    }

    public void cut() {
        write(new byte[] { GS, 'V', 0x41, 0x00 });
    }

    public void printItem(String name, int qty, double price, double total) {
        printItem(name, qty, price, total, null, null);
    }

    private void printItem(String name, int qty, double price, double total, Double mrp, Double savings) {
        writeText(name, "left", false, 1, false);
        writeColumns((qty > 0 ? qty + " x " : "") + formatAmount(price), formatAmount(total), false);
        if (mrp != null) {
            write(new byte[] { ESC, 'M', 1 });
            writeText("(MRP " + formatAmount(mrp) + ")", "right", false, 1, false);
            write(new byte[] { ESC, 'M', 0 });
        }
        if (savings != null) {
            write(new byte[] { ESC, 'M', 1 });
            writeText("(Saved " + formatAmount(savings) + ")", "right", false, 1, false);
            write(new byte[] { ESC, 'M', 0 });
        }
    }

    private int getCharacterWidth() {
        return paperWidthMm <= 58 ? 32 : 48;
    }

    private void writeColumns(String left, String right, boolean bold) {
        int width = getCharacterWidth();
        if (right.length() >= width) {
            right = right.substring(right.length() - width + 1);
        }
        int leftWidth = Math.max(0, width - right.length());
        if (left.length() > leftWidth) {
            left = left.substring(0, leftWidth);
        }
        int spaces = Math.max(1, width - left.length() - right.length());
        writeText(left + repeat(' ', spaces) + right, "left", bold, 1, false);
    }

    private String repeat(char character, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            value.append(character);
        }
        return value.toString();
    }

    public byte[] buildReceipt(JSONArray content) throws JSONException {
        output.reset();
        initialize();

        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.getJSONObject(i);
            String type = item.optString("type", "text");

            if ("text".equalsIgnoreCase(type)) {
                String text = item.optString("text", "");
                String align = item.optString("align", "left");
                boolean bold = item.optBoolean("bold", false);
                int size = item.optInt("size", 1);
                boolean underline = item.optBoolean("underline", false);
                writeText(text, align, bold, size, underline);
            } else if ("line".equalsIgnoreCase(type) || "separator".equalsIgnoreCase(type)) {
                lineSeparator();
            } else if ("item".equalsIgnoreCase(type)) {
                String name = item.optString("name", "");
                int qty = item.optInt("qty", 1);
                double price = item.optDouble("price", 0d);
                double total = item.has("total") ? item.optDouble("total", 0d) : item.optDouble("amount", 0d);
                Double mrp = item.has("mrp") ? item.optDouble("mrp") : null;
                Double savings = item.has("savings") ? item.optDouble("savings")
                        : item.has("offer") ? item.optDouble("offer") : null;
                printItem(name, qty, price, total, mrp, savings);
            } else if ("order".equalsIgnoreCase(type) || "header".equalsIgnoreCase(type)) {
                writeOrderHeader(item);
            } else if ("subtotal".equalsIgnoreCase(type) || "total".equalsIgnoreCase(type)) {
                String label = item.optString("label",
                        "total".equalsIgnoreCase(type) ? "Total" : "Subtotal");
                double amount = item.has("amount")
                        ? item.optDouble("amount", 0d) : item.optDouble("total", 0d);
                writeColumns(label, formatAmount(amount), "total".equalsIgnoreCase(type));
            } else if ("feed".equalsIgnoreCase(type)) {
                feed(item.optInt("lines", 1));
            } else if ("cut".equalsIgnoreCase(type)) {
                cut();
            } else {
                throw new JSONException("Unsupported print command: " + type);
            }
        }

        return output.toByteArray();
    }

    private void writeOrderHeader(JSONObject item) {
        writeText(item.optString("title", "ORDER RECEIPT"), "center", true, 1, false);
        String orderNo = item.has("orderNo") ? item.optString("orderNo") : item.optString("orderId", "");
        if (!TextUtils.isEmpty(orderNo)) {
            writeText("Order #" + orderNo, "right", true, 1, false);
        }
        String time = item.has("time") ? item.optString("time") : item.optString("createdAt", "");
        if (!TextUtils.isEmpty(time)) {
            writeText(time, "right", false, 1, false);
        }
        lineSeparator();
    }

    public byte[] buildTestPrint(String printerName, String printerIp, int printerPort) {
        output.reset();
        initialize();
        setAlignment("center");
        setBold(true);
        setTextSize(2);
        write(toBytes("COST2COST\n"));
        setBold(false);
        setTextSize(1);
        write(toBytes("PRINT TEST\n"));
        lineSeparator();
        setAlignment("left");
        write(toBytes("Printer: " + printerName + "\n"));
        write(toBytes("IP: " + printerIp + "\n"));
        write(toBytes("Port: " + printerPort + "\n"));
        write(toBytes("Status: SUCCESS\n"));
        lineSeparator();
        feed(3);
        cut();
        return output.toByteArray();
    }

    private Charset resolveCharset(String encoding) {
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }

        String trimmed = encoding.trim();
        if ("UTF-8".equalsIgnoreCase(trimmed)) {
            return StandardCharsets.UTF_8;
        }
        if ("ASCII".equalsIgnoreCase(trimmed)) {
            return StandardCharsets.US_ASCII;
        }
        if ("ISO-8859-1".equalsIgnoreCase(trimmed)) {
            return StandardCharsets.ISO_8859_1;
        }
        return StandardCharsets.UTF_8;
    }

    private byte[] toBytes(String value) {
        if (value == null) {
            return new byte[0];
        }
        return value.getBytes(charset);
    }

    private void write(byte[] data) {
        try {
            output.write(data);
        } catch (IOException ignored) {
        }
    }

    private String formatAmount(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
