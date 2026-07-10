package com.mink.signals

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The browser-style fingerprint any web page can compute. Mink spins up an
 * offscreen [WebView], runs the same JavaScript a tracker would, and reads back
 * what the page sees: the user agent, the screen, the timezone, and a canvas
 * and WebGL rendering hash. Those two hashes are stable across sites and let a
 * page recognise your phone without a cookie.
 *
 * WebView is a main-thread object, so the read is marshalled onto the main
 * dispatcher through a suspending bridge with a hard timeout. Nothing leaves
 * the device; the page is a local data URL.
 */
class WebViewFingerprintProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.WEB_VIEW_FINGERPRINT

    override suspend fun collect(): List<FingerprintSignal> {
        val raw = runCatching { runFingerprintScript() }.getOrNull()
        val parsed = raw?.let { unwrap(it) }
        if (parsed == null) {
            return listOf(
                FingerprintSignal.make(
                    key = "unavailable",
                    category = category,
                    name = "WebView fingerprint",
                    value = "Unavailable",
                    rationale =
                        "The offscreen WebView did not return in time or WebView is not " +
                            "installed. On a normal phone any web page computes this in a " +
                            "fraction of a second.",
                ),
            )
        }
        return buildSignals(parsed)
    }

    private fun buildSignals(fp: JSONObject): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        stringOf(fp, "userAgent")?.let {
            signals += FingerprintSignal.make(
                key = "userAgent",
                category = category,
                name = "User agent",
                value = it,
                rationale =
                    "The string every web page reads first. It names the Android version, the " +
                        "build, and the WebView revision, which together narrow you sharply.",
            )
        }

        val navEntries = buildList {
            stringOf(fp, "platform")?.let { add(SignalEntry("Platform", it)) }
            stringOf(fp, "hardwareConcurrency")?.let { add(SignalEntry("CPU threads", it)) }
            stringOf(fp, "deviceMemory")?.let { add(SignalEntry("Device memory (GB)", it)) }
            stringOf(fp, "languages")?.let { add(SignalEntry("Languages", it)) }
        }
        if (navEntries.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "navigator",
                category = category,
                name = "Navigator",
                value = navEntries.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "Properties a page reads from navigator. CPU thread count and memory bucket " +
                        "the hardware; the language list adds bits on top.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = navEntries,
            )
        }

        val screenEntries = buildList {
            stringOf(fp, "screen")?.let { add(SignalEntry("Resolution", it)) }
            stringOf(fp, "colorDepth")?.let { add(SignalEntry("Color depth", it)) }
            stringOf(fp, "pixelRatio")?.let { add(SignalEntry("Pixel ratio", it)) }
            stringOf(fp, "timezone")?.let { add(SignalEntry("Timezone", it)) }
        }
        if (screenEntries.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "screen",
                category = category,
                name = "Screen and timezone",
                value = screenEntries.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "The screen geometry and your timezone. Resolution and pixel ratio track " +
                        "the model; the timezone places you in a region.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = screenEntries,
            )
        }

        stringOf(fp, "canvasHash")?.let {
            signals += FingerprintSignal.make(
                key = "canvasHash",
                category = category,
                name = "Canvas 2D hash",
                value = it,
                rationale =
                    "A hash of text drawn to an offscreen canvas. Tiny differences in font and " +
                        "graphics rendering make this stable for your phone and different from " +
                        "most others. This is classic canvas fingerprinting.",
            )
        }

        val glEntries = buildList {
            stringOf(fp, "webglVendor")?.let { add(SignalEntry("Vendor", it)) }
            stringOf(fp, "webglRenderer")?.let { add(SignalEntry("Renderer", it)) }
            stringOf(fp, "webglHash")?.let { add(SignalEntry("Render hash", it)) }
        }
        if (glEntries.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "webgl",
                category = category,
                name = "WebGL",
                value = glEntries.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "The GPU vendor and renderer a page reads through WebGL, plus a hash of a " +
                        "rendered scene. This names your graphics chip and pins the model.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = glEntries,
            )
        }

        if (signals.isEmpty()) {
            signals += FingerprintSignal.make(
                key = "empty",
                category = category,
                name = "WebView fingerprint",
                value = "No readable fields",
                rationale =
                    "The WebView loaded but returned nothing usable. A normal page would still " +
                        "read a user agent and a canvas hash.",
            )
        }
        return signals
    }

    /**
     * Loads a local page into an offscreen WebView on the main thread, runs the
     * fingerprint script once the page settles, and returns the raw
     * evaluateJavascript result. Bounded by [TIMEOUT_MS]; returns null on
     * timeout or any failure so the provider never blocks or throws.
     */
    private suspend fun runFingerprintScript(): String? = withTimeoutOrNull(TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = runCatching { buildWebView() }.getOrNull()
                if (webView == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        runCatching {
                            view.evaluateJavascript(SCRIPT) { result ->
                                if (cont.isActive) cont.resume(result)
                                runCatching { view.destroy() }
                            }
                        }.onFailure {
                            if (cont.isActive) cont.resume(null)
                            runCatching { view.destroy() }
                        }
                    }
                }
                cont.invokeOnCancellation {
                    // invokeOnCancellation runs undispatched on the cancelling
                    // thread, which on the timeout path is not the main thread.
                    // WebView must be destroyed on the thread it was created on,
                    // so marshal the teardown back onto the main Looper.
                    val wv = webView
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { wv.destroy() }
                    }
                }
                runCatching {
                    webView.loadDataWithBaseURL(
                        "https://localhost/",
                        PAGE_HTML,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                    runCatching { webView.destroy() }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val webView = WebView(ctx.appContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.blockNetworkLoads = true
        return webView
    }

    /**
     * evaluateJavascript hands back the JS return value already JSON-encoded, so
     * a returned JSON string arrives double-quoted and escaped. Wrapping it in a
     * one-element array and reading element zero unescapes it into the inner
     * JSON, which then parses cleanly.
     */
    private fun unwrap(raw: String): JSONObject? = runCatching {
        if (raw.isBlank() || raw == "null") return null
        val inner = JSONArray("[$raw]").getString(0)
        JSONObject(inner)
    }.getOrNull()

    private fun stringOf(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.optString(key, "").trim()
        return value.ifEmpty { null }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        const val PAGE_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width\"></head>" +
                "<body></body></html>"

        /**
         * Reads the same surface a tracker's fingerprint library reads, hashes
         * the canvas and WebGL output, and returns one JSON string. Everything
         * is wrapped so a missing API yields a null field instead of throwing.
         */
        const val SCRIPT = """
(function() {
  function hash(str) {
    var h = 2166136261;
    for (var i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = (h * 16777619) >>> 0;
    }
    return ('00000000' + h.toString(16)).slice(-8);
  }
  var fp = {};
  try { fp.userAgent = navigator.userAgent; } catch (e) {}
  try { fp.platform = navigator.platform; } catch (e) {}
  try { fp.hardwareConcurrency = String(navigator.hardwareConcurrency); } catch (e) {}
  try { fp.deviceMemory = navigator.deviceMemory != null ? String(navigator.deviceMemory) : null; } catch (e) {}
  try { fp.languages = (navigator.languages || []).join(', '); } catch (e) {}
  try { fp.screen = window.screen.width + ' x ' + window.screen.height; } catch (e) {}
  try { fp.colorDepth = String(window.screen.colorDepth); } catch (e) {}
  try { fp.pixelRatio = String(window.devicePixelRatio); } catch (e) {}
  try { fp.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch (e) {}
  try {
    var c = document.createElement('canvas');
    c.width = 240; c.height = 60;
    var ctx = c.getContext('2d');
    ctx.textBaseline = 'top';
    ctx.font = '14px Arial';
    ctx.fillStyle = '#f60';
    ctx.fillRect(0, 0, 240, 60);
    ctx.fillStyle = '#069';
    ctx.fillText('Mink fingerprint ⚡ 0123', 2, 15);
    ctx.strokeStyle = 'rgba(102,204,0,0.7)';
    ctx.arc(50, 30, 20, 0, Math.PI * 2);
    ctx.stroke();
    fp.canvasHash = hash(c.toDataURL());
  } catch (e) {}
  try {
    var gc = document.createElement('canvas');
    var gl = gc.getContext('webgl') || gc.getContext('experimental-webgl');
    if (gl) {
      var dbg = gl.getExtension('WEBGL_debug_renderer_info');
      if (dbg) {
        fp.webglVendor = gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL);
        fp.webglRenderer = gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL);
      } else {
        fp.webglVendor = gl.getParameter(gl.VENDOR);
        fp.webglRenderer = gl.getParameter(gl.RENDERER);
      }
      var parts = [];
      parts.push(gl.getParameter(gl.VERSION));
      parts.push(gl.getParameter(gl.SHADING_LANGUAGE_VERSION));
      parts.push(gl.getParameter(gl.MAX_TEXTURE_SIZE));
      var ext = gl.getSupportedExtensions() || [];
      parts.push(ext.join(','));
      fp.webglHash = hash(parts.join('|'));
    }
  } catch (e) {}
  return JSON.stringify(fp);
})();
"""
    }
}
