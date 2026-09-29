package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.AttachmentStore
import com.example.itinerary.scanner.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/** Manual photo capture followed by four-corner correction. Files stay private until the event is saved. */
@Composable
fun ManualDocumentScanner(pdf: Boolean, store: AttachmentStore, onDismiss: () -> Unit, onComplete: (List<Attachment>) -> Unit, durable: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember {
        if (durable) File(context.filesDir, "draft-scan") else File(context.cacheDir, "manual-scans/${UUID.randomUUID()}")
    }
    val sessionStore = remember { ScanSession(session) }
    val restored = remember { sessionStore.read() }
    val pages = remember { mutableStateListOf<ScanImages.Page>().apply { addAll(sessionStore.pages(restored)) } }
    var index by remember { mutableIntStateOf((restored?.optInt("index") ?: 0).coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
    var reviewing by remember { mutableStateOf(restored?.optBoolean("review") ?: false) }
    var discarded by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pageTools by remember { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    val page = pages.getOrNull(index)
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewKey by remember { mutableStateOf<Triple<File, Int, List<ScanPoint>?>?>(null) }

    val wantedPreview = page?.let { Triple(it.file, it.turns, if (reviewing) it.corners else null) }

    DisposableEffect(session) {
        onDispose {
            // Wait for decode/export/copy to leave IO before deleting their input files.
            val job = scope.coroutineContext[Job]
            scope.cancel()
            if (durable && discarded) session.deleteRecursively()
            else if (!durable) CoroutineScope(Dispatchers.IO).launch { job?.join(); session.deleteRecursively() }
        }
    }
    SideEffect {
        if (durable && !finished) try { sessionStore.save(pages.toList(), index, pendingPhoto, reviewing) }
        catch (_: Exception) { error = "Couldn't protect these pages. Keep the app open until you've saved." }
    }
    fun completeDismiss() { discarded = true; finished = true; onDismiss() }
    // Preview bitmaps are GC-owned: recycling one while Compose still draws it can crash the render thread.
    LaunchedEffect(page?.file, page?.turns, if (reviewing) page?.corners else null, reviewing) {
        preview = null; previewKey = null
        if (page != null) {
            try {
                preview = withContext(Dispatchers.IO) {
                    val decoded = ScanImages.decode(page.file, 1200)
                    val rotated = ScanImages.rotate(decoded, page.turns).also { if (it !== decoded) decoded.recycle() }
                    if (reviewing) try { ScanImages.crop(rotated, page.corners) } finally { rotated.recycle() } else rotated
                }
                previewKey = wantedPreview
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error = "This image is too large. Remove it and try a smaller image." }
            catch (_: Exception) { error = "Couldn't read this page. Remove it and try again." }
        }
    }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error = "This image is too large. Try a smaller image." }
            catch (_: Exception) { error = "Couldn't process the scan. Check available storage and try again." }
            finally { busy = false }
        }
    }
    suspend fun addImage(file: File) {
        try {
            val prepared = withContext(Dispatchers.IO) { ScanImages.preparePage(file) }
            check(pages.size < ScanImages.MAX_PAGES)
            pages += prepared; index = pages.lastIndex
        } catch (e: Throwable) { file.delete(); throw e }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingPhoto
        pendingPhoto = null
        if (file != null) {
            if (success && file.length() > 0) work { addImage(file) }
            else file.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) work {
            check(session.isDirectory || session.mkdirs())
            val destination = File(session, "${UUID.randomUUID()}.image")
            try {
                withContext(Dispatchers.IO) {
                    val source = context.contentResolver.openInputStream(uri) ?: error("Image unavailable")
                    source.use { input -> destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size; require(total <= 50L * 1024 * 1024)
                            output.write(buffer, 0, size)
                        }
                    } }
                }
                addImage(destination)
            } catch (e: Throwable) { destination.delete(); throw e }
        }
    }
    fun takePage() {
        if (busy || pendingPhoto != null || pages.size >= ScanImages.MAX_PAGES) return
        val file = File(session, "${UUID.randomUUID()}.jpg")
        try {
            check(session.isDirectory || session.mkdirs())
            pendingPhoto = file
            if (durable) sessionStore.save(pages.toList(), index, pendingPhoto, reviewing)
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } catch (_: ActivityNotFoundException) {
            pendingPhoto = null; file.delete(); error = "No camera app is available. You can import an image instead."
        } catch (_: Exception) {
            pendingPhoto = null; file.delete(); error = "Couldn't open the camera. Try again or import an image."
        }
    }
    fun dismiss() { if (!busy && pendingPhoto == null) { if (pages.isEmpty()) completeDismiss() else confirmDiscard = true } }
    // Capture still requires a shutter tap; page edges are detected only after the photo returns.
    LaunchedEffect(Unit) {
        val interrupted = restored?.optString("pending")?.takeIf { it.isNotEmpty() }?.let { File(session, it) }
        if (interrupted != null && interrupted.length() > 0 && pages.none { it.file == interrupted }) work { addImage(interrupted) }
        else if (restored == null) takePage()
    }

    // Use the same full-screen content surface as the editor. Compose 1.7's full-width Dialog
    // measures against the whole display even when Android constrains it below the status bar.
    Box(Modifier.fillMaxSize()) {
        BackHandler { if (reviewing && !busy) reviewing = false else dismiss() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reviewing) "Scan preview" else "Scan document", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                    if (page != null && !reviewing) Box {
                        TextButton(enabled = !busy && pendingPhoto == null, onClick = { pageTools = true }) { Text("Page tools") }
                        DropdownMenu(expanded = pageTools, onDismissRequest = { pageTools = false }) {
                            DropdownMenuItem(text = { Text("Rotate") }, onClick = {
                                pageTools = false; pages[index] = page.copy(turns = (page.turns + 1) % 4, corners = rotatedScanCorners(page.corners))
                            })
                            DropdownMenuItem(text = { Text("Fit image") }, onClick = { pageTools = false; fitRequest++ })
                            DropdownMenuItem(text = { Text("Reset crop") }, onClick = { pageTools = false; pages[index] = page.copy(corners = fullPageCorners) })
                            DropdownMenuItem(text = { Text("Remove page") }, onClick = {
                                pageTools = false; pages.removeAt(index); index = index.coerceAtMost((pages.size - 1).coerceAtLeast(0))
                            })
                            if (pages.size > 1) DropdownMenuItem(text = { Text("Move earlier") }, enabled = index > 0, onClick = {
                                pageTools = false; pages.removeAt(index); pages.add(index - 1, page); index--
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Take page") }, enabled = pages.size < ScanImages.MAX_PAGES, onClick = { pageTools = false; takePage() })
                            DropdownMenuItem(text = { Text("Import image") }, enabled = pages.size < ScanImages.MAX_PAGES, onClick = {
                                pageTools = false
                                try { gallery.launch("image/*") } catch (_: ActivityNotFoundException) { error = "No image picker is available." }
                            })
                        }
                    }
                }
                if (page != null) {
                    Text("Page ${index + 1} of ${pages.size} · " +
                        if (reviewing) "Cropped preview" else if (page.autoDetected) "Edges detected" else "Adjust edges",
                        Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if (!reviewing) Text("Pinch to zoom · Drag image to pan or a corner to crop.", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                    val bitmap = preview
                    if (bitmap != null && previewKey == wantedPreview) {
                        if (reviewing) androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Cropped page preview", Modifier.weight(1f).fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        else ScanCropCanvas(bitmap, page.corners, enabled = !busy, Modifier.weight(1f).fillMaxWidth(), fitRequest) { corners ->
                            pages[index] = page.copy(corners = corners)
                        }
                    } else Box(Modifier.weight(1f).fillMaxWidth()) { if (error == null) CircularProgressIndicator() }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) { Text("Take a photo when you're ready, then adjust the page corners here.") }
                }
                ScrollHints(rememberScrollState(), Modifier.fillMaxWidth().heightIn(max = 270.dp), fitContent = true) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (page != null) {
                        if (pages.size > 1) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = !busy && index > 0, onClick = { index-- }) { Text("Previous") }
                                TextButton(enabled = !busy && index < pages.lastIndex, onClick = { index++ }) { Text("Next") }
                            }
                        }
                    }
                    if (!reviewing && page == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && pendingPhoto == null && pages.size < ScanImages.MAX_PAGES, onClick = ::takePage) { Text("Take page") }
                        OutlinedButton(enabled = !busy && pendingPhoto == null && pages.size < ScanImages.MAX_PAGES, onClick = {
                            try { gallery.launch("image/*") } catch (_: ActivityNotFoundException) { error = "No image picker is available." }
                        }) { Text("Import image") }
                    }
                    if (pages.size == ScanImages.MAX_PAGES) Text("Maximum 10 pages per scan.")
                } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !busy && pendingPhoto == null, onClick = { if (reviewing) reviewing = false else dismiss() }) { Text(if (reviewing) "Adjust corners" else "Cancel") }
                    Button(enabled = !busy && pages.isNotEmpty() && preview != null && previewKey == wantedPreview && pendingPhoto == null, onClick = {
                        if (!reviewing) reviewing = true else {
                            val snapshot = pages.toList()
                            work {
                                val result = ScanImages.export(snapshot, pdf, store)
                                onComplete(result)
                                finished = true
                            }
                        }
                    }) { Text(if (reviewing) "Attach scan" else "Preview scan") }
                }
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard scan?") },
            text = { Text("These scanned pages haven't been attached yet.") },
            confirmButton = { TextButton(onClick = ::completeDismiss) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep scanning") } })
    }
}

