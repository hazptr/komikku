package eu.kanade.tachiyomi.testdriver

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ViewRootForTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.interactor.GetRemoteManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * HTTP/JSON driver for automated device tests, on the abstract socket [SOCKET_NAME].
 * Reach it from the host with `adb forward tcp:<port> localabstract:komikku-testdriver`.
 * Only adb shell (uid 2000) and root may connect.
 */
internal object TestDriver {

    const val SOCKET_NAME = "komikku-testdriver"
    private const val VERSION = 1

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newCachedThreadPool()
    private val json = Json { prettyPrint = false }

    @Volatile private var resumed: WeakReference<Activity>? = null

    @Volatile private var started = false

    private val crashFile by lazy { File(app.filesDir, "testdriver/crashes.jsonl").apply { parentFile?.mkdirs() } }

    fun start(application: Application) {
        if (started) return
        started = true
        app = application
        val previous = ViewRootForTest.onViewCreatedCallback
        ViewRootForTest.onViewCreatedCallback = {
            Semantics.register(it)
            previous?.invoke(it)
        }
        app.registerActivityLifecycleCallbacks(ActivityTracker)
        installCrashRecorder()
        thread(name = "testdriver", isDaemon = true) { serve() }
    }

    // ---------------------------------------------------------------- server --

    private class DriverError(val status: Int, message: String) : Exception(message)

    private fun fail(status: Int, message: String): Nothing = throw DriverError(status, message)

