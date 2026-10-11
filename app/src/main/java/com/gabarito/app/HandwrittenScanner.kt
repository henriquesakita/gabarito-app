package com.gabarito.app

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Descrição do modelo de folha, lida de assets/templates/*.json (ordem das questões: linha a linha). */
data class SheetTemplate(
    val name: String, val rows: Int, val cols: Int, val alternatives: String,
    val cellW: Int, val cellH: Int, val x0: Double, val x1: Double, val y0: Double, val y1: Double
) {
    val questions get() = rows * cols

    companion object {
        fun load(ctx: Context, asset: String): SheetTemplate {
            val j = JSONObject(ctx.assets.open(asset).bufferedReader().readText())
            return SheetTemplate(
                j.getString("name"), j.getInt("rows"), j.getInt("cols"), j.getString("alternatives"),
                j.getInt("cell_w"), j.getInt("cell_h"),
                j.getDouble("letter_x0"), j.getDouble("letter_x1"), j.getDouble("letter_y0"), j.getDouble("letter_y1")
            )
        }
    }
}

object HandwrittenScanner {
    /**
     * corners = cantos da faixa de respostas na ordem de leitura (sup.esq, sup.dir, inf.dir, inf.esq),
     * em pixels do bitmap. Retorna uma imagem binarizada (letra preta, fundo branco) por questão.
     */
    fun cropCells(bmp: Bitmap, corners: List<Point>, t: SheetTemplate): List<Bitmap> {
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val w = (t.cols * t.cellW).toDouble(); val h = (t.rows * t.cellH).toDouble()
        val m = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*corners.toTypedArray()),
            MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
        )
        val warp = Mat(); Imgproc.warpPerspective(gray, warp, m, Size(w, h))
        Imgproc.GaussianBlur(warp, warp, Size(3.0, 3.0), 0.0)

        return (0 until t.questions).map { i ->
            val r = i / t.cols; val c = i % t.cols
            val roi = warp.submat(Rect(
                (c * t.cellW + t.x0 * t.cellW).toInt(), (r * t.cellH + t.y0 * t.cellH).toInt(),
                ((t.x1 - t.x0) * t.cellW).toInt(), ((t.y1 - t.y0) * t.cellH).toInt()
            ))
            // tinta = pixels bem mais escuros que o papel (média do recorte ≈ nível do papel)
            val bin = Mat(); Imgproc.threshold(roi, bin, Core.mean(roi).`val`[0] * 0.65, 255.0, Imgproc.THRESH_BINARY)
            val pad = Mat(); Core.copyMakeBorder(bin, pad, 40, 40, 40, 40, Core.BORDER_CONSTANT, Scalar(255.0))
            Bitmap.createBitmap(pad.cols(), pad.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(pad, it) }
        }
    }

    /** OCR on-device (ML Kit). Chame fora da thread principal. "" = não reconheceu / em branco. */
    fun recognize(cells: List<Bitmap>, alternatives: String): List<String> {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return cells.map { b ->
            val text = Tasks.await(rec.process(InputImage.fromBitmap(b, 0))).text.uppercase()
            text.firstOrNull { it in alternatives }?.toString() ?: ""
        }
    }
}
