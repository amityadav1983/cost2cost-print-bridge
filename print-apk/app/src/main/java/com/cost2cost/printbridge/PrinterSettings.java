package com.cost2cost.printbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public class PrinterSettings {
    public static final String CONNECTION_ETHERNET = "ETHERNET";
    public static final String CONNECTION_USB = "USB";

    private static final String PREFS_NAME = "cost2cost_print_bridge_settings";
    private static final String KEY_PRINTER_NAME = "printer_name";
    private static final String KEY_PRINTER_IP = "printer_ip";
    private static final String KEY_PRINTER_PORT = "printer_port";
    private static final String KEY_PAPER_WIDTH = "paper_width";
    private static final String KEY_ENCODING = "encoding";
    private static final String KEY_TIMEOUT_MS = "timeout_ms";
    private static final String KEY_BRIDGE_PORT = "bridge_port";
    private static final String KEY_ALLOW_LAN = "allow_lan";
    private static final String KEY_API_TOKEN = "api_token";
    private static final String KEY_LAST_PRINT = "last_print";
    private static final String KEY_AUTO_START_ON_BOOT = "auto_start_on_boot";
    private static final String KEY_SERVER_RUNNING = "server_running";
    private static final String KEY_CONNECTION_TYPE = "connection_type";
    private static final String KEY_USB_DEVICE_NAME = "usb_device_name";
    private static final String KEY_USB_VENDOR_ID = "usb_vendor_id";
    private static final String KEY_USB_PRODUCT_ID = "usb_product_id";

    private final SharedPreferences preferences;

    private PrinterSettings(Context context) {
        this.preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static PrinterSettings get(Context context) {
        return new PrinterSettings(context);
    }

    public void save(String printerName, String printerIp, int printerPort, String paperWidth,
                     String encoding, int timeoutMs, int bridgePort, boolean allowLan,
                     String apiToken, boolean autoStartOnBoot, String connectionType) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(KEY_PRINTER_NAME, printerName);
        editor.putString(KEY_PRINTER_IP, printerIp);
        editor.putInt(KEY_PRINTER_PORT, printerPort);
        editor.putString(KEY_PAPER_WIDTH, paperWidth);
        editor.putString(KEY_ENCODING, encoding);
        editor.putInt(KEY_TIMEOUT_MS, timeoutMs);
        editor.putInt(KEY_BRIDGE_PORT, bridgePort);
        editor.putBoolean(KEY_ALLOW_LAN, allowLan);
        editor.putString(KEY_API_TOKEN, apiToken == null ? "" : apiToken);
        editor.putBoolean(KEY_AUTO_START_ON_BOOT, autoStartOnBoot);
        editor.putString(KEY_CONNECTION_TYPE, connectionType == null ? CONNECTION_ETHERNET : connectionType);
        editor.apply();
    }

    public String getPrinterName() {
        return preferences.getString(KEY_PRINTER_NAME, "Rugtek");
    }

    public String getPrinterIp() {
        return preferences.getString(KEY_PRINTER_IP, "192.168.1.87");
    }

    public int getPrinterPort() {
        return preferences.getInt(KEY_PRINTER_PORT, 9100);
    }

    public String getPaperWidth() {
        return preferences.getString(KEY_PAPER_WIDTH, "80mm");
    }

    public String getEncoding() {
        return preferences.getString(KEY_ENCODING, "UTF-8");
    }

    public int getTimeoutMs() {
        return preferences.getInt(KEY_TIMEOUT_MS, 5000);
    }

    public int getBridgePort() {
        return preferences.getInt(KEY_BRIDGE_PORT, 8765);
    }

    public boolean getAllowLan() {
        return preferences.getBoolean(KEY_ALLOW_LAN, false);
    }

    public String getApiToken() {
        return preferences.getString(KEY_API_TOKEN, "");
    }

    public boolean isAutoStartOnBootEnabled() {
        return preferences.getBoolean(KEY_AUTO_START_ON_BOOT, false);
    }

    public boolean isServerRunning() {
        return preferences.getBoolean(KEY_SERVER_RUNNING, false);
    }

    public void setServerRunning(boolean value) {
        preferences.edit().putBoolean(KEY_SERVER_RUNNING, value).apply();
    }

    public String getConnectionType() {
        return preferences.getString(KEY_CONNECTION_TYPE, CONNECTION_ETHERNET);
    }

    public void setConnectionType(String type) {
        preferences.edit().putString(KEY_CONNECTION_TYPE, type == null ? CONNECTION_ETHERNET : type).apply();
    }

    public boolean isUsbConnection() {
        return CONNECTION_USB.equalsIgnoreCase(getConnectionType());
    }

    public String getSelectedUsbDeviceName() {
        return preferences.getString(KEY_USB_DEVICE_NAME, "");
    }

    public void setSelectedUsbDeviceName(String name) {
        preferences.edit().putString(KEY_USB_DEVICE_NAME, name == null ? "" : name).apply();
    }

    public int getSelectedUsbVendorId() {
        return preferences.getInt(KEY_USB_VENDOR_ID, -1);
    }

    public void setSelectedUsbVendorId(int vendorId) {
        preferences.edit().putInt(KEY_USB_VENDOR_ID, vendorId).apply();
    }

    public int getSelectedUsbProductId() {
        return preferences.getInt(KEY_USB_PRODUCT_ID, -1);
    }

    public void setSelectedUsbProductId(int productId) {
        preferences.edit().putInt(KEY_USB_PRODUCT_ID, productId).apply();
    }

    public String getLastPrintStatus() {
        return preferences.getString(KEY_LAST_PRINT, "No print yet");
    }

    public void setLastPrintStatus(String status) {
        preferences.edit().putString(KEY_LAST_PRINT, status).apply();
    }

    public boolean isPrinterConfigured() {
        if (isUsbConnection()) {
            return !TextUtils.isEmpty(getSelectedUsbDeviceName()) || getSelectedUsbVendorId() > 0;
        }
        return !TextUtils.isEmpty(getPrinterIp()) && getPrinterPort() > 0;
    }

    public boolean hasValidBridgePort() {
        return getBridgePort() > 0 && getBridgePort() < 65536;
    }
}
