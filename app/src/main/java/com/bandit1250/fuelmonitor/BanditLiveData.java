package com.bandit1250.fuelmonitor;

public final class BanditLiveData {
    public final int rpm;
    public final double inj1, inj2, inj3, inj4, averageMs;
    public BanditLiveData(int rpm, double i1, double i2, double i3, double i4) {
        this.rpm = rpm; inj1=i1; inj2=i2; inj3=i3; inj4=i4;
        averageMs=(i1+i2+i3+i4)/4.0;
    }
}
