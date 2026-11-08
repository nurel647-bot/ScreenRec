package com.example.screenrec

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Настройки записи, которые выбирает пользователь. */
data class RecConfig(
    val maxShortSide: Int,   // Int.MAX_VALUE = родное разрешение экрана
    val fps: Int,
    val preferHevc: Boolean,
    val quality: Int,        // 0 стандартное, 1 высокое, 2 максимальное
    val audio: Boolean
)

/**
 * Muxer, который дожидается всех дорожек (видео + аудио) перед стартом
 * и безопасно принимает данные из двух потоков.
 */
internal class SafeMuxer(private val muxer: MediaMuxer, private var expectedTracks: Int) {
    private class Pending(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private var started = false
    private var added = 0
    private val pending = ArrayList<Pending>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val t = muxer.addTrack(format)
        added++
        tryStart()
        return t
    }

    /** Дорожка не состоялась (например, упал звук) — не ждём её. */
    @Synchronized
    fun trackFailed() {
        expectedTracks--
        tryStart()
    }

    private fun tryStart() {
        if (!started && expectedTracks > 0 && added >= expectedTracks) {
            muxer.start()
            started = true
            for (p in pending) muxer.writeSampleData(p.track, p.data, p.info)
            pending.clear()
        }
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started) {
            muxer.writeSampleData(track, buf, info)
            return
        }
        // Пока не знаем формат второй дорожки — копим данные в памяти.
        val copy = ByteBuffer.allocate(info.size)
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        copy.put(buf)
        copy.flip()
        val i = MediaCodec.BufferInfo()
        i.set(0, info.size, info.presentationTimeUs, info.flags)
        pending.add(Pending(track, copy, i))
    }

    /** true — файл корректно закрыт и в нём есть данные. */
    @Synchronized
    fun finish(): Boolean {
        var ok = false
        try {
            if (started) {
                muxer.stop()
                ok = true
            }
        } catch (e: Throwable) {
            Log.w("ScreenRec", "muxer.stop failed", e)
        }
        try {
            muxer.release()
        } catch (e: Throwable) {
            Log.w("ScreenRec", "muxer.release failed", e)
        }
        return ok
    }
}

/**
 * Запись экрана: MediaProjection -> VirtualDisplay -> аппаратный энкодер (Surface) -> MP4.
 * Кадры идут напрямую в видеокодек через Surface, без копирования через CPU —
 * поэтому нагрузка на процессор минимальна. Звук системы: AudioPlaybackCapture -> AAC.
 */
class ScreenRecorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val cfg: RecConfig
) {
    companion object {
        private const val TAG = "ScreenRec"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private const val SAMPLE_RATE = 48000
    }

    private class Plan(
        val codec: MediaCodecInfo,
        val mime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val vbr: Boolean,
        val highProfile: Boolean
    )

    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var stopping = false
    private var baseUs = 0L

    private var videoEnc: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var vDisplay: VirtualDisplay? = null
    private var audioEnc: MediaCodec? = null
    private var audioRec: AudioRecord? = null
    private var mux: SafeMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // --- пауза ---
    private val pauseLock = Any()
    private val pauses = ArrayList<LongArray>()   // завершённые паузы: [начало, конец] в мкс от baseUs
    private var pauseStartRel = 0L

    @Volatile
    var paused = false
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Система остановила проекцию (например, пользователь нажал «Остановить» в статус-баре).
            main.post { stop() }
        }
    }

    /** Вызывается в главном потоке, когда файл полностью записан (uri == null при ошибке). */
    var onFinished: ((Uri?) -> Unit)? = null

    // ---------------------------------------------------------------- старт

    /** Запускает запись. Возвращает описание параметров. При ошибке освобождает всё и бросает исключение. */
    fun start(): String {
        try {
            return startInternal()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            abort()
            throw t
        }
    }

    private fun startInternal(): String {
        val dm = DisplayMetrics()
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)

        val plan = choosePlan(dm.widthPixels, dm.heightPixels)
            ?: error("Видеокодек устройства не поддерживает запись в нужном разрешении")

        var audioOn = cfg.audio &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        baseUs = System.nanoTime() / 1000
        // С Android 14 callback нужно зарегистрировать до createVirtualDisplay.
        projection.registerCallback(projectionCallback, main)

        if (audioOn) {
            try {
                setupAudio()
            } catch (e: Throwable) {
                Log.w(TAG, "audio setup failed, recording without sound", e)
                audioOn = false
                try { audioRec?.release() } catch (x: Throwable) { }
                audioRec = null
                try { audioEnc?.release() } catch (x: Throwable) { }
                audioEnc = null
            }
        }

        val (uri, fd) = createOutput()
        outUri = uri
        pfd = fd
        val muxer = SafeMuxer(
            MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            if (audioOn) 2 else 1
        )
        mux = muxer

        val enc = createVideoEncoder(plan)
        videoEnc = enc
        val surface = enc.createInputSurface()
        inputSurface = surface
        enc.start()
        videoThread = Thread({ videoLoop(enc, muxer) }, "rec-video").also { it.start() }

        vDisplay = projection.createVirtualDisplay(
            "ScreenRec",
            plan.width, plan.height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        val rec = audioRec
        val aenc = audioEnc
        if (audioOn && rec != null && aenc != null) {
            audioThread = Thread({ audioLoop(rec, aenc, muxer) }, "rec-audio").also { it.start() }
        }

        val codecName = if (plan.mime == MIME_HEVC) "HEVC" else "H.264"
        return "${plan.width}×${plan.height} · ${plan.fps} к/с · $codecName · " +
            "${plan.bitrate / 1_000_000} Мбит/с" + if (audioOn) " · со звуком" else " · без звука"
    }

    // ------------------------------------------------- выбор кодека и размера

    private fun findEncoder(mime: String): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }   // сначала аппаратные
            .firstOrNull()
    }

    private fun choosePlan(nw: Int, nh: Int): Plan? {
        val mimes = if (cfg.preferHevc) listOf(MIME_HEVC, MIME_AVC) else listOf(MIME_AVC)
        for (mime in mimes) {
            val ci = findEncoder(mime) ?: continue
            val caps = try { ci.getCapabilitiesForType(mime) } catch (e: Throwable) { continue }
            val vc = caps.videoCapabilities ?: continue
            val (w, h, fps) = chooseSize(vc, nw, nh) ?: continue

            // Бит на пиксель: AVC базовый, HEVC эффективнее примерно на треть.
            val bpp = when (cfg.quality) {
                0 -> 0.08
                1 -> 0.12
                else -> 0.20
            }
            val factor = if (mime == MIME_HEVC) 0.65 else 1.0
            val raw = (w.toLong() * h * fps * bpp * factor).toLong()
            val bitrate = raw.coerceIn(vc.bitrateRange.lower.toLong(), vc.bitrateRange.upper.toLong()).toInt()

            val vbr = caps.encoderCapabilities?.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            ) == true
            val high = mime == MIME_AVC && caps.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            }
            return Plan(ci, mime, w, h, fps, bitrate, vbr, high)
        }
        return null
    }

    /** Подбирает самое большое поддерживаемое разрешение (не больше выбранного и не больше экрана). */
    private fun chooseSize(vc: MediaCodecInfo.VideoCapabilities, nw: Int, nh: Int): Triple<Int, Int, Int>? {
        val nativeShort = min(nw, nh)
        val nativeLong = max(nw, nh)
        val top = min(cfg.maxShortSide, nativeShort)
        val shorts = (listOf(top) + listOf(2160, 1440, 1080, 720, 480).filter { it < top }).distinct()
        val fpsList = listOf(cfg.fps, 30).distinct().filter { it <= cfg.fps }
        val portrait = nw <= nh

        for (s in shorts) {
            val l = (s.toLong() * nativeLong / nativeShort).toInt()
            for (align in intArrayOf(2, 16)) {
                val sa = s / align * align
                val la = l / align * align
                val w = if (portrait) sa else la
                val h = if (portrait) la else sa
                for (f in fpsList) {
                    if (vc.areSizeAndRateSupported(w, h, f.toDouble())) return Triple(w, h, f)
                }
            }
        }
        return null
    }

    private fun createVideoEncoder(plan: Plan): MediaCodec {
        fun format(full: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat(plan.mime, plan.width, plan.height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (full) {
                if (plan.vbr) {
                    f.setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
                if (plan.highProfile) {
                    f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
                f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                // Не кодировать лишние кадры на экранах 90/120 Гц.
                f.setInteger("max-fps-to-encoder", plan.fps)
                f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            return f
        }

        var enc = MediaCodec.createByCodecName(plan.codec.name)
        try {
            enc.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "full format rejected, retrying with minimal", e)
            try { enc.release() } catch (x: Throwable) { }
            enc = MediaCodec.createByCodecName(plan.codec.name)
            enc.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return enc
    }

    // ------------------------------------------------------------------ звук

    private fun setupAudio() {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val af = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(capture)
            .setAudioFormat(af)
            // ~2 секунды запаса, чтобы звук не терялся, пока игра грузит процессор
            .setBufferSizeInBytes(max(minBuf * 4, SAMPLE_RATE * 4 * 2))
            .build()
        audioRec = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord не инициализирован")

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEnc = enc
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
    }

    // --------------------------------------------------------------- потоки

    private fun videoLoop(enc: MediaCodec, muxer: SafeMuxer) {
        // Если этот поток «голодает» под нагрузкой игры, кодек перестаёт принимать кадры.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Throwable) { }
        val info = MediaCodec.BufferInfo()
        var track = -1
        var offset = Long.MIN_VALUE
        var last = -1L
        try {
            var running = true
            while (running) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.outputFormat)
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0) {
                        val nowUs = System.nanoTime() / 1000
                        if (offset == Long.MIN_VALUE) {
                            // Метки кадров с Surface обычно в часах CLOCK_MONOTONIC (как nanoTime).
                            // Если у устройства они относительные — привязываемся к текущему времени.
                            offset = if (abs(info.presentationTimeUs - baseUs) < 5_000_000L) baseUs
                            else info.presentationTimeUs - (nowUs - baseUs)
                        }
                        val rel = max(0L, info.presentationTimeUs - offset)
                        val shift = pauseShiftFor(rel)
                        // shift < 0: кадр снят во время паузы — пропускаем
                        if (shift >= 0) {
                            var pts = rel - shift
                            if (pts <= last) pts = last + 1
                            last = pts
                            info.presentationTimeUs = pts
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.write(track, buf, info)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) running = false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "video loop failed", e)
            main.post { stop() }
        }
    }

    private fun audioLoop(rec: AudioRecord, enc: MediaCodec, muxer: SafeMuxer) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Throwable) { }
        val info = MediaCodec.BufferInfo()
        var track = -1
        var last = -1L
        var frames = 0L      // сколько кадров звука уже отдано кодеку (включая вставленную тишину)
        var startUs = 0L     // момент старта записи звука относительно baseUs

        fun drain(): Boolean {
            var eos = false
            var more = true
            while (more) {
                val idx = enc.dequeueOutputBuffer(info, 0)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.outputFormat)
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0) {
                        var pts = max(0L, info.presentationTimeUs)
                        if (pts <= last) pts = last + 1
                        last = pts
                        info.presentationTimeUs = pts
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.write(track, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eos = true
                        more = false
                    }
                } else {
                    more = false
                }
            }
            return eos
        }

        fun feed(data: ByteArray, size: Int, ptsUs: Long, eos: Boolean) {
            while (true) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val ib = enc.getInputBuffer(i)!!
                    ib.clear()
                    if (size > 0) ib.put(data, 0, size)
                    enc.queueInputBuffer(
                        i, 0, size, ptsUs,
                        if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )
                    return
                }
                drain()
            }
        }

        // Вставляет тишину, чтобы звук не «убегал» вперёд видео, если часть данных потерялась.
        fun feedSilence(frameCount: Long) {
            val zeros = ByteArray(8192)
            var left = frameCount
            while (left > 0) {
                val f = min(left, 2048L)   // 2048 кадров * 4 байта = 8192 байта
                feed(zeros, (f * 4).toInt(), startUs + frames * 1_000_000L / SAMPLE_RATE, false)
                frames += f
                left -= f
                drain()
            }
        }

        try {
            val pcm = ByteArray(8192)
            rec.startRecording()
            startUs = System.nanoTime() / 1000 - baseUs
            while (!stopping) {
                val t0 = System.nanoTime()
                val n = rec.read(pcm, 0, pcm.size)
                val t1 = System.nanoTime()
                if (n > 0 && paused) {
                    continue   // на паузе звук читаем и выбрасываем, чтобы буфер не переполнялся
                }
                if (n > 0) {
                    // Если чтение ждало данные (буфер был пуст), мы «догнали» реальное время.
                    // Тогда звуковая дорожка не должна отставать от часов больше, чем на ~100 мс:
                    // иначе часть звука была потеряна, и мы заполняем разрыв тишиной.
                    if (t1 - t0 > 3_000_000L) {
                        val nowRel = t1 / 1000 - baseUs
                        val endRel = startUs + (frames + n / 4) * 1_000_000L / SAMPLE_RATE
                        val lagUs = nowRel - totalPausedUs() - endRel
                        if (lagUs > 120_000L) {
                            feedSilence((lagUs - 20_000L) * SAMPLE_RATE / 1_000_000L)
                        }
                    }
                    feed(pcm, n, startUs + frames * 1_000_000L / SAMPLE_RATE, false)
                    frames += n / 4   // 2 канала * 2 байта
                    drain()
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    break
                }
            }
            feed(pcm, 0, startUs + frames * 1_000_000L / SAMPLE_RATE, true)
            var eos = false
            var guard = 0
            while (!eos && guard++ < 500) {
                eos = drain()
                if (!eos) Thread.sleep(10)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "audio loop failed, continuing without sound", e)
            muxer.trackFailed()
        }
    }

    // ------------------------------------------------------------- остановка

    // ----------------------------------------------------------------- пауза

    private fun nowRel() = System.nanoTime() / 1000 - baseUs

    /** Ставит запись на паузу: экран перестаёт попадать в кодек, нагрузки нет. */
    fun pause() {
        if (stopped.get()) return
        synchronized(pauseLock) {
            if (paused) return
            pauseStartRel = nowRel()
            paused = true
        }
        try { vDisplay?.setSurface(null) } catch (e: Throwable) { Log.w(TAG, "pause: setSurface(null)", e) }
    }

    /** Продолжает запись. Время паузы вырезается из видео и звука. */
    fun resume() {
        if (stopped.get()) return
        synchronized(pauseLock) {
            if (!paused) return
            pauses.add(longArrayOf(pauseStartRel, nowRel()))
            paused = false
        }
        try { vDisplay?.setSurface(inputSurface) } catch (e: Throwable) { Log.w(TAG, "resume: setSurface", e) }
        try {
            // Первый кадр после паузы делаем опорным, чтобы не было артефактов.
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            videoEnc?.setParameters(b)
        } catch (e: Throwable) { }
    }

    /** Сдвиг времени из-за пауз для кадра с меткой p; -1 — кадр снят во время паузы. */
    private fun pauseShiftFor(p: Long): Long = synchronized(pauseLock) {
        var shift = 0L
        for (iv in pauses) {
            if (p >= iv[1]) shift += iv[1] - iv[0]
            else if (p >= iv[0]) return@synchronized -1L
        }
        if (paused && p >= pauseStartRel) -1L else shift
    }

    private fun totalPausedUs(): Long = synchronized(pauseLock) {
        var s = 0L
        for (iv in pauses) s += iv[1] - iv[0]
        s
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        Thread({ doStop() }, "rec-stop").start()
    }

    private fun doStop() {
        try { vDisplay?.release() } catch (e: Throwable) { Log.w(TAG, "display release", e) }
        try { videoEnc?.signalEndOfInputStream() } catch (e: Throwable) { Log.w(TAG, "signal EOS", e) }

        stopping = true
        try { videoThread?.join(5000) } catch (e: Throwable) { }
        val at = audioThread
        if (at != null) {
            try { at.join(3000) } catch (e: Throwable) { }
            if (at.isAlive) {
                try { audioRec?.stop() } catch (e: Throwable) { }
                try { at.join(2000) } catch (e: Throwable) { }
            }
        }

        try { projection.unregisterCallback(projectionCallback) } catch (e: Throwable) { }
        try { projection.stop() } catch (e: Throwable) { }

        releaseCodecs()

        val ok = try { mux?.finish() ?: false } catch (e: Throwable) { false }
        try { pfd?.close() } catch (e: Throwable) { }
        pfd = null

        val uri = outUri
        var result: Uri? = null
        if (uri != null) {
            try {
                if (ok) {
                    val v = ContentValues()
                    v.put(MediaStore.Video.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, v, null, null)
                    result = uri
                } else {
                    ctx.contentResolver.delete(uri, null, null)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "finalize uri failed", e)
            }
        }
        main.post { onFinished?.invoke(result) }
    }

    private fun releaseCodecs() {
        try { videoEnc?.stop() } catch (e: Throwable) { }
        try { videoEnc?.release() } catch (e: Throwable) { }
        videoEnc = null
        try { inputSurface?.release() } catch (e: Throwable) { }
        inputSurface = null
        try { audioEnc?.stop() } catch (e: Throwable) { }
        try { audioEnc?.release() } catch (e: Throwable) { }
        audioEnc = null
        try { audioRec?.stop() } catch (e: Throwable) { }
        try { audioRec?.release() } catch (e: Throwable) { }
        audioRec = null
    }

    /** Аварийная очистка, если запись не удалось запустить. */
    private fun abort() {
        stopped.set(true)
        stopping = true
        try { vDisplay?.release() } catch (e: Throwable) { }
        try { projection.unregisterCallback(projectionCallback) } catch (e: Throwable) { }
        try { projection.stop() } catch (e: Throwable) { }
        releaseCodecs()
        try { mux?.finish() } catch (e: Throwable) { }
        try { pfd?.close() } catch (e: Throwable) { }
        val uri = outUri
        if (uri != null) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (e: Throwable) { }
        }
    }

    // ---------------------------------------------------------- выходной файл

    private fun createOutput(): Pair<Uri, ParcelFileDescriptor> {
        val name = "ScreenRec_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в Movies/ScreenRec")
        val fd = resolver.openFileDescriptor(uri, "rw")
            ?: error("Не удалось открыть файл для записи")
        return Pair(uri, fd)
    }
}
