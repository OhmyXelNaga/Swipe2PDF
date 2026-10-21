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
    /** PDF 생성 진행률. 생성 중이 아니면 null. */
    val pdfProgress = MutableStateFlow<PdfProgress?>(null)
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
        if (_captures.value.isEmpty() && _pdfRecord.value?.started != true) {
            _pdfRecord.value = null
            saveRecord()
            moveTo(JobState.Idle)
        }
    }

    fun discardCaptures() {
        _captures.value.forEach(File::delete)
        _captures.value = emptyList()
        _pdfRecord.value = null
        filesDir?.let { partsDir(it).listFiles()?.forEach(File::delete) }
        saveRecord()
    }

    fun partsDir(filesDir: File) = File(filesDir, "pdf_parts").apply { mkdirs() }

    /** 마지막 상태를 기록 파일에 쓴다. 쓰기가 겹쳐도 가장 최근 상태가 남는다. */
    private fun saveRecord() {
        scope.launch(Dispatchers.IO) { saveRecordNow() }
    }

    /** 촬영본을 지우기 전처럼 기록이 먼저 남아야 할 때 쓰기가 끝날 때까지 기다린다. */
    private suspend fun saveRecordNow() = withContext(Dispatchers.IO) {
        val dir = filesDir ?: return@withContext
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

    /** 기록에 없는 중간 파일(완성 직후 기록 전에 중단된 것)과 임시 파일을 지운다. */
    private fun cleanupParts(record: PdfRecord?) {
        val dir = partsDir(filesDir ?: return)
        dir.listFiles()?.forEach { f ->
            val index = PagePartFile.index(f.name)
            if (index == null || record == null || !record.merging || index >= record.parts) f.delete()
        }
    }

    private fun partFiles(record: PdfRecord) = (0 until record.parts).map { File(partsDir(filesDir!!), PagePartFile.name(it)) }

    /** 프로세스가 종료돼 목록을 잃었을 때 저장소에 남은 촬영본과 검토 순서, PDF 진행 상황을 다시 불러온다. */
    fun restoreCaptures(context: Context) {
        init(context)
        if (_captures.value.isNotEmpty() || state.value != JobState.Idle) return
        val dir = captureDir(filesDir!!)
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach(File::delete)
        val onDisk = dir.list { _, name -> name.endsWith(".png") }?.toSet().orEmpty()
        val record = runCatching { File(dir, RECORD_FILE).takeIf { it.exists() }?.readText()?.let(JobRecord::decode) }.getOrNull()
        val pdf = record?.pdf?.takeIf { it.started }
        cleanupParts(pdf)
        if (pdf != null) {
            // PDF 작업 중에는 새 촬영이 없으므로 기록에 없는 촬영본은 이미 PDF에 들어간 것이다.
            val listed = record!!.files
            onDisk.filter { it !in listed }.forEach { File(dir, it).delete() }
            _captures.value = listed.filter { it in onDisk }.map { File(dir, it) }
            _pdfRecord.value = pdf
            moveTo(JobState.Reviewing)
            message.value = if (_captures.value.isEmpty()) "PDF 합치기만 남았습니다. PDF 만들기를 누르면 이어서 완성합니다."
            else "이전에 촬영한 이미지 ${_captures.value.size}장을 복구했습니다. PDF는 ${pdf.nextPage}쪽부터 이어서 만들 수 있습니다."
            return
        }
        if (onDisk.isEmpty()) {
            if (record != null) File(dir, RECORD_FILE).delete()
            return
        }
        val ordered = record?.files?.filter { it in onDisk }.orEmpty()
        val extra = CaptureNaming.sort((onDisk - ordered.toSet()).toList())
        _captures.value = (ordered + extra).map { File(dir, it) }
        moveTo(JobState.Reviewing)
        message.value = "이전에 촬영한 이미지 ${_captures.value.size}장을 복구했습니다."
    }

    /** 합치기만 남은 작업이 있거나 촬영본이 있으면 PDF를 만들 수 있다. */
    fun canBuildPdf() = _captures.value.isNotEmpty() || _pdfRecord.value?.let { it.merging && it.parts > 0 } == true

    /** PDF 생성을 시작한다. 앱이 백그라운드로 가도 끊기지 않도록 포그라운드 서비스에서 실행한다. */
    fun buildPdf(context: Context, config: JobConfig) {
        init(context)
        if (!canBuildPdf()) return
        val files = _captures.value
        if (state.value == JobState.Idle) moveTo(JobState.Reviewing)
        if (!moveTo(JobState.Processing)) {
            message.value = "촬영이 진행 중일 때는 PDF를 만들 수 없습니다."
            return
        }
        // 이미 일부를 만든 작업은 같은 이름·방식으로 이어서 만든다.
        val record = _pdfRecord.value?.takeIf { it.started }
            ?: PdfRecord(PdfPlan.baseName(config.filename), config.splitPages, 1, PdfPlan.pageDigits(files.size))
        _pdfRecord.value = record
        saveRecord()
        pdfResults.value = emptyList()
        pdfProgress.value = PdfProgress(0, files.size)
        val app = context.applicationContext
        val started = runCatching {
            app.startForegroundService(Intent(app, PdfService::class.java).putExtra(PdfService.EXTRA_CONFIG, ConfigCodec.encode(config)))
        }.isSuccess
        if (!started) scope.launch { runPdf(app, config.crop) }
    }

    private fun ensureSpace(context: Context, needed: Long) {
        if (ImageAndPdf.freeBytes(context.filesDir) < needed + PDF_RESERVE_BYTES) {
            throw IllegalStateException("저장 공간이 부족합니다. 약 ${(needed + PDF_RESERVE_BYTES) / (1024 * 1024)}MB가 필요합니다.")
        }
    }

    /**
     * 묶음 하나가 완성되면 기록을 먼저 확정한 뒤 해당 촬영본을 지운다.
     * 순서가 바뀌면 중단 시 같은 페이지가 두 번 들어갈 수 있다.
     */
    private suspend fun commitChunk(chunkFiles: List<File>, update: (PdfRecord) -> PdfRecord) {
        val finished = chunkFiles.toSet()
        _captures.update { list -> list.filterNot { it in finished } }
        _pdfRecord.update { it?.let(update) }
        saveRecordNow()
        withContext(Dispatchers.IO) { chunkFiles.forEach(File::delete) }
    }

    /** 묶음 단위로 PDF를 만든다. 실패하거나 중단돼도 완성된 묶음은 남아 다음에 이어서 만든다. */
    suspend fun runPdf(context: Context, crop: NormalizedRect) {
        val start = _pdfRecord.value
        val files = _captures.value
        if (start == null || !canBuildPdf() || state.value != JobState.Processing) {
            pdfProgress.value = null
            if (state.value == JobState.Processing) moveTo(if (canBuildPdf()) JobState.Reviewing else JobState.Completed)
            return
        }
        val results = mutableListOf<Pair<Uri, String>>()
        var done = 0
        try {
            withContext(Dispatchers.IO) { ImageAndPdf.cleanupPending(context) }
            if (start.merging) {
                PdfPlan.chunks(files.size, PdfPlan.MERGE_CHUNK).forEach { range ->
                    val chunkFiles = files.subList(range.first, range.last + 1)
                    ensureSpace(context, chunkFiles.sumOf { it.length() } * 3)
                    val record = _pdfRecord.value!!
                    val target = File(partsDir(filesDir!!), PagePartFile.name(record.parts))
                    withContext(Dispatchers.IO) {
                        ImageAndPdf.writePart(chunkFiles, crop, target) { n -> pdfProgress.value = PdfProgress(done + n, files.size) }
                    }
                    done += chunkFiles.size
                    commitChunk(chunkFiles) { it.copy(nextPage = it.nextPage + chunkFiles.size, parts = it.parts + 1) }
                }
                val record = _pdfRecord.value!!
                val parts = partFiles(record)
                ensureSpace(context, parts.sumOf { it.length() })
                val total = record.nextPage - 1
                pdfProgress.value = PdfProgress(0, total, merging = true)
                results += withContext(Dispatchers.IO) {
                    ImageAndPdf.mergeParts(context, parts, "${record.base}.pdf") { n -> pdfProgress.value = PdfProgress(n, total, merging = true) }
                }
                // 최종 파일이 완성된 뒤에만 중간 파일을 지운다.
                _pdfRecord.value = null
                saveRecordNow()
                withContext(Dispatchers.IO) { parts.forEach(File::delete) }
            } else {
                val chunks = PdfPlan.chunks(files.size, start.split)
                val names = PdfPlan.fileNames(start.base, start.nextPage, files.size, start.split, start.digits)
                chunks.forEachIndexed { c, range ->
                    val chunkFiles = files.subList(range.first, range.last + 1)
                    ensureSpace(context, chunkFiles.sumOf { it.length() } * 3)
                    results += withContext(Dispatchers.IO) {
                        ImageAndPdf.writePdf(context, chunkFiles, crop, names[c]) { n -> pdfProgress.value = PdfProgress(done + n, files.size) }
                    }
                    done += chunkFiles.size
                    pdfResults.value = results.toList()
                    commitChunk(chunkFiles) { it.copy(nextPage = it.nextPage + chunkFiles.size) }
                }
                _pdfRecord.value = null
                saveRecordNow()
            }
            pdfResults.value = results.toList()
            moveTo(JobState.Completed)
            message.value = if (results.size == 1) "PDF를 저장했습니다: ${results[0].second}" else "PDF ${results.size}개를 저장했습니다."
        } catch (t: Throwable) {
            pdfResults.value = results.toList()
            moveTo(JobState.Reviewing)
            val saved = if (results.isEmpty()) "" else "PDF ${results.size}개는 저장했습니다. "
            message.value = if (t is CancellationException) "${saved}PDF 생성이 중단됐습니다. 다시 누르면 이어서 만듭니다."
            else "${saved}PDF 생성 실패: ${t.message} 다시 누르면 이어서 만듭니다."
            if (t is CancellationException) throw t
        } finally {
            pdfProgress.value = null
        }
    }
}
