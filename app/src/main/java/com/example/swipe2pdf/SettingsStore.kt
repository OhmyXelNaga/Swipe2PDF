package com.example.swipe2pdf

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
class SettingsStore(private val context: Context) {
    private object K { val pages=intPreferencesKey("pages"); val sx=floatPreferencesKey("sx"); val ex=floatPreferencesKey("ex"); val y=floatPreferencesKey("y"); val gm=longPreferencesKey("gm"); val dm=longPreferencesKey("dm"); val l=floatPreferencesKey("l"); val t=floatPreferencesKey("t"); val r=floatPreferencesKey("r"); val b=floatPreferencesKey("b"); val name=stringPreferencesKey("name") }
    val config = context.dataStore.data.map { p -> JobConfig(p[K.pages]?:10,p[K.sx]?:.85f,p[K.ex]?:.15f,p[K.y]?:.5f,p[K.gm]?:300,p[K.dm]?:800,NormalizedRect(p[K.l]?:0f,p[K.t]?:.05f,p[K.r]?:1f,p[K.b]?:.95f),p[K.name]?:"스캔") }
    suspend fun save(c: JobConfig)=context.dataStore.edit { p -> p[K.pages]=c.pages;p[K.sx]=c.startX;p[K.ex]=c.endX;p[K.y]=c.y;p[K.gm]=c.gestureMs;p[K.dm]=c.delayMs;p[K.l]=c.crop.left;p[K.t]=c.crop.top;p[K.r]=c.crop.right;p[K.b]=c.crop.bottom;p[K.name]=c.filename }
}
