package com.devon.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CoreTest {
    private val inst = parseLineageVersion("24.0-20261107-NIGHTLY-devon")
    private fun manifest(date: String, version: String = "24.0", device: String = "devon") = Manifest(
        device, date, version, majorOf(version) - 7, "https://x/ota.zip", "00", 1, "1da2d0861244",
        "https://x/boot.img", "11", 30000, "v2.4.0", "v2.3.0",
    )

    @Test fun parsesNightlyProp() {
        assertEquals(Installed("24.0", "20261107", 24, "devon"), inst)
        assertThrows(IllegalArgumentException::class.java) { parseLineageVersion("garbage") }
    }

    @Test fun noOfferWhenNotNewer() {
        assertEquals(Decision.None, decide(inst, LineageBuild("20261107", "24.0"), manifest("20261107"), true))
        assertEquals(Decision.None, decide(inst, LineageBuild("20261031", "24.0"), manifest("20261031"), true))
    }

    @Test fun noOfferForOtherDevice() {
        assertEquals(Decision.None, decide(inst, null, manifest("20261114", device = "rhode"), true))
    }

    @Test fun noOfferWithoutKernel() {
        assertEquals(Decision.NoKernelYet("20261114"), decide(inst, LineageBuild("20261114", "24.0"), manifest("20261107"), true))
        assertEquals(Decision.NoKernelYet("20261114"), decide(inst, LineageBuild("20261114", "24.0"), null, true))
    }

    @Test fun offerWhenManifestNewer() {
        val m = manifest("20261114")
        assertEquals(Decision.Offer(m), decide(inst, LineageBuild("20261114", "24.0"), m, true))
        // a newer nightly without kernel yet still lets the older-but-newer-than-installed one through
        assertEquals(Decision.Offer(m), decide(inst, LineageBuild("20261121", "24.0"), m, true))
    }

    @Test fun kernelOnlyWhenSameDateAndStockKernel() {
        val m = manifest("20261107")
        assertEquals(Decision.KernelOnly(m), decide(inst, LineageBuild("20261107", "24.0"), m, false))
    }

    @Test fun blocksMajorUpgrade() {
        assertEquals(Decision.MajorUpgrade("25.0", "20261205"), decide(inst, LineageBuild("20261205", "25.0"), manifest("20261205", "25.0"), true))
        assertEquals(Decision.MajorUpgrade("25.0", "20261205"), decide(inst, null, manifest("20261205", "25.0"), true))
        assertEquals(Decision.MajorUpgrade("25.0", "20261205"), decide(inst, LineageBuild("20261205", "25.0"), manifest("20261114"), true))
    }

    @Test fun parsesManifestJson() {
        val m = manifest("20261114")
        assertEquals(m, parseManifest(encodeManifest(m)))
        assertEquals(LineageBuild("20261114", "24.0"),
            newestLineageBuild("""[{"date":"2026-11-07","version":"24.0"},{"date":"2026-11-14","version":"24.0","x":1}]"""))
    }

    @Test fun rejectsShaMismatch() {
        val f = File.createTempFile("dl-test", ".bin").apply { writeText("hello") }
        verifySha256(f, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        assertThrows(ShaMismatch::class.java) { verifySha256(f, "00".repeat(32)) }
    }

    @Test fun findsPayloadOffsetInRealOta() {
        val ota = File(System.getProperty("user.home"), "kernel-build/nightlies/20261114/lineage-24.0-20261114-nightly-devon-signed.zip")
        assumeTrue(ota.exists())
        val p = findPayload(ota)
        assertTrue(p.offset > 0 && p.offset + p.size <= ota.length())
        assertTrue(p.headers.any { it.startsWith("FILE_HASH=") })
        val magic = ByteArray(4)
        java.io.RandomAccessFile(ota, "r").use { it.seek(p.offset); it.readFully(magic) }
        assertEquals("CrAU", String(magic))
    }
}
