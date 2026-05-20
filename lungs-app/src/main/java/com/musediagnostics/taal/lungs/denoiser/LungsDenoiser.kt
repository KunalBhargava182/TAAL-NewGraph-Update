package com.musediagnostics.taal.lungs.denoiser

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class LungsDenoiser {

    private val stft = StftEngine()

    // inputPath  = path to original WAV (e.g. lungs/01/01_aar.wav)
    // outputPath = path for denoised WAV (e.g. lungs/01/denoised/01_aar.wav)
    // Returns true on success, false on any error (caller keeps original)
    fun denoiseWav(inputPath: String, outputPath: String): Boolean {
        return try {
            // 1. Read WAV
            val (audio, sourceSr) = readWav(inputPath)

            // 2. Resample to 8000 Hz if needed
            val audio8k = when {
                sourceSr == 8000 -> audio
                sourceSr % 8000 == 0 -> decimate(audio, sourceSr / 8000)
                else -> resampleLinear(audio, sourceSr, 8000)
            }

            // 3. STFT
            val (real, imag) = stft.computeStft(audio8k)

            // 4. Wiener denoise
            val wiener = WienerDenoiser()
            val (enhReal, enhImag) = wiener.denoise(real, imag)

            // 5. Reconstruct
            val denoised = stft.computeIstft(enhReal, enhImag, audio8k.size)

            // 6. Ensure output folder exists
            File(outputPath).parentFile?.mkdirs()

            // 7. Write WAV at 8000 Hz
            writeWav(outputPath, denoised, sampleRate = 8000)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Read 16-bit PCM WAV. Returns Pair(floatSamples normalised -1..1, sampleRate).
    // Handles mono and stereo (stereo: keep left channel only).
    private fun readWav(path: String): Pair<FloatArray, Int> {
        val bytes = FileInputStream(path).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(22)
        val channels = buf.short.toInt()
        val sampleRate = buf.int
        buf.position(34)
        val bitsPerSample = buf.short.toInt()
        // Skip to data chunk
        buf.position(44)
        val remaining = buf.remaining()
        val totalSamples = remaining / (bitsPerSample / 8)
        val frameSamples = totalSamples / channels
        val result = FloatArray(frameSamples)
        for (i in 0 until frameSamples) {
            result[i] = buf.short / 32768f
            if (channels == 2) buf.short  // skip right channel
        }
        return Pair(result, sampleRate)
    }

    // Write FloatArray as 16-bit PCM mono WAV
    private fun writeWav(path: String, data: FloatArray, sampleRate: Int) {
        val numSamples = data.size
        val dataSize = numSamples * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)       // chunk size
        buf.putShort(1)      // PCM
        buf.putShort(1)      // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)  // byte rate
        buf.putShort(2)      // block align
        buf.putShort(16)     // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (sample in data) {
            buf.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        }
        FileOutputStream(path).use { it.write(buf.array()) }
    }

    // Integer-ratio decimation (e.g. 48000→8000 factor=6: keep every 6th sample)
    private fun decimate(data: FloatArray, factor: Int): FloatArray =
        FloatArray(data.size / factor) { i -> data[i * factor] }

    // Linear interpolation resample for non-integer ratios (e.g. 44100→8000)
    private fun resampleLinear(data: FloatArray, fromSr: Int, toSr: Int): FloatArray {
        val ratio = fromSr.toDouble() / toSr
        val outLen = (data.size / ratio).toInt()
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val lo = pos.toInt().coerceAtMost(data.size - 2)
            val frac = (pos - lo).toFloat()
            data[lo] * (1f - frac) + data[lo + 1] * frac
        }
    }

}
