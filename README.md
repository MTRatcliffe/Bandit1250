# Bandit 1250 Fuel Monitor

Experimental Android app for a 2008 Suzuki GSF1250SA Bandit. It connects to a Bluetooth Classic ELM327-compatible interface, sends Suzuki SDS/K-Line commands, reads live data via `2108`, and estimates petrol flow from RPM and injector pulse width.

The Android source used by the build is stored in `BanditFuelMonitor-source.b64` and unpacked by GitHub Actions. This is the first on-bike diagnostic test build; raw SDS frames are deliberately shown so the Bandit-specific decoder can be verified against SZ Viewer.
