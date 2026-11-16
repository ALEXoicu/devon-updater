# devon-updater

Keeps a Moto G32 (`devon`) on the newest LineageOS 24 nightly **without losing root**:
each nightly gets a matching SukiSU + SUSFS + NoMount kernel built by GitHub Actions, and an Android app
installs nightly + kernel to the inactive slot with one tap.

## Download

- **App APK:** release tag [`app-v1.0`](https://github.com/ALEXoicu/devon-updater/releases/tag/app-v1.0) → `devon-updater-1.0.apk`.
  (App releases are tagged `app-v*` and never marked "latest".)
- **Kernels:** releases tagged `k-<yyyymmdd>` (one per Lineage nightly), each with `boot-<date>-sukisu.img` and
  `manifest.json`. The newest kernel release is always the one marked **Latest** — the app reads
  `releases/latest/download/manifest.json`, so don't mark any other release as latest.

## Pieces

- `kernel/` — patch set, pins and scripts. `apply-patches.sh` → `build.sh` → `ci/extract-vendor-modules.sh` +
  `modcheck.py` (vendor modules must load: vermagic + CRCs) → `repack.sh`. Tests: `cd kernel && python3 -m unittest discover -s tests`.
- `.github/workflows/kernel.yml` — every 6 h: newest unreleased 24.x nightly → release `k-<date>` with
  `boot-<date>-sukisu.img` + `manifest.json`. No release if patches don't apply or modules don't match.
- `android/` — the app (`com.devon.updater`). Build: `cd android && ./gradlew :app:assembleRelease -PmanifestUrl=<url>`.

## Using the app

1. Install the APK, open it once, then allow **devon-updater** in SukiSU manager → Superuser.
2. The app checks daily and notifies "Update ready". Open it → **Install update**:
   download kernel → download OTA (~1.1 GB) → `update_engine` writes the inactive slot → kernel written to
   `boot_<inactive>` and read back → **Reboot now** (only when you tap).
3. After the reboot, open the app: it confirms root (and Google Play services) and records a success.
   After one success, Settings → **Disable** turns off the stock Lineage updater (Re-enable is always available).

Rules it follows: never writes the active slot; if the kernel can't be written after the OTA, the OTA is
cancelled (`resetStatus`) so you never boot a slot without root; a different Lineage major version
(e.g. 24 → 25) is never installed — it only tells you to upgrade manually. GApps carry over via
MindTheGapps' addon.d (same Android version only, which is why majors are blocked).

## If something goes wrong

- New slot doesn't boot: A/B falls back to the old slot automatically after failed boots. Or in fastboot:
  `fastboot set_active a` (or `b`, whichever was the old one).
- New slot boots without root: `fastboot flash boot_<slot> boot-<date>-sukisu.img` from the matching release.
- Stock kernel back: flash the nightly's own `boot.img` from download.lineageos.org.

## Why not GApps through recovery

Tested 2026-11-16 (`docs/spikes/`): recovery can't read anything stored on metadata-encrypted `/data`
("footer is wrong"), and devon has no unencrypted space big enough for the GApps zip.
