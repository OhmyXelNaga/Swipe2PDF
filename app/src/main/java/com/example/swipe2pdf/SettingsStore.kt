package com.example.swipe2pdf

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val pages = intPreferencesKey("pages")
        val sx = floatPreferencesKey("sx")
        val ex = floatPreferencesKey("ex")
        val y = floatPreferencesKey("y")
        val gm = longPreferencesKey("gm")
        val dm = longPreferencesKey("dm")
        val l = floatPreferencesKey("l")
        val t = floatPreferencesKey("t")
        val r = floatPreferencesKey("r")
        val b = floatPreferencesKey("b")
        val name = stringPreferencesKey("name")
        val split = intPreferencesKey("split")
    }

    private val defaults = JobConfig()

    val config = context.dataStore.data.map { p ->
        JobConfig(
            p[K.pages] ?: defaults.pages, p[K.sx] ?: defaults.startX, p[K.ex] ?: defaults.endX, p[K.y] ?: defaults.y,
            p[K.gm] ?: defaults.gestureMs, p[K.dm] ?: defaults.delayMs,
            NormalizedRect(p[K.l] ?: defaults.crop.left, p[K.t] ?: defaults.crop.top, p[K.r] ?: defaults.crop.right, p[K.b] ?: defaults.crop.bottom),
            p[K.name] ?: defaults.filename,
            p[K.split] ?: defaults.splitPages
        )
    }

    private var pendingSave: Job? = null

    /** 슬라이더 조작마다 디스크에 쓰지 않도록 마지막 값만 잠시 뒤 저장한다. */
    fun saveLater(c: JobConfig) {
        pendingSave?.cancel()
        pendingSave = AppState.scope.launch {
            delay(400)
            save(c)
        }
    }

    suspend fun save(c: JobConfig) = context.dataStore.edit { p ->
        p[K.pages] = c.pages; p[K.sx] = c.startX; p[K.ex] = c.endX; p[K.y] = c.y
        p[K.gm] = c.gestureMs; p[K.dm] = c.delayMs
        p[K.l] = c.crop.left; p[K.t] = c.crop.top; p[K.r] = c.crop.right; p[K.b] = c.crop.bottom
        p[K.name] = c.filename
        p[K.split] = c.splitPages
    }
}
