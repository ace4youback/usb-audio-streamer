package com.usbaudio.streamer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    companion object {
        const val SAMPLE_RATE = 48000
        const val TCP_PORT    = 59125
        const val HOST        = "127.0.0.1"
        const val HEADER_SIZE = 20
    }

    private var streamJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var btnConnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
    }

    private fun buildUI(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF080808.toInt())
            setPadding(80, 80, 80, 80)
        }
        root.addView(TextView(this).apply {
            text = "USB AUDIO"
            textSize = 28f
            setTextColor(0xFF00FF88.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        })
        root.addView(TextView(this).apply {
            text = "STREAMER"
            textSize = 14f
            setTextColor(0xFF004422.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 60)
        })
        statusView = TextView(this).apply {
            text = "● OFFLINE"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 8)
        }
        root.addView(statusView)
        root.addView(TextView(this).apply {
            text = "48kHz · 16-bit · Stereo · PCM"
            textSize = 11f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 60)
        })
        btnConnect = Button(this).apply {
            text = "▶  KẾT NỐI"
            textSize = 15f
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(0xFF00FF88.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(btnConnect)
        statsView = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF1A5533.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 32, 0, 0)
        }
        root.addView(statsView)
        root.addView(TextView(this).apply {
            text = "\n1. Cắm USB vào PC\n2. Chạy pc_audio_sender.py\n3. adb reverse tcp:59125 tcp:59125\n4. Nhấn KẾT NỐI"
            textSize = 10f
            setTextColor(0xFF222222.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 40, 0, 0)
        })
        btnConnect.setOnClickListener {
            if (streamJob?.isActive == true) {
                stopStream()
                btnConnect.text = "▶  KẾT NỐI"
                btnConnect.setBackgroundColor(0xFF00FF88.toInt())
                setStatus("● OFFLINE", 0xFF555555.toInt())
            } else {
                startStream()
                btnConnect.text = "■  DỪNG"
                btnConnect.setBackgroundColor(0xFFFF4444.toInt())
            }
        }
        return root
    }

    private fun setStatus(msg: String, color: Int) {
        runOnUiThread { statusView.text = msg; statusView.setTextColor(color) }
    }

    private fun startStream() {
        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            setStatus("⟳ Đang kết nối...", 0xFFFFAA00.toInt())
            try {
                val socket = Socket(HOST, TCP_PORT).apply {
                    tcpNoDelay = true
                    receiveBufferSize = 8192
                    soTimeout = 5000
                }
                setStatus("● ĐANG STREAM", 0xFF00FF88.toInt())
                val track = createAudioTrack()
                audioTrack = track
                track.play()
                val input   = socket.getInputStream()
                val header  = ByteArray(HEADER_SIZE)
                var packets = 0L
                var bytes   = 0L
                val t0      = System.currentTimeMillis()
                while (isActive && !socket.isClosed) {
                    if (!readFully(input, header, HEADER_SIZE)) break
                    if (header[0] != 0x55.toByte() || header[1] != 0x41.toByte() ||
                        header[2] != 0x53.toByte() || header[3] != 0x50.toByte()) continue
                    val size = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.BIG_ENDIAN).int
                    if (size <= 0 || size > 65536) continue
                    val pcm = ByteArray(size)
                    if (!readFully(input, pcm, size)) break
                    track.write(pcm, 0, size, AudioTrack.WRITE_NON_BLOCKING)
                    packets++
                    bytes += size
                    if (packets % 150 == 0L) {
                        val sec  = (System.currentTimeMillis() - t0) / 1000.0
                        val kbps = (bytes * 8 / sec / 1000).toInt()
                        runOnUiThread { statsView.text = "▶ $packets pkts · ~$kbps kbps" }
                    }
                }
                track.stop(); track.release(); audioTrack = null; socket.close()
                setStatus("● OFFLINE", 0xFF555555.toInt())
                runOnUiThread {
                    btnConnect.text = "▶  KẾT NỐI"
                    btnConnect.setBackgroundColor(0xFF00FF88.toInt())
                }
            } catch (e: Exception) {
                setStatus("✕ ${e.message?.take(40)}", 0xFFFF4444.toInt())
                runOnUiThread {
                    btnConnect.text = "▶  KẾT NỐI"
                    btnConnect.setBackgroundColor(0xFF00FF88.toInt())
                }
            }
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    private fun stopStream() {
        streamJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        runOnUiThread { statsView.text = "" }
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }
}
