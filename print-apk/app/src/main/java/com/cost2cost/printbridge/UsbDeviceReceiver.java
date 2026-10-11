package com.cost2cost.printbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class UsbDeviceReceiver extends BroadcastReceiver {
    public static final String ACTION_USB_PERMISSION = "com.cost2cost.printbridge.USB_PERMISSION";
    private static final String TAG = "UsbDeviceReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Log.d(TAG, "USB device attached: " + device.getDeviceName() + " VID=" + device.getVendorId() + " PID=" + device.getProductId());
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Log.d(TAG, "USB device detached: " + device.getDeviceName());
            }
            PrinterSettings settings = PrinterSettings.get(context);
            if (device != null && settings.getSelectedUsbVendorId() == device.getVendorId() && settings.getSelectedUsbProductId() == device.getProductId()) {
                settings.setSelectedUsbDeviceName("");
                settings.setSelectedUsbVendorId(-1);
                settings.setSelectedUsbProductId(-1);
            }
        } else if (ACTION_USB_PERMISSION.equals(action)) {
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Log.d(TAG, "USB permission " + (granted ? "granted" : "denied") + " for " + device.getDeviceName());
            }
            if (!granted) {
                PrinterSettings.get(context).setSelectedUsbDeviceName("");
            }
        }
    }
}
