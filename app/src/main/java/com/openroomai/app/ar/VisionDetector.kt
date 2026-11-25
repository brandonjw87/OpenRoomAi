package com.openroomai.app.ar

import android.content.Context
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.openroomai.app.engine.Detection
import com.openroomai.app.engine.NBox
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * On-device furniture recognition with EfficientDet-Lite0 (COCO) on LiteRT. Runs off the GL
 * thread; returns detections in the upright (display-oriented) image.
 */
class VisionDetector private constructor(private val interpreter: Interpreter, private val labels: List<String>) : Closeable {
    private val input: ByteBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val boxes = Array(1) { Array(MAX) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(MAX) }
    private val scores = Array(1) { FloatArray(MAX) }
    private val count = FloatArray(1)

    /** Wall-clock time of the last inference (ms), for the Device Check. */
    @Volatile var lastMillis = 0L
        private set

    @Synchronized
    fun detect(rgb: ByteArray): List<Detection> {
        val t0 = SystemClock.elapsedRealtime()
        input.rewind(); input.put(rgb, 0, SIZE * SIZE * 3); input.rewind()
        val outputs = HashMap<Int, Any>()
        outputs[0] = boxes; outputs[1] = classes; outputs[2] = scores; outputs[3] = count
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        var cls = classes[0]
        var sc = scores[0]
        // Defensive: class ids are integers > 1, scores are in 0…1. Swap if a runtime ever reorders them.
        if (sc.any { it > 1.001f } && cls.all { it <= 1.001f }) { val tmp = cls; cls = sc; sc = tmp }
        val n = count[0].toInt().coerceIn(0, MAX)
        val out = ArrayList<Detection>(n)
        for (i in 0 until n) {
            val label = labels.getOrNull(cls[i].roundToInt()) ?: continue
            val b = boxes[0][i] // ymin, xmin, ymax, xmax
            out += Detection(
                label, sc[i],
                NBox(b[1].coerceIn(0f, 1f), b[0].coerceIn(0f, 1f), b[3].coerceIn(0f, 1f), b[2].coerceIn(0f, 1f)),
                meanColor(rgb, NBox(b[1], b[0], b[3], b[2])),
            )
        }
        lastMillis = SystemClock.elapsedRealtime() - t0
        return out
    }

    override fun close() = interpreter.close()

    companion object {
        const val SIZE = 320
        const val MAX = 25
        private const val TAG = "OpenRoomVision"

        /** Null if the model or the native runtime isn't available on this device. */
        fun create(context: Context): VisionDetector? = try {
            val fd = context.assets.openFd("models/efficientdet_lite0.tflite")
            val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val labels = context.assets.open("models/coco_labels.txt").bufferedReader().readLines()
            val opts = Interpreter.Options().apply { numThreads = 4 }
            VisionDetector(Interpreter(model, opts), labels)
        } catch (t: Throwable) {
            Log.w(TAG, "vision unavailable", t)
            null
        }

        /**
         * ARCore CPU image (YUV_420_888, sensor orientation) → upright [SIZE]² RGB bytes, rotating by
         * [rotationDeg] (clockwise) and resampling in one pass. Strides are honoured for any device.
         */
        fun yuvToUprightRgb(image: Image, rotationDeg: Int, out: ByteArray) {
            val w = image.width; val h = image.height
            val p = image.planes
            val yb = p[0].buffer; val yr = p[0].rowStride; val yp = p[0].pixelStride
            val ub = p[1].buffer; val ur = p[1].rowStride; val up = p[1].pixelStride
            val vb = p[2].buffer; val vr = p[2].rowStride; val vp = p[2].pixelStride
            val rot = ((rotationDeg % 360) + 360) % 360
            var o = 0
            for (oy in 0 until SIZE) {
                val fy = (oy + 0.5f) / SIZE
                for (ox in 0 until SIZE) {
                    val fx = (ox + 0.5f) / SIZE
                    val sxN: Float; val syN: Float
                    when (rot) {
                        90 -> { sxN = fy; syN = 1f - fx }
                        180 -> { sxN = 1f - fx; syN = 1f - fy }
                        270 -> { sxN = 1f - fy; syN = fx }
                        else -> { sxN = fx; syN = fy }
                    }
                    val sx = (sxN * w).toInt().coerceIn(0, w - 1)
                    val sy = (syN * h).toInt().coerceIn(0, h - 1)
                    val y = yb.get(sy * yr + sx * yp).toInt() and 0xFF
                    val cx = sx shr 1; val cy = sy shr 1
                    val u = (ub.get(cy * ur + cx * up).toInt() and 0xFF) - 128
                    val v = (vb.get(cy * vr + cx * vp).toInt() and 0xFF) - 128
                    out[o++] = clamp(y + 1.402f * v)
                    out[o++] = clamp(y - 0.344136f * u - 0.714136f * v)
                    out[o++] = clamp(y + 1.772f * u)
                }
            }
        }

        private fun clamp(v: Float): Byte = v.roundToInt().coerceIn(0, 255).toByte()

        /** Average colour of the middle half of [box] — the object, not its surroundings. */
        fun meanColor(rgb: ByteArray, box: NBox): Int? {
            val l = ((box.l + box.w * 0.25f) * SIZE).toInt().coerceIn(0, SIZE - 1)
            val r = ((box.r - box.w * 0.25f) * SIZE).toInt().coerceIn(0, SIZE - 1)
            val t = ((box.t + box.h * 0.25f) * SIZE).toInt().coerceIn(0, SIZE - 1)
            val b = ((box.b - box.h * 0.25f) * SIZE).toInt().coerceIn(0, SIZE - 1)
            if (r <= l || b <= t) return null
            var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
            var y = t
            while (y <= b) {
                var x = l
                while (x <= r) {
                    val i = (y * SIZE + x) * 3
                    sr += rgb[i].toInt() and 0xFF; sg += rgb[i + 1].toInt() and 0xFF; sb += rgb[i + 2].toInt() and 0xFF; n++
                    x += 2
                }
                y += 2
            }
            if (n == 0) return null
            return (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
        }
    }
}
