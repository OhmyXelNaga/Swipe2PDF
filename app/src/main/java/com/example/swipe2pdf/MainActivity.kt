package com.example.swipe2pdf

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.restoreCaptures(this)
        setContent { MaterialTheme { SwipeApp() } }
    }
}

private val ACTIVE_STATES = setOf(JobState.Preparing, JobState.Capturing, JobState.Paused, JobState.Processing)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    var config by remember { mutableStateOf<JobConfig?>(null) }
    LaunchedEffect(Unit) { config = store.config.first() }
    val state by AppState.state.collectAsStateWithLifecycle()
    val captures by AppState.captures.collectAsStateWithLifecycle()
    val msg by AppState.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (AppState.canBuildPdf()) 2 else 0) }
    var projectionResult by remember { mutableStateOf<Pair<Int, Intent>?>(null) }
    var startAfterConsent by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            projectionResult = r.resultCode to data
        } else {
            startAfterConsent = false
            AppState.message.value = "화면 캡처 권한이 허용되지 않았습니다."
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestProjection = {
        val m = context.getSystemService(MediaProjectionManager::class.java)!!
        projectionLauncher.launch(m.createScreenCaptureIntent())
    }

    fun startJob(c: JobConfig) {
        val pr = projectionResult
        when {
            state in ACTIVE_STATES -> AppState.message.value = "이미 진행 중인 작업이 있습니다."
            !Settings.canDrawOverlays(context) -> { AppState.message.value = "다른 앱 위에 표시 권한이 필요합니다."; tab = 0 }
            SwipeAccessibilityService.instance == null -> { AppState.message.value = "접근성 서비스를 켜 주세요."; tab = 0 }
            pr == null -> { startAfterConsent = true; requestProjection() }
            else -> {
                store.saveLater(c)
                context.startForegroundService(
                    Intent(context, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_RESULT, pr.first)
                        .putExtra(CaptureService.EXTRA_DATA, pr.second)
                        .putExtra(CaptureService.EXTRA_CONFIG, ConfigCodec.encode(c))
                )
                // 화면 캡처 동의는 한 번만 사용할 수 있다.
                projectionResult = null
                AppState.message.value = "플로팅 컨트롤러가 켜졌습니다. 대상 앱으로 이동해 시작을 누르세요."
            }
        }
    }

    LaunchedEffect(projectionResult) {
        val c = config
        if (projectionResult != null && startAfterConsent && c != null) {
            startAfterConsent = false
            startJob(c)
        }
    }
    LaunchedEffect(state) {
        if (AppState.canBuildPdf() && (state == JobState.Reviewing || state == JobState.Error)) tab = 2
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Swipe2PDF") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                listOf("권한", "작업 설정", "결과 검토").forEachIndexed { i, s -> Tab(tab == i, { tab = i }, text = { Text(s) }) }
            }
            msg?.let { AssistChip({ AppState.message.value = null }, label = { Text(it) }, modifier = Modifier.padding(horizontal = 12.dp)) }
            val c = config
            when {
                tab == 0 -> PermissionsScreen(projectionResult != null, requestProjection) {
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                tab == 1 -> ConfigScreen(
                    c, captures.firstOrNull(), state !in ACTIVE_STATES,
                    onChange = { config = it; store.saveLater(it) },
                    onStart = { startJob(c) }
                )
                else -> ReviewScreen(c, captures, state)
            }
        }
    }
}

@Composable
private fun PermissionsScreen(projectionReady: Boolean, onProjection: () -> Unit, onNotification: () -> Unit) {
    val context = LocalContext.current
    // 설정 화면에서 돌아올 때마다 권한 상태를 다시 읽는다.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val access = remember(tick) { SwipeAccessibilityService.instance != null }
    val overlay = remember(tick) { Settings.canDrawOverlays(context) }
    val notif = remember(tick) {
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("필수 권한 설정", style = MaterialTheme.typography.headlineSmall)
        PermissionRow("접근성 서비스", access, "오른쪽에서 왼쪽으로 스와이프 동작을 수행합니다.") {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionRow("화면 캡처 동의", projectionReady, "Android 시스템 확인창에서 현재 화면 캡처를 허용합니다. 작업을 시작할 때 동의하지 않았다면 자동으로 요청합니다.", onProjection)
        PermissionRow("알림", notif, "캡처 중 필수 상시 알림을 표시합니다.", onNotification)
        PermissionRow("다른 앱 위에 표시", overlay, "대상 앱에서도 시작·일시정지·중지 컨트롤러를 표시합니다.") {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
        Button({ tick++ }) { Text("권한 상태 새로고침") }
    }
}

@Composable
private fun PermissionRow(title: String, ok: Boolean, desc: String, action: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "$title · ${if (ok) "허용됨" else "필요함"}", fontWeight = FontWeight.Bold,
                color = if (ok) Color(0xff19794b) else MaterialTheme.colorScheme.error
            )
            Text(desc)
            Button(action) { Text(if (ok) "설정 열기" else "허용하기") }
        }
    }
}

