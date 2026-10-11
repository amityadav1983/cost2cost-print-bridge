package com.cost2cost.printbridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class EthernetPrinterTransport implements PrinterTransport {
    private final String host;
    private final int port;
    private final int timeoutMs;
    private Socket socket;

    public EthernetPrinterTransport(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public boolean connect() {
        try {
            socket = new Socket();
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket.isConnected();
        } catch (IOException e) {
            socket = null;
            return false;
        }
    }

    @Override
    public boolean print(byte[] data) {
        if (!connect()) {
            return false;
        }
        try {
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            disconnect();
        }
    }

    @Override
    public void disconnect() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        } finally {
            socket = null;
        }
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public String getConnectionName() {
        return "Ethernet / Wi‑Fi";
    }
}
