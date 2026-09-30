package com.bandit1250.fuelmonitor;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Elm327Client {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final BluetoothDevice device;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;

    public Elm327Client(BluetoothDevice device) { this.device = device; }

    public synchronized void connect() throws IOException {
        close();
        socket = device.createRfcommSocketToServiceRecord(SPP);
        socket.connect();
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public synchronized String command(String cmd, long timeoutMs) throws IOException {
        if (!isConnected()) throw new IOException("ELM327 not connected");
        while (in.available() > 0) in.read();
        out.write((cmd.trim() + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int n = in.available();
            if (n > 0) {
                for (int i=0;i<n;i++) {
                    int b = in.read();
                    if (b < 0) throw new IOException("Bluetooth stream closed");
                    char c=(char)b;
                    if (c=='>') return sb.toString().trim();
                    sb.append(c);
                }
            } else {
                try { Thread.sleep(5); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        }
        throw new IOException("ELM timeout. Partial: " + sb.toString().trim());
    }

    public synchronized void close() {
        try { if (in != null) in.close(); } catch(Exception ignored){}
        try { if (out != null) out.close(); } catch(Exception ignored){}
        try { if (socket != null) socket.close(); } catch(Exception ignored){}
        in=null; out=null; socket=null;
    }
}
