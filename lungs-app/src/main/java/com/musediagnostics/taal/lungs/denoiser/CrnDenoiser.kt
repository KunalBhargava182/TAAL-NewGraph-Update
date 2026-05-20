package com.musediagnostics.taal.lungs.denoiser

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class CrnDenoiser(context: Context) {

    private val interpreter: Interpreter
    val chunkFrames: Int

    init {
        interpreter = Interpreter(loadModelFile(context))
        chunkFrames = probeChunkFrames()
        Log.i(TAG, "CrnDenoiser ready — chunkFrames=$chunkFrames")
    }

    // Scan candidate T values. For each, try resizeInput → allocateTensors → dummy run.
    // The first T where all three succeed is used for all enhance() calls.
    // Results are logged so we can diagnose from Logcat if nothing works.
    private fun probeChunkFrames(): Int {
        val candidates = intArrayOf(33, 64, 100, 128, 160, 200, 256, 312, 313, 320, 400, 512)
        for (t in candidates) {
            try {
                interpreter.resizeInput(0, intArrayOf(1, 1, StftEngine.N_BINS, t))
                interpreter.allocateTensors()
                // Verify inference works end-to-end with a zero-filled dummy chunk
                val dummyIn  = Array(1) { Array(1) { Array(StftEngine.N_BINS) { FloatArray(t) } } }
                val dummyOut = Array(1) { Array(1) { Array(StftEngine.N_BINS) { FloatArray(t) } } }
                interpreter.run(dummyIn, dummyOut)
                Log.i(TAG, "chunkFrames=$t OK")
                return t
            } catch (e: Exception) {
                Log.w(TAG, "chunkFrames=$t failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        error("CrnDenoiser: no valid chunk size found among $candidates — see Logcat for details")
    }

    // magnitude: [N_BINS][nFrames] — arbitrary length
    // Returns enhanced magnitude: [N_BINS][nFrames]
    fun enhance(magnitude: Array<FloatArray>): Array<FloatArray> {
        val nBins = magnitude.size
        val nFrames = magnitude[0].size
        val output = Array(nBins) { FloatArray(nFrames) }
        val chunkOut = Array(1) { Array(1) { Array(nBins) { FloatArray(chunkFrames) } } }

        var t = 0
        while (t < nFrames) {
            val chunkLen = minOf(chunkFrames, nFrames - t)

            // Build [1, 1, N_BINS, chunkFrames] — zero-pad the last chunk if shorter
            val chunkIn = Array(1) { Array(1) { Array(nBins) { bin ->
                FloatArray(chunkFrames) { f -> if (f < chunkLen) magnitude[bin][t + f] else 0f }
            }}}

            interpreter.run(chunkIn, chunkOut)

            for (bin in 0 until nBins) {
                for (f in 0 until chunkLen) {
                    output[bin][t + f] = chunkOut[0][0][bin][f]
                }
            }
            t += chunkFrames
        }
        return output
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd("crn_float32.tflite")
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() = interpreter.close()

    companion object {
        private const val TAG = "CrnDenoiser"
    }
}
