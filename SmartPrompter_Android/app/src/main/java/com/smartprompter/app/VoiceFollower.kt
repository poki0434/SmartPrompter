package com.smartprompter.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class VoiceFollower(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onModelReady()
        fun onNewSpokenText(deltaText: String)
        fun onError(msg: String)
    }

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initModelAsync() {
        if (recognizer != null) {
            mainHandler.post { listener.onModelReady() }
            return
        }
        Thread {
            try {
                val modelDir = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                            decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                            joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx"
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = 2,
                        modelType = "zipformer"
                    ),
                    enableEndpoint = true
                )
                recognizer = OnlineRecognizer(assetManager = context.assets, config = config)
                mainHandler.post { listener.onModelReady() }
            } catch (e: Throwable) {
                mainHandler.post { listener.onError("离线语音模型加载失败: ${e.message}") }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRunning) return
        val rec = recognizer ?: return
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, sampleRate / 5 * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { listener.onError("麦克风初始化失败") }
            return
        }

        isRunning = true
        audioRecord?.startRecording()

        workerThread = Thread {
            val stream: OnlineStream = rec.createStream()
            val shortBuf = ShortArray(1600) // 100ms chunks
            var lastLength = 0

            while (isRunning) {
                val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                if (read > 0) {
                    val floatBuf = FloatArray(read) { i -> shortBuf[i] / 32768.0f }
                    stream.acceptWaveform(floatBuf, sampleRate)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val text = rec.getResult(stream).text
                    if (text.length > lastLength) {
                        val delta = text.substring(lastLength)
                        lastLength = text.length
                        mainHandler.post { listener.onNewSpokenText(delta) }
                    }
                    if (rec.isEndpoint(stream)) {
                        rec.reset(stream)
                        lastLength = 0
                    }
                }
            }
            stream.release()
        }
        workerThread?.start()
    }

    fun stopListening() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun release() {
        stopListening()
        try {
            recognizer?.release()
        } catch (_: Exception) {}
        recognizer = null
    }
}
