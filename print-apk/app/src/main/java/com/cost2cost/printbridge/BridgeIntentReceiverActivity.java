package com.cost2cost.printbridge;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.net.URLDecoder;

public class BridgeIntentReceiverActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            Toast.makeText(this, "No data received", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = intent.getData();
        String payload = uri.getQueryParameter("data");
        if (payload == null) {
            payload = uri.getQueryParameter("json");
        }

        if (payload == null || payload.trim().isEmpty()) {
            Toast.makeText(this, "Empty payload received", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            String decoded = URLDecoder.decode(payload, "UTF-8");
            JSONObject json = new JSONObject(decoded);
            PrinterSettings settings = PrinterSettings.get(this);
            PrinterConnection.PrintResult result = PrinterConnection.printJsonReceipt(this, settings, json);
            Toast.makeText(this, result.success ? "PRINT SUCCESS" : "PRINT FAILED: " + result.error,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Invalid print request payload", Toast.LENGTH_LONG).show();
        }
    }
}
