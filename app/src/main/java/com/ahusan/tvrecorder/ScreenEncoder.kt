package com.ahusan.tvrecorder

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Records the screen (H.264) and, when allowed, the TV's playback audio (AAC)
 * into one MP4 using MediaCodec + MediaMuxer.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitrate: Int,
    private val wantAudio: Boolean,
    private val output: File,
    private val onFatal: (String) -> Unit
) {
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: MediaMuxer? = null

    private val lock = Object()
    private var expectedTracks = 1
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var gotKeyFrame = false
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var samplesWritten = 0

    @Volatile private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    val hasAudio: Boolean get() = audioCodec != null

    /** Throws if the encoder can't be set up; everything is released on failure. */
    fun start() {
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ---- Video ----
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Keep producing frames on a still screen so the timeline stays smooth.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
            val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec = vc
            vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vc.createInputSurface()
            vc.start()

            // ---- Audio (optional) ----
            if (wantAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    setupAudio()
                } catch (e: Exception) {
                    Log.w(App.TAG, "Audio capture unavailable, video only: ${e.message}")
                    releaseAudio()
                }
            }
            expectedTracks = if (audioCodec != null) 2 else 1

            // ---- Screen -> encoder ----
            virtualDisplay = projection.createVirtualDisplay(
                "TVRecorder", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            running = true
            videoThread = Thread({ videoLoop() }, "rec-video").also { it.start() }
            if (audioCodec != null) {
                audioThread = Thread({ audioLoop() }, "rec-audio").also { it.start() }
            }
        } catch (e: Exception) {
            releaseAll(deleteIfEmpty = true)
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized")
        }
        audioRecord = rec

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec = ac
        ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ac.start()
    }

    // ---------------- Video ----------------

    private fun videoLoop() {
        val vc = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = vc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, vc.outputFormat)
                } else if (idx >= 0) {
                    val buf = vc.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) writeSample(true, buf, info)
                    vc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            Log.e(App.TAG, "Video encoder error", e)
            if (running) onFatal("Video encoder error: ${e.message}")
        }
    }

    // ---------------- Audio ----------------

    private fun audioLoop() {
        val rec = audioRecord ?: return
        val ac = audioCodec ?: return
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var basePtsUs = -1L
        var totalFrames = 0L // per-channel sample frames
        try {
            rec.startRecording()
            // Prime the encoder with a little silence so its format is known right away,
            // even if nothing is playing yet (otherwise the file can't start).
            basePtsUs = System.nanoTime() / 1000
            queueAudio(ac, ByteArray(4096), 4096, basePtsUs, false)
            totalFrames = 1024
            drainAudio(ac, info, false)
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (basePtsUs < 0) basePtsUs = System.nanoTime() / 1000
                val ptsUs = basePtsUs + totalFrames * 1_000_000L / SAMPLE_RATE
                totalFrames += n / 4 // 16-bit stereo = 4 bytes per frame
                queueAudio(ac, pcm, n, ptsUs, false)
                drainAudio(ac, info, false)
            }
            // End of stream
            val ptsUs = if (basePtsUs < 0) System.nanoTime() / 1000
                        else basePtsUs + totalFrames * 1_000_000L / SAMPLE_RATE
            queueAudio(ac, pcm, 0, ptsUs, true)
            drainAudio(ac, info, true)
        } catch (e: Exception) {
            Log.e(App.TAG, "Audio encoder error", e)
        } finally {
            try { rec.stop() } catch (_: Exception) {}
        }
    }

    private fun queueAudio(ac: MediaCodec, data: ByteArray, len: Int, ptsUs: Long, eos: Boolean) {
        var offset = 0
        var pts = ptsUs
        val deadline = System.currentTimeMillis() + 2000
        while (true) {
            val idx = ac.dequeueInputBuffer(10_000)
            if (idx < 0) {
                if (System.currentTimeMillis() > deadline) return
                continue
            }
            val inBuf = ac.getInputBuffer(idx) ?: return
            inBuf.clear()
            val chunk = minOf(len - offset, inBuf.remaining())
            if (chunk > 0) inBuf.put(data, offset, chunk)
            val last = offset + chunk >= len
            val flags = if (eos && last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            ac.queueInputBuffer(idx, 0, chunk, pts, flags)
            offset += chunk
            pts += (chunk / 4) * 1_000_000L / SAMPLE_RATE
            if (last) return
        }
    }

    private fun drainAudio(ac: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (true) {
            val idx = ac.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || System.currentTimeMillis() > deadline) return
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, ac.outputFormat)
            } else if (idx >= 0) {
                val buf = ac.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null) writeSample(false, buf, info)
                ac.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    // ---------------- Muxer ----------------

    private fun addTrack(isVideo: Boolean, format: MediaFormat) {
        synchronized(lock) {
            val m = muxer ?: return
            if (muxerStarted) return
            if (isVideo && videoTrack < 0) videoTrack = m.addTrack(format)
            if (!isVideo && audioTrack < 0) audioTrack = m.addTrack(format)
            val added = (if (videoTrack >= 0) 1 else 0) + (if (audioTrack >= 0) 1 else 0)
            if (added == expectedTracks) {
                m.start()
                muxerStarted = true
                // Ask for a key frame right away so the file starts cleanly.
                try {
                    videoCodec?.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    })
                } catch (_: Exception) {}
            }
        }
    }

    private fun writeSample(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val m = muxer ?: return
            if (!muxerStarted) return
            if (isVideo) {
                if (!gotKeyFrame) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
                    gotKeyFrame = true
                }
                if (info.presentationTimeUs <= lastVideoPts) return
                lastVideoPts = info.presentationTimeUs
            } else {
                if (!gotKeyFrame) return // keep audio aligned with the first video frame
                if (info.presentationTimeUs <= lastAudioPts) return
                lastAudioPts = info.presentationTimeUs
            }
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            m.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
            samplesWritten++
        }
    }

    // ---------------- Stop / release ----------------

    /** Finishes the file. Blocking; call off the main thread. */
    fun stop() {
        if (!running) { releaseAll(deleteIfEmpty = true); return }
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {} // unblocks a waiting read()
        videoThread?.join(4000)
        audioThread?.join(4000)
        releaseAll(deleteIfEmpty = true)
    }

    private fun releaseAudio() {
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
    }

    private fun releaseAll(deleteIfEmpty: Boolean) {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        releaseAudio()
        synchronized(lock) {
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) {
                Log.w(App.TAG, "Muxer stop failed: ${e.message}")
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxerStarted = false
        }
        if (deleteIfEmpty && samplesWritten == 0) output.delete()
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }
}
