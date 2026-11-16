# devon-updater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Root Android app + GitHub Actions pipeline that keeps devon on the newest Lineage 24 nightly with the SukiSU/SUSFS/NoMount kernel and latest MindTheGapps, installed to the inactive slot on one tap.

**Architecture:** CI (public repo `devon-updater`) builds a verified custom `boot.img` per nightly and publishes `manifest.json` as a GitHub Release. The app polls releases daily, notifies, and on tap runs `update_engine` (OTA → inactive slot), writes the kernel to `boot_<inactive>`, stages MindTheGapps for recovery via uncrypt/BCB, and reboots on tap.

**Tech Stack:** Bash + Python 3 (CI scripts, pytest), GitHub Actions (ubuntu-24.04, LLVM 22 release toolchain), Kotlin Android app (AGP, Jetpack Compose Material 3, WorkManager, OkHttp, kotlinx.serialization, JUnit), magiskboot, `update_engine_client`, `uncrypt`.

**Spec:** `docs/superpowers/specs/2026-11-16-devon-updater-design.md`

## Global Constraints

- Device `devon`, Lineage `24.x`, A/B + Virtual A/B; never write to the active slot except the explicit "Install custom kernel" (manual path) action.
- Kernel release string must be `4.19.325-cip136-st20-perf-g<12hex>`; `CONFIG_LOCALVERSION="-perf-g<12hex>"`, `# CONFIG_LOCALVERSION_AUTO is not set`, build with `LOCALVERSION=` on the make line.
- Pins: SukiSU `builtin` @ `4bec532d`, NoMount @ `7914458`, SUSFS backport from `JackA1ltman/NonGKI_Kernel_Build_2nd` @ `bbe032e`; KSU version must compute to `30000`.
- No release is published unless `modcheck.py` passes for that nightly's vendor modules.
- Reboot only on user tap. Kernel-write failure after OTA ⇒ `update_engine_client --reset_status`.
- Lineage API: `https://download.lineageos.org/api/v2/devices/devon/builds`.
- Preflight thresholds: battery ≥ 40 % or charging; ≥ 3 GB free on `/data`.
- App check: WorkManager daily, network required. CI schedule: every 6 h + `workflow_dispatch`.
- Release tag `k-<yyyymmdd>`; assets `boot-<yyyymmdd>-sukisu.img`, `manifest.json`.

## Review Focus

- Interrupted install (app killed / reboot mid-OTA) → next app start must detect `update_engine` state and offer resume or reset, never leave a half-written slot marked bootable with stock boot. (Task 6 test `resumesOrResetsAfterInterruptedUpdate`.)
- Download corruption / partial file → sha256 mismatch must abort before `update_engine` is called. (Task 5 test `rejectsShaMismatch`.)
- Manifest for an older/equal build or other device → no offer. (Task 5 tests `noOfferWhenNotNewer`, `noOfferForOtherDevice`.)
- Lineage major version change (24 → 25) → never offer install, show "Major upgrade — install manually". (Task 5 test `blocksMajorUpgrade`.)
- Kernel patch drift: new kernel commit where patches don't apply → CI fails red, no release, app shows "kernel not ready". (Task 3 test via `apply-patches.sh` exit code on a conflicting fixture; Task 5 `noOfferWithoutKernel`.)

---

### Task 1: Spike — recovery installs GApps handed over from Android — DONE: not viable (docs/spikes/2026-11-16-recovery-gapps.md); GApps → module

Throwaway, on the device. Decides the GApps path for Task 6.

**Files:** notes only → `docs/spikes/2026-11-16-recovery-gapps.md`

