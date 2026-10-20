package hu.serules.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { App() } } }
    }
}

/** Élő kamerakép szövegfelismerése: 3 egyező találat után elfogadja a rendszámot. */
@OptIn(ExperimentalGetImage::class)
class PlateAnalyzer(private val onPlate: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val seen = mutableMapOf<String, Int>()
    private var done = false

    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || done) { proxy.close(); return }
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(img)
            .addOnSuccessListener { t ->
                val lines = t.textBlocks.flatMap { it.lines }.map { it.text }
                // A címer miatt a rendszám két sorra is szakadhat, ezért a szomszédos sorokat is összefűzzük.
                val candidates = lines + lines.zipWithNext { a, b -> a + b }
                val plate = candidates.firstNotNullOfOrNull { Plate.parse(it) }
                if (plate != null && !done) {
                    val n = (seen[plate] ?: 0) + 1
                    seen[plate] = n
                    if (n >= 3) { done = true; onPlate(plate) }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }
}

@Composable
fun CameraView(modifier: Modifier, analyzer: ImageAnalysis.Analyzer? = null, capture: ImageCapture? = null) {
    val owner = LocalContext.current as ComponentActivity
    AndroidView(modifier = modifier, factory = { c ->
        val pv = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val uses = mutableListOf<UseCase>(preview)
            if (analyzer != null) {
                uses += ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }
            }
            if (capture != null) uses += capture
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *uses.toTypedArray())
        }, ContextCompat.getMainExecutor(c))
        pv
    })
}

/** Kép mentése: Pictures/serulesek/<RENDSZÁM>/<ÉÉÉÉ-HH-NN>/ */
fun takePhoto(ctx: Context, cap: ImageCapture, plate: String, onDone: (Boolean) -> Unit) {
    val now = Date()
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
    val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(now)
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${plate}_$time")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/serulesek/$plate/$day")
    }
    val opts = ImageCapture.OutputFileOptions.Builder(
        ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
    ).build()
    cap.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(r: ImageCapture.OutputFileResults) = onDone(true)
        override fun onError(e: ImageCaptureException) = onDone(false)
    })
}

@Composable
fun BigButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().widthIn(max = 520.dp).height(88.dp)
    if (primary) Button(onClick, m) { Text(text, fontSize = 20.sp, textAlign = TextAlign.Center) }
    else OutlinedButton(onClick, m) { Text(text, fontSize = 20.sp, textAlign = TextAlign.Center) }
}

fun exportAndShare(ctx: Context, recs: List<Rec>) {
    val uri = Xlsx.export(ctx, recs)
    if (uri == null) {
        Toast.makeText(ctx, "Az export nem sikerült", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(ctx, "Mentve: Letöltések/serulesek", Toast.LENGTH_LONG).show()
    val send = Intent(Intent.ACTION_SEND).setType(XLSX_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Megosztás"))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("home") }
    var plate by rememberSaveable { mutableStateOf("") }
    var photos by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    var recs by remember { mutableStateOf(Store.load(ctx)) }
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    BackHandler(screen != "home") {
        screen = when (screen) { "photos" -> "edit"; "note" -> "photos"; else -> "home" }
    }

    when (screen) {
        "home" -> BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🚗 Sérülésnapló", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                BigButton("Új sérülés\n(rendszám beolvasása)") { plate = ""; photos = 0; note = ""; screen = "scan" }
                recs.lastOrNull()?.let { last ->
                    BigButton("Új sérülés az előző rendszámmal\n${last.plate}", primary = false) {
                        plate = last.plate; photos = 0; note = ""; screen = "photos"
                    }
                }
                if (recs.isNotEmpty()) {
                    BigButton("📊 Export Excelbe (.xlsx)", primary = false) { exportAndShare(ctx, recs) }
                }
                BigButton("📋 Lista (${recs.size})", primary = false) { screen = "list" }
            }
        }

        "scan" -> {
            val analyzer = remember { PlateAnalyzer { p -> plate = p; photos = 0; screen = "photos" } }
            Column(Modifier.fillMaxSize()) {
                if (hasCam) CameraView(Modifier.weight(1f).fillMaxWidth(), analyzer = analyzer)
                else Text("Kamera-engedély szükséges.", Modifier.weight(1f).padding(20.dp))
                Text("Tartsd a kamerát a rendszám felé, magától felismeri.", Modifier.padding(12.dp))
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ screen = "home" }) { Text("Vissza") }
                    Button({ screen = "edit" }) { Text("Kézi megadás") }
                }
            }
        }

        "edit" -> {
            var text by rememberSaveable { mutableStateOf(plate) }
            val parsed = Plate.parse(text)
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rendszám módosítása", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(text, { text = it.uppercase() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("ABC-123 vagy ABCD-123") },
                    supportingText = { Text(if (parsed != null) "✓ $parsed" else "Érvénytelen formátum") })
                Button({ plate = parsed!!; screen = "photos" }, Modifier.fillMaxWidth(), enabled = parsed != null) { Text("Kész, tovább a fotókhoz") }
                OutlinedButton({ screen = "scan" }, Modifier.fillMaxWidth()) { Text("Újraolvasás kamerával") }
            }
        }

        "photos" -> {
            val capture = remember { ImageCapture.Builder().build() }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(plate, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    TextButton({ screen = "edit" }) { Text("Rendszám módosítása") }
                }
                if (hasCam) CameraView(Modifier.weight(1f).fillMaxWidth(), capture = capture)
                else Text("Kamera-engedély szükséges.", Modifier.weight(1f).padding(20.dp))
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        takePhoto(ctx, capture, plate) { ok ->
                            if (ok) photos++ else Toast.makeText(ctx, "A mentés nem sikerült", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("📷 Fotó ($photos)") }
                    OutlinedButton({ screen = "note" }) { Text("Tovább a megjegyzéshez") }
                }
            }
        }

        "note" -> {
            val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (t != null) note = if (note.isBlank()) t else "$note $t"
            }
            Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$plate · $photos fotó", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("Megjegyzés (sérülés mértéke, helye stb.)") })
                OutlinedButton({
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Diktáld a megjegyzést")
                    try { speech.launch(i) } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "Nincs elérhető hangfelismerés", Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.fillMaxWidth()) { Text("🎤 Diktálás") }
                Button({
                    recs = recs + Rec(System.currentTimeMillis(), plate, note.trim(), photos)
                    Store.save(ctx, recs)
                    Toast.makeText(ctx, "Mentve ✓", Toast.LENGTH_SHORT).show()
                    screen = "home"
                }, Modifier.fillMaxWidth()) { Text("💾 Mentés") }
            }
        }

        "list" -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rögzített sérülések", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recs.reversed()) { r ->
                    Column {
                        Text("${r.plate} · ${SimpleDateFormat("yyyy.MM.dd. HH:mm", Locale.US).format(Date(r.ts))} · ${r.photos} fotó", fontWeight = FontWeight.Bold)
                        Text(r.note.ifBlank { "(nincs megjegyzés)" })
                        TextButton({ recs = recs.filter { it !== r }; Store.save(ctx, recs) }) { Text("Törlés") }
                    }
                }
            }
            Button({ exportAndShare(ctx, recs) }, Modifier.fillMaxWidth(), enabled = recs.isNotEmpty()) { Text("📊 Export Excelbe (.xlsx)") }
            OutlinedButton({ screen = "home" }, Modifier.fillMaxWidth()) { Text("Vissza") }
        }
    }
}
