# devon-updater — design

Date: 2026-11-16 · Device: Moto G32 (`devon`), LineageOS 24 (Android 17), A/B + Virtual A/B
Root: SukiSU-Nova (builtin) + SUSFS v2.4.0 + NoMount, custom kernel built in `~/kernel-build`

## Intent

Stay on Lineage nightlies without losing root/hiding. Today an OTA replaces `boot` with the stock
kernel, which drops SukiSU/SUSFS/NoMount, and the custom kernel must be rebuilt for every nightly
because vendor modules require an exact version string + symbol CRCs. The app makes "new nightly"
a one-tap update that lands with root intact, and replaces the stock Lineage updater.

## Goals

- Daily background check; notify when a newer nightly **and** its matching custom kernel are ready.
- One tap: download OTA → install to inactive slot → install matching custom kernel to inactive slot →
  ask to reboot.
- Kernel builds happen in GitHub Actions, automatically, for every new devon nightly.
- Once the app is proven, disable `org.lineageos.updater`.

## Non-goals

- Building anything on the phone.
- Supporting other devices, other ROMs, or LKM/GKI kernels.
- Auto-tracking the newest SukiSU / SUSFS / NoMount upstream (pinned; bumped deliberately — upstream
  renamed SukiSU and broke 4.19 builds three ways on the day this was written).

## Anti-goals (failure even if it "works")

- Booting a kernel whose modules don't match → no Wi-Fi/audio/touch. **CI must prove module
  compatibility before publishing.**
- Ever touching the active slot. The running slot is the fallback; A/B auto-rollback must stay intact.
- Rebooting without the user's tap.
- Shipping an OTA with the stock kernel (i.e. root lost) because the kernel step failed after the OTA step.

## Constraints (verified today)

- Lineage API `https://download.lineageos.org/api/v2/devices/devon/builds` lists each build with
  separate `boot.img` and full signed OTA zip (~1.1 GB). Newest now: 20261114 (installed: 20261107).
- Kernel source: `LineageOS/android_kernel_motorola_sm6225`, branch `lineage-24.0`. Nightly's kernel
  commit = the `-g<12hex>` suffix in its `boot.img` kernel banner (e.g. `-g986c913a0783`).
- Vendor modules (~62 loaded) use `MODVERSIONS`; vermagic must equal
  `4.19.325-cip136-st20-perf-g<hash>`. We pin `CONFIG_LOCALVERSION="-perf-g<hash>"`, no auto version.
- Kernel needs: clang/LLD 22 with ThinLTO + CFI; host OpenSSL 3.5 `extract-cert.c` fix.
- `boot` is a raw-kernel v3 header image (gzip ramdisk); repacked with `magiskboot`, swapping only kernel.
- Lineage OTAs are full payloads (no incremental), applied by `update_engine`; `boot` is a real
  partition outside `super`, so writing `boot_<inactive>` after `update_engine` finishes is safe
  (same approach Magisk uses for "install to inactive slot").
- GApps: MindTheGapps installs an `addon.d` script; Lineage's A/B postinstall (backuptool) carries
  GApps into the new slot **for the same Android version**. Modifying the inactive slot's `system`
  directly under Virtual A/B (snapshot/COW) is not reasonably possible from Android.
- Phone RAM 4 GB — no on-device builds.

## Approaches considered

1. **PC build server** (app asks this PC over LAN). Reuses today's setup; updates only when PC is on.
2. **GitHub Actions** — *chosen by user.* Works anywhere; one-time GitHub setup.
3. **On-device build** — rejected: toolchain/source ~5 GB, LTO link exceeds 4 GB RAM.

Trigger: *user chose* "check automatically and notify, tap to install" (over fully manual or fully automatic).

## Design

### Part 1 — GitHub repo `devon-updater` (public; my call — see "Calls to check")

```
kernel/
  patches/kernel-tree.diff        # today's tracked-file diff (SUSFS 2.4.0 port, hooks, extract-cert fix)
  patches/new-files/              # fs/susfs.c, include/linux/susfs*.h
  patches/sukisu-local-fixes.diff # static_call guard, ksu_access_ok, unused-var guards
  pins.env                        # SUKISU_COMMIT=4bec532d…, NOMOUNT_COMMIT=7914458…, SUSFS_SRC=nongki2@bbe032e
  config.fragment                 # KSU/SUSFS/NoMount options
  modcheck.py                     # vendor module vermagic + CRC gate
.github/workflows/kernel.yml
app/                              # Android app (built locally; CI build optional later)
docs/
```

