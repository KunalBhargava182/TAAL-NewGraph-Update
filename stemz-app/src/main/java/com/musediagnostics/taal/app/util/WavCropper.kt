package com.musediagnostics.taal.app.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for cropping WAV files (16-bit PCM, mono, 44100 Hz).
 * Reads the source, extracts the sample range, and writes a valid WAV with updated header.
 */
object WavCropper {

    private const val WAV_HEADER_SIZE = 44
    private const val SAMPLE_RATE = 44100
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8 // 2

    /**
     * Returns the duration of a WAV file in seconds.
     */
    fun getDurationSeconds(filePath: String): Float {
        val file = File(filePath)
        if (!file.exists()) return 0f
        val dataSize = file.length() - WAV_HEADER_SIZE
        val totalSamples = dataSize / BYTES_PER_SAMPLE
        return totalSamples.toFloat() / SAMPLE_RATE
    }

    /**
     * Reads all audio samples from a WAV file as normalized floats [-1.0, 1.0].
     * Downsamples to maxPoints for display.
     */
    fun getWaveformData(filePath: String, maxPoints: Int = 1000): FloatArray {
        val file = File(filePath)
        if (!file.exists()) return FloatArray(0)

        val dataSize = (file.length() - WAV_HEADER_SIZE).toInt()
        val totalSamples = dataSize / BYTES_PER_SAMPLE

        if (totalSamples <= 0) return FloatArray(0)

        val step = maxOf(1, totalSamples / maxPoints)
        val resultSize = totalSamples / step
        val result = FloatArray(resultSize)

        FileInputStream(file).use { fis ->
            fis.skip(WAV_HEADER_SIZE.toLong())
            val buffer = ByteArray(BYTES_PER_SAMPLE)
            var sampleIndex = 0
            var outIndex = 0

            while (outIndex < resultSize) {
                val read = fis.read(buffer)
                if (read < BYTES_PER_SAMPLE) break

                if (sampleIndex % step == 0 && outIndex < resultSize) {
                    val sample = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).short
                    result[outIndex] = sample.toFloat() / 32768f
                    outIndex++
                }
                sampleIndex++

                // Skip ahead if step > 1
                if (step > 1 && sampleIndex % step != 0) {
                    val skip = ((step - (sampleIndex % step)) * BYTES_PER_SAMPLE).toLong()
                    fis.skip(skip)
                    sampleIndex += (skip / BYTES_PER_SAMPLE).toInt()
                }
            }
        }

        return result
    }

    /**
     * Crops a WAV file between startSeconds and endSeconds,
     * writing the result to outputPath.
     * Returns true on success.
     */
    fun cropWav(
        inputPath: String,
        outputPath: String,
        startSeconds: Float,
        endSeconds: Float
    ): Boolean {
        try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) return false

            val startSample = (startSeconds * SAMPLE_RATE).toInt()
            val endSample = (endSeconds * SAMPLE_RATE).toInt()
            val numSamples = endSample - startSample

            if (numSamples <= 0) return false

            val dataSize = numSamples * BYTES_PER_SAMPLE
            val startByte = WAV_HEADER_SIZE + (startSample * BYTES_PER_SAMPLE)

            FileInputStream(inputFile).use { fis ->
                FileOutputStream(outputPath).use { fos ->
                    // Write WAV header
                    writeWavHeader(fos, dataSize)

                    // Skip to start position
                    fis.skip(startByte.toLong())

                    // Copy audio data
                    val bufferSize = 8192
                    val buffer = ByteArray(bufferSize)
                    var remaining = dataSize
                    while (remaining > 0) {
                        val toRead = minOf(bufferSize, remaining)
                        val read = fis.read(buffer, 0, toRead)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = CHANNELS * BYTES_PER_SAMPLE

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(totalSize)
        header.put("WAVE".toByteArray())

        // fmt sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size (PCM)
        header.putShort(1) // AudioFormat (PCM = 1)
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())

        // data sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        fos.write(header.array())
    }
}
