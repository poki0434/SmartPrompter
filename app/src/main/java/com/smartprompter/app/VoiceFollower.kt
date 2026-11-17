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

    @Volatile
    private var recognizer: OnlineRecognizer? = null
    @Volatile
    private var isModelLoading = false
    @Volatile
    private var wantListening = false
    @Volatile
    private var isRunning = false

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 主线程合并回调：工作线程先把增量文本累积到 pendingDelta，
    // 主线程只调度一个 Runnable 一次性取走，避免消息队列随录制时长积压。
    private val pendingDelta = StringBuilder()
    @Volatile
    private var dispatchScheduled = false
    private val dispatchRunnable = Runnable {
        dispatchScheduled = false
        val text: String
        synchronized(pendingDelta) {
            if (pendingDelta.isEmpty()) return@Runnable
            text = pendingDelta.toString()
            pendingDelta.setLength(0)
        }
        if (wantListening) listener.onNewSpokenText(text)
    }

    fun initAndStart() {
        wantListening = true
        if (recognizer != null) {
            startListeningInternal()
            return
        }
        if (isModelLoading) return
        isModelLoading = true

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
                isModelLoading = false
                mainHandler.post {
                    listener.onModelReady()
                    if (wantListening) {
                        startListeningInternal()
                    }
                }
            } catch (e: Throwable) {
                isModelLoading = false
                mainHandler.post { listener.onError("离线语音模型加载失败: ${e.message}") }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startListeningInternal() {
        synchronized(this) {
            if (isRunning || !wantListening) return
            val rec = recognizer ?: return

            // 确保上一个工作线程已完全退出，避免重复创建线程叠加
            workerThread?.let { old ->
                try { old.join(300) } catch (_: Exception) {}
            }

            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, sampleRate / 5 * 2)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Throwable) {
                mainHandler.post { listener.onError("麦克风初始化失败: ${e.message}") }
                return
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                try { record.release() } catch (_: Exception) {}
                mainHandler.post { listener.onError("麦克风初始化失败") }
                return
            }

            audioRecord = record
            isRunning = true
            record.startRecording()

            workerThread = Thread {
                val stream: OnlineStream = rec.createStream()
                try {
                    val shortBuf = ShortArray(1600)
                    val floatBuf = FloatArray(1600) // Pre-allocated: zero GC churn
                    var lastLength = 0

                    while (isRunning) {
                        val read = try {
                            audioRecord?.read(shortBuf, 0, shortBuf.size) ?: -1
                        } catch (e: Throwable) {
                            break
                        }
                        if (read > 0) {
                            for (i in 0 until read) {
                                floatBuf[i] = shortBuf[i] / 32768.0f
                            }
                            val slice = if (read == floatBuf.size) floatBuf else floatBuf.copyOf(read)
                            stream.acceptWaveform(slice, sampleRate)
                            while (rec.isReady(stream)) {
                                rec.decode(stream)
                            }
                            val text = rec.getResult(stream).text
                            if (text.length > lastLength) {
                                val delta = text.substring(lastLength)
                                lastLength = text.length
                                enqueueDelta(delta)
                            }
                            if (rec.isEndpoint(stream)) {
                                rec.reset(stream)
                                lastLength = 0
                            }
                        } else if (read < 0) {
                            break
                        }
                    }
                } catch (e: Throwable) {
                    mainHandler.post { listener.onError("语音识别异常: ${e.message}") }
                } finally {
                    try { stream.release() } catch (_: Exception) {}
                }
            }
            workerThread?.start()
        }
    }

    private fun enqueueDelta(delta: String) {
        synchronized(pendingDelta) {
            pendingDelta.append(delta)
        }
        if (!dispatchScheduled) {
            dispatchScheduled = true
            mainHandler.post(dispatchRunnable)
        }
    }

    fun stopListening() {
        wantListening = false
        isRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        // 等待旧工作线程退出并清空引用，杜绝线程泄漏与重复创建
        val t = workerThread
        workerThread = null
        if (t != null && t.isAlive) {
            try { t.join(500) } catch (_: Exception) {}
        }

        mainHandler.removeCallbacks(dispatchRunnable)
        dispatchScheduled = false
        synchronized(pendingDelta) { pendingDelta.setLength(0) }
    }

    fun release() {
        stopListening()
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
