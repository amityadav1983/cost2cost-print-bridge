# Cost2Cost Windows Print Bridge

This standalone Java bridge provides the same local HTTP endpoints and Ethernet/Wi-Fi ESC/POS printing flow as the Android app. On Windows, it can also send RAW ESC/POS jobs to a USB printer installed as a Windows printer queue. It requires Java 17 or newer.

## Run on Windows

From the repository root:

```powershell
cd windows-bridge
New-Item -ItemType Directory -Force out | Out-Null
javac -d out src\main\java\com\cost2cost\printbridge\WindowsPrintBridge.java
java -cp out com.cost2cost.printbridge.WindowsPrintBridge --printer-ip 192.168.1.87 --printer-port 9100
```

For a USB printer installed in Windows (the detected queue on this computer is `POS80 Printer(7)`):

```powershell
java -cp out com.cost2cost.printbridge.WindowsPrintBridge --connection usb --printer-name "POS80 Printer(7)"
```

USB mode uses the Windows printer queue and sends the generated ESC/POS bytes as a RAW spooler job. Ensure the queue uses the printer's USB port and an ESC/POS-compatible driver. Windows queue printing is not identical to Android's direct USB-host bulk endpoint access; USB devices without an installed Windows printer queue are not supported.

Ethernet remains the default. By default, the bridge binds only to `127.0.0.1:8765`, uses the sample printer address `192.168.1.87:9100`, and waits up to 5 seconds for the printer. Set `--printer-ip` and `--printer-port` to match your printer. Optional arguments:

```text
--host 127.0.0.1       Bind address (keep loopback for local testing)
--bridge-port 8765     HTTP API port
--connection ethernet  Printer transport: ethernet (default) or usb
--printer-name NAME    Installed Windows printer queue (required for USB)
--spooler-script PATH  RAW Windows spooler helper path
--printer-ip ADDRESS   Network printer address
--printer-port 9100    Network printer raw TCP port
--timeout 5000         Printer connection timeout in milliseconds
--encoding UTF-8       Text encoding supported by the Java runtime
--token SECRET         Require Authorization: Bearer SECRET on /print
```

Non-loopback bindings (such as `0.0.0.0`) require `--token`; send that token on every print request. Keep the service on `127.0.0.1` for local testing.

## API

- `GET http://127.0.0.1:8765/status`
- `POST http://127.0.0.1:8765/print` with a JSON receipt body, for example:

```json
{
  "type": "receipt",
  "content": [
    { "type": "text", "text": "COST2COST", "align": "center", "bold": true, "size": 2 },
    { "type": "line" },
    { "type": "item", "name": "Product A", "qty": 2, "price": 100, "total": 200 },
    { "type": "feed", "lines": 4 },
    { "type": "cut" }
  ]
}
```

The bridge accepts the same `text`, `line`, `item`, `feed`, and `cut` content commands as the Android app. CORS allows `https://cost2cost.in` and HTTP origins on `localhost` or `127.0.0.1` (including development ports such as `http://localhost:8080`). Android's custom URL scheme is not available in this Windows version.
