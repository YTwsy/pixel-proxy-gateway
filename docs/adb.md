# ADB Interface

Start with default ports:

```sh
scripts/adb_start.sh
```

Install, apply bootstrap hints, start, wait for health, and verify the listener ports on the Pixel:

```sh
scripts/adb_verify_device.sh
```

Run the full first-device acceptance flow:

```sh
scripts/acceptance_check.sh
```

This wraps APK packaging verification, device install/start verification, GOST fault injection, and LAN smoke into a timestamped `reports/acceptance-*` directory.

Failed device-side steps automatically trigger `adb_collect_diagnostics.sh` and record the diagnostics directory in `summary.tsv`.

The acceptance flow also runs `adb_restore_check.sh`, which reinstalls the same APK once and verifies the app restores from persisted config after `MY_PACKAGE_REPLACED`.

Device reboot restore uses normal `BOOT_COMPLETED`, so it is expected after the Pixel has completed credential unlock. The app does not claim Direct Boot restore before first unlock. To validate this explicitly, run `scripts/adb_reboot_restore_check.sh`, or set `RUN_REBOOT_RESTORE_CHECK=true` for the acceptance flow. This is opt-in because it reboots the phone.

The verifier also checks that runtime status reports:

- status freshness through `statusUpdatedAt`, `statusUpdatedAtEpochMillis`, and Pixel-clock `statusAgeSeconds`
- GOST `v3.2.6`
- a native-library GOST execution path
- GOST sha256 metadata
- wake lock held while the proxy is enabled
- battery optimization state
- persisted restore flags through `autoStart` and `startOnBoot`

Before installing, verify the APK contains the pinned GOST asset and metadata:

```sh
scripts/verify_apk.sh
```

This check requires Android `aapt` by default, because the ADB workflow depends on manifest-level declarations for foreground service startup, boot/package restore, notification, wake lock, network access, exported control components, and the status provider.

Or run the complete host-side preflight bundle:

```sh
scripts/host_preflight.sh
```

This writes a timestamped `reports/host-preflight-*` directory with shell syntax, status parser self-test, ADB start guard self-test, LAN smoke strict-assertion self-test, build/lint/unit-test, APK manifest/GOST provenance logs, and the remaining Pixel-only checklist.

Start with explicit values:

```sh
HTTP_PORT=8080 \
SOCKS_PORT=1080 \
BIND_ADDRESS=0.0.0.0 \
ENABLE_HTTP=true \
ENABLE_SOCKS=true \
scripts/adb_start.sh
```

Start with authentication:

```sh
AUTH_ENABLED=true USERNAME=proxy PASSWORD=secret scripts/adb_start.sh
```

Read service state:

```sh
adb shell dumpsys activity service com.wsy.pixelproxygateway/.ProxyForegroundService
adb shell content query --uri content://com.wsy.pixelproxygateway.status/status
```

Collect a timestamped diagnostic bundle:

```sh
scripts/adb_collect_diagnostics.sh
```

This writes service status, content-provider status, package/appops/battery/background policy, connectivity, process, socket, address, route, and recent logcat snapshots under `reports/diagnostics-*`.

Run an ADB-connected supervisor as a last-resort recovery loop:

```sh
DURATION_SECONDS=28800 CHECK_INTERVAL_SECONDS=60 PIXEL_IP=<pixel-lan-ip> scripts/adb_supervise.sh
```

The app's own watchdogs should normally recover GOST process, port, and request failures first. The ADB supervisor is for cases where the Android service state itself stops looking healthy.

`adb_verify_device.sh`, `adb_restore_check.sh`, `adb_fault_inject.sh`, `stability_monitor.sh`, and `adb_supervise.sh` treat status as stale when `statusAgeSeconds` exceeds `STATUS_MAX_AGE_SECONDS` (default `120`). Raise that only when deliberately debugging a slow or suspended device.

Stop:

```sh
scripts/adb_stop.sh
```

Inject a GOST process failure and verify the in-app watchdog recovers it:

```sh
scripts/adb_fault_inject.sh
```

Verify restore after APK replacement:

```sh
scripts/adb_restore_check.sh
```

Verify post-unlock restore after a device reboot:

```sh
scripts/adb_reboot_restore_check.sh
```

## LAN Egress Check

After `scripts/adb_verify_device.sh` prints a candidate LAN IP, run this from the Mac or another LAN client:

```sh
PIXEL_IP=<pixel-lan-ip> scripts/lan_smoke.sh
```

If you know the expected Google VPN exit IP, make the egress assertion strict:

```sh
EXPECTED_PROXY_IP=<google-vpn-exit-ip> PIXEL_IP=<pixel-lan-ip> scripts/lan_smoke.sh
```

When the Mac should not share the same public exit, add `REQUIRE_PROXY_DIFF_FROM_DIRECT=true` to fail if proxy and direct IPs are identical.

The script checks:

- HTTP and SOCKS5 ports are reachable from LAN.
- Public IP through the HTTP proxy.
- Public IP through the SOCKS5 proxy.
- `generate_204` returns 204 through both proxy modes.
- optional exact proxy exit IP matching through `EXPECTED_PROXY_IP`
- optional proxy/direct public IP separation through `REQUIRE_PROXY_DIFF_FROM_DIRECT=true`

If proxy public IPs match the expected Google VPN exit, the traffic path is doing the thing we actually care about.
