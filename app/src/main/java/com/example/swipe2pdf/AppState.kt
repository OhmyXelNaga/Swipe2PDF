package com.example.swipe2pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 화면과 서비스가 공유하는 상태. 작업 상태는 [StateMachine] 규칙을 거쳐서만 바뀐다. */
object AppState {
    private const val TAG = "Swipe2PDF"
    private const val RECORD_FILE = "job.txt"
    /** PDF 묶음 하나를 만들 때 이미 확보해 둘 여유 공간. */
    private const val PDF_RESERVE_BYTES = 100L * 1024 * 1024
    private val machine = StateMachine()
    /** 화면 회전 등으로 Activity가 다시 만들어져도 끊기면 안 되는 작업(PDF 생성, 설정 저장)용. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()
    val progress = MutableStateFlow(0 to 0)
    val message = MutableStateFlow<String?>(null)
    private val _captures = MutableStateFlow<List<File>>(emptyList())
    val captures: StateFlow<List<File>> = _captures.asStateFlow()
    private val _pdfRecord = MutableStateFlow<PdfRecord?>(null)
    /** 이어서 만들 PDF 작업. 묶음 일부가 이미 저장됐으면 nextPage가 1보다 크다. */
    val pdfRecord: StateFlow<PdfRecord?> = _pdfRecord.asStateFlow()
    /** PDF 생성 진행률(완료 장수, 전체 장수). 생성 중이 아니면 null. */
    val pdfProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val pdfResults = MutableStateFlow<List<Pair<Uri, String>>>(emptyList())

    @Volatile private var filesDir: File? = null
    private val recordLock = Mutex()

    fun init(context: Context) {
        if (filesDir == null) filesDir = context.applicationContext.filesDir
    }

    @Synchronized
    fun moveTo(next: JobState): Boolean {
        if (machine.state == next) return true
        if (!machine.canMove(next)) {
            Log.w(TAG, "Ignored transition: ${machine.state} -> $next")
            return false
        }
        machine.move(next)
        _state.value = next
        return true
    }

    fun captureDir(filesDir: File) = File(filesDir, "captures").apply { mkdirs() }

    fun addCapture(file: File) {
        _captures.update { it + file }
        saveRecord()
    }

    /** 검토 화면의 순서 변경·삭제. 앱이 종료돼도 유지되도록 기록한다. */
    fun setCaptures(files: List<File>) {
        _captures.value = files
        saveRecord()
    }

    fun removeCapture(file: File) {
        file.delete()
        setCaptures(_captures.value - file)
        if (_captures.value.isEmpty()) {
            _pdfRecord.value = null
            saveRecord()
            moveTo(JobState.Idle)
        }
    }

    fun discardCaptures() {
        _captures.value.forEach(File::delete)
        _captures.value = emptyList()
        _pdfRecord.value = null
        saveRecord()
    }

    /** 마지막 상태를 기록 파일에 쓴다. 쓰기가 겹쳐도 가장 최근 상태가 남는다. */
    private fun saveRecord() {
        val dir = filesDir ?: return
        scope.launch(Dispatchers.IO) {
            recordLock.withLock {
                val file = File(captureDir(dir), RECORD_FILE)
                val record = JobRecord(_captures.value.map { it.name }, _pdfRecord.value)
                if (record.files.isEmpty() && record.pdf == null) {
                    file.delete()
                    return@withLock
                }
                val tmp = File(file.path + ".tmp")
                runCatching {
                    tmp.writeText(record.encode())
                    if (!tmp.renameTo(file)) error("rename failed")
                }.onFailure { Log.w(TAG, "Failed to save job record", it) }
            }
        }
    }

    /** 프로세스가 종료돼 목록을 잃었을 때 저장소에 남은 촬영본과 검토 순서, PDF 진행 상황을 다시 불러온다. */
    fun restoreCaptures(context: Context) {
        init(context)
        if (_captures.value.isNotEmpty() || state.value != JobState.Idle) return
        val dir = captureDir(filesDir!!)
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach(File::delete)
        val onDisk = dir.list { _, name -> name.endsWith(".png") }?.toSet().orEmpty()
        val record = runCatching { File(dir, RECORD_FILE).takeIf { it.exists() }?.readText()?.let(JobRecord::decode) }.getOrNull()
        if (onDisk.isEmpty()) {
            if (record != null) File(dir, RECORD_FILE).delete()
            return
        }
        val ordered = record?.files?.filter { it in onDisk }.orEmpty()
        val extra = CaptureNaming.sort((onDisk - ordered.toSet()).toList())
        _captures.value = (ordered + extra).map { File(dir, it) }
        _pdfRecord.value = record?.pdf
        moveTo(JobState.Reviewing)
        val resume = record?.pdf?.takeIf { it.nextPage > 1 }?.let { " PDF는 ${it.nextPage}쪽부터 이어서 만들 수 있습니다." }.orEmpty()
        message.value = "이전에 촬영한 이미지 ${_captures.value.size}장을 복구했습니다.$resume"
    }