**Workflow `kernel.yml`** — runs on schedule (every 6 h) and manual dispatch:
1. Read Lineage API; for each `24.x` nightly with no matching release tag `k-<date>`:
2. Download that build's `boot.img`; read kernel banner → `<hash>`.
3. Checkout kernel `lineage-24.0` at `<hash>` (fail clearly if the hash isn't on the branch).
4. Apply patches; link SukiSU (pinned, full history + `main` ref for version 30000) and NoMount (pinned).
5. Config = kernel config extracted from that `boot.img` (IKCONFIG) + `config.fragment` +
   `LOCALVERSION="-perf-g<hash>"`.
6. Build with LLVM 22 release toolchain (`LLVM=1 LLVM_IAS=1`).
7. **Gate:** extract vendor modules from the OTA's payload (`vendor` image → `/lib/modules`),
   run `modcheck.py`; also assert banner/kernelrelease string. Any failure → no release, job red.
8. Repack the nightly's own `boot.img` with the new kernel (`magiskboot`).
9. Publish GitHub Release `k-<date>` with `boot-<date>-sukisu.img`, `sha256`, and `manifest.json`
   (`{date, version, ota_url, ota_sha256, ota_size, kernel_hash, boot_sha256, sukisu, susfs, nomount}`).

Patch conflicts against a new kernel commit make step 4 fail → no release → app never offers that
nightly. I fix the patches by hand when that happens.

### Part 2 — Android app (`app/`, Kotlin, minSdk = targetSdk = 37/Android 17, built locally)

- **Check (WorkManager, daily, network-required):** read current `ro.lineage.build.version` /
  build date; fetch `releases/latest` of the repo → `manifest.json`. If `manifest.date > installed`
  and `manifest.version` major == installed major → notification "Lineage <date> + kernel ready".
  If the major differs → notification "Major upgrade — manual install needed" (no Install button).
- **Install (foreground service, user tap):**
  1. Preflight: root via `su` granted; battery ≥ 40 % or charging; ≥ 3 GB free; not mid-update
     (`update_engine` idle).
  2. Download OTA zip → verify sha256 from manifest (resumable). Download kernel boot.img → verify.
  3. Parse zip for `payload.bin` offset/size + `payload_properties.txt`; apply with
     `update_engine_client --update --payload=file://… --offset --size --headers=…` via `su`,
     streaming progress. Wait for `UPDATED_NEED_REBOOT`.
  4. Write kernel: `dd` boot image to `/dev/block/by-name/boot_<inactive>`, read back, compare sha256.
     If anything in step 4 fails → cancel the pending update (`update_engine_client --reset_status`)
     so the phone doesn't boot a root-less slot.
  5. Show "Ready — reboot now?"; reboot only on tap.
- **After boot:** on next app start, record success (new slot booted, `su` works, kernel banner has
  our build). Status screen shows current build, kernel, SukiSU/SUSFS versions, last check, last result.
- **Re-apply kernel (manual path):** if the running build has a stock kernel but a release exists
  for it (`manifest.date == installed`), offer "Install custom kernel": verify, `dd` to the *current*
  slot's `boot`, read back + compare, then offer reboot. Used after a manual sideload.
- **Settings:** "Disable Lineage updater" button (`pm disable-user --user 0 org.lineageos.updater`,
  with re-enable) — enabled only after one successful app update.
- UI: single screen, plain Material 3:

```
 devon-updater
 Installed   24.0-20261107   kernel SukiSU 30000 · SUSFS 2.4.0
 Available   24.0-20261114   kernel build ✓ (modules verified)
 [ Install update ]
 Last check: today 09:12
 ─────────────
 ⚙ Lineage updater: enabled   [Disable]
```

### GApps — addon.d carry-over only; major upgrades blocked (user decision after spike)

Recovery route ruled out (docs/spikes/2026-11-16-recovery-gapps.md). User chose: keep GApps via MindTheGapps' existing
`addon.d` script, which Lineage's OTA postinstall (backuptool) restores into the new slot for the same Android version.
- Same Lineage major (e.g. 24.x → 24.x): normal install; after reboot the app checks `com.google.android.gms` and
  `com.android.vending` are present and warns if not.
- Different Lineage major (e.g. 24 → 25): **no install offered**; notification/screen says "Major upgrade — install
  manually". The app does nothing else for that build (not even the kernel-only path).
- App does not download or install MindTheGapps.

## Calls to check (mine, not yours yet)

1. GApps: addon.d carry-over only, major upgrades blocked (user decision).
2. Repo is **public** (kernel is GPL; avoids putting a GitHub token on the phone).
3. SukiSU/SUSFS/NoMount **pinned**; upgrading them is a deliberate change by me, not automatic.
4. App language Kotlin, check schedule daily, CI schedule every 6 h.

## Left to the builder

Toolchain download/caching in CI, payload extraction tool choice, app package name/icon,
notification channel names, exact retry/backoff.
