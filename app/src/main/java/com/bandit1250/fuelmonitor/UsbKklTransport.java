package com.bandit1250.fuelmonitor;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Raw USB-serial helper for dumb K-line / KKL interfaces.
 *
 * Two intentionally separate operations live here:
 *
 * 1) capabilityTest()
 *    Opens the USB serial port and asks its driver to configure 10,400 and
 *    57,600 baud. No payload byte is transmitted to the vehicle. RTS/DTR
 *    availability is only QUERIED, not toggled.
 *
 * 2) readDensoImage()
 *    Experimental read-only implementation of the M32R-era Denso serial
 *    monitor used by the open-source Suzuki ECUeditor family. This DOES reset
 *    / enter the monitor and send its unlock/read protocol, but it contains no
 *    erase or programming command. Because a wrong ECU/protocol assumption can
 *    still have side effects, the UI puts this behind an explicit warning.
 *
 * A generic KKL cable exposing 57,600 baud is only a transport candidate.
 * The cable/harness still has to route the K-line correctly and, for automated
 * monitor entry, the appropriate reset/programming-mode wiring must exist.
 */
public final class UsbKklTransport {
    private UsbKklTransport() {}

    public interface Progress {
        void onProgress(int pagesDone, int totalPages, long bytesDone);
    }

    public static final class DeviceInfo {
        public final int deviceId;
        public final int vendorId;
        public final int productId;
        public final String driver;
        public final String label;
        public final boolean permission;

        DeviceInfo(
                int deviceId,
                int vendorId,
                int productId,
                String driver,
                String label,
                boolean permission
        ) {
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
            this.driver = driver;
            this.label = label;
            this.permission = permission;
        }
    }

    public static final class Capability {
        public final DeviceInfo device;
        public final boolean baud10400;
        public final boolean baud57600;
        public final boolean rtsSupported;
        public final boolean dtrSupported;
        public final String report;

        Capability(
                DeviceInfo device,
                boolean baud10400,
                boolean baud57600,
                boolean rtsSupported,
                boolean dtrSupported,
                String report
        ) {
            this.device = device;
            this.baud10400 = baud10400;
            this.baud57600 = baud57600;
            this.rtsSupported = rtsSupported;
            this.dtrSupported = dtrSupported;
            this.report = report;
        }

        public boolean readCandidate() {
            return baud57600 && rtsSupported;
        }
    }

    public static final class ReadResult {
        public final File file;
        public final long bytes;
        public final String sha256;
        public final String ecuId;

        ReadResult(File file, long bytes, String sha256, String ecuId) {
            this.file = file;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.ecuId = ecuId;
        }
    }

    public static List<DeviceInfo> list(Context context) {
        UsbManager manager =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);

        ArrayList<DeviceInfo> out = new ArrayList<>();
        if (manager == null) return out;

        List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(manager);

        for (UsbSerialDriver driver : drivers) {
            UsbDevice d = driver.getDevice();

            String product = safeProduct(d);
            String maker = safeManufacturer(d);
            String name = "";

            if (!maker.isEmpty()) name += maker + " ";
            if (!product.isEmpty()) name += product;

            if (name.trim().isEmpty()) {
                name = driver.getClass().getSimpleName();
            }

            String label = name.trim() + "  •  " +
                    String.format(
                            Locale.US,
                            "VID %04X / PID %04X",
                            d.getVendorId(),
                            d.getProductId()
                    ) + "\n" +
                    driver.getClass().getSimpleName();

            out.add(new DeviceInfo(
                    d.getDeviceId(),
                    d.getVendorId(),
                    d.getProductId(),
                    driver.getClass().getSimpleName(),
                    label,
                    manager.hasPermission(d)
            ));
        }

