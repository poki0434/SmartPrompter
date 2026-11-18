package com.smartprompter.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.abs
import kotlin.math.tanh

class VoiceFollower(
    private val context: Context,
    private val listener: Listener
) {
    enum class MicSource {
        PHONE_MIC,
        BLUETOOTH_MIC
    }

    interface Listener {
        fun onModelReady()
        fun onNewSpokenText(deltaText: String)
        fun onError(msg: String)
    }

    companion object {
        @Volatile
        private var sharedRecognizer: OnlineRecognizer? = null
        private val modelLock = Any()

        fun releaseSharedModelIfIdle() {
            synchronized(modelLock) {
                try {
                    sharedRecognizer?.release()
                } catch (_: Throwable) {}
                sharedRecognizer = null
            }
        }
    }

    @Volatile
    private var micSource: MicSource = MicSource.PHONE_MIC
    @Volatile
    private var isModelLoading = false
    @Volatile
    private var wantListening = false
    @Volatile
    private var isRunning = false

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setMicSource(source: MicSource) {
        if (this.micSource == source) return
        this.micSource = source
        if (isRunning || wantListening) {
            stopListening()
            initAndStart()
        }
    }

    fun getMicSource(): MicSource = micSource

    fun initAndStart() {
        wantListening = true
        if (sharedRecognizer != null) {
            startListeningInternal()
            return
        }
        if (isModelLoading) return
        isModelLoading = true

        Thread {
            try {
                synchronized(modelLock) {
                    if (sharedRecognizer == null) {
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
                                numThreads = 1,
                                modelType = "zipformer"
                            ),
                            enableEndpoint = true
                        )
                        sharedRecognizer = OnlineRecognizer(
                            assetManager = context.applicationContext.assets,
                            config = config
                        )
                    }
                }
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

    private fun configureAudioRouting(ar: AudioRecord) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (micSource == MicSource.PHONE_MIC) {
                try {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.mode = AudioManager.MODE_NORMAL
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.clearCommunicationDevice()
                    }
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val phoneMic = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (phoneMic != null) {
                        ar.setPreferredDevice(phoneMic)
                    }
                }
            } else {
                var btDevice: AudioDeviceInfo? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    btDevice = inputs.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                    }
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && btDevice != null) {
                        am.setCommunicationDevice(btDevice)
                    } else {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && btDevice != null) {
                    ar.setPreferredDevice(btDevice)
                }
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun startListeningInternal() {
        if (isRunning || !wantListening) return
        val rec = sharedRecognizer ?: return
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, sampleRate / 5 * 2)

        var ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            mainHandler.post { listener.onError("麦克风初始化失败") }
            return
        }
        configureAudioRouting(ar)
        audioRecord = ar
        isRunning = true
        ar.startRecording()

        val t = Thread {
            var stream: OnlineStream? = null
            try {
                stream = rec.createStream()
                val shortBuf = ShortArray(1600)
                val floatBuf = FloatArray(1600)
                var lastLength = 0
                var voiceHoldFrames = 0
                var lastStreamResetMs = System.currentTimeMillis()

                while (isRunning) {
                    val read = ar.read(shortBuf, 0, shortBuf.size)
                    if (read > 0 && isRunning) {
                        var peak = 0f
                        for (i in 0 until read) {
                            val v = abs(shortBuf[i] / 32768.0f)
                            if (v > peak) peak = v
                        }
                        // 静音门限：极小声(peak >= 0.0022f)即可唤醒，纯静音超过 400ms 跳过神经网路推理以大幅降低 CPU 与内存
                        if (peak >= 0.0022f) {
                            voiceHoldFrames = 4
                        } else if (voiceHoldFrames > 0) {
                            voiceHoldFrames--
                        } else {
                            continue
                        }

                        val gain = if (peak > 0.0015f) minOf(8.0f, 0.55f / peak) else 6.0f
                        for (i in 0 until read) {
                            val boosted = (shortBuf[i] / 32768.0f) * gain
                            floatBuf[i] = if (abs(boosted) > 0.85f) tanh(boosted.toDouble()).toFloat() else boosted
                        }
                        val slice = if (read == floatBuf.size) floatBuf else floatBuf.copyOf(read)
                        stream!!.acceptWaveform(slice, sampleRate)
                        var decoded = false
                        while (isRunning && rec.isReady(stream!!)) {
                            rec.decode(stream!!)
                            decoded = true
                        }
                        if (!isRunning) break
                        if (decoded) {
                            val text = rec.getResult(stream!!).text
                            if (text.length > lastLength) {
                                val delta = text.substring(lastLength)
                                lastLength = text.length
                                mainHandler.post { listener.onNewSpokenText(delta) }
                            }
                            val nowMs = System.currentTimeMillis()
                            if (rec.isEndpoint(stream!!) || lastLength > 60 || (nowMs - lastStreamResetMs) > 20000L) {
                                stream!!.release()
                                stream = rec.createStream()
                                lastLength = 0
                                lastStreamResetMs = nowMs
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    stream?.release()
                } catch (_: Exception) {}
                try {
                    ar.stop()
                } catch (_: Exception) {}
                try {
                    ar.release()
                } catch (_: Exception) {}
                if (micSource == MicSource.BLUETOOTH_MIC) {
                    try {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.stopBluetoothSco()
                        am?.isBluetoothScoOn = false
                        am?.mode = AudioManager.MODE_NORMAL
                    } catch (_: Exception) {}
                }
            }
        }
        workerThread = t
        t.start()
    }

    fun stopListening() {
        wantListening = false
        isRunning = false
        val t = workerThread
        workerThread = null
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500)
            } catch (_: InterruptedException) {}
        }
        audioRecord = null
    }

    fun release() {
        stopListening()
        releaseSharedModelIfIdle()
    }
}