@Composable
private fun ConfigScreen(c: JobConfig, sample: File?, canStart: Boolean, onChange: (JobConfig) -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val screenAspect = remember {
        val b = context.getSystemService(WindowManager::class.java)!!.maximumWindowMetrics.bounds
        b.width().toFloat() / b.height()
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("캡처 설정", style = MaterialTheme.typography.headlineSmall)
        NumberField("총 출력 페이지", c.pages.toLong(), 1L..999L) { onChange(c.copy(pages = it.toInt())) }
        PercentSlider("스와이프 시작 X", c.startX) { onChange(c.copy(startX = it)) }
        PercentSlider("스와이프 끝 X", c.endX) { onChange(c.copy(endX = it)) }
        PercentSlider("스와이프 Y", c.y) { onChange(c.copy(y = it)) }
        NumberField("제스처 시간(ms)", c.gestureMs, 100L..3000L) { onChange(c.copy(gestureMs = it)) }
        NumberField("페이지 전환 대기(ms)", c.delayMs, 100L..10000L) { onChange(c.copy(delayMs = it)) }

        Text("공통 자르기 영역", fontWeight = FontWeight.Bold)
        CropPreview(c.crop, screenAspect, sample, Modifier.fillMaxWidth(.6f).align(Alignment.CenterHorizontally))
        PercentSlider("위쪽", c.crop.top, 0f, c.crop.bottom - .02f) { onChange(c.copy(crop = c.crop.copy(top = it))) }
        PercentSlider("아래쪽", c.crop.bottom, c.crop.top + .02f, 1f) { onChange(c.copy(crop = c.crop.copy(bottom = it))) }
        PercentSlider("왼쪽", c.crop.left, 0f, c.crop.right - .02f) { onChange(c.copy(crop = c.crop.copy(left = it))) }
        PercentSlider("오른쪽", c.crop.right, c.crop.left + .02f, 1f) { onChange(c.copy(crop = c.crop.copy(right = it))) }

        OutlinedTextField(c.filename, { onChange(c.copy(filename = it)) }, label = { Text("출력 파일 이름") }, modifier = Modifier.fillMaxWidth())
        Text("PDF 분할", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PdfPlan.SPLIT_OPTIONS.forEach { n ->
                FilterChip(c.splitPages == n, { onChange(c.copy(splitPages = n)) }, label = { Text(if (n == 0) "한 파일로 합치기" else "${n}장씩") })
            }
        }
        Text(
            if (c.splitPages == 0) "${PdfPlan.MERGE_CHUNK}장씩 나눠 만든 뒤 PDF 하나로 합칩니다. 중단돼도 이어서 만들고, 합친 뒤 중간 파일은 지웁니다."
            else "${c.splitPages}장마다 파일을 따로 저장합니다. 중단돼도 완성된 파일은 남습니다.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("첫 화면을 먼저 캡처하며 ${c.pages}장 촬영 시 스와이프는 ${CapturePlan.swipesFor(c.pages)}회입니다.")
        Button(onStart, Modifier.fillMaxWidth(), enabled = canStart) { Text("플로팅 컨트롤러 켜기") }
    }
}

/** 실제 화면 비율의 틀 위에 자르기 영역을 그린다. 촬영본이 있으면 첫 장을 배경으로 보여 준다. */
@Composable
private fun CropPreview(crop: NormalizedRect, aspect: Float, sample: File?, modifier: Modifier) {
    BoxWithConstraints(modifier.aspectRatio(aspect).background(Color(0xffdddddd))) {
        sample?.let { f -> rememberBitmap(f, 800)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) } }
        Box(
            Modifier
                .offset(x = maxWidth * crop.left, y = maxHeight * crop.top)
                .size(width = maxWidth * (crop.right - crop.left), height = maxHeight * (crop.bottom - crop.top))
                .border(3.dp, Color.Red)
        )
    }
}

@Composable
private fun NumberField(label: String, value: Long, range: LongRange, onValue: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toLongOrNull()?.let { it in range } == true
    OutlinedTextField(
        text,
        { input ->
            text = input.filter(Char::isDigit).take(6)
            text.toLongOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text("$label (${range.first}~${range.last})") },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PercentSlider(label: String, value: Float, min: Float = 0f, max: Float = 1f, onValue: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).toInt()}%")
        Slider(value, onValue, valueRange = min..max)
    }
}

private val thumbnailCache = LruCache<String, ImageBitmap>(80)

/** 백그라운드에서 축소 디코딩한 이미지. 썸네일 크기는 캐시해 스크롤할 때 다시 읽지 않는다. */
@Composable
private fun rememberBitmap(file: File, maxSide: Int): ImageBitmap? {
    val key = "${file.path}@$maxSide"
    val cached = if (maxSide <= 300) thumbnailCache.get(key) else null
    val bitmap by produceState(cached, key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { ImageAndPdf.decodeSampled(file, maxSide)?.asImageBitmap() }
            if (maxSide <= 300) value?.let { thumbnailCache.put(key, it) }
        }
    }
    return bitmap
}

