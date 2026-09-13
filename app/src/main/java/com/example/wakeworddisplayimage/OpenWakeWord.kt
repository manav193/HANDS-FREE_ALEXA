package com.example.wakeworddisplayimage

import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.wakeworddisplayimage.ml.AlexaCa2500015000100
import com.example.wakeworddisplayimage.ml.EmbeddingModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedList

class OpenWakeWord(private val context: MainActivity, private val viewModel: MainViewModel) {
    private val gain = 100
    private val maxPatience = 20
    private val audioBufferSizeInBytes = 1280 * 4
    private val maxScores = 1
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

    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        context.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListeningForKeyword()
            else Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_SHORT).show()
        }

    private fun initializeModels() {
        if (::env.isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            val melspecModelPath = context.assets.open("melspectrogram.onnx").readBytes()
            val verifierModelPath = context.assets.open("alexa_verifier.onnx").readBytes()
            melspecOnnx = env.createSession(melspecModelPath)
            embeddingModel = EmbeddingModel.newInstance(context)
            wakewordModel = AlexaCa2500015000100.newInstance(context)
            verifierOnnx = env.createSession(verifierModelPath)
        } catch (ex: Exception) {
            Log.e("openWakeWord", "FAILED TO LOAD MODELS", ex)
            Toast.makeText(context, "Wake-word model failed to load", Toast.LENGTH_LONG).show()
            throw ex
        }
    }

    private fun initializeMicrophone(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                while (isListening && !Thread.currentThread().isInterrupted) {
                    val floatsRead = recorder.read(newAudioData, 0, 1280, AudioRecord.READ_BLOCKING)
                    if (floatsRead != 1280) continue
                    bufferRawData()
                    bufferMelspec()
                    bufferEmbeddings()
                    getWakeWordPrediction(embeddingBuffer)

                    if (patience > 0) {
                        patience--
                        continue
                    }
                    if (confidence[0] <= 0.35f) continue
                    val verifierScore = verifierOnnxPredict(embeddingBuffer) ?: continue
                    if (verifierScore <= 0.35f) continue

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
        // Stop and release our microphone BEFORE starting Amazon Alexa.
        isListening = false
        if (audioRecord === recorder) {
            releaseRecorderIfOwned(recorder)
            audioRecord = null
        }

        withContext(Dispatchers.Main.immediate) {
            viewModel.addCount()
            launchAlexa()
        }
    }

    private fun launchAlexa() {
        try {
            // Tell MainActivity that Alexa owns the foreground now. The listener
            // will be restarted from MainActivity.onResume() when Alexa returns.
            context.markAlexaHandoff()
            val intent = Intent().apply {
                component = ComponentName(
                    "com.amazon.dee.app",
                    "com.amazon.alexa.voice.VoiceHandsFreeSearchActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("ALEXA", "Alexa voice activity launched; listener paused")
        } catch (e: Exception) {
            Log.e("ALEXA", "Failed to launch Alexa", e)
            Toast.makeText(context, "Unable to open Alexa", Toast.LENGTH_SHORT).show()
        }
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
        val newEmbeddings = embeddingModelPredict(embeddingInput(melspecBuffer)).floatArray
        for (i in 0 until 15) for (j in 0 until 96) embeddingBuffer[0][i][j] = embeddingBuffer[0][i + 1][j]
        for (j in 0 until 96) embeddingBuffer[0][15][j] = newEmbeddings[j]
    }

    private fun melspecOnnxPredict(floatArray: FloatArray): Array<Array<Array<FloatArray>>>? = runMelspecPrediction(FloatBuffer.wrap(floatArray))

    private fun runMelspecPrediction(inputData: FloatBuffer?): Array<Array<Array<FloatArray>>>? = try {
        val inputTensor = OnnxTensor.createTensor(env, inputData, longArrayOf(1, 1760))
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input"] = inputTensor
            melspecOnnx.run(inputs).use { result -> (result[0] as OnnxTensor).value as Array<Array<Array<FloatArray>>> }
        } finally { inputTensor.close() }
    } catch (e: Exception) {
        Log.e("openWakeWord", "Melspec inference failed", e)
        null
    }

    private fun embeddingInput(data: Array<Array<Array<FloatArray>>>): ByteBuffer {
        val flattenedData = FloatArray(76 * 32)
        var index = 0
        for (i in 0 until 76) for (j in 0 until 32) flattenedData[index++] = data[0][i][j][0]
        return ByteBuffer.allocateDirect(flattenedData.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(flattenedData) }
    }

    private fun embeddingModelPredict(byteBuffer: ByteBuffer): TensorBuffer {
        val input = TensorBuffer.createFixedSize(intArrayOf(1, 76, 32, 1), DataType.FLOAT32)
        input.loadBuffer(byteBuffer)
        return embeddingModel.process(input).outputFeature0AsTensorBuffer
    }

    private fun wakewordInput(data: Array<Array<FloatArray>>): ByteBuffer {
        val flattenedData = FloatArray(16 * 96)
        var index = 0
        for (i in 0 until 16) for (j in 0 until 96) flattenedData[index++] = data[0][i][j]
        return ByteBuffer.allocateDirect(flattenedData.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(flattenedData) }
    }

    private fun wakewordModelPredict(byteBuffer: ByteBuffer): TensorBuffer {
        val input = TensorBuffer.createFixedSize(intArrayOf(1, 16, 96), DataType.FLOAT32)
        input.loadBuffer(byteBuffer)
        return wakewordModel.process(input).outputFeature0AsTensorBuffer
    }

    private suspend fun getWakeWordPrediction(array: Array<Array<FloatArray>>) {
        val prediction = wakewordModelPredict(wakewordInput(array))
        confidence = prediction.floatArray
        withContext(Dispatchers.Main.immediate) { viewModel.updatePredictionScore(confidence) }
        addScore(confidence[0])
    }

    private fun verifierOnnxPredict(data: Array<Array<FloatArray>>): Float? {
        val floatArray = FloatArray(16 * 96)
        var index = 0
        for (i in 0 until 16) for (j in 0 until 96) floatArray[index++] = data[0][i][j]
        return runVerifierPrediction(FloatBuffer.wrap(floatArray))
    }

    private fun runVerifierPrediction(inputData: FloatBuffer?): Float? = try {
        val inputTensor = OnnxTensor.createTensor(env, inputData, longArrayOf(1, 1536))
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input"] = inputTensor
            verifierOnnx.run(inputs).use { result ->
                val outputSequence = result[1] as OnnxSequence
                try { (outputSequence.getValue()[0].value as HashMap<*, *>)[1L] as Float }
                finally { outputSequence.close() }
            }
        } finally { inputTensor.close() }
    } catch (e: Exception) {
        Log.e("openWakeWord", "Verifier inference failed", e)
        null
    }

    private fun addScore(newScore: Float) {
        if (scoreQueue.size == maxScores) scoreQueue.pollFirst()
        scoreQueue.add(newScore)
    }

    fun release() {
        stopListening()
    }
}
