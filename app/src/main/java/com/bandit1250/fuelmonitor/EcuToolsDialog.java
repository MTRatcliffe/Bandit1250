package com.bandit1250.fuelmonitor;

import android.app.*;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Fault-code and experimental ECU tools popup.
 *
 * The experimental ECU discovery is intentionally conservative. It uses only
 * read-only or deliberately non-state-changing KWP probes and never sends
 * erase/write/download/programming-mode/state-changing actuator/routine/key commands.
 */
public final class EcuToolsDialog {
    public interface SdsAction<T> {
        T run(SuzukiSds sds) throws Exception;
    }

    public interface SdsCallback<T> {
        void done(T result, Exception error);
    }

    private final MainActivity activity;

    private AlertDialog dialog;
    private TextView dtcResult;
    private TextView experimentalResult;
    private Button readDtc;
    private Button clearDtc;
    private Button probeRead;
    private Button elmLengthTest;
    private Button densoReadOnlyTest;
    private Button actuatorIdScan;
    private Button profileRefresh;
    private Button protocolLab;
    private Button shareProtocolLog;
    private TextView profileSummary;
    private TextView actuatorResult;

    public EcuToolsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView profileHead = heading("ECU / adapter profile");
        box.addView(profileHead);

        profileSummary = mono(profileSummaryText());
        box.addView(profileSummary);

        LinearLayout profileButtons = row();

        profileRefresh = new Button(activity);
        profileRefresh.setText("REFRESH INFO");
        profileRefresh.setOnClickListener(v -> refreshProfile());
        profileButtons.addView(profileRefresh, weight());

        protocolLab = new Button(activity);
        protocolLab.setText("PROTOCOL LAB");
        protocolLab.setOnClickListener(v -> new ProtocolLabDialog(activity).show());
        profileButtons.addView(protocolLab, weight());

        box.addView(profileButtons);

        LinearLayout logButtons = row();

        shareProtocolLog = new Button(activity);
        shareProtocolLog.setText("SHARE PROTOCOL LOG");
        shareProtocolLog.setOnClickListener(v -> activity.shareProtocolLog());
        logButtons.addView(shareProtocolLog, weight());

