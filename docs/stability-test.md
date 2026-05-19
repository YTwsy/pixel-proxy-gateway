# Stability Test

## Manual fault injection

Kill the GOST child process from ADB after the app is running:

```sh
scripts/adb_status.sh
scripts/adb_fault_inject.sh
```

Expected result:

- `restartCount` increases.
- `proxyRunning=true` after recovery.
- `lastRestartReason` contains `process_exit` or `process_watchdog`.

## Port watchdog

The port watchdog checks each enabled listener every 30 seconds. For wildcard binds such as `0.0.0.0` it checks loopback; for a specific bind address it checks that address directly. If an enabled listener does not accept connections, the app restarts GOST and records `port_watchdog`.

## Request watchdog

The request watchdog calls the configured health URL through the local proxy. Consecutive failures reaching `failureThreshold` trigger a GOST restart.

## Overnight run

1. Start the service.
2. Confirm LAN proxy access works.
3. Lock the Pixel and keep it charging.
4. Leave it overnight.
5. Run:

```sh
scripts/adb_status.sh
```

Acceptance target:

- The proxy port is still reachable from LAN.
- The service is still foregrounded.
- Any failure has an automatic restart reason and bounded restart count.
- Logs are present and rotated instead of growing without limit.

## Scripted long run

If the Pixel can stay connected to ADB during the test:

```sh
DURATION_SECONDS=28800 INTERVAL_SECONDS=300 PIXEL_IP=<pixel-lan-ip> scripts/stability_monitor.sh
```

The monitor writes a timestamped directory under `reports/` containing:

- `summary.tsv`
- per-sample ADB service/status output
- GOST pid and listener snapshots
- per-sample LAN smoke output when `PIXEL_IP` is available
- `statusUpdatedAt`, `statusUpdatedAtEpochMillis`, Pixel-clock `statusAgeSeconds`, `autoStart`, and `startOnBoot` in the summary, with stale or unreadable status marked as `adb_exit=1`

For a charging overnight check, run this after `scripts/adb_verify_device.sh` passes.

## ADB supervisor

The in-app watchdogs are the primary stability mechanism. If the Pixel remains connected to ADB and you want a last-resort host-side recovery loop, run:

```sh
DURATION_SECONDS=28800 CHECK_INTERVAL_SECONDS=60 PIXEL_IP=<pixel-lan-ip> scripts/adb_supervise.sh
```

The supervisor polls `dumpsys` status, optionally runs LAN smoke checks, treats stale status older than `STATUS_MAX_AGE_SECONDS` (default `120`) as unhealthy, and only calls `adb_start.sh` after consecutive unhealthy samples reach `SUPERVISOR_FAILURE_THRESHOLD` (default `3`). Before recovery it writes a diagnostic bundle under `reports/supervise-*` when `COLLECT_ON_RECOVERY=true`.

## One-shot validation sequence

Before attaching the Pixel, run the host preflight bundle:

```sh
scripts/host_preflight.sh
```

This validates shell syntax, status parser behavior, ADB start guard behavior, LAN smoke strict-assertion behavior, Gradle build/lint/unit tests, APK packaging and stability-critical manifest entries, and GOST provenance, then writes the remaining device checklist under `reports/host-preflight-*`. The APK verification step requires Android `aapt` by default so manifest checks cannot be skipped silently.

With the Pixel connected over ADB:

```sh
scripts/acceptance_check.sh
```

This is the preferred first-device check. It runs APK verification, install/start verification, APK replacement restore verification, GOST fault injection, and LAN smoke, then writes per-step logs under `reports/acceptance-*`.

If a device-side step fails, `acceptance_check.sh` also writes a diagnostics bundle beside the failed step log. Disable that with `COLLECT_DIAGNOSTICS_ON_FAILURE=false` only when you deliberately want a lighter run.

Reboot restore is available as an opt-in acceptance step because it restarts the phone:

```sh
RUN_REBOOT_RESTORE_CHECK=true scripts/acceptance_check.sh
```

The same checks can be run manually:

```sh
scripts/adb_verify_device.sh
scripts/adb_restore_check.sh
scripts/adb_reboot_restore_check.sh
scripts/adb_fault_inject.sh
```

This covers installation, ADB battery/background hints, service start, persisted restore flags, package replacement restore, status polling, `/proc/net/tcp*` listener checks, native GOST provenance, wake-lock reporting, and process-watchdog recovery. It does not prove Google VPN egress because that must be observed from a LAN client through the Pixel proxy.

The restore and fault-injection checks also reject stale runtime status older than `STATUS_MAX_AGE_SECONDS` (default `120`), so a previous healthy dump cannot accidentally prove recovery.

Full reboot restore is a post-unlock `BOOT_COMPLETED` behavior. The app does not use Direct Boot storage, so do not treat locked-before-first-unlock reboot recovery as a supported stability guarantee. `adb_reboot_restore_check.sh` waits for Android boot completion and then polls for a fresh healthy service status while you unlock the Pixel if needed.

If either command fails or reports an unclear state, collect a diagnostic bundle before changing the device:

```sh
scripts/adb_collect_diagnostics.sh
```

Then from the Mac or another LAN client:

```sh
PIXEL_IP=<pixel-lan-ip> scripts/lan_smoke.sh
```

This covers LAN reachability, HTTP/SOCKS5 proxy request success, and public exit IP observation.
Set `EXPECTED_PROXY_IP=<google-vpn-exit-ip>` when you want LAN smoke, acceptance, stability monitor, or supervisor LAN checks to fail automatically if the proxy egress is not the expected Google VPN exit.
Set `REQUIRE_PROXY_DIFF_FROM_DIRECT=true` only when the Mac/client should not share the same public exit as the Pixel proxy.