        return out;
    }

    public static UsbDevice getUsbDevice(Context context, int deviceId) {
        UsbSerialDriver d = findDriver(context, deviceId);
        return d == null ? null : d.getDevice();
    }

    public static Capability capabilityTest(
            Context context,
            int deviceId
    ) throws Exception {
        UsbManager manager = requireManager(context);
        UsbSerialDriver driver = requireDriver(context, deviceId);
        UsbDevice device = driver.getDevice();

        if (!manager.hasPermission(device)) {
            throw new SecurityException("USB permission has not been granted.");
        }

        DeviceInfo info = infoFor(manager, driver);

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Android could not open the selected USB device.");
        }

        UsbSerialPort port = firstPort(driver);
        boolean rate10400 = false;
        boolean rate57600 = false;
        boolean rts = false;
        boolean dtr = false;
        String rate10400Error = "";
        String rate57600Error = "";
        String controlError = "";

        try {
            port.open(connection);

            // No serial payload is written during this test.
            try {
                port.setParameters(
                        10400,
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                );
                rate10400 = true;
            } catch (Exception e) {
                rate10400Error = oneLine(e.getMessage());
            }

            try {
                port.setParameters(
                        57600,
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                );
                rate57600 = true;
            } catch (Exception e) {
                rate57600Error = oneLine(e.getMessage());
            }

            // Query capabilities only. Do not change RTS/DTR in the safe test:
            // those lines can be wired to ECU reset/programming circuitry.
            try {
                EnumSet<UsbSerialPort.ControlLine> controls =
                        port.getSupportedControlLines();
                rts = controls.contains(UsbSerialPort.ControlLine.RTS);
                dtr = controls.contains(UsbSerialPort.ControlLine.DTR);
            } catch (Exception e) {
                controlError = oneLine(e.getMessage());
            }

            // Leave the UART in the normal Bandit diagnostic rate before close.
            if (rate10400) {
                try {
                    port.setParameters(
                            10400,
                            UsbSerialPort.DATABITS_8,
                            UsbSerialPort.STOPBITS_1,
                            UsbSerialPort.PARITY_NONE
                    );
                } catch (Exception ignored) {}
            }

        } finally {
            try { port.close(); } catch (Exception ignored) {}
            connection.close();
        }

        StringBuilder report = new StringBuilder();
        report.append("USB KKL / RAW SERIAL CAPABILITY\n");
        report.append(info.label).append("\n");
        report.append("Android USB permission: YES\n\n");

        report.append("10,400 baud 8-N-1: ")
                .append(rate10400 ? "PASS" : "FAIL")
                .append(rate10400Error.isEmpty() ? "" : " • " + rate10400Error)
                .append("\n");

        report.append("57,600 baud 8-N-1: ")
                .append(rate57600 ? "PASS" : "FAIL")
                .append(rate57600Error.isEmpty() ? "" : " • " + rate57600Error)
                .append("\n");

        report.append("RTS control exposed: ")
                .append(rts ? "YES" : "NO")
                .append("\n");

        report.append("DTR control exposed: ")
                .append(dtr ? "YES" : "NO")
                .append("\n");

        if (!controlError.isEmpty()) {
            report.append("Control-line query: ").append(controlError).append("\n");
        }

        report.append("\nNo byte was transmitted on the serial/K-line during this test.\n\n");

        if (rate57600 && rts) {
            report.append(
                    "VERDICT: CANDIDATE for the ECUeditor-style read transport. " +
                    "57.6k and RTS are available at the USB/UART layer. This still " +
                    "does NOT prove that the KKL cable/harness routes RTS/reset or " +
                    "the ECU programming-mode signal correctly."
            );
        } else if (rate57600) {
            report.append(
                    "VERDICT: 57.6k raw serial works, but automated ECUeditor-style " +
                    "monitor entry is NOT proven because RTS control is unavailable."
            );
        } else {
            report.append(
                    "VERDICT: unsuitable for the known 57.6k ECUeditor-style transport."
            );
        }

        return new Capability(
                info,
                rate10400,
                rate57600,
                rts,
                dtr,
                report.toString()
        );
    }

    /**
     * Experimental 1 MiB read of the M32R-era Denso serial monitor.
     *
     * No erase or flash-write opcode is present here. The operation still:
     * - toggles RTS to reset/enter the monitor;
     * - sends monitor synchronisation and unlock bytes;
     * - sends checksum/read-page requests.
     *
     * If the ECU is not the expected protocol family, stop on the first
     * unexpected response rather than guessing further.
     */
    public static ReadResult readDensoImage(
            Context context,
            int deviceId,
            Progress progress
    ) throws Exception {
        final int pageSize = 256;
        final int pages = 0x100000 / pageSize;

        UsbManager manager = requireManager(context);
        UsbSerialDriver driver = requireDriver(context, deviceId);
        UsbDevice device = driver.getDevice();

        if (!manager.hasPermission(device)) {
            throw new SecurityException("USB permission has not been granted.");
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Android could not open the selected USB device.");
        }

        UsbSerialPort port = firstPort(driver);

        File dir = new File(context.getFilesDir(), "ecu_dumps");
        if (!dir.exists() && !dir.mkdirs()) {
            connection.close();
            throw new IOException("Could not create ECU dump directory.");
        }

        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd_HHmmss",
                Locale.UK
        ).format(new Date());

        File output = new File(
                dir,
                "Bandit1250_DensoRaw_" + stamp + ".bin"
        );

        boolean complete = false;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] idBytes = new byte[8];

        try {
            port.open(connection);
            port.setParameters(
                    57600,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
            );

            EnumSet<UsbSerialPort.ControlLine> controls =
                    port.getSupportedControlLines();

            if (!controls.contains(UsbSerialPort.ControlLine.RTS)) {
                throw new IOException(
                        "Selected USB serial driver does not expose RTS; " +
                        "automatic ECU monitor reset/entry cannot be attempted."
                );
            }

            try {
                port.purgeHwBuffers(true, true);
            } catch (Exception ignored) {}

            // The original Suzuki interface uses RTS to reset the ECU after the
            // hardware is placed in its programming/monitor mode.
            port.setRTS(true);
            sleep(300);
            port.setRTS(false);
            sleep(300);

            drain(port, 40);

            // Synchronise: up to 18 zero bytes, waiting for monitor ACK 0x06.
            boolean synced = false;
            for (int i = 0; i < 18 && !synced; i++) {
                byte[] tx = {(byte)0x00};
                port.write(tx, 500);
                byte[] rx = collect(port, 90, 32);
                synced = contains(rx, 0x06);
                if (!synced) sleep(40);
            }

            if (!synced) {
                throw new IOException(
                        "No 0x06 monitor ACK during 57.6k sync. " +
                        "Do not continue: programming-mode/reset wiring or protocol may differ."
                );
            }

            drain(port, 40);

            // Ask monitor status. An 0x8C response means the monitor is already
            // unlocked. Otherwise use the known M32R Suzuki monitor read-unlock
            // sequence, then verify 0x8C before issuing any page-read command.
            byte[] status = transactLoose(
                    port,
                    new byte[]{(byte)0x70},
                    220,
                    32
            );

            if (!contains(status, 0x8C)) {
                byte[] unlock = new byte[]{
                        (byte)0xF5, (byte)0x84,
                        (byte)0x00, (byte)0x00, (byte)0x0C,
                        (byte)0x53, (byte)0x55, (byte)0x45,
                        (byte)0x46, (byte)0x49, (byte)0x4D,
                        (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
                        (byte)0x56, (byte)0x30
                };

                drain(port, 20);
                port.write(unlock, 1000);
                byte[] unlockReply = collect(port, 260, 64);

                if (!contains(unlockReply, 0x06)) {
                    throw new IOException(
                            "Monitor did not ACK the read-unlock sequence. " +
                            "Stopped before any flash page read."
                    );
                }

                status = transactLoose(
                        port,
                        new byte[]{(byte)0x70},
                        300,
                        32
                );

                if (!contains(status, 0x8C)) {
                    throw new IOException(
                            "Monitor unlock could not be verified (no 0x8C). " +
                            "Stopped before any flash page read."
                    );
                }
            }

            try (BufferedOutputStream out =
                         new BufferedOutputStream(new FileOutputStream(output))) {

                for (int page = 0; page < pages; page++) {
                    int high = (page >> 8) & 0x0F;
                    int mid = page & 0xFF;

                    byte[] checksumCmd = new byte[]{
                            (byte)0xE1,
                            (byte)mid,
                            (byte)high,
                            (byte)mid,
                            (byte)high
                    };

                    byte[] checksumReply = transactExpected(
                            port,
                            checksumCmd,
                            2,
                            500
                    );

                    int expectedChecksum =
                            (checksumReply[0] & 0xFF) |
                            ((checksumReply[1] & 0xFF) << 8);

                    byte[] pageCmd = new byte[]{
                            (byte)0xFF,
                            (byte)mid,
                            (byte)high
                    };

                    byte[] pageData = null;
                    int actualChecksum = -1;

                    for (int attempt = 1; attempt <= 4; attempt++) {
                        pageData = transactExpected(
                                port,
                                pageCmd,
                                pageSize,
                                900
                        );

                        actualChecksum = pageChecksum(pageData);

                        if (actualChecksum == expectedChecksum) {
                            break;
                        }

                        if (attempt < 4) {
                            drain(port, 50);
                            sleep(100);
                        }
                    }

                    if (pageData == null ||
                            pageData.length != pageSize ||
                            actualChecksum != expectedChecksum) {
                        throw new IOException(String.format(
                                Locale.US,
                                "Page checksum mismatch at 0x%05X: ECU %04X, read %04X",
                                page * pageSize,
                                expectedChecksum,
                                actualChecksum & 0xFFFF
                        ));
                    }

                    out.write(pageData);
                    sha.update(pageData);

                    long base = (long)page * pageSize;
                    long idStart = 0xFFFF0L;
                    long idEnd = idStart + idBytes.length;

                    if (base < idEnd && base + pageSize > idStart) {
                        for (int i = 0; i < pageSize; i++) {
                            long absolute = base + i;
                            if (absolute >= idStart && absolute < idEnd) {
                                idBytes[(int)(absolute - idStart)] = pageData[i];
                            }
                        }
                    }

                    if (progress != null) {
                        progress.onProgress(
                                page + 1,
                                pages,
                                (long)(page + 1) * pageSize
                        );
                    }
                }

                out.flush();
                complete = true;
            }

        } finally {
            try { port.close(); } catch (Exception ignored) {}
            connection.close();

            if (!complete && output.exists()) {
                // Never leave a partial file looking like a valid 1 MiB BIN.
                //noinspection ResultOfMethodCallIgnored
                output.delete();
            }
        }

        return new ReadResult(
                output,
                0x100000L,
                toHex(sha.digest()),
                printableAscii(idBytes)
        );
    }

    private static UsbManager requireManager(Context context) throws IOException {
        UsbManager manager =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) throw new IOException("Android USB host is unavailable.");
        return manager;
    }

    private static UsbSerialDriver requireDriver(
            Context context,
            int deviceId
    ) throws IOException {
        UsbSerialDriver driver = findDriver(context, deviceId);
        if (driver == null) {
            throw new IOException("Selected USB serial adapter is no longer attached.");
        }
        return driver;
    }

    private static UsbSerialDriver findDriver(
            Context context,
            int deviceId
    ) {
        UsbManager manager =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return null;

        for (UsbSerialDriver driver :
                UsbSerialProber.getDefaultProber().findAllDrivers(manager)) {
            if (driver.getDevice().getDeviceId() == deviceId) {
                return driver;
            }
        }
        return null;
    }

    private static DeviceInfo infoFor(
            UsbManager manager,
            UsbSerialDriver driver
    ) {
        UsbDevice d = driver.getDevice();
        String product = safeProduct(d);
        String maker = safeManufacturer(d);
        String name = "";

        if (!maker.isEmpty()) name += maker + " ";
        if (!product.isEmpty()) name += product;

        if (name.trim().isEmpty()) {
            name = driver.getClass().getSimpleName();
        }

        String label = name.trim() + "  •  " +
                String.format(
                        Locale.US,
                        "VID %04X / PID %04X",
                        d.getVendorId(),
                        d.getProductId()
                ) + "\n" +
                driver.getClass().getSimpleName();

        return new DeviceInfo(
                d.getDeviceId(),
                d.getVendorId(),
                d.getProductId(),
                driver.getClass().getSimpleName(),
                label,
                manager.hasPermission(d)
        );
    }

    private static UsbSerialPort firstPort(
            UsbSerialDriver driver
    ) throws IOException {
        if (driver.getPorts().isEmpty()) {
            throw new IOException("USB serial driver exposes no serial ports.");
        }
        return driver.getPorts().get(0);
    }

    private static byte[] transactLoose(
            UsbSerialPort port,
            byte[] command,
            int waitMs,
            int maxBytes
    ) throws IOException {
        drain(port, 20);
        port.write(command, 1000);
        byte[] raw = collect(port, waitMs, maxBytes);
        return stripEcho(raw, command);
    }

    private static byte[] transactExpected(
            UsbSerialPort port,
            byte[] command,
            int expected,
            int totalTimeoutMs
    ) throws IOException {
        drain(port, 20);
        port.write(command, 1000);

        long end = System.currentTimeMillis() + totalTimeoutMs;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();

        while (System.currentTimeMillis() < end) {
            byte[] b = new byte[Math.max(64, expected + command.length + 8)];
            int remain = (int)Math.max(
                    1L,
                    Math.min(80L, end - System.currentTimeMillis())
            );

            int n;
            try {
                n = port.read(b, remain);
            } catch (Exception e) {
                throw new IOException("USB serial read failed: " + e.getMessage(), e);
            }

            if (n > 0) {
                raw.write(b, 0, n);

                byte[] candidate = stripEcho(raw.toByteArray(), command);
                if (candidate.length >= expected) {
                    if (candidate.length > expected) {
                        // Unknown extra bytes are safer treated as framing failure
                        // than silently shifted into a firmware dump.
                        throw new IOException(
                                "Unexpected extra serial bytes: expected " +
                                expected + ", received " + candidate.length
                        );
                    }
                    return candidate;
                }
            }
        }

        byte[] candidate = stripEcho(raw.toByteArray(), command);
        throw new IOException(
                "Short serial reply: expected " + expected +
                " bytes, received " + candidate.length
        );
    }

    private static byte[] stripEcho(byte[] raw, byte[] command) {
        if (raw == null) return new byte[0];
        if (command == null || command.length == 0) return raw;

        if (raw.length >= command.length &&
                startsWith(raw, command)) {
            return Arrays.copyOfRange(raw, command.length, raw.length);
        }

        return raw;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] collect(
            UsbSerialPort port,
            int totalMs,
            int maxBytes
    ) throws IOException {
        long end = System.currentTimeMillis() + totalMs;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (System.currentTimeMillis() < end && out.size() < maxBytes) {
            byte[] b = new byte[Math.min(128, maxBytes - out.size())];
            int wait = (int)Math.max(
                    1L,
                    Math.min(50L, end - System.currentTimeMillis())
            );

            int n;
            try {
                n = port.read(b, wait);
            } catch (Exception e) {
                throw new IOException("USB serial read failed: " + e.getMessage(), e);
            }

            if (n > 0) {
                out.write(b, 0, n);
            }
        }

        return out.toByteArray();
    }

    private static void drain(
            UsbSerialPort port,
            int quietMs
    ) {
        long end = System.currentTimeMillis() + quietMs;
        byte[] b = new byte[256];

        while (System.currentTimeMillis() < end) {
            try {
                int n = port.read(b, 5);
                if (n <= 0) break;
            } catch (Exception e) {
                break;
            }
        }
    }

    private static int pageChecksum(byte[] page) {
        int sum = 0;
        for (int i = 0; i + 1 < page.length; i += 2) {
            sum += ((page[i] & 0xFF) << 8) |
                    (page[i + 1] & 0xFF);
            sum &= 0xFFFF;
        }
        return sum;
    }

    private static boolean contains(byte[] bytes, int wanted) {
        if (bytes == null) return false;
        for (byte b : bytes) {
            if ((b & 0xFF) == (wanted & 0xFF)) return true;
        }
        return false;
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("USB ECU operation interrupted.", e);
        }
    }

    private static String safeManufacturer(UsbDevice d) {
        try {
            String s = d.getManufacturerName();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeProduct(UsbDevice d) {
        try {
            String s = d.getProductName();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String printableAscii(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v >= 0x20 && v <= 0x7E) {
                s.append((char)v);
            } else {
                s.append('.');
            }
        }
        return s.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) {
            s.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return s.toString();
    }

    private static String oneLine(String value) {
        return value == null
                ? ""
                : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
