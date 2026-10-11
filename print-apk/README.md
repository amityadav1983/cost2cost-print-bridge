# Cost2Cost Print Bridge

A lightweight Android print bridge that receives JSON receipt print jobs from Cost2Cost and sends raw ESC/POS commands over TCP/IP to a configured thermal printer.

## Features

- Android 4.4.4+ compatible (minSdkVersion 19)
- Local embedded HTTP API on the device
- Printer configuration stored locally in `SharedPreferences`
- Real ESC/POS engine for thermal printers
- Support for receipt text, items, lines, feed, and cut commands
- CORS handling for Cost2Cost and local browser development
- Optional LAN access with HTTP bearer authentication
- Custom URL scheme fallback for `https://cost2cost.in` to native app communication
- Background service support with optional boot startup

## Project structure

- `app/src/main/java/com/cost2cost/printbridge/` – Android application code
- `app/src/main/res/` – UI and configuration resources
- `cost2cost-print-bridge.js` – browser/client integration helper

## Android Studio setup

1. Open the project in Android Studio.
2. Allow Gradle sync to download required dependencies.
3. Choose a device or emulator running Android 4.4+.
4. Build the project and deploy the APK.

## Build instructions

### Debug build

```bash
gradlew assembleDebug
```

### Release build

```bash
gradlew assembleRelease
```

> If Android Studio is already managing Gradle, use the built-in Gradle tasks instead of the command line.

## Installation

1. Install the APK on the Android device.
2. Open the app.
3. Configure printer IP, port, paper width, encoding, timeout, and bridge port.
4. Save settings.
5. Use `START SERVER` to begin the local HTTP bridge.

## Printer configuration

Default values are intentionally examples only:

- Printer name: `Rugtek`
- Printer IP: `192.168.1.87`
- Printer port: `9100`
- Paper width: `80mm`
- Encoding: `UTF-8`
- Timeout: `5000 ms`

The same APK works for multiple shops and networks because the values are read from saved device settings instead of source code.

## Local API

### Start the bridge

The app runs a local HTTP server on `127.0.0.1:<bridge-port>` by default.

### POST print

```http
POST http://127.0.0.1:8765/print
Content-Type: application/json
```

Example body:

```json
{
  "type": "receipt",
  "content": [
    { "type": "text", "text": "COST2COST", "align": "center", "bold": true, "size": 2 },
    { "type": "text", "text": "Your Local Marketplace", "align": "center" },
    { "type": "line" },
    { "type": "item", "name": "Product A", "qty": 2, "price": 100, "total": 200 },
    { "type": "feed", "lines": 4 },
    { "type": "cut" }
  ]
}
```

### GET status

```http
GET http://127.0.0.1:8765/status
```

Example response:

```json
{
  "bridgeRunning": true,
  "printerConfigured": true,
  "printerIp": "192.168.1.87",
  "printerPort": 9100,
  "printerReachable": false
}
```

## CORS support

The bridge allows the following origins:

- `https://cost2cost.in`
- `http://localhost`
- `http://127.0.0.1`

The API also responses to `OPTIONS`, `POST`, and `GET` requests.

## Security

- Local HTTP server binds to `127.0.0.1` by default.
- LAN access is disabled unless the user explicitly enables `Allow LAN printing API`.
- When LAN access is enabled, the app requires `Authorization: Bearer <token>`.

## Custom URL fallback

If HTTP access from `https://cost2cost.in` is restricted by browser security policies, the app registers this custom scheme:

```text
cost2costprint://print?data=<base64-or-url-encoded-json>
```

This allows the Cost2Cost website to hand off a print job to the native app.

## Troubleshooting

### Printer not reachable

- Check the IP address, port, and network connectivity.
- Confirm the printer is powered on and on the same LAN.
- Ensure the configured TCP port matches the printer’s ESC/POS port.

### App cannot connect to localhost from the browser

- Use the custom scheme fallback or keep the request on the same device.
- Confirm the bridge server is started.
- Use `http://127.0.0.1:<bridge-port>/status` to confirm it is running.

### Print request fails with 401

- Turn on LAN access only when needed.
- Provide a valid bearer token in `Authorization`.

## JavaScript integration

```javascript
const printer = new Cost2CostPrintBridge({ bridgePort: 8765 });

async function printReceipt(receipt) {
  return await printer.print(receipt);
}
```

See `cost2cost-print-bridge.js` for the helper library.

## Production notes

This implementation is designed to be light, configurable, and reusable across multiple cost2cost shops and device networks without any business-specific hard-coded printer IP or router values.
