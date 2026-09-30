package com.bandit1250.fuelmonitor;

import java.io.IOException;

public final class SuzukiSds {
    public interface Logger { void log(String s); }
    private final Elm327Client elm;
    private final Logger logger;

    public SuzukiSds(Elm327Client elm, Logger logger) { this.elm=elm; this.logger=logger; }

    private String run(String c, long t) throws IOException {
        logger.log("> " + c);
        String r = elm.command(c,t);
        logger.log("< " + r.replace('\r',' ').replace('\n',' ').trim());
        return r;
    }

    public void initialise() throws IOException {
        run("ATZ",4000);
        run("ATE0",2000);
        run("ATL0",2000);
        run("ATS1",2000);
        run("ATH0",2000);
        run("ATSP5",2500);
        run("ATWM8012F1013E",2500);
        run("ATSH8112F1",2500);
        run("ATFI",6000);
        run("ATSH8012F1",2500);
    }

    public String read2108() throws IOException { return run("2108",2200); }
}
