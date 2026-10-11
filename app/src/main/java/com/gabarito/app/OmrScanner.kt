package com.gabarito.app

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/** Geometria da folha no espaço "normalizado" (800x1100). Deve ser idêntica à de generate_sheet.py. */
object SheetLayout {
    const val W = 800; const val H = 1100
    const val COLS = 2; const val ROWS = 25; const val OPTS = 5
    const val X0 = 120.0; const val COL_GAP = 360.0; const val OPT_GAP = 50.0
    const val Y0 = 140.0; const val ROW_GAP = 36.0
    const val MARK_CENTER = 40.0
    const val MAX_Q = COLS * ROWS
    fun center(q: Int, o: Int) = Point(X0 + (q / ROWS) * COL_GAP + o * OPT_GAP, Y0 + (q % ROWS) * ROW_GAP)
}

object OmrScanner {
    const val BLANK = -1
    const val MULTIPLE = -2
    private const val FILL_THRESHOLD = 0.45   // fração mínima de tinta dentro da bolha
    private const val R = 8                   // raio de leitura (menor que a bolha impressa: ignora o contorno)

    /** Retorna uma resposta por questão (0..4 = A..E, BLANK, MULTIPLE) ou null se não achou os 4 marcadores. */
    fun scan(bmp: Bitmap, questions: Int): List<Int>? {
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val k = 1600.0 / max(gray.cols(), gray.rows())
        if (k < 1) Imgproc.resize(gray, gray, Size(), k, k, Imgproc.INTER_AREA)
        val blur = Mat(); Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

        val bin = Mat()
        Imgproc.adaptiveThreshold(blur, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 51, 15.0)
        val corners = findMarkers(bin) ?: return null

        val c = SheetLayout.MARK_CENTER
        val dst = MatOfPoint2f(Point(c, c), Point(SheetLayout.W - c, c),
            Point(SheetLayout.W - c, SheetLayout.H - c), Point(c, SheetLayout.H - c))
        val m = Imgproc.getPerspectiveTransform(MatOfPoint2f(*corners), dst)
        val warped = Mat()
        Imgproc.warpPerspective(blur, warped, m, Size(SheetLayout.W.toDouble(), SheetLayout.H.toDouble()))
        Imgproc.threshold(warped, warped, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        val mask = Mat.zeros(2 * R + 1, 2 * R + 1, CvType.CV_8U)
        Imgproc.circle(mask, Point(R.toDouble(), R.toDouble()), R, Scalar(255.0), -1)

        return (0 until questions.coerceAtMost(SheetLayout.MAX_Q)).map { q ->
            val fills = (0 until SheetLayout.OPTS).map { o ->
                val p = SheetLayout.center(q, o)
                val roi = warped.submat(Rect((p.x - R).toInt(), (p.y - R).toInt(), 2 * R + 1, 2 * R + 1))
                Core.mean(roi, mask).`val`[0] / 255.0
            }
            val marked = fills.indices.filter { fills[it] > FILL_THRESHOLD }
            when (marked.size) { 0 -> BLANK; 1 -> marked[0]; else -> MULTIPLE }
        }
    }

    /** Procura os 4 quadrados pretos: o candidato mais próximo de cada canto da imagem. */
    private fun findMarkers(bin: Mat): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val minA = bin.total() * 0.0004; val maxA = bin.total() * 0.02
        val cand = contours.mapNotNull { c ->
            val a = Imgproc.contourArea(c)
            if (a < minA || a > maxA) return@mapNotNull null
            val r = Imgproc.boundingRect(c)
            val ratio = r.width.toDouble() / r.height
            if (ratio !in 0.7..1.3 || a / (r.width * r.height) < 0.8) null
            else Point(r.x + r.width / 2.0, r.y + r.height / 2.0)
        }
        if (cand.size < 4) return null
        val w = bin.cols().toDouble(); val h = bin.rows().toDouble()
        val targets = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)) // TL, TR, BR, BL
        val pick = targets.map { t -> cand.minBy { hypot(it.x - t.x, it.y - t.y) } }
        return if (pick.toSet().size == 4) pick.toTypedArray() else null
    }
}

data class Grade(val correct: Int, val wrong: Int, val blank: Int, val multiple: Int, val total: Int) {
    val percent get() = if (total == 0) 0.0 else correct * 100.0 / total
}

fun grade(answers: List<Int>, key: String): Grade {
    var ok = 0; var wrong = 0; var blank = 0; var multi = 0
    answers.forEachIndexed { i, a ->
        when {
            a == OmrScanner.BLANK -> blank++
            a == OmrScanner.MULTIPLE -> multi++     // marcação dupla = anulada
            key[i] - 'A' == a -> ok++
            else -> wrong++
        }
    }
    return Grade(ok, wrong, blank, multi, answers.size)
}
