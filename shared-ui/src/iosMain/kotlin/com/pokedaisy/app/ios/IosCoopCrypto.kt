@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.CoopCrypto
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/** CryptoKit at the native boundary, with NSData to move a frame in one copy. */
interface IosCoopPrimitives {
    fun randomBytes(size: Int): NSData?
    fun publicKey(secret: NSData): NSData?
    fun dh(secret: NSData, peerPublic: NSData): NSData?
    fun seal(key: NSData, nonce: NSData, associated: NSData, plain: NSData): NSData?
    fun open(key: NSData, nonce: NSData, associated: NSData, sealed: NSData): NSData?
}

internal object IosCoopCrypto : CoopCrypto {
    @Volatile private var primitives: IosCoopPrimitives? = null

    fun install(value: IosCoopPrimitives) { primitives = value }

    override fun randomBytes(size: Int) = primitives?.randomBytes(size)?.copyBytes()
    override fun publicKey(secret: ByteArray) = primitives?.publicKey(secret.nativeData())?.copyBytes()
    override fun dh(secret: ByteArray, peerPublic: ByteArray) =
        primitives?.dh(secret.nativeData(), peerPublic.nativeData())?.copyBytes()

    override fun seal(key: ByteArray, nonce: ByteArray, associated: ByteArray, plain: ByteArray) =
        primitives?.seal(key.nativeData(), nonce.nativeData(), associated.nativeData(), plain.nativeData())?.copyBytes()

    override fun open(key: ByteArray, nonce: ByteArray, associated: ByteArray, sealed: ByteArray) =
        primitives?.open(key.nativeData(), nonce.nativeData(), associated.nativeData(), sealed.nativeData())?.copyBytes()

}

internal fun ByteArray.nativeData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.copyBytes(): ByteArray? = when {
    length == 0uL -> ByteArray(0)
    length > Int.MAX_VALUE.toULong() -> null
    else -> bytes?.reinterpret<ByteVar>()?.readBytes(length.toInt())
}