- [x] **Step 1:** Over `adb shell su -c`, record presence/paths of `update_engine_client`, `uncrypt`, `/system/bin/recovery`, `ctl.start setup-bcb` support, `/cache/recovery` vs `/data/cache/recovery`, `misc` partition by-name.
- [ ] **Step 2:** Push the already-installed `MindTheGapps-17.0.0-arm64-20261022_132445.zip` to `/data/media/0/devon-updater/gapps.zip`; create block map with `uncrypt <zip> <map>`; write recovery command `--update_package=@<map>` (via `setprop ctl.start setup-bcb` with `/cache/recovery/command` or `recovery --update_package` BCB write). Reboot.
- [ ] **Step 3:** Observe at the phone: does recovery start the install unattended? Signature prompt? Auto-reboot to Android? Then verify Android boots, `su -c id` works, Play Services present.
- [ ] **Step 4:** Write findings (exact working command sequence, or failure) to the notes file. If it fails: switch Task 6 GApps step to the spec fallback (same-major addon.d carry-over; major ⇒ manual).

### Task 2: Local tooling + repo

**Files:** Create `~/devon-updater/.gitignore`, `README.md`

- [ ] **Step 1:** Install GitHub CLI (Fedora `dnf install gh`) and Android SDK cmdline-tools to `~/Android/Sdk` (`platform-tools`, `platforms;android-37`, `build-tools;37.0.0`); JDK 21 via sdkman for Gradle.
- [ ] **Step 2:** User runs `! gh auth login`. Verify: `gh auth status` shows logged in.
- [ ] **Step 3:** `git init`, commit spec+plan, `gh repo create devon-updater --public --source . --push`.
  Expected: repo URL printed.

### Task 3: Kernel patch set + local reproducible build script

**Files:**
- Create: `kernel/pins.env`, `kernel/config.fragment`, `kernel/patches/kernel-tree.diff`, `kernel/patches/new-files/**`, `kernel/patches/sukisu-local-fixes.diff`, `kernel/apply-patches.sh`, `kernel/build.sh`, `kernel/repack.sh`, `kernel/modcheck.py`, `kernel/kinfo.py`
- Test: `kernel/tests/test_modcheck.py`, `kernel/tests/test_kinfo.py`, `kernel/tests/fixtures/`

**Interfaces:**
- Produces: `kinfo.py banner <boot.img>` → prints banner; `kinfo.py hash <boot.img>` → prints 12-hex commit; `kinfo.py config <boot.img> <out>` → writes IKCONFIG.
- Produces: `apply-patches.sh <kernel_src>` (exit ≠ 0 on any reject), `build.sh <kernel_src> <hash>` → `out/arch/arm64/boot/Image`, `repack.sh <stock_boot.img> <Image> <out.img>`, `modcheck.py <Module.symvers> <release> <ko_dir>` (exit 0/1, as written today).

- [ ] **Step 1:** Export today's state from `~/kernel-build` (`release/kernel-tree.diff` minus SukiSU/NoMount symlink lines, susfs new files, `sukisu-local-fixes.diff`, `kernel.config` → derive `config.fragment` = KSU/SUSFS/NoMount/LOCALVERSION lines).
- [ ] **Step 2:** Tests: `test_kinfo_hash` on `~/Downloads/boot-24.img` → `"986c913a0783"`; `test_modcheck_detects_bad_vermagic` and `test_modcheck_detects_crc_mismatch` (tiny fixture `.ko` + symvers), `test_modcheck_passes_matching`. Run → FAIL (no modules yet).
- [ ] **Step 3:** Implement `kinfo.py` (banner regex `Linux version (\S+)`, hash = trailing `-g([0-9a-f]{12})`; config via IKCFG_ST/ED gunzip); move `modcheck.py` from scratchpad. Run tests → PASS.
- [ ] **Step 4:** `apply-patches.sh`: `patch -p1 --forward --no-backup-if-mismatch`, fail on any `.rej`; copy new-files; clone SukiSU at pin with full history + `git fetch origin main:main`, apply local fixes, symlink `drivers/kernelsu`; clone NoMount at pin, symlink `fs/nomount`; Kconfig/Makefile lines. Test: on a fresh clone of `lineage-24.0@986c913a0`, exits 0; on a fixture tree with conflicting `fs/exec.c`, exits ≠ 0.
- [ ] **Step 5:** `build.sh`: config = `kinfo.py config` + fragment + LOCALVERSION from hash, `olddefconfig`, assert `kernelrelease` == `4.19.325-cip136-st20-perf-g<hash>`, build `Image`. Run locally for `986c913a0783`; then `modcheck.py` against `~/kernel-build/.../mods/modules` → `0 modules with problems`.
- [ ] **Step 6:** `repack.sh` with magiskboot; verify unpack round-trip (kernel == Image, ramdisk == stock). Commit.

