package com.cost2cost.printbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URLDecoder;

public class BridgeIntentReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }

        Uri uri = intent.getData();
        String payload = uri.getQueryParameter("data");
        if (payload == null) {
            payload = uri.getQueryParameter("json");
        }

        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        final String rawPayload = payload;
        final PrinterSettings settings = PrinterSettings.get(getApplicationContext());

        new Thread(() -> {
            try {
                JSONObject json;
                try {
                    json = new JSONObject(rawPayload);
                } catch (Exception e) {
                    String decoded = URLDecoder.decode(rawPayload, "UTF-8");
                    json = new JSONObject(decoded);
                }
                PrinterConnection.PrintResult result = PrinterConnection.printJsonReceipt(getApplicationContext(), settings, json);
                String msg = result.success ? "PRINT SUCCESS" : "PRINT FAILED: " + result.error;
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Invalid print request payload", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
