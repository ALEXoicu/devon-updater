package com.devon.updater.device

import java.io.File

class BootWriteException(msg: String) : Exception(msg)

/** Writes a boot image to boot_<slot> and proves it by reading it back. */
class BootWriter(private val shell: Shell, private val slots: Slots) {
    fun write(img: File, sha256: String, slot: String, allowActiveSlot: Boolean = false) {
        require(slot == "_a" || slot == "_b") { "bad slot $slot" }
        if (slot == slots.current() && !allowActiveSlot)
            throw BootWriteException("refusing to write the active slot boot$slot")
        val dev = "/dev/block/by-name/boot$slot"
        val partSize = shell.check("blockdev --getsize64 $dev").toLong()
        val len = img.length()
        if (len == 0L || len > partSize) throw BootWriteException("image size $len doesn't fit boot$slot ($partSize)")
        // update_engine leaves the slot it just wrote marked read-only (BLKROSET); clear it first, as Magisk does
        shell.check("blockdev --setrw $dev")
        shell.check("dd if=${q(img.path)} of=$dev bs=4M conv=fsync")
        val back = shell.check("head -c $len $dev | sha256sum").substringBefore(' ')
        if (!back.equals(sha256, ignoreCase = true))
            throw BootWriteException("readback of boot$slot is $back, expected $sha256")
    }
}
