package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Reads the document aloud with the system text-to-speech engine, sentence by sentence, from a
 * starting page to the end. Works offline when the voice is installed on the device. The current
 * sentence is exposed (page and rectangles) so the viewer can highlight it and follow along.
 */
class ReadAloud(
    private val context: Context,
    private val scope: CoroutineScope,
    private val doc: PdfDocument,
    private val text: suspend (Int) -> NormalizedText,
) {
    enum class State { IDLE, STARTING, PLAYING, PAUSED, UNAVAILABLE }

    var state by mutableStateOf(State.IDLE)
        private set
    var page by mutableStateOf(0)
        private set
    var rects by mutableStateOf<List<RectF>>(emptyList())
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var sentences: List<Pair<String, Pair<Int, Int>>> = emptyList()
    private var index = 0
    private var generation = 0

    fun start(fromPage: Int) {
        page = fromPage
        index = 0
        sentences = emptyList()
        state = State.STARTING
        val engine = tts
        if (engine != null && ready) {
            loadPageAndSpeak()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            scope.launch(Dispatchers.Main) {
                if (status != TextToSpeech.SUCCESS) {
                    state = State.UNAVAILABLE
                    return@launch
                }
                ready = true
                tts?.apply {
                    language = Locale.getDefault()
                    setSpeechRate(speed)
                    setOnUtteranceProgressListener(listener)
                }
                if (state == State.STARTING) loadPageAndSpeak()
            }
        }
    }

    fun pause() {
        if (state != State.PLAYING) return
        generation++
        tts?.stop()
        state = State.PAUSED
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        speakCurrent()
    }

    fun stop() {
        generation++
        tts?.stop()
        state = State.IDLE
        rects = emptyList()
    }

    fun setRate(rate: Float) {
        speed = rate
        tts?.setSpeechRate(rate)
        if (state == State.PLAYING) {
            generation++
            tts?.stop()
            speakCurrent()
        }
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun loadPageAndSpeak() {
        val gen = ++generation
        scope.launch {
            // Skip pages without text (scans, blank pages) until one has something to read.
            while (page < doc.pageCount) {
                val n = text(page)
                sentences = n.sentences(Locale.getDefault()).map { r ->
                    n.text.substring(r.first, r.last + 1) to n.toPage(r.first, r.last + 1)
                }
                if (sentences.isNotEmpty()) break
                page++
            }
            if (gen != generation) return@launch
            if (page >= doc.pageCount) {
                stop()
                return@launch
            }
            index = 0
            state = State.PLAYING
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        val (sentence, range) = sentences.getOrNull(index) ?: return nextPage()
        val gen = generation
        scope.launch {
            rects = doc.textRects(page, range.first, range.second)
            if (gen != generation || state != State.PLAYING) return@launch
            engine.speak(sentence, TextToSpeech.QUEUE_FLUSH, Bundle(), "s:$gen:$page:$index")
        }
    }

    private fun nextPage() {
        page++
        if (page >= doc.pageCount) {
            stop()
            return
        }
        loadPageAndSpeak()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            scope.launch(Dispatchers.Main) {
                // Ignore utterances cut short by pause, stop or a rate change.
                if (utteranceId != "s:$generation:$page:$index" || state != State.PLAYING) return@launch
                index++
                speakCurrent()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            scope.launch(Dispatchers.Main) { if (state == State.PLAYING) stop() }
        }
    }
}
