package com.cost2cost.printbridge;

public interface PrinterTransport {
    boolean connect();
    boolean print(byte[] data);
    void disconnect();
    boolean isConnected();
    String getConnectionName();
}