    private fun serve() {
        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: Exception) {
            return // another process of this app already owns it
        }
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                continue
            }
            workers.execute { handle(socket) }
        }
    }

    private fun handle(socket: LocalSocket): Unit = socket.use { s ->
        val (status, body) = try {
            val uid = s.peerCredentials.uid
            if (uid != 0 && uid != 2000) fail(403, "uid $uid not allowed")
            val input = BufferedInputStream(s.inputStream)
            val requestLine = readLine(input) ?: return
            val (method, target) = requestLine.split(" ").let { it[0] to it.getOrElse(1) { "/" } }
            var length = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            }
            val raw = ByteArray(length).also {
                var r = 0
                while (r < length) r += input.read(it, r, length - r).also { n -> if (n < 0) fail(400, "short body") }
            }
            val bodyJson = if (length > 0) json.parseToJsonElement(raw.decodeToString()).jsonObject else JsonObject(emptyMap())
            val uri = Uri.parse(target)
            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
            200 to route(method, uri.path.orEmpty(), params, bodyJson)
        } catch (e: DriverError) {
            e.status to error(e.message)
        } catch (e: IllegalArgumentException) {
            400 to error(e.message)
        } catch (e: Throwable) {
            500 to error("${e::class.java.simpleName}: ${e.message}")
        }
        val bytes = json.encodeToString(JsonElement.serializer(), body).toByteArray()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        s.outputStream.write(head.toByteArray())
        s.outputStream.write(bytes)
        s.outputStream.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.size() == 0) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.write(c)
        }
    }

    private fun error(message: String?) = buildJsonObject {
        put("ok", false)
        put("error", message ?: "unknown")
    }

    private fun route(method: String, path: String, q: Map<String, String>, b: JsonObject): JsonElement = when ("$method $path") {
        "GET /ping" -> buildJsonObject {
            put("ok", true)
            put("version", VERSION)
            put("package", app.packageName)
        }
        "GET /screen" -> screen()
        "GET /tree" -> onMain { Semantics.tree(q["merged"] != "false", q["all"] != "true") }
        "POST /find" -> onMain { buildJsonArray { Semantics.find(selector(b)).forEach { add(Semantics.toJson(it)) } } }
        "POST /wait" -> waitFor(selector(b), b.bool("gone") ?: false, b.long("timeoutMs") ?: 10_000)
        "POST /click" -> act(b) { Semantics.click(it.node) }
        "POST /longclick" -> act(b) { Semantics.longClick(it.node) }
        "POST /type" -> type(b)
        "POST /scroll" -> scroll(b)
        "POST /idle" -> idle(b.long("quietMs") ?: 600, b.long("timeoutMs") ?: 20_000)
        "POST /back" -> back()
        "POST /navigate" -> navigate(b)
        "GET /reader" -> reader()
        "POST /reader/page" -> readerPage(b.int("page") ?: fail(400, "page required"))
        "GET /manga" -> manga(q)
        "GET /prefs/source" -> sourcePrefs(q["id"]?.toLongOrNull() ?: fail(400, "id required"))
        "POST /prefs/source" -> putSourcePrefs(b)
        "GET /crashes" -> crashes(q["since"]?.toLongOrNull() ?: 0)
        "GET /net" -> net()
        else -> fail(404, "no route $method $path")
    }

    // --------------------------------------------------------------- helpers --

    private fun JsonObject.str(k: String) = this[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

    private fun selector(b: JsonObject): JsonObject =
        (b["selector"] ?: b["on"])?.jsonObject ?: b.takeIf { it.isNotEmpty() } ?: fail(400, "selector required")

    /** Runs [block] on the main thread and waits for it. */
    private fun <T> onMain(timeoutMs: Long = 5_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) fail(504, "main thread busy for ${timeoutMs}ms")
        return result!!.getOrThrow()
    }

    private fun waitFor(sel: JsonObject, gone: Boolean, timeoutMs: Long): JsonObject {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val found = onMain { Semantics.find(sel).firstOrNull()?.let(Semantics::toJson) }
            if ((found != null) != gone) {
                return buildJsonObject {
                    put("ok", true)
                    put("waitedMs", SystemClock.uptimeMillis() - start)
                    found?.let { put("node", it) }
                }
            }
            if (SystemClock.uptimeMillis() - start > timeoutMs) {
                fail(408, "${if (gone) "still present" else "not found"} after ${timeoutMs}ms: $sel")
            }
            Thread.sleep(100)
        }
    }

    /** Waits for the node (up to timeoutMs, default 5 s), then performs [action] on it. */
    private fun act(b: JsonObject, action: (Semantics.Found) -> Boolean): JsonObject {
        val sel = selector(b)
        waitFor(sel, gone = false, timeoutMs = b.long("timeoutMs") ?: 5_000)
        return onMain {
            val f = Semantics.find(sel).firstOrNull() ?: fail(404, "not found: $sel")
            val ok = action(f)
            if (!ok) fail(409, "action not available on node: ${Semantics.toJson(f)}")
            buildJsonObject {
                put("ok", true)
                put("node", Semantics.toJson(f))
            }
        }
    }

    /** Sets the field's text, lets the screen take it in, then optionally sends the keyboard action. */
    private fun type(b: JsonObject): JsonObject {
        val value = b.str("value") ?: fail(400, "value required")
        val result = act(b) { Semantics.setText(it.node, value) }
        if (b.bool("submit") == true) {
            idle(quietMs = 300, timeoutMs = 5_000, network = false)
            onMain {
                val f = Semantics.find(selector(b)).firstOrNull() ?: fail(404, "field gone before submit")
                if (!Semantics.imeAction(f.node)) fail(409, "no keyboard action on field")
            }
        }
        return result
    }

    private fun scroll(b: JsonObject): JsonObject {
        val target = b["to"]?.jsonObject
        val container = b["in"]?.jsonObject
        val down = b.str("direction") != "up"
        val max = b.int("maxSwipes") ?: 40
        var swipes = 0
        while (true) {
            if (target != null) {
                val hit = onMain { Semantics.find(target).firstOrNull()?.let(Semantics::toJson) }
                if (hit != null) {
                    return buildJsonObject {
                        put("ok", true)
                        put("swipes", swipes)
                        put("node", hit)
                    }
                }
            }
            if (swipes >= max) fail(404, "not found after $swipes scrolls: $target")
            val moved = onMain {
                val c = (if (container != null) Semantics.find(container).firstOrNull() else Semantics.scrollables().firstOrNull())
                    ?: fail(404, "no scrollable container")
                val h = c.node.boundsInRoot.height * 0.7f
                Semantics.scrollBy(c.node, 0f, if (down) h else -h)
            }
            swipes++
            idle(quietMs = 250, timeoutMs = 5_000, network = false)
            if (!moved || target == null) {
                if (target == null) {
                    return buildJsonObject {
                        put("ok", moved)
                        put("swipes", swipes)
                    }
                }
                fail(404, "end of list after $swipes scrolls, not found: $target")
            }
        }
    }

    private fun runningCalls(): Int = runCatching {
        Injekt.get<NetworkHelper>().client.dispatcher.let { it.runningCallsCount() + it.queuedCallsCount() }
    }.getOrDefault(0)

    /** Waits until the screen has not changed for [quietMs] and, if [network], no HTTP call is running. */
    private fun idle(quietMs: Long, timeoutMs: Long, network: Boolean = true): JsonObject {
        val start = SystemClock.uptimeMillis()
        var last = Int.MIN_VALUE
        var stableSince = start
        while (true) {
            val (fp, pending) = onMain { Semantics.fingerprint() to Semantics.hasPendingLayout() }
            val calls = if (network) runningCalls() else 0
            val now = SystemClock.uptimeMillis()
            if (fp != last || pending || calls > 0) {
                last = fp
                stableSince = now
            } else if (now - stableSince >= quietMs) {
                return buildJsonObject {
                    put("ok", true)
                    put("waitedMs", now - start)
                }
            }
            if (now - start > timeoutMs) fail(408, "not idle after ${timeoutMs}ms (running HTTP calls: $calls)")
            Thread.sleep(50)
        }
    }

    // ---------------------------------------------------------------- screens --

    private object ActivityTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
        }
        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun current(): Activity? = resumed?.get()

    private fun navigatorOf(activity: MainActivity): Navigator? =
        MainActivity::class.java.getDeclaredField("navigator").apply { isAccessible = true }.get(activity) as Navigator?

    /** Simple fields of a screen object: ids, queries, flags. */
    private fun screenJson(screen: Any) = buildJsonObject {
        put("screen", screen::class.java.simpleName)
        putJsonObject("args") {
            var c: Class<*>? = screen::class.java
            while (c != null && c != Any::class.java) {
                c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { f ->
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(screen)
                    }.getOrNull()
                    when (v) {
                        is Number -> put(f.name, JsonPrimitive(v))
                        is Boolean -> put(f.name, v)
                        is String -> put(f.name, v)
                    }
                }
                c = c.superclass
            }
        }
    }

    private fun screen(): JsonObject = onMain {
        val a = current()
        buildJsonObject {
            put("activity", a?.let { it::class.java.simpleName })
            put("windows", Semantics.attachedRoots().size)
            if (a is MainActivity) {
                val nav = navigatorOf(a)
                nav?.lastItem?.let { put("top", screenJson(it)) }
                putJsonArray("stack") { nav?.items?.forEach { add(screenJson(it)) } }
            }
            if (a is ReaderActivity) put("reader", readerState(a))
        }
    }

    /**
     * A BACK key press delivered to the top window. With a dialog or sheet on top, waits until it is
     * gone, falling back to its own back dispatcher if the key alone did not close it.
     */
    private fun back(): JsonObject {
        val top = onMain { Semantics.attachedRoots().firstOrNull() }
        val dialog = top != null && onMain { Semantics.isDialog(top) }
        onMain {
            val view = top?.view?.rootView ?: (current() ?: fail(409, "no resumed activity")).window.decorView
            val now = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
        if (dialog) {
            fun gone() = !onMain { top.view.isAttachedToWindow && top.view.isShown }
            if (!waitUntil(2_000, ::gone)) {
                onMain { top.view.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher?.onBackPressed() }
                if (!waitUntil(2_000, ::gone)) fail(409, "top window did not close on back")
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("closed", if (dialog) "window" else "screen")
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun waitForActivity(timeoutMs: Long, predicate: (Activity) -> Boolean): Activity {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            current()?.takeIf(predicate)?.let { return it }
            Thread.sleep(100)
        }
        fail(408, "activity not reached after ${timeoutMs}ms (now ${current()?.let { it::class.java.simpleName }})")
    }

    /** Brings MainActivity to the front and returns it once its navigator exists. */
    private fun mainActivity(): MainActivity {
        if (current() !is MainActivity) {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        val a = waitForActivity(10_000) { it is MainActivity && onMain { navigatorOf(it) } != null } as MainActivity
        return a
    }

    private fun navigate(b: JsonObject): JsonObject {
        when (val to = b.str("to") ?: fail(400, "to required")) {
            "root" -> mainActivity().let { a -> onMain { navigatorOf(a)!!.popUntilRoot() } }
            "manga" -> {
                val id = b.long("mangaId") ?: fail(400, "mangaId required")
                mainActivity().let { a -> onMain { navigatorOf(a)!!.push(MangaScreen(id)) } }
            }
            "browse" -> {
                val sourceId = b.long("sourceId") ?: fail(400, "sourceId required")
                val listing = when (val l = b.str("listing") ?: "popular") {
                    "popular" -> GetRemoteManga.QUERY_POPULAR
                    "latest" -> GetRemoteManga.QUERY_LATEST
                    else -> l
                }
                mainActivity().let { a -> onMain { navigatorOf(a)!!.push(BrowseSourceScreen(sourceId, listing)) } }
            }
            "reader" -> {
                val mangaId = b.long("mangaId") ?: fail(400, "mangaId required")
                val chapterId = b.long("chapterId") ?: fail(400, "chapterId required")
                val intent = ReaderActivity.newIntent(app, mangaId, chapterId, b.int("page"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                waitForActivity(10_000) { it is ReaderActivity }
            }
            else -> fail(400, "unknown destination $to")
        }
        if (b.bool("idle") != false) idle(quietMs = 600, timeoutMs = b.long("timeoutMs") ?: 20_000)
        return screen()
    }

    // ----------------------------------------------------------------- reader --

    private fun readerState(a: ReaderActivity) = buildJsonObject {
        val s = a.viewModel.state.value
        put("mangaId", s.manga?.id)
        put("mangaTitle", s.manga?.title)
        put("currentPage", s.currentPage)
        put("viewer", s.viewer?.let { it::class.java.simpleName })
        put("menuVisible", s.menuVisible)
        val chapter = s.viewerChapters?.currChapter
        if (chapter != null) {
            putJsonObject("chapter") {
                put("id", chapter.chapter.id)
                put("name", chapter.chapter.name)
                put("url", chapter.chapter.url)
                when (val st = chapter.state) {
                    is ReaderChapter.State.Error -> {
                        put("state", "error")
                        put("error", st.error.message ?: st.error::class.java.simpleName)
                    }
                    is ReaderChapter.State.Loaded -> put("state", "loaded")
                    ReaderChapter.State.Loading -> put("state", "loading")
                    ReaderChapter.State.Wait -> put("state", "wait")
                }
            }
            putJsonArray("pages") {
                chapter.pages?.forEach { p ->
                    add(
                        buildJsonObject {
                            put("index", p.index)
                            put("imageUrl", p.imageUrl)
                            when (val st = p.status) {
                                is Page.State.Error -> {
                                    put("status", "error")
                                    put("error", st.error.message ?: st.error::class.java.simpleName)
                                }
                                Page.State.Ready -> put("status", "ready")
                                Page.State.Queue -> put("status", "queue")
                                Page.State.LoadPage -> put("status", "loadPage")
                                Page.State.DownloadImage -> put("status", "downloadImage")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun reader(): JsonObject = onMain {
        val a = current() as? ReaderActivity ?: fail(409, "reader not open (now ${current()?.let { it::class.java.simpleName }})")
        readerState(a)
    }

    private fun readerPage(page: Int): JsonObject {
        onMain {
            val a = current() as? ReaderActivity ?: fail(409, "reader not open")
            ReaderActivity::class.java.getDeclaredMethod("moveToPageIndex", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(a, page)
        }
        return reader()
    }

    // ------------------------------------------------------------------- data --

    private fun manga(q: Map<String, String>): JsonObject = runBlocking {
        val getManga = Injekt.get<GetManga>()
        val manga = q["id"]?.toLongOrNull()?.let { getManga.await(it) }
            ?: q["url"]?.let { url -> getManga.await(url, q["sourceId"]?.toLongOrNull() ?: fail(400, "sourceId required")) }
            ?: fail(404, "manga not found")
        val chapters = Injekt.get<GetChaptersByMangaId>().await(manga.id)
        buildJsonObject {
            put("id", manga.id)
            put("source", manga.source)
            put("url", manga.url)
            put("title", manga.title)
            put("author", manga.author)
            put("artist", manga.artist)
            put("status", manga.status)
            put("genre", manga.genre?.joinToString(", "))
            put("description", manga.description)
            put("thumbnailUrl", manga.thumbnailUrl)
            put("initialized", manga.initialized)
            put("favorite", manga.favorite)
            putJsonObject("chapterStats") {
                put("count", chapters.size)
                put("uniqueUrls", chapters.map { it.url }.toSet().size)
                put("noDate", chapters.count { it.dateUpload <= 0L })
                put("numberMissing", chapters.count { it.chapterNumber < 0 })
                putJsonObject("scanlators") {
                    chapters.groupingBy { it.scanlator.orEmpty() }.eachCount().forEach { (k, v) -> put(k, v) }
                }
            }
            if (q["chapters"] == "full") {
                putJsonArray("chapters") {
                    chapters.forEach { c ->
                        add(
                            buildJsonObject {
                                put("id", c.id)
                                put("url", c.url)
                                put("name", c.name)
                                put("number", c.chapterNumber)
                                put("dateUpload", c.dateUpload)
                                put("scanlator", c.scanlator)
                                put("read", c.read)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun prefs(id: Long) = app.getSharedPreferences("source_$id", Context.MODE_PRIVATE)

    private fun sourcePrefs(id: Long) = buildJsonObject {
        put("id", id)
        putJsonObject("values") {
            prefs(id).all.toSortedMap().forEach { (k, v) ->
                putJsonObject(k) {
                    when (v) {
                        is Boolean -> {
                            put("type", "boolean")
                            put("value", v)
                        }
                        is Int -> {
                            put("type", "int")
                            put("value", v)
                        }
                        is Long -> {
                            put("type", "long")
                            put("value", v)
                        }
                        is Float -> {
                            put("type", "float")
                            put("value", v)
                        }
                        is String -> {
                            put("type", "string")
                            put("value", v)
                        }
                        is Set<*> -> {
                            put("type", "stringSet")
                            putJsonArray("value") { v.forEach { add(JsonPrimitive(it.toString())) } }
                        }
                        else -> {
                            put("type", "unknown")
                            put("value", v.toString())
                        }
                    }
                }
            }
        }
    }

    /** Body: {id, clear?: bool, remove?: [keys], values?: {key: {type, value}}} */
    private fun putSourcePrefs(b: JsonObject): JsonObject {
        val id = b.long("id") ?: fail(400, "id required")
        val editor = prefs(id).edit()
        if (b.bool("clear") == true) editor.clear()
        b["remove"]?.jsonArray?.forEach { editor.remove(it.jsonPrimitive.content) }
        b["values"]?.jsonObject?.forEach { (k, e) ->
            val o = e.jsonObject
            val v = o["value"] ?: fail(400, "value missing for $k")
            when (o.str("type")) {
                "boolean" -> editor.putBoolean(k, v.jsonPrimitive.boolean)
                "int" -> editor.putInt(k, v.jsonPrimitive.int)
                "long" -> editor.putLong(k, v.jsonPrimitive.long)
                "float" -> editor.putFloat(k, v.jsonPrimitive.float)
                "string" -> editor.putString(k, v.jsonPrimitive.content)
                "stringSet" -> editor.putStringSet(k, v.jsonArray.map { it.jsonPrimitive.content }.toSet())
                else -> fail(400, "unknown type for $k")
            }
        }
        if (!editor.commit()) fail(500, "commit failed")
        return sourcePrefs(id)
    }

    private fun net() = buildJsonObject {
        val d = Injekt.get<NetworkHelper>().client.dispatcher
        put("running", d.runningCallsCount())
        put("queued", d.queuedCallsCount())
    }

    // ---------------------------------------------------------------- crashes --

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val line = buildJsonObject {
                    put("ts", System.currentTimeMillis())
                    put("thread", t.name)
                    put("exception", e::class.java.name)
                    put("message", e.message)
                    putJsonArray("stack") { e.stackTrace.take(15).forEach { add(JsonPrimitive(it.toString())) } }
                    e.cause?.let { put("cause", "${it::class.java.name}: ${it.message}") }
                }
                crashFile.appendText(json.encodeToString(JsonElement.serializer(), line) + "\n")
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun crashes(since: Long) = buildJsonArray {
        if (crashFile.exists()) {
            crashFile.readLines().mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                .filter { (it["ts"]?.jsonPrimitive?.longOrNull ?: 0) >= since }
                .forEach { add(it) }
        }
    }
}