### Task 4: GitHub Actions workflow `kernel.yml`

**Files:** Create `.github/workflows/kernel.yml`, `kernel/ci/find-pending.py`, `kernel/ci/extract-vendor-modules.sh`, `kernel/ci/make-manifest.py`; Test `kernel/tests/test_ci.py`

**Interfaces:**
- Produces: release `k-<date>` with `boot-<date>-sukisu.img`, `manifest.json`:
  `{"device":"devon","date":"20261114","version":"24.0","android":17,"ota_url":str,"ota_sha256":str,"ota_size":int,"kernel_hash":str,"boot_url":str,"boot_sha256":str,"sukisu":30000,"susfs":"v2.4.0","nomount":"v2.3.0"}`.
- Consumes: Task 3 scripts.

- [ ] **Step 1:** Tests: `find-pending.py` given fixture API JSON + existing tags `["k-20261107"]` → `["20261114"]`; skips non-`24.` versions; `make-manifest.py` output validates the schema above with Android version mapped `24.x → 17`.
- [ ] **Step 2:** Implement both. Run tests → PASS.
- [ ] **Step 3:** `extract-vendor-modules.sh <ota.zip> <outdir>`: unzip `payload.bin`, extract `vendor` with `payload-dumper-go` (pinned release), pull `lib/modules/*.ko` via `fsck.erofs --extract` / `debugfs` depending on fs type.
- [ ] **Step 4:** Workflow: schedule `0 */6 * * *` + dispatch(input `date` optional); job matrix over pending dates; cache LLVM 22 + kernel git; steps = download boot+OTA (verify API sha256) → apply → build → extract modules → modcheck → repack → `gh release create`.
- [ ] **Step 5:** Push; `gh workflow run kernel.yml -f date=20261114`; `gh run watch`. Expected: green, release `k-20261114` with both assets; download and `kinfo.py banner` shows `-g<new hash>` and `(…@…)` our builder.

### Task 5: App core logic (pure Kotlin, unit-tested)

**Files:** Create `app/` Gradle project (`com.devon.updater`), `core/Version.kt`, `core/Manifest.kt`, `core/UpdateDecision.kt`, `core/PayloadZip.kt`, `core/Sha256.kt`; tests in `app/src/test/.../core/`.

**Interfaces:**
- `data class Installed(val version: String, val date: String, val android: Int, val device: String)`; `fun parseLineageVersion(prop: String): Installed` (input like `24.0-20261107-NIGHTLY-devon`).
- `@Serializable data class Manifest(...)` matching Task 4 schema.
- `sealed interface Decision { object None; data class Offer(m: Manifest); data class KernelOnly(m: Manifest); object NoKernelYet; data class MajorUpgrade(version: String) }`; `fun decide(installed: Installed, latestLineageDate: String, manifest: Manifest?, runningOurKernel: Boolean): Decision`.
- `data class Payload(val offset: Long, val size: Long, val headers: List<String>)`; `fun findPayload(zip: File): Payload` (stored entry `payload.bin` data offset + `payload_properties.txt` lines).
- `fun sha256(file: File): String`.

- [ ] **Step 1:** Tests: `parsesNightlyProp`, `noOfferWhenNotNewer`, `noOfferForOtherDevice`, `noOfferWithoutKernel` (newer Lineage date but manifest older → `NoKernelYet`), `offerWhenManifestNewer`, `kernelOnlyWhenSameDateAndStockKernel`, `findsPayloadOffsetInRealOta` (uses `~/Downloads/lineage-24.0-20261107-nightly-devon-signed.zip`, skipped if absent; offset+size within file, headers contain `FILE_HASH=`), `blocksMajorUpgrade` (installed 24.0, manifest 25.0 → `Decision.MajorUpgrade`), `rejectsShaMismatch`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL. Implement. → PASS. Commit.

