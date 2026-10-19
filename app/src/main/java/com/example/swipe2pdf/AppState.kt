package com.example.swipe2pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 화면과 캡처 서비스가 공유하는 상태. 작업 상태는 [StateMachine] 규칙을 거쳐서만 바뀐다. */
object AppState {
    private const val TAG = "Swipe2PDF"
    private val machine = StateMachine()
    /** 화면 회전 등으로 Activity가 다시 만들어져도 끊기면 안 되는 작업(PDF 생성, 설정 저장)용. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()
    val progress = MutableStateFlow(0 to 0)
    val message = MutableStateFlow<String?>(null)
    val captures = MutableStateFlow<List<File>>(emptyList())
    val pdf = MutableStateFlow<Pair<Uri, String>?>(null)

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

    fun addCapture(file: File) = captures.update { it + file }

    fun discardCaptures() {
        captures.value.forEach(File::delete)
        captures.value = emptyList()
    }

    fun captureDir(filesDir: File) = File(filesDir, "captures").apply { mkdirs() }

    /** 프로세스가 종료돼 목록을 잃었을 때 저장소에 남은 촬영본을 다시 불러온다. */
    fun restoreCaptures(filesDir: File) {
        if (captures.value.isNotEmpty() || state.value != JobState.Idle) return
        val dir = captureDir(filesDir)
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach(File::delete)
        val names = dir.list { _, name -> name.endsWith(".png") }?.toList().orEmpty()
        if (names.isEmpty()) return
        captures.value = CaptureNaming.sort(names).map { File(dir, it) }
        moveTo(JobState.Reviewing)
        message.value = "이전에 촬영한 이미지 ${names.size}장을 복구했습니다."
    }

    /** 현재 촬영본으로 PDF를 만든다. 성공하면 촬영본을 지운다. */
    fun buildPdf(context: Context, config: JobConfig) {
        val files = captures.value
        if (files.isEmpty()) return
        if (state.value == JobState.Idle) moveTo(JobState.Reviewing)
        if (!moveTo(JobState.Processing)) {
            message.value = "촬영이 진행 중일 때는 PDF를 만들 수 없습니다."
            return
        }
        val app = context.applicationContext
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ImageAndPdf.createPdf(app, files, config.crop, config.filename).also { files.forEach(File::delete) }
                }
            }.onSuccess {
                pdf.value = it
                captures.value = emptyList()
                moveTo(JobState.Completed)
                message.value = "PDF를 저장했습니다: ${it.second}"
            }.onFailure {
                moveTo(JobState.Reviewing)
                message.value = "PDF 생성 실패: ${it.message}"
            }
        }
    }
}
