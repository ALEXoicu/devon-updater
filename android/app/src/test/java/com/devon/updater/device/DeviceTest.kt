package com.devon.updater.device

import com.devon.updater.Downloads
import com.devon.updater.InstallFailed
import com.devon.updater.Installer
import com.devon.updater.PendingStore
import com.devon.updater.core.Manifest
import com.devon.updater.core.PendingInstall
import com.devon.updater.core.RecoveryAction
import com.devon.updater.core.recoveryAction
import com.devon.updater.core.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeShell(var slot: String = "_a", var readbackSha: String? = null) : Shell {
    val log = mutableListOf<String>()
    override fun run(cmd: String): ShellResult {
        log += cmd
        return when {
            cmd == "getprop ro.boot.slot_suffix" -> ShellResult(0, "$slot\n", "")
            cmd.startsWith("blockdev --getsize64") -> ShellResult(0, "100663296\n", "")
            cmd.startsWith("head -c") -> ShellResult(0, "${readbackSha ?: "0".repeat(64)}  -\n", "")
            else -> ShellResult(0, "", "")
        }
    }
    override fun spawn(cmd: String): Process = throw UnsupportedOperationException()
}

class FakeEngine(var statusValue: Int = 0, var applyResult: Int = 0) : Engine {
    var resets = 0
    var applied = false
    override fun status() = statusValue
    override fun apply(zipPath: String, onStatus: (Int, Float) -> Unit): Int {
        applied = true; onStatus(3, 0.5f); if (applyResult == 0) statusValue = 6; return applyResult
    }
    override fun watch(onStatus: (Int, Float) -> Unit): Int { statusValue = 6; return 0 }
    override fun reset() { resets++; statusValue = 0 }
}

class MemStore(override var pending: PendingInstall? = null) : PendingStore

class DeviceTest {
    private val img = File.createTempFile("boot", ".img").apply { writeBytes(ByteArray(4096) { 7 }) }
    private val imgSha = sha256(img)

    private fun manifest() = Manifest("devon", "20261114", "24.0", 17, "https://x/ota.zip", "aa", 10, "1da2d0861244",
        "https://x/boot.img", imgSha, 30000, "v2.4.0", "v2.3.0")

    private fun downloads() = object : Downloads {
        override fun toFile(url: String, dest: File, sha256: String, onProgress: (Long, Long) -> Unit) { img.copyTo(dest, true) }
        override fun toRootFile(url: String, path: String, size: Long, sha256: String, onProgress: (Long, Long) -> Unit) {}
    }

    private fun installer(shell: FakeShell, engine: FakeEngine, store: MemStore, problems: List<String> = emptyList()): Installer {
        val slots = Slots(shell)
        val work = kotlin.io.path.createTempDirectory().toFile()
        return Installer(shell, engine, slots, BootWriter(shell, slots), downloads(), store, work, { problems }, { _, _, _ -> })
    }

    @Test fun inactiveIsOppositeOfCurrent() {
        assertEquals("_b", Slots(FakeShell("_a")).inactive())
        assertEquals("_a", Slots(FakeShell("_b")).inactive())
    }

    @Test fun bootWriterRefusesActiveSlotUnlessExplicit() {
        val sh = FakeShell("_a", readbackSha = imgSha)
        val w = BootWriter(sh, Slots(sh))
        assertThrows(BootWriteException::class.java) { w.write(img, imgSha, "_a") }
        assertFalse(sh.log.any { it.startsWith("dd ") })
        w.write(img, imgSha, "_a", allowActiveSlot = true)
        w.write(img, imgSha, "_b")
        assertTrue(sh.log.any { it.contains("of=/dev/block/by-name/boot_b") })
        val setrw = sh.log.indexOfLast { it == "blockdev --setrw /dev/block/by-name/boot_b" }
        val dd = sh.log.indexOfLast { it.startsWith("dd ") }
        assertTrue("setrw must come right before dd", setrw >= 0 && setrw == dd - 1)
    }

