package com.flux.manga.ui.pages

import android.webkit.WebView
import com.flux.manga.core.SourceException
import com.flux.manga.data.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener

/** Read only the official shelf through the browser that completed website verification.
 * No cookie export to another host, JS interface, authentication shortcut or remote write. */
internal suspend fun readBaoziWebShelf(view: WebView, origin: String, username: String,
    checkPage: () -> Unit): BaoziShelfSnapshot {
    require(origin in BaoziAccountOrigins)
    val source = baoziAccountId(username)
    suspend fun evaluate(script: String): String {
        checkPage()
        return suspendCancellableCoroutine<String> { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }.also { checkPage() }
    }
    val script = """
        (() => {
          if (location.origin !== ${JSONObject.quote(origin.removeSuffix("/"))}) return;
          window.__fluxBaoziShelf?.abort();
          const controller = new AbortController();
          const state = {done:false, status:0, text:'', abort:()=>controller.abort()};
          window.__fluxBaoziShelf = state;
          const timer = setTimeout(()=>controller.abort(), 15000);
          (async () => {
            try {
              const response = await fetch('/user/my_bookshelf', {
                credentials:'same-origin', redirect:'error', cache:'no-store', signal:controller.signal
              });
              state.status = response.status;
              if (!response.ok) { await response.body?.cancel(); return; }
              const mime = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
              if (!['text/html','application/xhtml+xml'].includes(mime)) {
                state.invalid = true; await response.body?.cancel(); return;
              }
              const reader = response.body.getReader();
              const decoder = new TextDecoder('utf-8');
              let size = 0;
              while (true) {
                const chunk = await reader.read();
                if (chunk.done) break;
                size += chunk.value.byteLength;
                if (size > 2097152) { state.invalid = true; await reader.cancel(); return; }
                state.text += decoder.decode(chunk.value, {stream:true});
              }
              state.text += decoder.decode();
            } catch (_) { state.status = 0; }
            finally { clearTimeout(timer); state.done = true; }
          })();
        })()
    """.trimIndent()
    try {
        return withTimeout(20_000) {
            evaluate(script)
            var result: JSONObject? = null
            while (result == null) {
                val raw = evaluate("JSON.stringify(window.__fluxBaoziShelf?.done ? {status:window.__fluxBaoziShelf.status, invalid:!!window.__fluxBaoziShelf.invalid, length:window.__fluxBaoziShelf.text.length} : null)")
                val decoded = JSONTokener(raw).nextValue() as? String
                if (decoded != null && decoded != "null") result = JSONObject(decoded) else delay(250)
            }
            val response = requireNotNull(result)
            when (val status = response.optInt("status")) {
                403 -> throw BaoziVerificationRequired()
                401 -> throw BaoziLoginExpired()
                0 -> throw IOException("浏览器未能读取官网书架，请确认仍已登录后重试")
                in 200..299 -> Unit
                else -> throw IOException("官网书架返回 HTTP $status，请稍后重试")
            }
            val length = response.optInt("length", -1)
            if (response.optBoolean("invalid") || length !in 1..(2 * 1024 * 1024))
                throw IOException("官网书架内容无法识别，请重新打开书架后重试")
            // Bound each WebView result; do not send a large HTML document in one callback.
            val html = StringBuilder(length)
            for (offset in 0 until length step 16_384) {
                val raw = evaluate("window.__fluxBaoziShelf.text.slice($offset, ${minOf(offset + 16_384, length)})")
                val part = JSONTokener(raw).nextValue() as? String
                    ?: throw IOException("官网书架读取中断，请重试")
                html.append(part)
            }
            checkPage()
            parseBaoziBookshelf(source, html.toString()).also { checkPage() }
        }
    } catch (_: TimeoutCancellationException) {
        throw IOException("读取官网书架超时，请重试")
    } catch (error: SourceException.HttpStatus) {
        if (error.status.toInt() == 403) throw BaoziVerificationRequired()
        throw error
    } catch (_: SourceException.AuthenticationRequired) {
        throw BaoziLoginExpired()
    } finally {
        // The owning WebView may already have navigated or been destroyed.
        val stillCurrent = try { checkPage(); true } catch (_: CancellationException) { false } catch (_: IOException) { false }
        if (stillCurrent) view.evaluateJavascript("window.__fluxBaoziShelf?.abort(); delete window.__fluxBaoziShelf;", null)
    }
}
