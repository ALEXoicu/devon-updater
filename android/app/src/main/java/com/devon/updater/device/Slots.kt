package com.devon.updater.device

class Slots(private val shell: Shell) {
    fun current(): String {
        val s = shell.check("getprop ro.boot.slot_suffix")
        require(s == "_a" || s == "_b") { "unexpected slot suffix '$s'" }
        return s
    }

    fun inactive(): String = opposite(current())

    companion object {
        fun opposite(slot: String) = when (slot) {
            "_a" -> "_b"; "_b" -> "_a"; else -> throw IllegalArgumentException(slot)
        }
    }
}
