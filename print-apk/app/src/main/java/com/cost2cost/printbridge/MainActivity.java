package com.cost2cost.printbridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, AdapterView.OnItemSelectedListener {
    private Spinner connectionTypeSpinner;
    private EditText printerNameInput;
    private EditText printerIpInput;
    private EditText printerPortInput;
    private Spinner paperWidthSpinner;
    private Spinner encodingSpinner;
    private EditText timeoutInput;
    private EditText bridgePortInput;
    private EditText apiTokenInput;
    private CheckBox allowLanCheckbox;
    private LinearLayout ethernetSection;
    private LinearLayout usbSection;
    private Spinner usbPrinterSpinner;
    private TextView usbStatusText;
    private TextView usbPrinterInfoText;
    private TextView printerStatusText;
    private TextView serverStatusText;
    private TextView lastPrintText;
    private TextView logTextView;

    private PrinterSettings settings;
    private UsbDeviceReceiver usbDeviceReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = PrinterSettings.get(this);
        bindViews();
        initializeSpinners();
        registerUsbReceiver();
        loadSettings();
        refreshUsbPrinterList();
        updateConnectionTypeVisibility();
        PrintBridgeService.startService(this);
        updateStatusPanel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbDeviceReceiver);
        } catch (Exception ignored) {
        }
    }

    private void bindViews() {
        connectionTypeSpinner = findViewById(R.id.connectionTypeSpinner);
        printerNameInput = findViewById(R.id.printerNameInput);
        printerIpInput = findViewById(R.id.printerIpInput);
        printerPortInput = findViewById(R.id.printerPortInput);
        paperWidthSpinner = findViewById(R.id.paperWidthSpinner);
        encodingSpinner = findViewById(R.id.encodingSpinner);
        timeoutInput = findViewById(R.id.timeoutInput);
        bridgePortInput = findViewById(R.id.bridgePortInput);
        apiTokenInput = findViewById(R.id.apiTokenInput);
        allowLanCheckbox = findViewById(R.id.allowLanCheckbox);
        ethernetSection = findViewById(R.id.ethernetSection);
        usbSection = findViewById(R.id.usbSection);
        usbPrinterSpinner = findViewById(R.id.usbPrinterSpinner);
        usbStatusText = findViewById(R.id.usbStatusText);
        usbPrinterInfoText = findViewById(R.id.usbPrinterInfoText);
        printerStatusText = findViewById(R.id.printerStatusText);
        serverStatusText = findViewById(R.id.serverStatusText);
        lastPrintText = findViewById(R.id.lastPrintText);
        logTextView = findViewById(R.id.logTextView);

        findViewById(R.id.saveSettingsButton).setOnClickListener(this);
        findViewById(R.id.testPrintButton).setOnClickListener(this);
        findViewById(R.id.startServerButton).setOnClickListener(this);
        findViewById(R.id.stopServerButton).setOnClickListener(this);
        findViewById(R.id.clearLogButton).setOnClickListener(this);
        findViewById(R.id.refreshUsbButton).setOnClickListener(this);
        findViewById(R.id.usbTestPrintButton).setOnClickListener(this);

        connectionTypeSpinner.setOnItemSelectedListener(this);
        usbPrinterSpinner.setOnItemSelectedListener(this);
    }

    private void initializeSpinners() {
        ArrayAdapter<String> connectionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                Arrays.asList("Ethernet / Wi‑Fi", "USB"));
        connectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        connectionTypeSpinner.setAdapter(connectionAdapter);

        ArrayAdapter<String> paperAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                Arrays.asList("80mm", "58mm", "76mm"));
        paperAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        paperWidthSpinner.setAdapter(paperAdapter);

        ArrayAdapter<String> encodingAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                Arrays.asList("UTF-8", "ASCII", "ISO-8859-1"));
        encodingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        encodingSpinner.setAdapter(encodingAdapter);
    }

    private void registerUsbReceiver() {
        usbDeviceReceiver = new UsbDeviceReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbDeviceReceiver.ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDeviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbDeviceReceiver, filter);
        }
    }

    private void loadSettings() {
        printerNameInput.setText(settings.getPrinterName());
        printerIpInput.setText(settings.getPrinterIp());
        printerPortInput.setText(String.valueOf(settings.getPrinterPort()));
        timeoutInput.setText(String.valueOf(settings.getTimeoutMs()));
        bridgePortInput.setText(String.valueOf(settings.getBridgePort()));
        apiTokenInput.setText(settings.getApiToken());
        allowLanCheckbox.setChecked(settings.getAllowLan());

        String connectionType = settings.getConnectionType();
        if (PrinterSettings.CONNECTION_USB.equalsIgnoreCase(connectionType)) {
            connectionTypeSpinner.setSelection(1);
        } else {
            connectionTypeSpinner.setSelection(0);
        }

        String paperWidth = settings.getPaperWidth();
        for (int i = 0; i < paperWidthSpinner.getCount(); i++) {
            if (paperWidthSpinner.getItemAtPosition(i).toString().equals(paperWidth)) {
                paperWidthSpinner.setSelection(i);
                break;
            }
        }

        String encoding = settings.getEncoding();
        for (int i = 0; i < encodingSpinner.getCount(); i++) {
            if (encodingSpinner.getItemAtPosition(i).toString().equals(encoding)) {
                encodingSpinner.setSelection(i);
                break;
            }
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.saveSettingsButton) {
            saveSettings();
        } else if (id == R.id.testPrintButton) {
            runTestPrint();
        } else if (id == R.id.startServerButton) {
            startBridgeServer();
        } else if (id == R.id.stopServerButton) {
            stopBridgeServer();
        } else if (id == R.id.clearLogButton) {
            logTextView.setText("Bridge ready.");
        } else if (id == R.id.refreshUsbButton) {
            refreshUsbPrinterList();
        } else if (id == R.id.usbTestPrintButton) {
            runUsbTestPrint();
        }
    }

    private void saveSettings() {
        if (!saveSettingsFromUi()) {
            return;
        }
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        appendLog("Settings saved for " + settings.getConnectionType() + " connection.");
        updateConnectionTypeVisibility();
        updateStatusPanel();
    }

    private boolean saveSettingsFromUi() {
        String printerName = printerNameInput.getText().toString().trim();
        String printerIp = printerIpInput.getText().toString().trim();
        String printerPortText = printerPortInput.getText().toString().trim();
        String timeoutText = timeoutInput.getText().toString().trim();
        String bridgePortText = bridgePortInput.getText().toString().trim();
        String apiToken = apiTokenInput.getText().toString().trim();

        if (TextUtils.isEmpty(printerName)) {
            Toast.makeText(this, "Printer name is required", Toast.LENGTH_SHORT).show();
            return false;
        }
        boolean isUsb = connectionTypeSpinner.getSelectedItemPosition() == 1;
        if (!isUsb && TextUtils.isEmpty(printerIp)) {
            Toast.makeText(this, "Printer IP is required", Toast.LENGTH_SHORT).show();
            return false;
        }

        int printerPort = parseInt(printerPortText, 9100);
        int bridgePort = parseInt(bridgePortText, 8765);
        int timeoutMs = parseInt(timeoutText, 5000);
        String connectionType = isUsb ? PrinterSettings.CONNECTION_USB : PrinterSettings.CONNECTION_ETHERNET;

        settings.save(
                printerName,
                printerIp,
                printerPort,
                paperWidthSpinner.getSelectedItem().toString(),
                encodingSpinner.getSelectedItem().toString(),
                timeoutMs,
                bridgePort,
                allowLanCheckbox.isChecked(),
                apiToken,
                settings.isAutoStartOnBootEnabled(),
                connectionType
        );

        if (settings.isUsbConnection()) {
            UsbPrinterDevice selected = getSelectedUsbDevice();
            if (selected != null) {
                settings.setSelectedUsbDeviceName(selected.getDeviceName());
                settings.setSelectedUsbVendorId(selected.getVendorId());
                settings.setSelectedUsbProductId(selected.getProductId());
            }
        }
        return true;
    }

    private void startBridgeServer() {
        PrintBridgeService.startService(this);
        updateStatusPanel();
        appendLog("Print bridge server start requested.");
    }

    private void stopBridgeServer() {
        PrintBridgeService.stopService(this);
        updateStatusPanel();
        appendLog("Print bridge server stopped.");
    }

    private void runTestPrint() {
        if (!saveSettingsFromUi()) {
            return;
        }

        if (settings.isUsbConnection()) {
            runUsbTestPrint();
            return;
        }

        if (!settings.isPrinterConfigured()) {
            Toast.makeText(this, "Configure printer IP and port before testing", Toast.LENGTH_SHORT).show();
            return;
        }

        appendLog("Sending Ethernet test print to " + settings.getPrinterIp() + ":" + settings.getPrinterPort() + "...");
        new Thread(() -> {
            PrinterConnection.PrintResult result = PrinterConnection.printTest(MainActivity.this, settings);
            runOnUiThread(() -> {
                if (result.success) {
                    Toast.makeText(MainActivity.this, "PRINT SUCCESS", Toast.LENGTH_SHORT).show();
                    appendLog("PRINT SUCCESS: " + result.message);
                } else {
                    Toast.makeText(MainActivity.this, "PRINT FAILED: " + result.error, Toast.LENGTH_LONG).show();
                    appendLog("PRINT FAILED: " + result.error);
                }
                updateStatusPanel();
            });
        }).start();
    }

    private void runUsbTestPrint() {
        if (!settings.isUsbConnection()) {
            Toast.makeText(this, "Switch to USB connection type before testing USB print.", Toast.LENGTH_SHORT).show();
            return;
        }

        UsbPrinterDevice device = getSelectedUsbDevice();
        if (device == null) {
            Toast.makeText(this, "Connect an ESC/POS USB printer first.", Toast.LENGTH_SHORT).show();
            return;
        }

        settings.setSelectedUsbDeviceName(device.getDeviceName());
        settings.setSelectedUsbVendorId(device.getVendorId());
        settings.setSelectedUsbProductId(device.getProductId());

        appendLog("Sending USB test print to " + device.getName() + "...");
        new Thread(() -> {
            PrinterConnection.PrintResult result = PrinterConnection.printTest(MainActivity.this, settings);
            runOnUiThread(() -> {
                if (result.success) {
                    Toast.makeText(MainActivity.this, "USB PRINT SUCCESS", Toast.LENGTH_SHORT).show();
                    appendLog("USB PRINT SUCCESS: " + result.message);
                } else {
                    Toast.makeText(MainActivity.this, "USB PRINT FAILED: " + result.error, Toast.LENGTH_LONG).show();
                    appendLog("USB PRINT FAILED: " + result.error);
                }
                updateStatusPanel();
            });
        }).start();
    }

    private void refreshUsbPrinterList() {
        List<UsbPrinterDevice> devices = UsbPrinterTransport.listAvailableUsbPrinters(this);
        List<String> labels = new ArrayList<String>();
        if (devices.isEmpty()) {
            labels.add("No USB printer detected");
            usbStatusText.setText("USB Printer: Not connected");
            usbPrinterInfoText.setText("Connect an ESC/POS USB printer.");
            settings.setSelectedUsbDeviceName("");
            settings.setSelectedUsbVendorId(-1);
            settings.setSelectedUsbProductId(-1);
        } else {
            for (UsbPrinterDevice device : devices) {
                labels.add(device.getName() + " | VID: " + device.getVendorId() + " PID: " + device.getProductId());
            }
            usbStatusText.setText("USB Printer: Connected");
            usbPrinterInfoText.setText("USB printer detected. Allow Cost2Cost Print Bridge to access this printer.");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        usbPrinterSpinner.setAdapter(adapter);

        if (!devices.isEmpty()) {
            for (int i = 0; i < devices.size(); i++) {
                UsbPrinterDevice device = devices.get(i);
                if (selectedMatches(device)) {
                    usbPrinterSpinner.setSelection(i);
                    break;
                }
            }
        }
    }

    private boolean selectedMatches(UsbPrinterDevice device) {
        return device != null && settings.getSelectedUsbVendorId() == device.getVendorId() && settings.getSelectedUsbProductId() == device.getProductId();
    }

    private UsbPrinterDevice getSelectedUsbDevice() {
        List<UsbPrinterDevice> devices = UsbPrinterTransport.listAvailableUsbPrinters(this);
        if (devices.isEmpty()) {
            return null;
        }
        int index = usbPrinterSpinner.getSelectedItemPosition();
        if (index >= 0 && index < devices.size()) {
            return devices.get(index);
        }
        return devices.get(0);
    }

    private void updateConnectionTypeVisibility() {
        boolean usbSelected = settings.isUsbConnection();
        ethernetSection.setVisibility(usbSelected ? View.GONE : View.VISIBLE);
        usbSection.setVisibility(usbSelected ? View.VISIBLE : View.GONE);
        if (usbSelected) {
            refreshUsbPrinterList();
        }
    }

    private void updateStatusPanel() {
        if (settings.isUsbConnection()) {
            UsbPrinterDevice device = getSelectedUsbDevice();
            if (device != null) {
                printerStatusText.setText("USB Printer: " + device.getName());
                usbPrinterInfoText.setText("VID: " + device.getVendorId() + " PID: " + device.getProductId());
            } else {
                printerStatusText.setText("USB Printer: Not connected");
                usbPrinterInfoText.setText("Connect an ESC/POS USB printer.");
            }
        } else {
            String printerState = PrinterConnection.isReachable(settings) ? "Connected" : "Disconnected";
            printerStatusText.setText("Printer: " + printerState);
        }

        serverStatusText.setText("Print Server: " + (PrintBridgeService.isRunning(this) ? "Running" : "Stopped"));
        lastPrintText.setText("Last Print: " + settings.getLastPrintStatus());
    }

    private void appendLog(String message) {
        String existing = logTextView.getText().toString();
        String next = existing + "\n" + message;
        logTextView.setText(next);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == R.id.connectionTypeSpinner) {
            settings.setConnectionType(position == 1 ? PrinterSettings.CONNECTION_USB : PrinterSettings.CONNECTION_ETHERNET);
            updateConnectionTypeVisibility();
            updateStatusPanel();
        } else if (parent.getId() == R.id.usbPrinterSpinner) {
            List<UsbPrinterDevice> devices = UsbPrinterTransport.listAvailableUsbPrinters(this);
            if (position >= 0 && position < devices.size()) {
                UsbPrinterDevice selected = devices.get(position);
                settings.setSelectedUsbDeviceName(selected.getDeviceName());
                settings.setSelectedUsbVendorId(selected.getVendorId());
                settings.setSelectedUsbProductId(selected.getProductId());
                usbStatusText.setText("USB Printer: Connected");
                usbPrinterInfoText.setText("VID: " + selected.getVendorId() + " PID: " + selected.getProductId());
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }
}
