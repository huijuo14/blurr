package com.blurr.voice.utilities

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.blurr.voice.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

class TTSManager private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private var nativeTts: TextToSpeech? = null
    private var isNativeTtsInitialized = CompletableDeferred<Unit>()

    var utteranceListener: ((isSpeaking: Boolean) -> Unit)? = null

    private var isDebugMode: Boolean = try {
        BuildConfig.SPEAK_INSTRUCTIONS
    } catch (e: Exception) {
        true
    }

    companion object {
        @Volatile private var INSTANCE: TTSManager? = null

        fun getInstance(context: Context): TTSManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TTSManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        nativeTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { utteranceListener?.invoke(true) }
                override fun onDone(utteranceId: String?) {
                    utteranceListener?.invoke(false)
                }
                override fun onError(utteranceId: String?) {
                    utteranceListener?.invoke(false)
                }
            })
            isNativeTtsInitialized.complete(Unit)
        } else {
            isNativeTtsInitialized.completeExceptionally(Exception("Native TTS Initialization failed"))
        }
    }

    fun stop() {
        nativeTts?.stop()
    }

    suspend fun speakText(text: String) {
        if (!isDebugMode) return
        speak(text)
    }

    suspend fun speakToUser(text: String) {
        speak(text)
    }

    fun getAudioSessionId(): Int {
        return 0
    }

    private suspend fun speak(text: String) {
        try {
            isNativeTtsInitialized.await()
            nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, this.hashCode().toString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("TTSManager", "Native TTS failed: ${e.message}")
        }
    }

    fun shutdown() {
        nativeTts?.shutdown()
        INSTANCE = null
    }
}