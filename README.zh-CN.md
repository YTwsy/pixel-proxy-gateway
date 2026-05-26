# Pixel Proxy Gateway

[English](README.md) | **中文**

## 概览

Pixel Proxy Gateway 是一个单 APK、侧载安装的 Android 应用，用来把运行 VPN 的 Android 手机变成一个受监控的局域网 HTTP/SOCKS 代理出口，例如运行 Pixel 自带的 Google VPN，或 Clash Meta 等手机侧 VPN。

本应用有意不实现 Android `VpnService`，这样手机侧 VPN 可以继续占用系统 VPN 槽位。Pixel Proxy Gateway 作为一个普通 Android 应用运行：它在手机上监听代理端口，并从手机上的应用进程发起出站连接。

非专业人员只需要阅读经典使用场景，从右侧release下载apk安装即可，本应用界面易于使用

## 为什么做这个项目

- **基于 GOST：** 代理引擎是固定版本、从源码构建的 GOST 二进制，并随 APK 打包，启动前会做校验。
- **面向长期稳定运行：** 应用内置前台服务、wake lock、进程监管、端口 watchdog、请求 watchdog、日志轮转、开机/升级恢复和 ADB 诊断。
- **稳定的 Every Proxy 替代方案：** 对于常驻局域网代理网关场景，本项目目标是比 Every Proxy 这类轻量代理共享应用更稳定、更可诊断，同时保持开源、免费、无广告。
- **可观测、可排障：** 状态、日志、健康检查、监听器状态和恢复行为都可以查看，而不是被藏在一个很轻量但不透明的界面后面。
- 为了能在不支持地区使用先进但容易封号的AI服务，如Claude，GPT等。不少机场节点容易被封号或是降智。但是我发现Google Pixel手机内置Google VPN可以提供纯净的Google LLC节点，可以帮助你全速，安全的使用AI服务。

## 典型使用场景

一个经典拓扑是：让 Android 手机作为上游 VPN 出口，其他设备可以直接把代理指向这台手机。如果需要实现规则分流，可以让一台电脑作为局域网内的分流节点，例如使用 `mihomo` 规则或 PAC，其他设备再把代理或 PAC 指向这台电脑。

**手机 A，通常是 Pixel 或其他 Android 手机：**

- 手机侧 VPN 已开启，例如 Clash Meta，或支持机型上的 Google VPN。
- Pixel Proxy Gateway 打开 HTTP 和 SOCKS 端口，供局域网设备访问。

**电脑 B：**

- `mihomo` 开启 `mixed-port` 和 `allow-lan`。
- 把手机 A 上的 Pixel Proxy Gateway 配成一个 outbound proxy，例如 `phoneA`。
- 规则里让国外或指定域名走 `phoneA`。
- 国内、局域网和 private 地址保持 `DIRECT`。
- 如果客户端更适合自动代理，也可以使用 PAC。

**其他设备：**

- 代理或 PAC 可以指向电脑 B。
- 在更简单的场景中，也可以直接指向手机 A 上的 Pixel Proxy Gateway 端口。

> 在支持的 Google Pixel 机型和账号环境下，可以使用随 Pixel 提供的 Google VPN 作为手机侧上游路径。初次启用或重新验证时，网络需要能完成 Google 服务验证；移动数据、eSIM，或者另一条已经可信的网络路径，通常更容易完成这一步握手。之后，本项目只负责暴露手机应用层的代理端口，并不替代或实现 VPN 本身。

## 相关需求和搜索关键词

Pixel Proxy Gateway 适合这些需求：Android HTTP 代理、Android SOCKS5 代理、手机代理网关、Android VPN 转局域网代理、Pixel Google VPN 代理、Clash Meta 代理共享、GOST Android 代理、Every Proxy 替代方案、开源无广告 Android 代理、mihomo 上游代理、局域网代理共享、PAC 代理网关、手机 VPN 转局域网代理、可监管的移动代理出口。

## 默认配置

| 项目 | 值 |
| --- | --- |
| 包名 | `com.wsy.pixelproxygateway` |
| HTTP | `0.0.0.0:8080` |
| SOCKS5 | `0.0.0.0:1080` |
| 认证 | 支持，默认关闭 |
| 健康检查 | `https://connectivitycheck.gstatic.com/generate_204` |
| GOST 标签 | `v3.2.6` |
| GOST 提交 | `340ba32ef0bebc7293908007cc423dd5f33dd88c` |

## 稳定性 V1

第一个可用版本已经直接包含稳定性增强层：

