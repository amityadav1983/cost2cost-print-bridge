param(
    [Parameter(Mandatory = $true)]
    [string] $PrinterName,

    [switch] $CheckOnly
)

$ErrorActionPreference = 'Stop'

try {
    $printer = Get-Printer -Name $PrinterName
} catch {
    [Console]::Error.WriteLine("Windows printer queue '$PrinterName' was not found: $($_.Exception.Message)")
    exit 2
}

if ($CheckOnly) {
    exit 0
}

$encoded = [Console]::In.ReadLine()
if ([string]::IsNullOrWhiteSpace($encoded)) {
    [Console]::Error.WriteLine('No ESC/POS data was provided.')
    exit 2
}

$payload = [Convert]::FromBase64String($encoded)
if ($payload.Length -eq 0) {
    [Console]::Error.WriteLine('The ESC/POS print job is empty.')
    exit 2
}

$source = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class Cost2CostRawPrinter {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DOC_INFO_1 {
        [MarshalAs(UnmanagedType.LPWStr)] public string pDocName;
        [MarshalAs(UnmanagedType.LPWStr)] public string pOutputFile;
        [MarshalAs(UnmanagedType.LPWStr)] public string pDataType;
    }

    [DllImport("winspool.drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool OpenPrinter(string printerName, out IntPtr printer, IntPtr defaults);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool ClosePrinter(IntPtr printer);

    [DllImport("winspool.drv", EntryPoint = "StartDocPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int StartDocPrinter(IntPtr printer, int level, ref DOC_INFO_1 documentInfo);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool EndDocPrinter(IntPtr printer);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool StartPagePrinter(IntPtr printer);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool EndPagePrinter(IntPtr printer);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool WritePrinter(IntPtr printer, byte[] data, int count, out int written);

    private static Exception LastError(string operation) {
        return new Win32Exception(Marshal.GetLastWin32Error(), operation);
    }

    public static void Write(string printerName, byte[] data) {
        IntPtr printer;
        if (!OpenPrinter(printerName, out printer, IntPtr.Zero)) {
            throw LastError("Could not open printer queue '" + printerName + "'");
        }

        int document = 0;
        bool page = false;
        try {
            DOC_INFO_1 info = new DOC_INFO_1 {
                pDocName = "Cost2Cost ESC/POS Receipt",
                pOutputFile = null,
                pDataType = "RAW"
            };
            document = StartDocPrinter(printer, 1, ref info);
            if (document == 0) {
                throw LastError("Could not start a RAW print job");
            }
            if (!StartPagePrinter(printer)) {
                throw LastError("Could not start the printer page");
            }
            page = true;

            int written;
            if (!WritePrinter(printer, data, data.Length, out written)) {
                throw LastError("Could not write ESC/POS data to the printer");
            }
            if (written != data.Length) {
                throw new InvalidOperationException("Printer accepted only " + written + " of " + data.Length + " bytes");
            }
        } finally {
            if (page) EndPagePrinter(printer);
            if (document != 0) EndDocPrinter(printer);
            ClosePrinter(printer);
        }
    }
}
'@

try {
    Add-Type -TypeDefinition $source
    [Cost2CostRawPrinter]::Write($printer.Name, $payload)
} catch {
    [Console]::Error.WriteLine("RAW print to '$PrinterName' failed: $($_.Exception.Message)")
    exit 1
}
