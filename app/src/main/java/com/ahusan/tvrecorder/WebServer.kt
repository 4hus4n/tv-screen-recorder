package com.ahusan.tvrecorder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Small LAN web server so a phone can browse, play, download and delete recordings. */
class WebServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private val nameRe = Regex("^[A-Za-z0-9_.-]+\\.mp4$")
    private val durationCache = ConcurrentHashMap<String, Long>()

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, "Error: ${e.message}")
        }
    }

    private fun route(s: IHTTPSession): Response {
        if (s.method == Method.POST) {
            try { s.parseBody(HashMap()) } catch (_: Exception) {}
        }
        val uri = s.uri ?: "/"
        if (uri == "/favicon.ico") return text(Status.NOT_FOUND, "")

        val key = param(s, "k")
        if (key != Prefs.key(ctx)) return html(Status.FORBIDDEN, lockedPage())

        return when {
            uri == "/" -> html(Status.OK, indexPage())
            uri == "/api/list" -> json(list())
            uri == "/api/status" -> json(status())
            uri == "/api/stop" && s.method == Method.POST -> {
                RecorderService.requestStop()
                json(JSONObject().put("ok", true))
            }
            uri == "/api/delete" && s.method == Method.POST -> delete(param(s, "name"))
            uri.startsWith("/v/") -> {
                val f = fileFor(uri.removePrefix("/v/")) ?: return text(Status.NOT_FOUND, "Not found")
                serveFile(s, f, "video/mp4", param(s, "dl") == "1")
            }
            uri.startsWith("/t/") -> {
                val f = fileFor(uri.removePrefix("/t/")) ?: return text(Status.NOT_FOUND, "Not found")
                val t = thumbFor(f) ?: return text(Status.NOT_FOUND, "No thumbnail")
                serveFile(s, t, "image/jpeg", false).also {
                    it.addHeader("Cache-Control", "max-age=86400")
                }
            }
            else -> text(Status.NOT_FOUND, "Not found")
        }
    }

    // ---------------- API ----------------

    private fun list(): JSONArray {
        val dir = App.recordingsDir(ctx)
        val files = (dir.listFiles { f -> nameRe.matches(f.name) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        val current = if (RecState.recording || RecState.saving) RecState.fileName else null
        val arr = JSONArray()
        for (f in files) {
            val busy = f.name == current
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("size", f.length())
                    .put("modified", f.lastModified())
                    .put("duration", if (busy) 0 else durationOf(f))
                    .put("recording", busy)
            )
        }
        return arr
    }

    private fun status(): JSONObject = JSONObject()
        .put("recording", RecState.recording)
        .put("saving", RecState.saving)
        .put("elapsed", RecState.elapsedSec())
        .put("remaining", RecState.remainingSec())
        .put("file", RecState.fileName ?: "")
        .put("audio", RecState.withAudio)
        .put("error", RecState.lastError ?: "")
        .put("free", App.recordingsDir(ctx).usableSpace)

    private fun delete(name: String?): Response {
        val f = name?.let { fileFor(it) } ?: return text(Status.NOT_FOUND, "Not found")
        if ((RecState.recording || RecState.saving) && f.name == RecState.fileName) {
            return text(Status.BAD_REQUEST, "Still recording")
        }
        val ok = f.delete()
        File(thumbDir(), f.name + ".jpg").delete()
        durationCache.keys.removeAll { it.startsWith(f.name + ":") }
        return json(JSONObject().put("ok", ok))
    }

    // ---------------- Files ----------------

    private fun fileFor(rawName: String): File? {
        val name = java.net.URLDecoder.decode(rawName, "UTF-8")
        if (!nameRe.matches(name)) return null
        val f = File(App.recordingsDir(ctx), name)
        return if (f.isFile) f else null
    }

    private fun durationOf(f: File): Long {
        val k = "${f.name}:${f.length()}"
        durationCache[k]?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durationCache[k] = ms
            ms
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun thumbDir(): File = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    @Synchronized
    private fun thumbFor(f: File): File? {
        if (RecState.recording && f.name == RecState.fileName) return null
        val t = File(thumbDir(), f.name + ".jpg")
        if (t.isFile && t.length() > 0) return t
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val bmp = r.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime() ?: return null
            val w = 480
            val h = (bmp.height.toLong() * w / bmp.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            FileOutputStream(t).use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            t
        } catch (e: Exception) {
            t.delete()
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Serves a file with HTTP Range support so phone video players can seek. */
    private fun serveFile(s: IHTTPSession, f: File, mime: String, download: Boolean): Response {
        val len = f.length()
        val range = s.headers["range"]
        val resp: Response
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split(",")[0].trim()
            val dash = spec.indexOf('-')
            val a = if (dash >= 0) spec.substring(0, dash) else spec
            val b = if (dash >= 0) spec.substring(dash + 1) else ""
            var start: Long
            var end: Long
            if (a.isEmpty()) { // suffix range: last N bytes
                val n = b.toLongOrNull() ?: 0L
                start = maxOf(0L, len - n)
                end = len - 1
            } else {
                start = a.toLongOrNull() ?: 0L
                end = b.toLongOrNull() ?: (len - 1)
            }
            if (end >= len) end = len - 1
            if (start >= len || start > end) {
                val r = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                r.addHeader("Content-Range", "bytes */$len")
                return r
            }
            val input = FileInputStream(f)
            skipFully(input, start)
            resp = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, input, end - start + 1)
            resp.addHeader("Content-Range", "bytes $start-$end/$len")
        } else {
            resp = newFixedLengthResponse(Status.OK, mime, FileInputStream(f), len)
        }
        resp.addHeader("Accept-Ranges", "bytes")
        if (download) resp.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        return resp
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) break
            left -= skipped
        }
    }

    // ---------------- Helpers ----------------

    private fun param(s: IHTTPSession, name: String): String? = s.parameters[name]?.firstOrNull()

    private fun text(st: Status, body: String) = newFixedLengthResponse(st, MIME_PLAINTEXT, body)

    private fun html(st: Status, body: String) = newFixedLengthResponse(st, "text/html; charset=utf-8", body)

    private fun json(o: Any) = newFixedLengthResponse(Status.OK, "application/json", o.toString()).also {
        it.addHeader("Cache-Control", "no-store")
    }

    private fun indexPage(): String =
        ctx.assets.open("index.html").bufferedReader().use { it.readText() }
            .replace("__KEY__", Prefs.key(ctx))

    private fun lockedPage(): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TV Recorder</title>
        <style>
          body{margin:0;min-height:100vh;display:grid;place-items:center;background:#12151c;color:#e8ebf0;font-family:system-ui,sans-serif}
          form{background:#1b1f27;padding:28px;border-radius:16px;width:min(320px,86vw);text-align:center}
          input{width:100%;box-sizing:border-box;font-size:22px;padding:12px;margin:16px 0;border-radius:10px;border:1px solid #333a47;background:#12151c;color:#fff;text-align:center;letter-spacing:4px}
          button{width:100%;padding:12px;font-size:17px;border:0;border-radius:10px;background:#e53935;color:#fff}
          p{color:#8a93a3;font-size:14px}
        </style></head><body>
        <form method="get" action="/">
          <h2 style="margin:0">TV Recorder</h2>
          <p>Scan the QR code on the TV, or enter the code shown under it.</p>
          <input name="k" inputmode="numeric" autocomplete="off" placeholder="Code">
          <button>Open</button>
        </form></body></html>
    """.trimIndent()
}