- 前台服务，包含 `specialUse` 声明和持续通知。
- 代理启用期间保持 wake lock。
- GOST 进程监管，支持自动重启和退避。
- HTTP/SOCKS 监听端口 watchdog。
- 通过本地代理发起请求检查，并按连续失败阈值判断异常。
- 运行状态带新鲜度时间戳，ADB 工具会按 Pixel 设备时钟检查状态是否过期。
- 应用日志和 GOST 日志自动轮转。
- 支持崩溃后、解锁后开机、APK 覆盖安装后的配置恢复。
- 支持通过 `dumpsys` 和 content provider 做 ADB 控制与状态导出。

## 构建 GOST

APK 期望把固定版本的 GOST 可执行文件打包成可提取的 native library：

```text
app/src/main/jniLibs/arm64-v8a/libgost.so
```

从源码构建：

```sh
tools/build-gost-android-arm64.sh
```

首次拉取源码时需要 `go` 和网络访问。脚本会写入：

- `app/src/main/jniLibs/arm64-v8a/libgost.so`
- `app/src/main/assets/gost/android-arm64/gost.sha256`
- `app/src/main/assets/gost/android-arm64/gost.tag`
- `app/src/main/assets/gost/android-arm64/gost.commit`

构建脚本同时固定 stable tag 和预期 commit。如果 `v3.2.6` 将来解析到不同提交，脚本会拒绝构建。

在不重新构建的情况下校验当前本地源码、APK 打包前的二进制和元数据：

```sh
scripts/verify_gost_provenance.sh
```

应用会从 Android 的 `nativeLibraryDir` 执行 GOST，而不是从可写 app data 目录执行。这对 Android 10+ 很重要，因为 target API 29+ 的应用不能直接执行自身可写目录里的文件。启动 native binary 前，应用会先校验 `gost.sha256`。

## 构建 APK

构建 debug APK：

```sh
./gradlew :app:assembleDebug
```

当前 debug APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

校验 APK 打包内容和内置 GOST 来源：

```sh
scripts/verify_apk.sh
```

`verify_apk.sh` 默认需要 Android `aapt`，这样才能真正检查 APK package 元数据和稳定性关键的 manifest 条目。只有在你明确只想做轻量级 asset/provenance 检查时，才设置 `ALLOW_MISSING_AAPT=true`。

连接 Pixel 前，先运行完整的主机侧预检：

```sh
scripts/host_preflight.sh
```

该命令会写入 `reports/host-preflight-*`，内容包括 shell 语法检查、状态解析器自测、ADB 启动防护自测、LAN smoke 严格断言自测、Gradle build/lint/unit-test、APK 打包与稳定性关键 manifest 检查、GOST 来源校验、ADB 设备状态，以及简短的设备验证清单。

## 发布 APK

GitHub Actions 会在推送 `v*` tag 时构建已签名 APK，并把 APK 和 SHA256 文件上传到 GitHub Release。

首次发布前，在本机生成长期保存的 release 签名 key：

