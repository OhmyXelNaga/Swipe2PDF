package com.example.swipe2pdf

import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

object AppState {
    val state=MutableStateFlow(JobState.Idle);val progress=MutableStateFlow(0 to 0);val message=MutableStateFlow<String?>(null);val captures=MutableStateFlow<List<File>>(emptyList());val pdf=MutableStateFlow<Pair<Uri,String>?>(null)
}
