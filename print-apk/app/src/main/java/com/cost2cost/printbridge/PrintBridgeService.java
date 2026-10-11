package com.cost2cost.printbridge;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

public class PrintBridgeService extends Service {
    private static final String TAG = "PrintBridgeService";
    private BridgeHttpServer server;

    public static void startService(Context context) {
        Intent intent = new Intent(context, PrintBridgeService.class);
        context.startService(intent);
    }

    public static void stopService(Context context) {
        context.stopService(new Intent(context, PrintBridgeService.class));
    }

    public static boolean isRunning(Context context) {
        return PrinterSettings.get(context).isServerRunning();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PrinterSettings.get(this).setServerRunning(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startBridgeServer();
        return START_STICKY;
    }

    private void startBridgeServer() {
        if (server != null && server.isAlive()) {
            return;
        }

        try {
            PrinterSettings settings = PrinterSettings.get(this);
            String host = settings.getAllowLan() ? "0.0.0.0" : "127.0.0.1";
            server = new BridgeHttpServer(host, settings.getBridgePort(), settings, getApplicationContext());
            server.start();
            settings.setServerRunning(true);
            Log.d(TAG, "Print bridge server started on " + host + ":" + settings.getBridgePort());
        } catch (IOException e) {
            Log.e(TAG, "Failed to start print bridge server", e);
            PrinterSettings.get(this).setServerRunning(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopBridgeServer();
    }

    private void stopBridgeServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        PrinterSettings.get(this).setServerRunning(false);
        Log.d(TAG, "Print bridge server stopped");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