```sh
keytool -genkeypair -v \
  -keystore release.jks \
  -alias pixel-proxy-gateway \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

不要提交 `release.jks`。把它转成一行 base64 后，填到 GitHub 仓库的 `Settings` -> `Secrets and variables` -> `Actions`：

```sh
base64 -i release.jks | tr -d '\n' | pbcopy
```

需要添加这些 repository secrets：

- `ANDROID_KEYSTORE_BASE64`: 上面复制的 base64 内容
- `ANDROID_KEYSTORE_PASSWORD`: keystore 密码
- `ANDROID_KEY_ALIAS`: 例如 `pixel-proxy-gateway`
- `ANDROID_KEY_PASSWORD`: key 密码；如果和 keystore 密码相同，可以留空

发布新版本时，先确认 `app/build.gradle` 里的 `versionName` 和 `versionCode` 已更新，然后推送 tag：

```sh
git tag -a v1.0 -m "Pixel Proxy Gateway v1.0"
git push origin v1.0
```

Actions 完成后，Release 页面会出现：

```text
pixel-proxy-gateway-v1.0.apk
pixel-proxy-gateway-v1.0.apk.sha256
```

## 安装与控制

安装并启动：

```sh
scripts/adb_install_debug.sh
scripts/adb_bootstrap.sh
scripts/adb_start.sh
```

连接 Pixel 后执行一次性设备侧验证：

```sh
scripts/adb_verify_device.sh
```

端到端验收检查：

```sh
scripts/acceptance_check.sh
```

杀掉 GOST 子进程，验证应用内重启恢复：

```sh
scripts/adb_fault_inject.sh
```

通过 ADB 覆盖端口：

```sh
HTTP_PORT=18080 SOCKS_PORT=11080 BIND_ADDRESS=0.0.0.0 scripts/adb_start.sh
```

通过 ADB 启用认证：

```sh
AUTH_ENABLED=true USERNAME=myuser PASSWORD=mypass scripts/adb_start.sh
```

查看状态：

```sh
scripts/adb_status.sh
```

如果设备侧检查失败，收集完整诊断包：

```sh
scripts/adb_collect_diagnostics.sh
```

从 Mac 或其他局域网客户端执行 LAN/proxy 出口 smoke test：

```sh
PIXEL_IP=<pixel-lan-ip> scripts/lan_smoke.sh
```

如果已知预期的 Google VPN 出口 IP，可以让出口验证变成严格断言：

```sh
EXPECTED_PROXY_IP=<google-vpn-exit-ip> PIXEL_IP=<pixel-lan-ip> scripts/lan_smoke.sh
```

当 Mac 不应与代理使用同一出口时，可以设置 `REQUIRE_PROXY_DIFF_FROM_DIRECT=true`；如果代理公网 IP 与直连公网 IP 相同，smoke test 会失败。

停止或重启：

```sh
scripts/adb_stop.sh
scripts/adb_restart.sh
```

## 真机验证

Google VPN 路由和过夜稳定性必须在 Pixel 真机上验证：

1. 运行 `scripts/host_preflight.sh`。
2. 连接 Pixel，允许 USB 调试，然后运行 `scripts/acceptance_check.sh`。
3. 如果脚本提示电池优化风险，打开 Android 电池设置，将 Pixel Proxy Gateway 设为 Unrestricted。
4. 确认 LAN smoke 步骤报告的代理公网 IP 与预期 Google VPN 出口一致；也可以设置 `EXPECTED_PROXY_IP=<google-vpn-exit-ip>`，让不匹配时自动失败。
5. 从局域网客户端使用 `http://<pixel-ip>:8080` 或 `socks5h://<pixel-ip>:1080`。
6. 锁屏并保持手机整夜充电。
7. 运行 `scripts/adb_status.sh`，检查重启次数、失败记录和日志。
8. 如有不清楚的地方，运行 `scripts/adb_collect_diagnostics.sh`，检查生成的 `reports/diagnostics-*` 目录。

`acceptance_check.sh` 会把 APK 打包验证、ADB 安装/启动验证、GOST 进程故障注入和 LAN smoke 写入一个带时间戳的 `reports/acceptance-*` 报告。设置 `RUN_LAN_SMOKE=false` 可以在 USB-only 检查中跳过 LAN 出口验证；设置 `RUN_SUPERVISOR_SMOKE=true` 可以加入短时间 ADB supervisor smoke run；设置 `RUN_REBOOT_RESTORE_CHECK=true` 可以加入需要显式启用的手机重启恢复检查。

如果设备侧验收步骤失败，脚本会自动在对应步骤日志旁写入诊断包，除非设置了 `COLLECT_DIAGNOSTICS_ON_FAILURE=false`。

验收流程还会重新安装一次 APK，用来验证 `MY_PACKAGE_REPLACED` 后能从持久化配置恢复。设置 `RUN_RESTORE_CHECK=false` 可以跳过该升级恢复检查。完整设备重启恢复被有意视为解锁后的 `BOOT_COMPLETED` 路径，因为应用配置存放在普通 credential-protected app storage 中，而不是 Direct Boot storage。需要显式验证该路径时，运行 `scripts/adb_reboot_restore_check.sh`。

## 过夜运行

Pixel 保持 ADB 连接时，可以使用脚本化过夜运行：

```sh
DURATION_SECONDS=28800 INTERVAL_SECONDS=300 PIXEL_IP=<pixel-ip> scripts/stability_monitor.sh
```

该命令会在 `reports/` 下写入带时间戳的采样结果，包括 ADB 状态、监听器状态、GOST pid，以及可选 LAN smoke 结果。摘要包含 `statusAgeSeconds`；状态过期或不可读的样本会标记为 `adb_exit=1`。

如果 Pixel 保持 ADB 连接，并且希望服务停止报告健康状态时有最后兜底恢复层：

```sh
DURATION_SECONDS=28800 CHECK_INTERVAL_SECONDS=60 PIXEL_IP=<pixel-ip> scripts/adb_supervise.sh
```

应用本身可以从进程、端口和请求失败中自恢复。`adb_supervise.sh` 刻意放在应用外部，应被视为最后兜底恢复层，用来处理 Android 服务状态、后台策略、Google VPN/radio 状态等应用内 GOST watchdog 无法自行修复的问题。supervisor 在计数恢复前，也会把超过 `STATUS_MAX_AGE_SECONDS`（默认 `120`）的过期状态视为不健康。