    /** PDF 생성을 시작한다. 앱이 백그라운드로 가도 끊기지 않도록 포그라운드 서비스에서 실행한다. */
    fun buildPdf(context: Context, config: JobConfig) {
        init(context)
        val files = _captures.value
        if (files.isEmpty()) return
        if (state.value == JobState.Idle) moveTo(JobState.Reviewing)
        if (!moveTo(JobState.Processing)) {
            message.value = "촬영이 진행 중일 때는 PDF를 만들 수 없습니다."
            return
        }
        // 일부 묶음이 이미 저장된 작업은 같은 이름·분할 단위로 이어서 만든다.
        val record = _pdfRecord.value?.takeIf { it.nextPage > 1 }
            ?: PdfRecord(PdfPlan.baseName(config.filename), config.splitPages, 1, PdfPlan.pageDigits(files.size))
        _pdfRecord.value = record
        saveRecord()
        pdfResults.value = emptyList()
        pdfProgress.value = 0 to files.size
        val app = context.applicationContext
        val started = runCatching {
            app.startForegroundService(Intent(app, PdfService::class.java).putExtra(PdfService.EXTRA_CONFIG, ConfigCodec.encode(config)))
        }.isSuccess
        if (!started) scope.launch { runPdf(app, config.crop) }
    }

    /** 묶음 단위로 PDF를 만들고, 완성된 묶음의 촬영본은 바로 지운다. 실패해도 완성된 묶음은 남는다. */
    suspend fun runPdf(context: Context, crop: NormalizedRect) {
        val start = _pdfRecord.value
        val files = _captures.value
        if (start == null || files.isEmpty() || state.value != JobState.Processing) {
            pdfProgress.value = null
            if (state.value == JobState.Processing) moveTo(if (files.isEmpty()) JobState.Completed else JobState.Reviewing)
            return
        }
        val chunks = PdfPlan.chunks(files.size, start.split)
        val names = PdfPlan.fileNames(start.base, start.nextPage, files.size, start.split, start.digits)
        val results = mutableListOf<Pair<Uri, String>>()
        var done = 0
        try {
            withContext(Dispatchers.IO) { ImageAndPdf.cleanupPending(context) }
            chunks.forEachIndexed { c, range ->
                val chunkFiles = files.subList(range.first, range.last + 1)
                val needed = chunkFiles.sumOf { it.length() } * 3 + PDF_RESERVE_BYTES
                if (ImageAndPdf.freeBytes(context.filesDir) < needed) {
                    throw IllegalStateException("저장 공간이 부족합니다. 약 ${needed / (1024 * 1024)}MB가 필요합니다.")
                }
                val result = withContext(Dispatchers.IO) {
                    ImageAndPdf.writePdf(context, chunkFiles, crop, names[c]) { n -> pdfProgress.value = (done + n) to files.size }
                }
                results += result
                done += chunkFiles.size
                // 완성된 묶음의 촬영본만 지우고 다음 시작 페이지를 기록한다.
                withContext(Dispatchers.IO) { chunkFiles.forEach(File::delete) }
                val finished = chunkFiles.toSet()
                _captures.update { list -> list.filterNot { it in finished } }
                _pdfRecord.update { it?.copy(nextPage = it.nextPage + chunkFiles.size) }
                pdfResults.value = results.toList()
                saveRecord()
            }
            _pdfRecord.value = null
            saveRecord()
            moveTo(JobState.Completed)
            message.value = if (results.size == 1) "PDF를 저장했습니다: ${results[0].second}" else "PDF ${results.size}개를 저장했습니다."
        } catch (t: Throwable) {
            pdfResults.value = results.toList()
            moveTo(JobState.Reviewing)
            val saved = if (results.isEmpty()) "" else "PDF ${results.size}개는 저장했습니다. "
            message.value = if (t is CancellationException) "${saved}PDF 생성이 중단됐습니다. 다시 누르면 이어서 만듭니다."
            else "${saved}PDF 생성 실패: ${t.message}"
            if (t is CancellationException) throw t
        } finally {
            pdfProgress.value = null
        }
    }
}
