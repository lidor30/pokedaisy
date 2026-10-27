package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveFile
import com.pokedaisy.app.companion.data.save.Gen3SaveFormat
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The co-op protocol version carried in every [CoopHello]; a partner on another one is refused. */
const val COOP_PROTOCOL = 1

/** Retail English Emerald, the only game co-op plays (both devices, checked in the HELLO). */
const val EMERALD_COOP_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"

/** A device on the link: its X25519 public key, 64 lowercase hex characters. */
@JvmInline
value class CoopPeerId(val hex: String) {
    init {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "peer id: 64 lowercase hex characters" }
    }

    /** The 32 raw key bytes, as the native side takes them. */
    fun bytes(): ByteArray = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    override fun toString(): String = hex

    companion object {
        fun of(bytes: ByteArray): CoopPeerId {
            require(bytes.size == 32) { "peer id: 32 bytes, got ${bytes.size}" }
            return CoopPeerId(toHex(bytes))
        }
    }
}

/**
 * A save, as co-op tells saves apart: SaveBlock2's trainer ID, secret ID and the 8 raw
 * Emerald-charset name bytes (shown only through `Gen3Text.decode`). Equal by [key].
 */
class CoopSaveId(val trainerId: Int, val secretId: Int, name: ByteArray) {
    init {
        require(trainerId in 0..0xFFFF && secretId in 0..0xFFFF) { "save id: u16 ids" }
        require(name.size == 8) { "save id: 8 name bytes, got ${name.size}" }
    }

    private val nameBytes = name.copyOf()

    /** The 8 raw name bytes (a copy). */
    val name: ByteArray get() = nameBytes.copyOf()

    val key: String = String.format(Locale.ROOT, "%04x%04x-", trainerId, secretId) + toHex(nameBytes)

    override fun equals(other: Any?): Boolean = other is CoopSaveId && other.key == key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "CoopSaveId($key)"

    companion object {
        /** The save in [saveFile] (Emerald's layout), or null: missing, unreadable, another layout or no trainer name. A save's content (party, boxes) is never judged: only its layout and name. */
        fun read(saveFile: File): CoopSaveId? = try {
            val format = Gen3SaveFormat.forGameCode("BPEE")!!
            when (val parsed = Gen3SaveFile.parse(saveFile.readBytes(), format)) {
                is Gen3SaveFile.Parsed.Failed -> null
                is Gen3SaveFile.Parsed.Ok -> Gen3SaveData(parsed.save).let { data ->
                    val name = data.trainerNameBytes
                    val first = name[0].toInt() and 0xFF
                    if (first == 0xFF || first == 0) null
                    else CoopSaveId(data.trainerId, data.secretId, name)
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }

        /** The save a partner's HELLO names. */
        fun fromHello(h: CoopHello): CoopSaveId = h.save
    }
}

data class CoopPartner(
    val peer: CoopPeerId,
    /** `Gen3Text.decode` of [CoopSaveId.name]. */
    val name: String,
    val trainerId: Int,
    val female: Boolean,
    val save: CoopSaveId,
)

data class CoopPair(
    val ownSave: CoopSaveId,
    val ownLabel: String,
    val partner: CoopPartner,
    val lastTicket: String?,
    val pairedAt: Long,
)

sealed interface CoopState {
    data class Off(val reason: CoopOffReason? = null) : CoopState
    data class Connecting(val partner: CoopPartner) : CoopState
    data class Connected(val partner: CoopPartner, val direct: Boolean) : CoopState
    data class Disconnected(val partner: CoopPartner, val reason: CoopDropReason, val retryAtMs: Long) : CoopState
}

enum class CoopOffReason { NOT_EMERALD, NO_SAVE, NOT_PAIRED, CHEATS_ON, NETWORK_ERROR }

enum class CoopDropReason { CLOSED, NETWORK, NOT_RECOGNISED, VERSION, DIFFERENT_ROM, DIFFERENT_SAVE }

/** One app message: a [CoopWire] type and its payload. */
class CoopMessage(val type: Int, val payload: ByteArray)

interface CoopLink {
    val state: StateFlow<CoopState>

    /** Only types >= [CoopWire.FIRST_APP_TYPE], only from the paired partner. */
    val messages: SharedFlow<CoopMessage>

    /**
     * False unless [CoopState.Connected]. Throws [IllegalArgumentException] for a type below
     * [CoopWire.FIRST_APP_TYPE] or above 0xFFFF, or a payload over [CoopWire.MAX_PAYLOAD].
     */
    fun send(type: Int, payload: ByteArray): Boolean
}

private const val HEX = "0123456789abcdef"

internal fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        append(HEX[v shr 4]).append(HEX[v and 0xF])
    }
}