@Composable
private fun ReviewScreen(c: JobConfig, files: List<File>, state: JobState) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val fingerprints = remember { mutableStateMapOf<String, IntArray?>() }
    LaunchedEffect(files) {
        files.filter { it.path !in fingerprints }.forEach { f ->
            fingerprints[f.path] = withContext(Dispatchers.IO) { ImageAndPdf.fingerprint(f) }
        }
    }
    val duplicates by remember(files) { derivedStateOf { similarIndices(files.map { fingerprints[it.path] }) } }
    val results by AppState.pdfResults.collectAsStateWithLifecycle()
    val pdfProgress by AppState.pdfProgress.collectAsStateWithLifecycle()
    val record by AppState.pdfRecord.collectAsStateWithLifecycle()
    val busy = state == JobState.Processing
    val editable = state !in ACTIVE_STATES

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("촬영 결과 ${files.size}장", style = MaterialTheme.typography.headlineSmall)
        if (duplicates.isNotEmpty()) {
            Text("유사한 연속 이미지가 있습니다: ${duplicates.sorted().map { it + 1 }.joinToString()}쪽", color = MaterialTheme.colorScheme.error)
        }
        record?.takeIf { it.started && !busy }?.let {
            Text(
                if (files.isEmpty()) "이전 PDF 작업(${it.base})은 합치기만 남았습니다."
                else "이전 PDF 작업(${it.base})을 ${it.nextPage}쪽부터 이어서 만듭니다.",
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (files.isNotEmpty()) {
            val i = selected.coerceIn(files.indices)
            val f = files[i]
            LazyRow(Modifier.height(130.dp)) {
                itemsIndexed(files, key = { _, file -> file.path }) { n, file ->
                    Card({ selected = n }, Modifier.width(90.dp).padding(3.dp)) {
                        Column {
                            Box(Modifier.height(90.dp).fillMaxWidth()) {
                                rememberBitmap(file, 240)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                            Text("${n + 1}쪽${if (n in duplicates) " ⚠" else ""}", fontWeight = if (n == i) FontWeight.Bold else null)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                rememberBitmap(f, 1600)?.let { Image(it, "선택 이미지", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ AppState.setCaptures(reorder(files, i, i - 1)); selected = i - 1 }, enabled = editable && i > 0) { Text("앞으로") }
                Button({ AppState.setCaptures(reorder(files, i, i + 1)); selected = i + 1 }, enabled = editable && i < files.lastIndex) { Text("뒤로") }
                Button({
                    AppState.removeCapture(f)
                    selected = (i - 1).coerceAtLeast(0)
                }, enabled = editable) { Text("삭제") }
            }
        }
        if (files.isNotEmpty() || (record?.started == true && AppState.canBuildPdf())) {
            pdfProgress?.let { p ->
                Text("${if (p.merging) "PDF 합치는 중" else "PDF 만드는 중"} ${p.done}/${p.total}장")
                LinearProgressIndicator({ if (p.total == 0) 0f else p.done.toFloat() / p.total }, Modifier.fillMaxWidth())
            }
            Button({ AppState.buildPdf(context, c) }, Modifier.fillMaxWidth(), enabled = editable) {
                Text(if (busy) "PDF 만드는 중…" else if (files.isEmpty()) "PDF 합치기 이어서 하기" else "PDF 만들기")
            }
            OutlinedButton({ AppState.discardCaptures(); AppState.moveTo(JobState.Idle) }, Modifier.fillMaxWidth(), enabled = editable) { Text("전체 폐기") }
        } else if (results.isEmpty()) {
            Text("아직 촬영한 이미지가 없습니다.")
        }
        if (results.isNotEmpty()) PdfResults(context, results)
    }
}

@Composable
private fun PdfResults(context: Context, results: List<Pair<Uri, String>>) {
    fun launch(intent: Intent, failure: String) {
        runCatching { context.startActivity(intent) }.onFailure { AppState.message.value = failure }
    }
    Text("저장 완료: 다운로드/Swipe2PDF/ (${results.size}개)", fontWeight = FontWeight.Bold)
    LazyColumn(Modifier.heightIn(max = 180.dp)) {
        items(results, key = { it.first.toString() }) { (uri, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                TextButton({
                    launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "PDF를 열 수 있는 앱을 찾지 못했습니다.")
                }) { Text("열기") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button({
            val uris = ArrayList(results.map { it.first })
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            send.setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launch(Intent.createChooser(send, "PDF 공유"), "공유할 수 있는 앱을 찾지 못했습니다.")
        }) { Text("공유") }
        Button({ launch(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS), "다운로드 폴더를 열 수 없습니다.") }) { Text("저장 위치 보기") }
    }
}
