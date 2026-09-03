package com.commacompliance.archiver.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/**
 * A host-JVM NaclBox backed by the desktop-native libsodium that
 * lazysodium-java bundles, so the envelope round-trip vectors exercise the real
 * NaCl primitive (identical libsodium to the device path) without a device or Ruby.
 */
object TestSodium {
    fun box(): LazySodiumNaclBox = LazySodiumNaclBox(LazySodiumJava(SodiumJava()))
}
