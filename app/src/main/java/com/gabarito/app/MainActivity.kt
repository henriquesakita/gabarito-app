package com.gabarito.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        setContent { MaterialTheme { App() } }
    }
}

private enum class Stage { INPUT, CORNERS, REVIEW }

private fun load(ctx: android.content.Context, uri: Uri): Bitmap? {
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
    } ?: return null
    val rot = ctx.contentResolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    return rotate(bmp, rot)
}

private fun rotate(b: Bitmap, deg: Float) =
    if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val template = remember { SheetTemplate.load(ctx, "templates/saresp_24.json") }
    var stage by remember { mutableStateOf(Stage.INPUT) }
    var key by remember { mutableStateOf("") }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf(listOf<Offset>()) }
    var cells by remember { mutableStateOf(listOf<Bitmap>()) }
    var letters by remember { mutableStateOf(listOf<String>()) }
    var result by remember { mutableStateOf<Grade?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val photo = remember { File(ctx.cacheDir, "scan.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photo) }
    fun open(uri: Uri) { bmp = load(ctx, uri); corners = emptyList(); result = null; msg = ""; stage = Stage.CORNERS }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { if (it) open(photoUri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::open) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Corretor de gabaritos", style = MaterialTheme.typography.headlineSmall)
        Text("Modelo: ${template.name}", style = MaterialTheme.typography.bodySmall)

        when (stage) {
            Stage.INPUT -> {
                OutlinedTextField(key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gabarito oficial (${template.questions} letras)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ camera.launch(photoUri) }) { Text("Fotografar") }
                    OutlinedButton({ gallery.launch("image/*") }) { Text("Da galeria") }
                }
            }
            Stage.CORNERS -> bmp?.let { b ->
                Text("Toque nos 4 cantos da faixa de respostas (as duas linhas de caixas), na ordem de leitura: " +
                    "superior esquerdo, superior direito, inferior direito, inferior esquerdo. Quanto mais perto a foto, melhor.")
                Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(corners.size) { detectTapGestures { if (corners.size < 4) corners = corners + it } }) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        corners.forEachIndexed { i, p ->
                            drawCircle(Color.Red, 10f, p)
                            if (i > 0) drawLine(Color.Red, corners[i - 1], p, 3f)
                        }
                        if (corners.size == 4) drawLine(Color.Red, corners[3], corners[0], 3f)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ corners = corners.dropLast(1) }) { Text("Desfazer") }
                    OutlinedButton({ bmp = rotate(b, 90f); corners = emptyList() }) { Text("Girar 90°") }
                    Button(enabled = corners.size == 4 && !busy, onClick = {
                        scope.launch {
                            busy = true
                            val sx = b.width.toDouble() / viewSize.width; val sy = b.height.toDouble() / viewSize.height
                            val pts = corners.map { Point(it.x * sx, it.y * sy) }
                            val (c, l) = withContext(Dispatchers.Default) {
                                val cs = HandwrittenScanner.cropCells(b, pts, template)
                                cs to HandwrittenScanner.recognize(cs, template.alternatives)
                            }
                            cells = c; letters = l; stage = Stage.REVIEW; busy = false
                        }
                    }) { Text(if (busy) "Lendo..." else "Ler respostas") }
                }
            }
            Stage.REVIEW -> {
                Text("Confira as letras lidas e corrija o que for preciso antes de corrigir a prova.")
                letters.indices.chunked(4).forEach { rowIdx ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowIdx.forEach { i ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(cells[i].asImageBitmap(), null, Modifier.size(72.dp))
                                OutlinedTextField(letters[i], { v ->
                                    val ch = v.uppercase().lastOrNull()?.takeIf { it in template.alternatives }?.toString() ?: ""
                                    letters = letters.toMutableList().also { it[i] = ch }
                                }, singleLine = true, label = { Text("${i + 1}") }, modifier = Modifier.width(80.dp))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val k = key.uppercase().filter { it in template.alternatives }
                        if (k.length < template.questions) { msg = "Gabarito oficial incompleto (${k.length}/${template.questions})." }
                        else {
                            msg = ""
                            result = grade(letters.map { if (it.isEmpty()) OmrScanner.BLANK else it[0] - 'A' }, k)
                        }
                    }) { Text("Corrigir") }
                    OutlinedButton({ stage = Stage.INPUT }) { Text("Nova prova") }
                }
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
                result?.let {
                    Text("Acertos: ${it.correct}/${it.total}  (%.1f%%)".format(it.percent), style = MaterialTheme.typography.titleLarge)
                    Text("Erros: ${it.wrong}  •  Em branco: ${it.blank}")
                }
            }
        }
    }
}
