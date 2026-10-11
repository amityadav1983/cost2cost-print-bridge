package com.cost2cost.printbridge;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class UsbPrinterTransport implements PrinterTransport {
    private static final String TAG = "UsbPrinterTransport";
    private final Context context;
    private final PrinterSettings settings;
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint bulkOutEndpoint;
    private String lastError = "USB printer not connected";

    public UsbPrinterTransport(Context context, PrinterSettings settings) {
        this.context = context;
        this.settings = settings;
        if (context != null) {
            this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            this.usbDevice = resolveSelectedDevice();
        }
    }

    public static List<UsbPrinterDevice> listAvailableUsbPrinters(Context context) {
        List<UsbPrinterDevice> devices = new ArrayList<UsbPrinterDevice>();
        if (context == null) {
            return devices;
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return devices;
        }

        for (UsbDevice device : manager.getDeviceList().values()) {
            String name = device.getDeviceName();
            devices.add(new UsbPrinterDevice(getDeviceLabel(device), device.getVendorId(), device.getProductId(), name));
        }
        return devices;
    }

    private static String getDeviceLabel(UsbDevice device) {
        if (device == null) {
            return "USB Printer";
        }
        String manufacturer = null;
        String product = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                manufacturer = device.getManufacturerName();
                product = device.getProductName();
            } catch (Exception ignored) {
            }
        }
        if (product != null && !product.trim().isEmpty()) {
            return (manufacturer != null && !manufacturer.trim().isEmpty() ? manufacturer + " " : "") + product;
        }
        return "USB Device (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + ")";
    }

    public static boolean requestPermission(Context context, UsbDevice device) {
        if (context == null || device == null) {
            return false;
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return false;
        }
        if (manager.hasPermission(device)) {
            return true;
        }

        Intent permissionIntent = new Intent(UsbDeviceReceiver.ACTION_USB_PERMISSION);
        permissionIntent.setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, permissionIntent, flags);
        manager.requestPermission(device, pendingIntent);
        return false;
    }

    @Override
    public boolean connect() {
        if (usbConnection != null && bulkOutEndpoint != null) {
            return true;
        }
        if (context == null || usbManager == null) {
            lastError = "USB manager unavailable";
            return false;
        }
        usbDevice = resolveSelectedDevice();
        if (usbDevice == null) {
            lastError = "Connect an ESC/POS USB printer via USB OTG cable.";
            return false;
        }

        if (!usbManager.hasPermission(usbDevice)) {
            requestPermission(context, usbDevice);
            lastError = "USB permission requested. Please grant permission on prompt.";
            return false;
        }

        usbInterface = null;
        bulkOutEndpoint = null;

        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            UsbInterface interfaceItem = usbDevice.getInterface(i);
            if (interfaceItem == null) {
                continue;
            }
            boolean isPrinterClass = (interfaceItem.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER);
            for (int j = 0; j < interfaceItem.getEndpointCount(); j++) {
                UsbEndpoint endpoint = interfaceItem.getEndpoint(j);
                if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    if (isPrinterClass || usbInterface == null) {
                        usbInterface = interfaceItem;
                        bulkOutEndpoint = endpoint;
                    }
                    if (isPrinterClass) {
                        break;
                    }
                }
            }
            if (usbInterface != null && isPrinterClass) {
                break;
            }
        }

        if (bulkOutEndpoint == null || usbInterface == null) {
            lastError = "No USB bulk OUT endpoint found for this printer.";
            return false;
        }

        usbConnection = usbManager.openDevice(usbDevice);
        if (usbConnection == null) {
            lastError = "USB device could not be opened.";
            return false;
        }

        if (!usbConnection.claimInterface(usbInterface, true)) {
            lastError = "USB interface could not be claimed.";
            usbConnection.close();
            usbConnection = null;
            return false;
        }

        Log.d(TAG, "USB printer connected: " + usbDevice.getDeviceName() + " endpoint=" + bulkOutEndpoint.getAddress());
        lastError = "";
        return true;
    }

    @Override
    public boolean print(byte[] data) {
        if (!connect()) {
            return false;
        }
        if (bulkOutEndpoint == null || usbConnection == null) {
            lastError = "USB printer not ready.";
            return false;
        }

        int offset = 0;
        while (offset < data.length) {
            int chunkLength = Math.min(4096, data.length - offset);
            int bytesSent = usbConnection.bulkTransfer(bulkOutEndpoint, data, offset, chunkLength, 15000);
            if (bytesSent < 0) {
                lastError = "USB transfer failed: " + bytesSent;
                return false;
            }
            offset += bytesSent;
        }

        Log.d(TAG, "Sent " + data.length + " bytes to USB printer.");
        return true;
    }

    @Override
    public void disconnect() {
        try {
            if (usbConnection != null) {
                if (usbInterface != null) {
                    usbConnection.releaseInterface(usbInterface);
                }
                usbConnection.close();
            }
        } catch (Exception e) {
            Log.d(TAG, "USB disconnect error", e);
        } finally {
            usbConnection = null;
            usbInterface = null;
            bulkOutEndpoint = null;
        }
    }

    @Override
    public boolean isConnected() {
        return usbConnection != null;
    }

    @Override
    public String getConnectionName() {
        return "USB";
    }

    public String getLastError() {
        return lastError;
    }

    private UsbDevice resolveSelectedDevice() {
        if (context == null || settings == null) {
            return null;
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return null;
        }
        if (settings.getSelectedUsbVendorId() > 0 && settings.getSelectedUsbProductId() > 0) {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getVendorId() == settings.getSelectedUsbVendorId() && device.getProductId() == settings.getSelectedUsbProductId()) {
                    return device;
                }
            }
        }
        if (settings.getSelectedUsbDeviceName() != null && !settings.getSelectedUsbDeviceName().trim().isEmpty()) {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (settings.getSelectedUsbDeviceName().equals(device.getDeviceName())) {
                    return device;
                }
            }
        }
        return manager.getDeviceList().isEmpty() ? null : manager.getDeviceList().values().iterator().next();
    }
}