### Task 6: Device operations via root

**Files:** Create `device/Root.kt` (su shell), `device/UpdateEngine.kt`, `device/Slots.kt`, `device/BootWriter.kt`, `device/Preflight.kt`, `helper/` (root helper dex run via `app_process` under `su`, calling `android.os.UpdateEngine` — no `update_engine_client` on this build); tests `app/src/test/.../device/` with a fake `Shell`.

**Interfaces:**
- `interface Shell { fun run(cmd: String): Result /* exit, stdout, stderr */ }`; `class SuShell : Shell`.
- `Slots(shell).current(): String /* "_a"|"_b" */`, `inactive(): String`.
- `UpdateEngine(shell).apply(p: Payload, zip: File, onProgress: (Float)->Unit): Status`, `status(): EngineState`, `reset()` — implemented by the root helper (`app_process -cp <apk> com.devon.updater.helper.EngineMain apply|status|reset …`, JSON lines on stdout).
- `BootWriter(shell).write(img: File, slot: String)` — dd to `/dev/block/by-name/boot<slot>`, read back exactly `img.length()` bytes, sha256 compare, throw on mismatch.
- `Preflight(ctx).check(): List<Problem>`.

- [ ] **Step 1:** Tests with fake shell: `inactiveIsOppositeOfCurrent`, `bootWriterRefusesActiveSlotUnlessExplicit`, `bootWriterThrowsOnReadbackMismatch`, `updateEngineParsesProgressLines`, `resetsWhenKernelWriteFails` (orchestrator calls `reset()`), `resumesOrResetsAfterInterruptedUpdate` (engine state `UPDATED_NEED_REBOOT` but no kernel-written marker ⇒ rewrite kernel or reset).
- [ ] **Step 2:** Implement; tests PASS. Then on device, read-only smoke: `Slots.current()` == `_a`, `UpdateEngine.status()` == IDLE. Commit.

### Task 7: Orchestrator, background check, notification, UI

**Files:** Create `Installer.kt` (foreground service running steps: preflight → download+verify OTA/boot/gapps → engine apply → boot write → engine apply → boot write → "ready"), `CheckWorker.kt`, `Notifications.kt`, `ui/MainScreen.kt`, `ui/Settings.kt`, `AndroidManifest.xml`.

- [ ] **Step 1:** `CheckWorker`: daily periodic, `NetworkType.CONNECTED`; reads `ro.lineage.version`, Lineage API, `releases/latest/download/manifest.json`; posts notification "Lineage <date> + kernel ready" with tap → MainScreen.
- [ ] **Step 2:** MainScreen per spec sketch; Install button runs `Installer`; progress per step; final "Reboot now" button (`su -c svc power reboot`). Post-boot check on start: banner contains `devon-updater@ci` (our builder id), `su` works, `com.google.android.gms` present → record success.
- [ ] **Step 3:** Settings: "Disable Lineage updater" (`pm disable-user --user 0 org.lineageos.updater`) enabled only after a recorded success; "Re-enable".
- [ ] **Step 4:** `./gradlew assembleRelease` (local keystore in `~/.android/devon-updater.jks`, not committed); `adb install`. Grant root in SukiSU manager. Commit.

### Task 8: End-to-end on device + disable stock OTA

- [ ] **Step 1:** Ensure `k-20261114` release exists (Task 4). In app: Check now → offer shown. Install → watch each step; tap Reboot.
- [ ] **Step 2:** Verify after boot: `ro.lineage.version` contains `20261114`, slot switched to `_b`, kernel banner `-g<new hash>` built by CI, `su -c id` → uid 0, 62 modules, Wi-Fi/audio OK, Play Services present, SukiSU modules (NoMount, BRENE) active.
- [ ] **Step 3:** In app Settings: Disable Lineage updater; verify `pm list packages -d | grep org.lineageos.updater`.
- [ ] **Step 4:** Update README with usage + recovery steps (flash previous slot / stock boot). Commit, push.
