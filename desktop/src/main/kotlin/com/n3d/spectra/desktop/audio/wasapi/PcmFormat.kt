package com.n3d.spectra.desktop.audio.wasapi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a WASAPI buffer holds, read from its WAVEFORMATEX / WAVEFORMATEXTENSIBLE.
 *
 * Pure Kotlin on purpose: the conversion is the part that can be wrong in
 * interesting ways (channel order, 24-bit packing, 5.1 folding), so it is kept
 * free of JNA and checked on any machine by `:desktop:loopbackCheck`.
 */
class PcmFormat(
    val sampleRate: Int,
    val channels: Int,
    /** Container size: 16, 24 or 32. */
    val bitsPerSample: Int,
    val isFloat: Boolean,
    /** Bytes per frame, as the format says — not assumed from the other fields. */
    val blockAlign: Int,
    /** SPEAKER_* bits, one per channel in ascending bit order. 0 when the format did not say. */
    val channelMask: Int,
) {
    val label: String
        get() = "${if (isFloat) "float" else "$bitsPerSample-bit"}${if (channels > 2) " · $channels ch" else ""}"

    /** A WAVEFORMATEX describing this format, for IAudioClient::Initialize. Plain, not EXTENSIBLE: only used for ≤ 2 channels. */
    fun toWaveFormatEx(): ByteArray {
        require(channels <= 2) { "a plain WAVEFORMATEX is only unambiguous up to stereo" }
        val b = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort((if (isFloat) WAVE_FORMAT_IEEE_FLOAT else WAVE_FORMAT_PCM).toShort())
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort(bitsPerSample.toShort())
        b.putShort(0)
        return b.array()
    }

    companion object {
        const val WAVE_FORMAT_PCM = 1
        const val WAVE_FORMAT_IEEE_FLOAT = 3
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        fun float32(sampleRate: Int, channels: Int) =
            PcmFormat(sampleRate, channels, 32, isFloat = true, blockAlign = 4 * channels, channelMask = 0)

        /** Reads a WAVEFORMATEX, or the WAVEFORMATEXTENSIBLE it introduces, from its raw bytes. */
        fun parse(raw: ByteArray): PcmFormat {
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val tag = b.getShort(0).toInt() and 0xFFFF
            val channels = b.getShort(2).toInt() and 0xFFFF
            val rate = b.getInt(4)
            val blockAlign = b.getShort(12).toInt() and 0xFFFF
            val bits = b.getShort(14).toInt() and 0xFFFF
            val extra = if (raw.size >= 18) b.getShort(16).toInt() and 0xFFFF else 0
            var mask = 0
            val subtype = if (tag == WAVE_FORMAT_EXTENSIBLE && extra >= 22 && raw.size >= 40) {
                mask = b.getInt(20)
                // The first four bytes of the SubFormat GUID are the classic format tag
                // (KSDATAFORMAT_SUBTYPE_PCM is 00000001-0000-0010-..., IEEE_FLOAT 00000003-...).
                b.getInt(24)
            } else {
                tag
            }
            val isFloat = when (subtype) {
                WAVE_FORMAT_IEEE_FLOAT -> true
                WAVE_FORMAT_PCM -> false
                else -> throw IllegalArgumentException("unsupported sample format 0x%04X".format(subtype))
            }
            require(channels in 1..32 && rate > 0 && blockAlign >= channels * bits / 8) { "malformed WAVEFORMATEX" }
            require(if (isFloat) bits == 32 else bits == 16 || bits == 24 || bits == 32) { "unsupported $bits-bit ${if (isFloat) "float" else "PCM"}" }
            return PcmFormat(rate, channels, bits, isFloat, blockAlign, mask)
        }
    }
}

/**
 * Turns frames of any [PcmFormat] into interleaved floats with one or two channels.
 *
 * Surround is folded to stereo with the usual ITU weights — fronts at 1, centre and
 * surrounds at −3 dB, LFE dropped — which is what Windows' own matrixer does when it
 * is allowed to. It only runs when Windows refused to convert for us; normally the
 * stream already arrives as float stereo and this is a straight copy.
 */
class PcmConverter(private val format: PcmFormat, val outChannels: Int) {