        Button clearProtocolLog = new Button(activity);
        clearProtocolLog.setText("CLEAR LOG");
        clearProtocolLog.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle("Clear protocol log?")
                        .setMessage(
                                "This clears the current CSV transaction log only. " +
                                "It does not change the ECU."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", (d, which) -> {
                            activity.clearProtocolLog();
                            Toast.makeText(
                                    activity,
                                    "Protocol log cleared",
                                    Toast.LENGTH_SHORT
                            ).show();
                        })
                        .show()
        );
        logButtons.addView(clearProtocolLog, weight());

        box.addView(logButtons);

        TextView profileNote = body(
                "Profile results persist across app restarts. Experimental ECU-tool " +
                "transactions are automatically written to the current CSV log."
        );
        profileNote.setPadding(0, dp(2), 0, dp(12));
        box.addView(profileNote);

        View profileDivider = new View(activity);
        profileDivider.setBackgroundColor(Color.rgb(170, 170, 170));
        box.addView(profileDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        TextView engineHead = heading("Engine ECU fault codes");
        box.addView(engineHead);

        TextView engineNote = body(
                "Reads the engine ECU using KWP/SDS. Raw ECU responses are " +
                "shown as well as a provisional standard DTC decode."
        );
        box.addView(engineNote);

        LinearLayout dtcButtons = row();

        readDtc = new Button(activity);
        readDtc.setText("READ CODES");
        readDtc.setOnClickListener(v -> readDtcs());
        dtcButtons.addView(readDtc, weight());

        clearDtc = new Button(activity);
        clearDtc.setText("CLEAR STORED");
        clearDtc.setOnClickListener(v -> confirmClear());
        dtcButtons.addView(clearDtc, weight());

        box.addView(dtcButtons);

        dtcResult = mono("No fault-code read performed yet.");
        box.addView(dtcResult);

        TextView absNote = body(
                "ABS is a separate SDS controller. ABS read/clear is not yet " +
                "enabled here until its diagnostic address/session is mapped."
        );
        absNote.setPadding(0, dp(10), 0, dp(14));
        box.addView(absNote);

        View actuatorDivider = new View(activity);
        actuatorDivider.setBackgroundColor(Color.rgb(170, 170, 170));
        box.addView(actuatorDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        TextView actuatorHead = heading(
                "⚙ Actuator discovery — REPORT ONLY"
        );
        actuatorHead.setPadding(0, dp(14), 0, dp(4));
        box.addView(actuatorHead);

        TextView actuatorNote = body(
                "Scans every KWP2000 I/O-control local identifier from 0x00 to 0xFF " +
                "using ONLY 30 XX 01 (Report Current State).\n\n" +
                "It does NOT send 0x00 Return Control, 0x07 Short Term Adjustment, " +
                "an actuator state/value, or any command intended to move/switch an output. " +
                "Positive 0x70 responses are only candidate controllable IDs until their " +
                "physical function is identified."
        );
        box.addView(actuatorNote);

        actuatorIdScan = new Button(activity);
        actuatorIdScan.setText("⚙ ACTUATOR ID SCAN");
        actuatorIdScan.setOnClickListener(v -> confirmActuatorIdScan());
        box.addView(actuatorIdScan);

        actuatorResult = mono(actuatorProfileSummary());
        actuatorResult.setPadding(0, dp(4), 0, dp(12));
        box.addView(actuatorResult);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.rgb(170, 170, 170));
        box.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        TextView experimentalHead = heading(
                "Experimental ECU memory read — USE AT YOUR OWN RISK"
        );
        experimentalHead.setTextColor(Color.rgb(175, 65, 0));
        experimentalHead.setPadding(0, dp(14), 0, dp(4));
        box.addView(experimentalHead);

        TextView warning = body(
                "Conservative experimental discovery tool. It pauses normal live-data " +
                "polling and tries safe/read-only KWP paths: ECU ID reads, 0x23 memory " +
                "reads, a security SEED request only, a non-state-changing 0x10 service " +
                "presence probe, and ECU-to-tester 0x35 RequestUpload formats.\n\n" +
                "It NEVER sends a security key, ECU reset, RequestDownload, erase/write, " +
                "programming-session change, actuator control or routine command.\n\n" +
                "Keep ignition and battery voltage stable and do not disconnect Bluetooth " +
                "while the discovery pass is running."
        );
        box.addView(warning);

        probeRead = new Button(activity);
        probeRead.setText("RUN SAFE ECU DISCOVERY");
        probeRead.setOnClickListener(v -> confirmProbe());
        box.addView(probeRead);

        elmLengthTest = new Button(activity);
        elmLengthTest.setText("ELM MESSAGE LENGTH TEST");
        elmLengthTest.setOnClickListener(v -> confirmElmLengthTest());
        box.addView(elmLengthTest);

        densoReadOnlyTest = new Button(activity);
        densoReadOnlyTest.setText("DENSO / K-LINE READ-ONLY TESTS");
        densoReadOnlyTest.setOnClickListener(v -> confirmDensoReadOnlyTest());
        box.addView(densoReadOnlyTest);

        experimentalResult = mono(
                "No ECU discovery pass performed yet."
        );
        box.addView(experimentalResult);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Fault codes / ECU tools")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private void readDtcs() {
        setDtcBusy(true);
        dtcResult.setText("Reading engine ECU fault codes…");

        activity.runExclusiveSdsTask(
                "DTC read",
                sds -> sds.requestRaw("1802FF00 1", 5000),
                (response, error) -> {
                    setDtcBusy(false);

                    if (error != null) {
                        dtcResult.setText("DTC read failed:\n" + error.getMessage());
                        return;
                    }

                    dtcResult.setText(describeDtcResponse(response));
                }
        );
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity)
                .setTitle("Clear stored ECU fault codes?")
                .setMessage(
                        "This removes diagnostic history from the engine ECU. " +
                        "An active fault will return if the fault is still present."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, which) -> clearDtcs())
                .show();
    }

    private void clearDtcs() {
        setDtcBusy(true);
        dtcResult.setText("Clearing stored engine ECU fault codes…");

        activity.runExclusiveSdsTask(
                "DTC clear",
                sds -> sds.requestRaw("14FF00 1", 5000),
                (response, error) -> {
                    setDtcBusy(false);

                    if (error != null) {
                        dtcResult.setText("Clear failed:\n" + error.getMessage());
                        return;
                    }

                    String compact = compactHex(response);

                    if (compact.contains("54")) {
                        dtcResult.setText(
                                "ECU acknowledged clear request.\n" +
                                "Re-reading fault codes…"
                        );
                        // Re-read automatically so the user sees what remains.
                        readDtcs();
                    } else {
                        String nrc = EcuMemoryReader.negativeResponseExplanation(
                                response,
                                0x14
                        );
                        dtcResult.setText(
                                "Clear response was not the expected positive 0x54.\n" +
                                (nrc == null ? "" : nrc + "\n") +
                                "Raw: " + oneLine(response)
                        );
                    }
                }
        );
    }

    private void confirmActuatorIdScan() {
        new AlertDialog.Builder(activity)
                .setTitle("Scan actuator local IDs without actuating?")
                .setMessage(
                        "This pauses live SDS polling and scans local identifiers 0x00 through " +
                        "0xFF with KWP request 30 XX 01 only. Under KWP2000, control parameter " +
                        "0x01 means Report Current State.\n\n" +
                        "The scan NEVER sends 0x00, 0x07 or an actuator state/value. " +
                        "Depending on ECU response time, a complete 256-ID sweep may take " +
                        "a few minutes.\n\nContinue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Scan", (d, which) -> startActuatorIdScan())
                .show();
    }

    private void startActuatorIdScan() {
        setExperimentalBusy(true);

        actuatorResult.setText(
                "Scanning KWP 0x30 local IDs…\n" +
                "Request: 30 XX 01 (Report Current State ONLY)\n" +
                "0 / 256 IDs"
        );

        activity.runExclusiveSdsTask(
                "non-actuating 0x30 actuator ID scan",
                sds -> EcuMemoryReader.scanActuatorLocalIds(
                        sds,
                        (done, total, localId) ->
                                activity.runOnUiThread(() -> {
                                    if (actuatorResult != null) {
                                        actuatorResult.setText(String.format(
                                                Locale.UK,
                                                "Scanning KWP 0x30 local IDs…\n" +
                                                "Request: 30 XX 01 (Report Current State ONLY)\n" +
                                                "%d / %d IDs  •  current 0x%02X",
                                                done,
                                                total,
                                                localId & 0xFF
                                        ));
                                    }
                                })
                ),
                (result, error) -> {
                    setExperimentalBusy(false);

                    if (error != null) {
                        actuatorResult.setText(
                                "Actuator-ID scan failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    if (result == null) {
                        actuatorResult.setText(
                                "Actuator-ID scan returned no result."
                        );
                        return;
                    }

                    prefs().edit()
                            .putInt(
                                    "ecu_profile_actuator_scan_count",
                                    result.scannedIds
                            )
                            .putInt(
                                    "ecu_profile_actuator_positive_count",
                                    result.positiveIds
                            )
                            .putString(
                                    "ecu_profile_actuator_ids",
                                    result.supportedIds
                            )
                            .putBoolean(
                                    "ecu_profile_actuator_service_unsupported",
                                    result.serviceUnsupported
                            )
                            .putLong(
                                    "ecu_profile_actuator_updated",
                                    System.currentTimeMillis()
                            )
                            .apply();

                    actuatorResult.setText(result.report);
                    updateProfileSummary();
                }
        );
    }

    private String actuatorProfileSummary() {
        SharedPreferences p = prefs();

        if (!p.contains("ecu_profile_actuator_scan_count")) {
            return "No non-actuating 0x30 local-ID scan performed yet.";
        }

        int scanned = p.getInt("ecu_profile_actuator_scan_count", 0);
        int positives = p.getInt("ecu_profile_actuator_positive_count", 0);
        String ids = p.getString("ecu_profile_actuator_ids", "");
        boolean unsupported = p.getBoolean(
                "ecu_profile_actuator_service_unsupported",
                false
        );
        long updated = p.getLong("ecu_profile_actuator_updated", 0L);

        String when = updated == 0L
                ? "unknown"
                : new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.UK
                ).format(new Date(updated));

        if (unsupported) {
            return "Last scan: service 0x30 reported unsupported in this SDS session.\n" +
                    "IDs scanned before stop: " + scanned + " / 256\n" +
                    "Updated: " + when;
        }

        return "Last scan: " + positives + " candidate actuator IDs from " +
                scanned + " IDs scanned.\n" +
                "Candidate IDs: " +
                (ids == null || ids.isEmpty() ? "none" : "0x" + ids.replace(", ", ", 0x")) +
                "\nUpdated: " + when;
    }

    private void confirmProbe() {
        new AlertDialog.Builder(activity)
                .setTitle("Run safe ECU discovery?")
                .setMessage(
                        "This pauses live SDS polling and runs only the conservative " +
                        "read/read-discovery probes listed on this page.\n\n" +
                        "No security key, ECU reset, programming-session change, " +
                        "RequestDownload, write, erase, actuator or routine commands are sent.\n\n" +
                        "Continue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, which) -> startProbe())
                .show();
    }

    private void startProbe() {
        setExperimentalBusy(true);
        experimentalResult.setText(
                "Running conservative ECU discovery…\n" +
                "Live 21 08 polling is temporarily paused."
        );

        activity.runExclusiveSdsTask(
                "safe ECU discovery",
                EcuMemoryReader::safeDiscovery,
                (discovery, error) -> {
                    setExperimentalBusy(false);

                    if (error != null) {
                        experimentalResult.setText(
                                "Discovery failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    if (discovery == null) {
                        experimentalResult.setText(
                                "Discovery returned no result."
                        );
                        return;
                    }

                    experimentalResult.setText(discovery.report);

                    // Preserve the existing full .bin path if direct 0x23
                    // memory access turns out to work on a different ECU/session.
                    if (discovery.directMemory != null &&
                            discovery.directMemory.supported) {
                        chooseDumpSize(discovery.directMemory);
                        return;
                    }

                    if (discovery.uploadAccepted) {
                        String message = discovery.uploadDataReturned
                                ? "A read-only 0x35/0x36 upload path returned data. " +
                                  "The raw discovery report above tells us which request worked. " +
                                  "Full .bin reconstruction via that path is not automated yet."
                                : "The ECU accepted RequestUpload, but the tiny TransferData " +
                                  "format was not confirmed. The raw responses above give us " +
                                  "the next protocol clue.";

                        new AlertDialog.Builder(activity)
                                .setTitle("Promising ECU upload response")
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                }
        );
    }

    private void confirmElmLengthTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Run ELM message-length test?")
                .setMessage(
                        "This uses the known read-only 1A91 ECU-ID request and adds " +
                        "zero padding to increasing request lengths. It is intended to " +
                        "prove whether '?' responses originate in the V-LINK/ELM parser.\n\n" +
                        "No ECU write, erase, reset, routine or actuator commands are sent."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run test", (d, which) -> startElmLengthTest())
                .show();
    }

    private void startElmLengthTest() {
        setExperimentalBusy(true);
        experimentalResult.setText(
                "Running ELM / K-Line message-length test…\n" +
                "Live 21 08 polling is temporarily paused."
        );

        activity.runExclusiveSdsTask(
                "ELM length test",
                EcuMemoryReader::testElmMessageLength,
                (result, error) -> {
                    setExperimentalBusy(false);

                    if (error != null) {
                        experimentalResult.setText(
                                "ELM length test failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    experimentalResult.setText(
                            result == null
                                    ? "ELM length test returned no result."
                                    : result.report
                    );

                    if (result != null) {
                        SharedPreferences p = prefs();
                        p.edit()
                                .putInt(
                                        "ecu_profile_max_tx",
                                        result.largestEcuSeenBytes
                                )
                                .putInt(
                                        "ecu_profile_first_question",
                                        result.firstQuestionMarkBytes
                                )
                                .putLong(
                                        "ecu_profile_updated",
                                        System.currentTimeMillis()
                                )
                                .apply();
                        updateProfileSummary();
                    }
                }
        );
    }

    private void confirmDensoReadOnlyTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Run Denso / K-Line read-only tests?")
                .setMessage(
                        "This performs additional read-only Suzuki/Denso identifier/data " +
                        "queries and temporarily tests alternate KWP initialisation at " +
                        "10,400 and 9,600 baud. The normal Bandit SDS connection is then " +
                        "restored automatically.\n\n" +
                        "It does not select a programming session, send a security key, " +
                        "reset the ECU, write/erase memory or operate actuators."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run tests", (d, which) -> startDensoReadOnlyTest())
                .show();
    }

    private void startDensoReadOnlyTest() {
        setExperimentalBusy(true);
        experimentalResult.setText(
                "Running Denso / Suzuki read-only compatibility tests…\n" +
                "This may take around a minute."
        );

        activity.runExclusiveSdsTask(
                "Denso read-only test",
                EcuMemoryReader::testDensoReadOnlyPaths,
                (result, error) -> {
                    setExperimentalBusy(false);

                    if (error != null) {
                        experimentalResult.setText(
                                "Denso compatibility test failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    experimentalResult.setText(
                            result == null
                                    ? "Denso compatibility test returned no result."
                                    : result.report
                    );

                    if (result != null) {
                        prefs().edit()
                                .putString(
                                        "ecu_profile_1a",
                                        result.supported1A
                                )
                                .putString(
                                        "ecu_profile_21",
                                        result.supported21
                                )
                                .putBoolean(
                                        "ecu_profile_normal_restored",
                                        result.normalSdsRestored
                                )
                                .putLong(
                                        "ecu_profile_updated",
                                        System.currentTimeMillis()
                                )
                                .apply();
                        updateProfileSummary();
                    }
                }
        );
    }

    private void setExperimentalBusy(boolean busy) {
        if (probeRead != null) probeRead.setEnabled(!busy);
        if (elmLengthTest != null) elmLengthTest.setEnabled(!busy);
        if (densoReadOnlyTest != null) densoReadOnlyTest.setEnabled(!busy);
        if (actuatorIdScan != null) actuatorIdScan.setEnabled(!busy);
        if (profileRefresh != null) profileRefresh.setEnabled(!busy);
        if (protocolLab != null) protocolLab.setEnabled(!busy);
        if (shareProtocolLog != null) shareProtocolLog.setEnabled(!busy);
    }

    private static final class ProfileRead {
        final String adapter;
        final String protocol;
        final String ecuRaw;
        final String ecuAscii;

        ProfileRead(
                String adapter,
                String protocol,
                String ecuRaw,
                String ecuAscii
        ) {
            this.adapter = adapter;
            this.protocol = protocol;
            this.ecuRaw = ecuRaw;
            this.ecuAscii = ecuAscii;
        }
    }

    private void refreshProfile() {
        profileRefresh.setEnabled(false);
        profileSummary.setText("Refreshing adapter / ECU identity…");

        activity.runExclusiveSdsTask(
                "ECU profile refresh",
                sds -> {
                    String adapter = sds.requestRaw("ATI", 2500);
                    String protocol = sds.requestRaw("ATDPN", 2500);
                    String ecu = sds.requestRaw("1A91 1", 4500);

                    return new ProfileRead(
                            oneLine(adapter),
                            oneLine(protocol),
                            oneLine(ecu),
                            extract1AAscii(ecu, 0x91)
                    );
                },
                (info, error) -> {
                    profileRefresh.setEnabled(true);

                    if (error != null) {
                        profileSummary.setText(
                                profileSummaryText() +
                                "\n\nRefresh error: " + error.getMessage()
                        );
                        return;
                    }

                    if (info != null) {
                        prefs().edit()
                                .putString("ecu_profile_adapter", info.adapter)
                                .putString("ecu_profile_protocol", info.protocol)
                                .putString("ecu_profile_id_raw", info.ecuRaw)
                                .putString("ecu_profile_id_ascii", info.ecuAscii)
                                .putLong(
                                        "ecu_profile_updated",
                                        System.currentTimeMillis()
                                )
                                .apply();
                    }

                    updateProfileSummary();
                }
        );
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(
                "bandit_monitor",
                android.content.Context.MODE_PRIVATE
        );
    }

    private void updateProfileSummary() {
        if (profileSummary != null) {
            profileSummary.setText(profileSummaryText());
        }
    }

    private String profileSummaryText() {
        SharedPreferences p = prefs();

        String adapter = p.getString("ecu_profile_adapter", "not refreshed");
        String protocol = p.getString("ecu_profile_protocol", "not refreshed");
        String ecuAscii = p.getString("ecu_profile_id_ascii", "not refreshed");
        String raw = p.getString("ecu_profile_id_raw", "not refreshed");
        int maxTx = p.getInt("ecu_profile_max_tx", 0);
        int firstQuestion = p.getInt("ecu_profile_first_question", 0);
        String ids1a = p.getString("ecu_profile_1a", "not tested");
        String ids21 = p.getString("ecu_profile_21", "not tested");
        String actuatorCandidates;

        if (p.contains("ecu_profile_actuator_positive_count")) {
            int actuatorCount = p.getInt(
                    "ecu_profile_actuator_positive_count",
                    0
            );
            int actuatorScanned = p.getInt(
                    "ecu_profile_actuator_scan_count",
                    0
            );
            boolean actuatorUnsupported = p.getBoolean(
                    "ecu_profile_actuator_service_unsupported",
                    false
            );

            actuatorCandidates = actuatorUnsupported
                    ? "service 0x30 unsupported"
                    : actuatorCount + " candidates / " + actuatorScanned + " IDs scanned";
        } else {
            actuatorCandidates = "not tested";
        }

        String restore;
        if (p.contains("ecu_profile_normal_restored")) {
            restore = p.getBoolean("ecu_profile_normal_restored", false)
                    ? "yes"
                    : "NO / not proven";
        } else {
            restore = "not tested";
        }

        long updated = p.getLong("ecu_profile_updated", 0L);
        String when = updated == 0L
                ? "never"
                : new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.UK
                ).format(new Date(updated));

        return "Adapter: " + adapter + "\n" +
                "ELM protocol: " + protocol + "\n" +
                "ECU ID: " + ecuAscii + "\n" +
                "1A91 raw: " + raw + "\n" +
                "Tested TX payload: " +
                (maxTx > 0 ? maxTx + " bytes" : "not tested") + "\n" +
                "First local ?: " +
                (firstQuestion > 0
                        ? firstQuestion + " bytes"
                        : "not tested") + "\n" +
                "Positive 0x1A IDs: " + ids1a + "\n" +
                "Positive 0x21 IDs: " + ids21 + "\n" +
                "0x30 actuator candidates: " + actuatorCandidates + "\n" +
                "Normal SDS recovered after alternate init: " + restore + "\n" +
                "Profile updated: " + when;
    }

    private String extract1AAscii(String response, int localId) {
        String hex = compactHex(response);
        String marker = String.format(Locale.US, "5A%02X", localId & 0xFF);
        int p = hex.indexOf(marker);

        if (p < 0) return "no positive 5A response";

        String payload = hex.substring(p + marker.length());
        StringBuilder out = new StringBuilder();

        for (int i = 0; i + 1 < payload.length(); i += 2) {
            int value;

            try {
                value = Integer.parseInt(payload.substring(i, i + 2), 16);
            } catch (Exception e) {
                break;
            }

            if (value == 0x00 || value == 0xFF) continue;

            if (value >= 0x20 && value <= 0x7E) {
                out.append((char)value);
            } else {
                out.append('.');
            }
        }

        return out.length() == 0 ? "no printable ID" : out.toString();
    }

    private void chooseDumpSize(EcuMemoryReader.ProbeResult probe) {
        final String[] labels = {
                "256 KiB",
                "512 KiB",
                "1 MiB"
        };

        final long[] sizes = {
                256L * 1024L,
                512L * 1024L,
                1024L * 1024L
        };

        new AlertDialog.Builder(activity)
                .setTitle("Memory access works")
                .setMessage(
                        "The ECU accepted a standard read request. The exact " +
                        "Bandit flash size is not yet verified in this app. " +
                        "Choose the experimental range to read from address 0x000000.\n\n" +
                        "A .bin will only be kept if the entire selected range completes."
                )
                .setItems(labels, (d, which) ->
                        startFullRead(probe, sizes[which])
                )
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startFullRead(
            EcuMemoryReader.ProbeResult probe,
            long totalBytes
    ) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Reading ECU memory");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.setMessage(
                "READ-ONLY experimental operation\nAddress 0x000000"
        );
        progress.show();

        setExperimentalBusy(true);

        File dir = new File(activity.getFilesDir(), "ecu_dumps");
        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd_HHmmss",
                Locale.UK
        ).format(new Date());

        File output = new File(
                dir,
                "Bandit1250_ECU_" + stamp + ".bin"
        );

        activity.runExclusiveSdsTask(
                "ECU read",
                sds -> EcuMemoryReader.read(
                        sds,
                        output,
                        totalBytes,
                        probe.addressBytes,
                        probe.blockSize,
                        (done, total, address) ->
                                activity.runOnUiThread(() -> {
                                    int pct = total <= 0
                                            ? 0
                                            : (int)Math.min(
                                                    100,
                                                    (done * 100L) / total
                                            );
                                    progress.setProgress(pct);
                                    progress.setMessage(String.format(
                                            Locale.UK,
                                            "READ-ONLY experimental operation\n" +
                                            "Address 0x%08X\n" +
                                            "%,d / %,d bytes",
                                            address,
                                            done,
                                            total
                                    ));
                                })
                ),
                (result, error) -> {
                    setExperimentalBusy(false);

                    if (progress.isShowing()) {
                        progress.dismiss();
                    }

                    if (error != null) {
                        experimentalResult.setText(
                                "ECU read stopped; no partial .bin was kept.\n\n" +
                                error.getMessage()
                        );
                        return;
                    }

                    experimentalResult.setText(
                            "ECU memory read complete.\n" +
                            "File: " + result.file.getName() + "\n" +
                            "Bytes: " + result.bytes + "\n" +
                            "SHA-256: " + result.sha256
                    );

                    showReadComplete(result);
                }
        );
    }

    private void showReadComplete(EcuMemoryReader.ReadResult result) {
        String size = humanBytes(result.bytes);

        new AlertDialog.Builder(activity)
                .setTitle("ECU read complete")
                .setMessage(
                        "File: " + result.file.getName() + "\n" +
                        "Size: " + size + "\n" +
                        "SHA-256:\n" + result.sha256 + "\n\n" +
                        "Would you like to share the .bin?"
                )
                .setNegativeButton("Close", null)
                .setPositiveButton("Share BIN", (d, which) ->
                        activity.shareEcuBin(result.file)
                )
                .show();
    }

    private void setDtcBusy(boolean busy) {
        if (readDtc != null) readDtc.setEnabled(!busy);
        if (clearDtc != null) clearDtc.setEnabled(!busy);
    }

    private String describeDtcResponse(String response) {
        String nrc = EcuMemoryReader.negativeResponseExplanation(response, 0x18);

        if (nrc != null) {
            return "DTC request rejected: " + nrc + "\n" +
                    "Raw: " + oneLine(response);
        }

        String hex = compactHex(response);
        int p = hex.indexOf("58");

        if (p < 0) {
            return "No positive 0x58 DTC response found.\n" +
                    "Raw: " + oneLine(response);
        }

        String payload = hex.substring(p + 2);

        if ((payload.length() & 1) != 0) {
            payload = payload.substring(0, payload.length() - 1);
        }

        ArrayList<Integer> bytes = new ArrayList<>();

        try {
            for (int i = 0; i + 1 < payload.length(); i += 2) {
                bytes.add(Integer.parseInt(payload.substring(i, i + 2), 16));
            }
        } catch (NumberFormatException e) {
            return "Could not decode DTC payload.\nRaw: " + oneLine(response);
        }

        int offset = 0;
        Integer reportedCount = null;

        // KWP implementations commonly prepend a count before 3-byte
        // DTC/status tuples. Handle both count and no-count forms.
        if (bytes.size() >= 1 && ((bytes.size() - 1) % 3 == 0)) {
            reportedCount = bytes.get(0);
            offset = 1;
        }

        StringBuilder out = new StringBuilder();
        out.append("Engine ECU DTC response\n");

        if (reportedCount != null) {
            out.append("Reported count: ")
                    .append(reportedCount)
                    .append("\n");
        }

        int decoded = 0;

        for (int i = offset; i + 2 < bytes.size(); i += 3) {
            int hi = bytes.get(i);
            int lo = bytes.get(i + 1);
            int status = bytes.get(i + 2);
            int code = (hi << 8) | lo;

            // Some ECUs pad empty records with zero.
            if (code == 0 && status == 0) continue;

            out.append(decodeStandardDtc(code))
                    .append("   raw ")
                    .append(String.format(Locale.US, "%02X %02X", hi, lo))
                    .append("   status ")
                    .append(String.format(Locale.US, "%02X", status))
                    .append("\n");
            decoded++;
        }

        if (decoded == 0) {
            out.append("No decodable DTC tuples in this response.\n");
        }

        out.append("\nRaw: ").append(oneLine(response));
        out.append("\n\nNote: code/status interpretation is provisional until " +
                "verified against the Bandit's Suzuki SDS format.");

        return out.toString();
    }

    private String decodeStandardDtc(int code) {
        char[] family = {'P', 'C', 'B', 'U'};
        char prefix = family[(code >> 14) & 0x03];
        int firstDigit = (code >> 12) & 0x03;
        int rest = code & 0x0FFF;

        return String.format(
                Locale.US,
                "%c%d%03X",
                prefix,
                firstDigit,
                rest
        );
    }

    private String compactHex(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");
    }

    private String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(
                    Locale.UK,
                    "%.2f MiB",
                    bytes / (1024.0 * 1024.0)
            );
        }
        return String.format(
                Locale.UK,
                "%.1f KiB",
                bytes / 1024.0
        );
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
    }

    private TextView heading(String text) {
        TextView t = body(text);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView mono(String text) {
        TextView t = body(text);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    private int dp(int value) {
        return Math.round(
                value * activity.getResources().getDisplayMetrics().density
        );
    }
}
