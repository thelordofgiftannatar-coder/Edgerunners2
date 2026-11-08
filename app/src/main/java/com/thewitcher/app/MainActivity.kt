package com.thewitcher.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.concurrent.thread

private const val HOST = "appassets.androidplatform.net"
private const val START_URL = "https://$HOST/assets/www/index.html"

/** Lets a stream end after [left] bytes (used for HTTP Range answers). */
private class LimitedStream(source: InputStream, private var left: Long) : FilterInputStream(source) {
    override fun read(): Int {
        if (left <= 0) return -1
        val r = super.read()
        if (r >= 0) left--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val r = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (r > 0) left -= r.toLong()
        return r
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var chooserTypes: Array<String> = arrayOf("*/*")

    private val reqChooser = 1001
    private val reqMedia = 1002
    private val reqTree = 1003

    private val mediaExt = Regex("\\.(mp3|m4a|flac|wav|ogg|opus|aac|wma|mp4|mkv|mov|webm|ts|m4v|3gp|avi)$", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportMultipleWindows(false)
        webView.addJavascriptInterface(NativeBridge(), "AndroidNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                val url = request.url
                if (url.host == HOST) {
                    val path = url.path ?: ""
                    if (path.startsWith("/media/")) return serveMedia(request)
                    if (path.endsWith("/sw.js")) return notFound()
                }
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.host == HOST) return false
                if (url.scheme == "https" || url.scheme == "http") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: Exception) {
                    }
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val types = params?.acceptTypes?.filter { it.contains("/") }?.toTypedArray()
                chooserTypes = if (types != null && types.isNotEmpty()) types else arrayOf("*/*")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, chooserTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    startActivityForResult(intent, reqChooser)
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }

        webView.loadUrl(START_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    /* ---------------- bridge used by the page ---------------- */

    inner class NativeBridge {
        @JavascriptInterface
        fun pickFiles() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try {
                    startActivityForResult(intent, reqMedia)
                } catch (e: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try {
                    startActivityForResult(intent, reqTree)
                } catch (e: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun rescan() {
            val trees = contentResolver.persistedUriPermissions
                .map { it.uri }
                .filter { DocumentsContract.isTreeUri(it) }
            if (trees.isEmpty()) {
                js("window.__nat&&window.__nat.note('No saved folder yet. Tap the folder icon first.')")
                return
            }
            thread {
                js("window.__nat&&window.__nat.begin()")
                for (t in trees) scanTree(t)
                js("window.__nat&&window.__nat.end(true)")
            }
        }

        /** Takes a JSON array of content URIs, returns the ones that can still be opened. */
        @JavascriptInterface
        fun readable(json: String): String {
            val out = JSONArray()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i)
                    try {
                        val afd = contentResolver.openAssetFileDescriptor(Uri.parse(s), "r")
                        if (afd != null) {
                            afd.close()
                            out.put(s)
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
            return out.toString()
        }
    }

    private fun js(code: String) {
        runOnUiThread { webView.evaluateJavascript(code, null) }
    }

    private fun sendItems(items: List<JSONObject>, folder: Boolean) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        js("window.__nat&&window.__nat.picked($arr,$folder)")
    }

    /* ---------------- picking results ---------------- */

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqChooser -> {
                val results = if (resultCode == Activity.RESULT_OK && data != null) {
                    val clip = data.clipData
                    when {
                        clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                } else null
                fileCallback?.onReceiveValue(results)
                fileCallback = null
            }

            reqMedia -> {
                if (resultCode != Activity.RESULT_OK || data == null) return
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else data.data?.let { uris.add(it) }
                if (uris.isEmpty()) return
                thread {
                    js("window.__nat&&window.__nat.begin()")
                    val batch = ArrayList<JSONObject>()
                    for (u in uris) {
                        try {
                            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {
                        }
                        describe(u, "Added files")?.let { batch.add(it) }
                        if (batch.size >= 100) {
                            sendItems(ArrayList(batch), false)
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) sendItems(ArrayList(batch), false)
                    js("window.__nat&&window.__nat.end(false)")
                }
            }

            reqTree -> {
                if (resultCode != Activity.RESULT_OK) return
                val tree = data?.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                thread {
                    js("window.__nat&&window.__nat.begin()")
                    scanTree(tree)
                    js("window.__nat&&window.__nat.end(true)")
                }
            }
        }
    }

    private fun describe(uri: Uri, dir: String): JSONObject? {
        var name: String? = null
        var size = 0L
        var mtime = 0L
        val mime = contentResolver.getType(uri) ?: ""
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                    if (!c.isNull(2)) mtime = c.getLong(2)
                }
            }
        } catch (e: Exception) {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (e2: Exception) {
            }
        }
        val finalName = name ?: uri.lastPathSegment ?: return null
        return JSONObject().apply {
            put("uri", uri.toString())
            put("name", finalName)
            put("size", size)
            put("mtime", mtime)
            put("mime", mime)
            put("dir", dir)
        }
    }

    /* ---------------- folder scan ---------------- */

    private fun scanTree(tree: Uri) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return
        }
        var rootName = "Folder"
        try {
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            contentResolver.query(
                rootDoc,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { rootName = it }
            }
        } catch (e: Exception) {
        }
        val batch = ArrayList<JSONObject>()
        walk(tree, rootId, rootName, batch)
        if (batch.isNotEmpty()) sendItems(ArrayList(batch), true)
    }

    private fun walk(tree: Uri, docId: String, path: String, batch: MutableList<JSONObject>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val subdirs = ArrayList<Pair<String, String>>()
        try {
            contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && name != "Android") subdirs.add(Pair(id, name))
                    } else if (mime.startsWith("audio/") || mime.startsWith("video/") || mediaExt.containsMatchIn(name)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        batch.add(JSONObject().apply {
                            put("uri", fileUri.toString())
                            put("name", name)
                            put("size", if (c.isNull(3)) 0L else c.getLong(3))
                            put("mtime", if (c.isNull(4)) 0L else c.getLong(4))
                            put("mime", mime)
                            put("dir", path)
                        })
                        if (batch.size >= 100) {
                            sendItems(ArrayList(batch), true)
                            batch.clear()
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        for (d in subdirs) walk(tree, d.first, path + "/" + d.second, batch)
    }

    /* ---------------- serving saved files to the player ---------------- */

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))

    private fun querySize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun serveMedia(request: WebResourceRequest): WebResourceResponse {
        val encoded = request.url.lastPathSegment ?: return notFound()
        val uri = try {
            Uri.parse(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
        } catch (e: Exception) {
            return notFound()
        }
        return try {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r") ?: return notFound()
            var total = afd.length
            if (total < 0) total = querySize(uri)
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val headers = HashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"

            var rangeHeader: String? = null
            for ((k, v) in request.requestHeaders) {
                if (k.equals("Range", ignoreCase = true)) rangeHeader = v
            }

            if (rangeHeader != null && total > 0) {
                val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
                val a = m?.groupValues?.get(1) ?: ""
                val b = m?.groupValues?.get(2) ?: ""
                val start: Long
                val end: Long
                if (a.isEmpty() && b.isNotEmpty()) {
                    start = maxOf(0L, total - b.toLong())
                    end = total - 1
                } else {
                    start = a.toLongOrNull() ?: 0L
                    end = b.toLongOrNull()?.let { minOf(it, total - 1) } ?: (total - 1)
                }
                if (start > end || start >= total) {
                    afd.close()
                    headers["Content-Range"] = "bytes */$total"
                    return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, ByteArrayInputStream(ByteArray(0)))
                }
                val stream = afd.createInputStream()
                var skipped = 0L
                while (skipped < start) {
                    val s = stream.skip(start - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val length = end - start + 1
                headers["Content-Range"] = "bytes $start-$end/$total"
                headers["Content-Length"] = length.toString()
                WebResourceResponse(mime, null, 206, "Partial Content", headers, LimitedStream(stream, length))
            } else {
                if (total > 0) headers["Content-Length"] = total.toString()
                WebResourceResponse(mime, null, 200, "OK", headers, afd.createInputStream())
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        webView.destroy()
        super.onDestroy()
    }
}
