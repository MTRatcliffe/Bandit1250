package com.bandit1250.fuelmonitor;

import java.util.*;

public final class BanditDecoder {
    private BanditDecoder(){}

    public static BanditLiveData decode(String raw) {
        byte[] f = extract(raw);
        if (f.length < 34) throw new IllegalArgumentException("2108 frame too short: " + f.length + " bytes");

        // Experimental Bandit mapping for first on-bike test.
        int rpmRaw = u16(f,12,13);
        int rpm = (int)Math.round(rpmRaw/4.0);
        double i1=u16(f,26,27)/1000.0;
        double i2=u16(f,28,29)/1000.0;
        double i3=u16(f,30,31)/1000.0;
        double i4=u16(f,32,33)/1000.0;

        if (rpm<0 || rpm>15000) throw new IllegalArgumentException("Implausible RPM: "+rpm);
        if (i1>30 || i2>30 || i3>30 || i4>30) throw new IllegalArgumentException("Implausible injector PW");
        return new BanditLiveData(rpm,i1,i2,i3,i4);
    }

    public static byte[] extract(String raw) {
        String hex = raw==null ? "" : raw.toUpperCase(Locale.US).replaceAll("[^0-9A-F]","");
        int p=hex.indexOf("6108");
        if (p<0) throw new IllegalArgumentException("No 61 08 SDS response found");
        hex=hex.substring(p);
        if ((hex.length()&1)!=0) hex=hex.substring(0,hex.length()-1);
        byte[] out=new byte[hex.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return out;
    }

    public static String hex(byte[] f) {
        StringBuilder s=new StringBuilder();
        for(int i=0;i<f.length;i++){ if(i>0)s.append(' '); s.append(String.format(Locale.US,"%02X",f[i]&255)); }
        return s.toString();
    }

    private static int u16(byte[] f,int hi,int lo){ return ((f[hi]&255)<<8)|(f[lo]&255); }
}