@Composable
private fun ScanCropCanvas(bitmap: Bitmap, corners: List<ScanPoint>, enabled: Boolean, modifier: Modifier, fitRequest: Int, onChange: (List<ScanPoint>) -> Unit) {
    val current by rememberUpdatedState(corners)
    val change by rememberUpdatedState(onChange)
    val tint = MaterialTheme.colorScheme.primary
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var viewport by remember(bitmap, fitRequest, canvasSize) { mutableStateOf(CropViewport()) }
    var selected by remember(bitmap, enabled, fitRequest, canvasSize) { mutableIntStateOf(-1) }
    val cornerNames = listOf("top left", "top right", "bottom right", "bottom left")
    fun move(index: Int, point: ScanPoint): Boolean {
        if (!enabled) return false
        val next = current.toMutableList().apply { set(index, ScanPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))) }
        return validScanCorners(next).also { if (it) change(next) }
    }
    Canvas(modifier.clipToBounds().onSizeChanged { canvasSize = it }.semantics {
        contentDescription = "Document crop. Adjust the four page corners."
        stateDescription = if (selected >= 0) "Magnifying ${cornerNames[selected]} corner" else "No corner selected · Zoom ${(viewport.zoom * 100).roundToInt()}%"
        customActions = listOf(CustomAccessibilityAction("Fit image") { viewport = CropViewport(); true }) + listOf("top left", "top right", "bottom right", "bottom left").flatMapIndexed { i, name ->
            listOf("left" to ScanPoint(-.02f, 0f), "right" to ScanPoint(.02f, 0f), "up" to ScanPoint(0f, -.02f), "down" to ScanPoint(0f, .02f)).map { (direction, delta) ->
                CustomAccessibilityAction("Move $name corner $direction") { move(i, ScanPoint(current[i].x + delta.x, current[i].y + delta.y)) }
            }
        }
    }.pointerInput(bitmap, enabled, fitRequest, canvasSize) {
        val inset = 24.dp.toPx()
        fun frame() = viewport.frame(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), inset)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled) return@awaitEachGesture
            val initialFrame = frame()
            val nearest = current.indices.minByOrNull { i ->
                (down.position - Offset(initialFrame.left + current[i].x * initialFrame.width, initialFrame.top + current[i].y * initialFrame.height)).getDistance()
            }
            val cornerPosition = nearest?.let { Offset(initialFrame.left + current[it].x * initialFrame.width, initialFrame.top + current[it].y * initialFrame.height) }
            val corner = if (cornerPosition != null && (down.position - cornerPosition).getDistance() <= 40.dp.toPx()) nearest!! else -1
            val grabOffset = if (corner >= 0) cornerPosition!! - down.position else Offset.Zero
            var transformed = false
            var dragging = false
            try {
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        transformed = true; selected = -1
                        val focus = event.calculateCentroid()
                        val pan = event.calculatePan()
                        viewport = viewport.transform(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), inset,
                            focus.x, focus.y, pan.x, pan.y, event.calculateZoom())
                        event.changes.forEach { it.consume() }
                    } else if (pressed.size == 1) {
                        val pointer = pressed.single()
                        if (!dragging && (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (dragging || transformed) {
                            if (corner >= 0 && !transformed) {
                                selected = corner
                                val position = pointer.position + grabOffset
                                move(corner, frame().imagePoint(position.x, position.y))
                            } else {
                                val pan = event.calculatePan()
                                viewport = viewport.transform(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), inset,
                                    pointer.position.x, pointer.position.y, pan.x, pan.y, 1f)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            } finally { selected = -1 }
        }
    }) {
        val frame = viewport.frame(size.width, size.height, bitmap.width.toFloat(), bitmap.height.toFloat(), 24.dp.toPx())
        val width = frame.width; val height = frame.height
        val left = frame.left; val top = frame.top
        drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.High)
        val positions = corners.map { Offset(left + it.x * width, top + it.y * height) }
        val path = Path().apply { moveTo(positions[0].x, positions[0].y); positions.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, tint.copy(alpha = .12f)); drawPath(path, tint, style = Stroke(2.dp.toPx()))
        positions.forEachIndexed { i, position ->
            if (i == selected) drawCircle(tint.copy(alpha = .3f), 20.dp.toPx(), position)
            drawCircle(Color.White, (if (i == selected) 12 else 10).dp.toPx(), position)
            drawCircle(tint, (if (i == selected) 8 else 7).dp.toPx(), position)
        }
        if (selected in positions.indices) {
            // Draw from the same rotated bitmap; no bitmap allocation or crop/export work during a drag.
            val side = min(132.dp.toPx(), min(size.width * .43f, size.height * .45f))
            val margin = 8.dp.toPx()
            val lensLeft = if (positions[selected].x < size.width / 2) size.width - side - margin else margin
            val lensTop = margin
            val center = Offset(lensLeft + side / 2, lensTop + side / 2)
            val zoom = 2.5f
            val source = corners[selected]
            drawRect(Color.Black, Offset(lensLeft - 2.dp.toPx(), lensTop - 2.dp.toPx()), Size(side + 4.dp.toPx(), side + 4.dp.toPx()))
            clipRect(lensLeft, lensTop, lensLeft + side, lensTop + side) {
                drawRect(Color(0xFF202020), Offset(lensLeft, lensTop), Size(side, side))
                val origin = Offset(center.x - source.x * width * zoom, center.y - source.y * height * zoom)
                drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = IntSize((width * zoom).roundToInt().coerceAtLeast(1), (height * zoom).roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.High)
                val zoomPath = Path().apply {
                    corners.forEachIndexed { i, corner ->
                        val point = Offset(center.x + (corner.x - source.x) * width * zoom, center.y + (corner.y - source.y) * height * zoom)
                        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }; close()
                }
                drawPath(zoomPath, tint, style = Stroke(1.dp.toPx()))
                drawCircle(Color.Black, 5.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            }
            drawRect(Color.White, Offset(lensLeft, lensTop), Size(side, side), style = Stroke(2.dp.toPx()))
        }
    }
}