    @Test fun bootWriterThrowsOnReadbackMismatch() {
        val sh = FakeShell("_a", readbackSha = "f".repeat(64))
        assertThrows(BootWriteException::class.java) { BootWriter(sh, Slots(sh)).write(img, imgSha, "_b") }
    }

    @Test fun updateEngineParsesProgressLines() {
        assertEquals(EngineEvent.Status(3, 0.25f), parseEngineLine("status 3 0.25"))
        assertEquals(EngineEvent.Payload(1234, 99), parseEngineLine("payload 1234 99"))
        assertEquals(EngineEvent.Done(0), parseEngineLine("done 0"))
        assertEquals(EngineEvent.Error("SecurityException: nope"), parseEngineLine("error SecurityException: nope"))
        assertNull(parseEngineLine("WARNING: linker noise"))
    }

    @Test fun installWritesKernelToInactiveSlot() {
        val sh = FakeShell("_a", readbackSha = imgSha); val eng = FakeEngine(); val store = MemStore()
        installer(sh, eng, store).install(manifest())
        assertTrue(eng.applied)
        assertTrue(store.pending!!.kernelWritten)
        assertEquals("_b", store.pending!!.slot)
        assertEquals(0, eng.resets)
    }

    @Test fun resetsWhenKernelWriteFails() {
        val sh = FakeShell("_a", readbackSha = "f".repeat(64)); val eng = FakeEngine(); val store = MemStore()
        assertThrows(InstallFailed::class.java) { installer(sh, eng, store).install(manifest()) }
        assertEquals(1, eng.resets)
        assertNull(store.pending)
        assertFalse("OTA kept for retry", sh.log.any { it.startsWith("rm -f /data/ota_package") })
    }

    @Test fun preflightProblemsStopBeforeAnything() {
        val sh = FakeShell(); val eng = FakeEngine(); val store = MemStore()
        assertThrows(InstallFailed::class.java) { installer(sh, eng, store, listOf("Battery 10%")).install(manifest()) }
        assertFalse(eng.applied)
        assertNull(store.pending)
    }

    @Test fun engineFailureLeavesNoPending() {
        val sh = FakeShell(readbackSha = imgSha); val eng = FakeEngine(applyResult = 7); val store = MemStore()
        assertThrows(InstallFailed::class.java) { installer(sh, eng, store).install(manifest()) }
        assertNull(store.pending)
        assertFalse(sh.log.any { it.startsWith("dd ") })
    }

    @Test fun resumesOrResetsAfterInterruptedUpdate() {
        val p = PendingInstall("20261114", "_b", "/x", "aa")
        assertEquals(RecoveryAction.WriteKernel, recoveryAction(6, p, bootImageOk = true))
        assertEquals(RecoveryAction.Reset, recoveryAction(6, p, bootImageOk = false))
        assertEquals(RecoveryAction.Ready, recoveryAction(6, p.copy(kernelWritten = true), true))
        assertEquals(RecoveryAction.Watch, recoveryAction(3, p, true))
        assertEquals(RecoveryAction.ClearPending, recoveryAction(0, p, true))
        assertEquals(RecoveryAction.ForeignUpdate, recoveryAction(6, null, false))
        assertEquals(RecoveryAction.None, recoveryAction(0, null, false))

        // end to end through Installer.resume(): app killed after OTA, before kernel write
        val sh = FakeShell("_a", readbackSha = imgSha); val eng = FakeEngine(statusValue = 6)
        val store = MemStore(PendingInstall("20261114", "_b", img.path, imgSha))
        assertTrue(installer(sh, eng, store).resume())
        assertTrue(store.pending!!.kernelWritten)
        // same, but the downloaded kernel is gone -> update is reset
        val eng2 = FakeEngine(statusValue = 6)
        val store2 = MemStore(PendingInstall("20261114", "_b", "/nonexistent", imgSha))
        assertFalse(installer(sh, eng2, store2).resume())
        assertEquals(1, eng2.resets)
        assertNull(store2.pending)
    }
}