    private val toLeft = FloatArray(format.channels)
    private val toRight = FloatArray(format.channels)
    private var scratch = ByteArray(0)

    init {
        require(outChannels == 1 || outChannels == 2)
        val mask = if (format.channelMask != 0) format.channelMask else defaultMask(format.channels)
        if (format.channels == 1) {
            toLeft[0] = 1f
            toRight[0] = 1f
        } else {
            // Channels are packed in ascending order of their speaker bit.
            var ch = 0
            var bit = 0
            while (ch < format.channels && bit < 32) {
                if (mask and (1 shl bit) != 0) {
                    val (l, r) = weights(1 shl bit)
                    toLeft[ch] = l
                    toRight[ch] = r
                    ch++
                }
                bit++
            }
            // A mask with fewer bits than channels: give the leftovers nothing
            // rather than guessing, except that the first two are always L and R.
            if (ch < 2) {
                toLeft[0] = 1f; toRight[0] = 0f
                toLeft[1] = 0f; toRight[1] = 1f
            }
        }
    }

    /** Bytes the caller must hand over for [frames] frames. */
    fun bytesFor(frames: Int): Int = frames * format.blockAlign

    /** A reusable buffer at least [bytes] long, for copying a packet out of native memory. */
    fun scratch(bytes: Int): ByteArray {
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        return scratch
    }

    /**
     * Converts [frames] frames from [src] into [dst] starting at [dstOffset].
     * [dst] must have room for `frames * outChannels` floats.
     */
    fun convert(src: ByteArray, frames: Int, dst: FloatArray, dstOffset: Int) {
        val chans = format.channels
        val stride = format.blockAlign
        val bytes = format.bitsPerSample / 8
        var w = dstOffset
        for (f in 0 until frames) {
            val base = f * stride
            var l = 0f
            var r = 0f
            for (c in 0 until chans) {
                val v = sample(src, base + c * bytes)
                l += v * toLeft[c]
                r += v * toRight[c]
            }
            if (outChannels == 2) {
                dst[w++] = l
                dst[w++] = r
            } else {
                dst[w++] = (l + r) * 0.5f
            }
        }
    }

    private fun sample(b: ByteArray, i: Int): Float = when {
        format.isFloat -> Float.fromBits(
            (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24),
        )
        format.bitsPerSample == 16 -> ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)) / 32768f
        format.bitsPerSample == 24 ->
            ((b[i + 2].toInt() shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)) / 8388608f
        // 32-bit containers, including 24 valid bits left-justified in 32: the
        // padding is in the low byte, so dividing by 2^31 is right for both.
        else -> ((b[i + 3].toInt() shl 24) or ((b[i + 2].toInt() and 0xFF) shl 16) or
            ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)) / 2147483648f
    }

    private companion object {
        const val FL = 0x1
        const val FR = 0x2
        const val FC = 0x4
        const val LFE = 0x8
        const val BL = 0x10
        const val BR = 0x20
        const val FLC = 0x40
        const val FRC = 0x80
        const val BC = 0x100
        const val SL = 0x200
        const val SR = 0x400
        const val TC = 0x800
        const val TFL = 0x1000
        const val TFC = 0x2000
        const val TFR = 0x4000
        const val TBL = 0x8000
        const val TBC = 0x10000
        const val TBR = 0x20000
        const val M3DB = 0.70710677f

        fun weights(speaker: Int): Pair<Float, Float> = when (speaker) {
            FL, FLC -> 1f to 0f
            FR, FRC -> 0f to 1f
            FC -> M3DB to M3DB
            LFE -> 0f to 0f
            BL, SL, TFL, TBL -> M3DB to 0f
            BR, SR, TFR, TBR -> 0f to M3DB
            BC, TC, TFC, TBC -> M3DB to M3DB
            else -> 0f to 0f
        }

        /** KSAUDIO_SPEAKER_* layouts, for formats that left the mask out. */
        fun defaultMask(channels: Int): Int = when (channels) {
            1 -> FC
            2 -> FL or FR
            3 -> FL or FR or FC
            4 -> FL or FR or BL or BR
            5 -> FL or FR or FC or BL or BR
            6 -> FL or FR or FC or LFE or SL or SR
            8 -> FL or FR or FC or LFE or BL or BR or SL or SR
            else -> FL or FR
        }
    }
}
