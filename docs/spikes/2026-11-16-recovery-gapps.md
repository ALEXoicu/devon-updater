# Spike: recovery installs GApps handed over from Android — RESULT: not viable on devon

Date 2026-11-16, Lineage 24 (20261107), SukiSU kernel.

Device facts:
- No `update_engine_client` binary on this build → app must use `android.os.UpdateEngine` (binder) from a root helper (`app_process` under `su`).
- `uncrypt` present; init services `uncrypt`, `setup-bcb`, `clear-bcb` exist. `/cache` → `/data/cache`.
- Encryption: FBE + metadata encryption (`ro.crypto.metadata.enabled=true`). Virtual A/B on.
- No SD card. `/metadata` = 11 MB. Partitions > 500 MB: only `userdata` and `super` (7.58 GB, group `moto_dynamic_partitions_a`).

What happened (BCB `boot-recovery` + `--update_package=@<block.map>`, written to first 832 bytes of `misc`, rest of misc verified unchanged):
1. Block map in `/cache/recovery` → recovery: "unknown volume path, unable to mount" (recovery can't mount /data → /cache).
2. Block map in `/metadata/devon-updater/` → recovery reads it, then "footer is wrong" → "signature verification failed".
   "footer is wrong" = the zip blocks read from raw `userdata` are ciphertext (metadata encryption). Recovery cannot read
   any package stored on /data. Signature (MindTheGapps is signed with AOSP testkey) would be the next blocker.
3. Recovery always clears BCB and reboots to Android on failure — no harm. Cleanup done; misc command field zeroed.

Conclusion: recovery-based GApps install needs ≥520 MB unencrypted storage; devon has none. Switch to GApps as a
systemless SukiSU module (see design doc). Trusting AOSP testkey in recovery was rejected as a security weakening.
