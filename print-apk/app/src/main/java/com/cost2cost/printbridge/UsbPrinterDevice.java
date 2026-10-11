package com.cost2cost.printbridge;

public class UsbPrinterDevice {
    private final String name;
    private final int vendorId;
    private final int productId;
    private final String deviceName;

    public UsbPrinterDevice(String name, int vendorId, int productId, String deviceName) {
        this.name = name;
        this.vendorId = vendorId;
        this.productId = productId;
        this.deviceName = deviceName;
    }

    public String getName() {
        return name;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public String getDeviceName() {
        return deviceName;
    }
}
