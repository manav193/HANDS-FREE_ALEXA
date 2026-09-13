package com.example.wakeworddisplayimage

import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.wakeworddisplayimage.ml.AlexaCa2500015000100
import com.example.wakeworddisplayimage.ml.EmbeddingModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedList

class OpenWakeWord(
    private val context: Context,
    private val viewModel: MainViewModel? = null,
    private val onWakeWord: (() -> Unit)? = null,
    private val onScore: ((Float) -> Unit)? = null
) {
    private val gain = 100
    private val maxPatience = 20
    private val audioBufferSizeInBytes = 1280 * 4
    private val maxScores = 1
    // Slightly more sensitive so natural variants such as "Alex" / "Lexa" can be caught by the Alexa model.
    // These are acoustic aliases, not separate trained wake-word models.
    private val wakeWordThreshold = 0.28f
    private val verifierThreshold = 0.28f
    private val scoreQueue = LinkedList<Float>()
    private val newAudioData = FloatArray(1280)
    private val rawDataBuffer = FloatArray(1760)
    private val melspecBuffer = Array(1) { Array(76) { Array(32) { FloatArray(1) } } }
    private val embeddingBuffer = Array(1) { Array(16) { FloatArray(96) } }
    private lateinit var melspecOnnx: OrtSession
    private lateinit var embeddingModel: EmbeddingModel
    private lateinit var wakewordModel: AlexaCa2500015000100
    private lateinit var verifierOnnx: OrtSession
    private lateinit var env: OrtEnvironment
    private var confidence = FloatArray(1)
    private var isListening = false
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null

    private fun initializeModels() {
        if (::env.isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            melspecOnnx = env.createSession(context.assets.open("melspectrogram.onnx").readBytes())
            embeddingModel = EmbeddingModel.newInstance(context)
            wakewordModel = AlexaCa2500015000100.newInstance(context)
            verifierOnnx = env.createSession(context.assets.open("alexa_verifier.onnx").readBytes())
        } catch (ex: Exception) {
            Log.e("openWakeWord", "FAILED TO LOAD MODELS", ex)
            if (context is android.app.Activity) {
                Toast.makeText(context, "Wake-word model failed to load", Toast.LENGTH_LONG).show()
            }
            throw ex
        }
    }

    private fun initializeMicrophone(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioRecord", "Microphone permission is not granted")
            return null
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            audioBufferSizeInBytes
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecord", "AudioRecord initialization failed")
            recorder.release()
            return null
        }
        return recorder
    }

    fun startListeningForKeyword() {
        if (isListening || listenJob?.isActive == true) return
        try { initializeModels() } catch (_: Exception) { return }
        resetDetectionState()
        val recorder = initializeMicrophone() ?: return
        audioRecord = recorder
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e("AudioRecord", "Unable to start recording", e)
            releaseRecorderIfOwned(recorder)
            return
        }
        isListening = true
        listenJob = CoroutineScope(Dispatchers.IO).launch {
            var patience = 0
            try {
                delay(1200)
                while (isListening && !Thread.currentThread().isInterrupted) {
                    val floatsRead = recorder.read(newAudioData, 0, 1280, AudioRecord.READ_BLOCKING)
                    if (floatsRead != 1280) continue
                    bufferRawData()
                    bufferMelspec()
                    bufferEmbeddings()
                    getWakeWordPrediction(embeddingBuffer)
                    if (patience > 0) { patience--; continue }
                    if (confidence[0] <= wakeWordThreshold) continue
                    val verifierScore = verifierOnnxPredict(embeddingBuffer) ?: continue
                    if (verifierScore <= verifierThreshold) continue
                    patience = maxPatience
                    onWakeWordDetected(recorder)
                    break
                }
            } catch (e: Exception) {
                if (isListening) Log.e("openWakeWord", "Wake-word loop stopped", e)
            } finally {
                if (audioRecord === recorder) releaseRecorderIfOwned(recorder)
            }
        }
    }

    private suspend fun onWakeWordDetected(recorder: AudioRecord) {
        isListening = false
        if (audioRecord === recorder) releaseRecorderIfOwned(recorder)
        withContext(Dispatchers.Main.immediate) {
            viewModel?.addCount()
            onWakeWord?.invoke()
        }
    }

    fun resetDetectionState() {
        java.util.Arrays.fill(newAudioData, 0f)
        java.util.Arrays.fill(rawDataBuffer, 0f)
        for (i in melspecBuffer[0].indices) for (j in melspecBuffer[0][i].indices) java.util.Arrays.fill(melspecBuffer[0][i][j], 0f)
        for (i in embeddingBuffer[0].indices) java.util.Arrays.fill(embeddingBuffer[0][i], 0f)
        scoreQueue.clear()
        confidence = FloatArray(1)
    }

    fun stopListening() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        audioRecord?.let { releaseRecorderIfOwned(it) }
        audioRecord = null
    }

    private fun releaseRecorderIfOwned(recorder: AudioRecord) {
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (e: Exception) {
            Log.w("AudioRecord", "Failed to stop recorder cleanly", e)
        } finally {
            try { recorder.release() } catch (e: Exception) { Log.w("AudioRecord", "Failed to release recorder", e) }
            if (audioRecord === recorder) audioRecord = null
        }
    }

    private fun bufferRawData() {
        for (i in newAudioData.indices) newAudioData[i] = gain * newAudioData[i]
        System.arraycopy(rawDataBuffer, 1280, rawDataBuffer, 0, 480)
        System.arraycopy(newAudioData, 0, rawDataBuffer, 480, 1280)
    }

    private fun bufferMelspec() {
        val p = melspecOnnxPredict(rawDataBuffer) ?: return
        for (i in 0 until 68) for (j in 0 until 32) melspecBuffer[0][i][j][0] = melspecBuffer[0][i + 8][j][0]
        for (i in 0 until 8) for (j in 0 until 32) melspecBuffer[0][68 + i][j][0] = 2 + p[0][0][i][j] / 10
    }

    private fun bufferEmbeddings() {
        val e = embeddingModelPredict(embeddingInput(melspecBuffer)).floatArray
        for (i in 0 until 15) for (j in 0 until 96) embeddingBuffer[0][i][j] = embeddingBuffer[0][i + 1][j]
        for (j in 0 until 96) embeddingBuffer[0][15][j] = e[j]
    }

    private fun melspecOnnxPredict(f: FloatArray): Array<Array<Array<FloatArray>>>? = try {
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, 1760))
        try {
            melspecOnnx.run(hashMapOf("input" to t)).use { (it[0] as OnnxTensor).value as Array<Array<Array<FloatArray>>> }
        } finally { t.close() }
    } catch (e: Exception) {
        Log.e("openWakeWord", "Melspec inference failed", e)
        null
    }

    private fun embeddingInput(data: Array<Array<Array<FloatArray>>>): ByteBuffer {
        val f = FloatArray(76 * 32); var x = 0
        for (i in 0 until 76) for (j in 0 until 32) f[x++] = data[0][i][j][0]
        return ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(f) }
    }

    private fun embeddingModelPredict(b: ByteBuffer): TensorBuffer {
        val input = TensorBuffer.createFixedSize(intArrayOf(1, 76, 32, 1), DataType.FLOAT32)
        input.loadBuffer(b)
        return embeddingModel.process(input).outputFeature0AsTensorBuffer
    }

    private fun wakewordInput(data: Array<Array<FloatArray>>): ByteBuffer {
        val f = FloatArray(16 * 96); var x = 0
        for (i in 0 until 16) for (j in 0 until 96) f[x++] = data[0][i][j]
        return ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(f) }
    }

    private fun wakewordModelPredict(b: ByteBuffer): TensorBuffer {
        val input = TensorBuffer.createFixedSize(intArrayOf(1, 16, 96), DataType.FLOAT32)
        input.loadBuffer(b)
        return wakewordModel.process(input).outputFeature0AsTensorBuffer
    }

    private suspend fun getWakeWordPrediction(a: Array<Array<FloatArray>>) {
        val p = wakewordModelPredict(wakewordInput(a))
        confidence = p.floatArray
        withContext(Dispatchers.Main.immediate) {
            viewModel?.updatePredictionScore(confidence)
            onScore?.invoke(confidence[0])
        }
        addScore(confidence[0])
    }

    private fun verifierOnnxPredict(data: Array<Array<FloatArray>>): Float? {
        val f = FloatArray(16 * 96); var x = 0
        for (i in 0 until 16) for (j in 0 until 96) f[x++] = data[0][i][j]
        return try {
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, 1536))
            val result = verifierOnnx.run(hashMapOf("input" to tensor))
            val sequence = result[1] as OnnxSequence
            val value = sequence.getValue()[0].value as HashMap<*, *>
            val score = value[1L] as Float
            sequence.close()
            result.close()
            tensor.close()
            score
        } catch (e: Exception) {
            Log.e("openWakeWord", "Verifier inference failed", e)
            null
        }
    }

    private fun addScore(newScore: Float) {
        if (scoreQueue.size == maxScores) scoreQueue.pollFirst()
        scoreQueue.add(newScore)
    }

    fun release() { stopListening() }
}