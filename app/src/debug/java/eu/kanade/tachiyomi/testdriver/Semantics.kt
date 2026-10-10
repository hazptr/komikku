package eu.kanade.tachiyomi.testdriver

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.ref.WeakReference

/**
 * Compose roots of this process and operations on their semantics trees.
 * Everything here must run on the main thread.
 */
internal object Semantics {

    private val roots = mutableListOf<WeakReference<ViewRootForTest>>()

    fun register(root: ViewRootForTest) {
        synchronized(roots) { roots += WeakReference(root) }
    }

    /** Attached roots, the focused window first, then the most recently created. */
    fun attachedRoots(): List<ViewRootForTest> {
        val live = synchronized(roots) {
            roots.removeAll { it.get() == null }
            roots.mapNotNull { it.get() }
        }
        return live
            .filter { it.view.isAttachedToWindow && it.view.isShown }
            .reversed()
            .sortedByDescending { it.view.hasWindowFocus() }
    }

    class Found(val root: ViewRootForTest, val node: SemanticsNode, val rootIndex: Int)

    /** All nodes of all attached roots, merged tree for text/desc, unmerged tree for test tags. */
    fun nodes(merged: Boolean = true): List<Found> = attachedRoots().flatMapIndexed { i, root ->
        val start = if (merged) root.semanticsOwner.rootSemanticsNode else root.semanticsOwner.unmergedRootSemanticsNode
        buildList { walk(start) { add(Found(root, it, i)) } }
    }

    private fun walk(node: SemanticsNode, visit: (SemanticsNode) -> Unit) {
        visit(node)
        node.children.forEach { walk(it, visit) }
    }

    fun text(node: SemanticsNode): String = buildList {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
    }.joinToString(" ")

    fun desc(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    fun tag(node: SemanticsNode): String? = node.config.getOrNull(SemanticsProperties.TestTag)

    fun clickable(node: SemanticsNode) = node.config.getOrNull(SemanticsActions.OnClick) != null

    fun scrollable(node: SemanticsNode) = node.config.getOrNull(SemanticsActions.ScrollBy) != null

    fun editable(node: SemanticsNode) = node.config.getOrNull(SemanticsActions.SetText) != null

    fun matches(node: SemanticsNode, sel: JsonObject): Boolean {
        fun s(key: String) = sel[key]?.jsonPrimitive?.content
        val text = text(node)
        val desc = desc(node)
        s("text")?.let { if (text != it) return false }
        s("textContains")?.let { if (!text.contains(it, ignoreCase = true)) return false }
        s("textRegex")?.let { if (!Regex(it).containsMatchIn(text)) return false }
        s("desc")?.let { if (desc != it) return false }
        s("descContains")?.let { if (!desc.contains(it, ignoreCase = true)) return false }
        s("any")?.let { if (!text.contains(it, ignoreCase = true) && !desc.contains(it, ignoreCase = true)) return false }
        s("tag")?.let { if (tag(node) != it) return false }
        s("role")?.let { if (node.config.getOrNull(SemanticsProperties.Role)?.toString() != it) return false }
        sel["clickable"]?.jsonPrimitive?.booleanOrNull?.let { if (clickable(node) != it) return false }
        sel["scrollable"]?.jsonPrimitive?.booleanOrNull?.let { if (scrollable(node) != it) return false }
        sel["editable"]?.jsonPrimitive?.booleanOrNull?.let { if (editable(node) != it) return false }
        return true
    }

    private val selectorKeys = setOf(
        "text", "textContains", "textRegex", "desc", "descContains", "any", "tag", "role",
        "clickable", "scrollable", "editable", "index",
    )

    fun checkSelector(sel: JsonObject) {
        val unknown = sel.keys - selectorKeys
        require(unknown.isEmpty()) { "unknown selector keys $unknown (known: $selectorKeys)" }
        require(sel.keys.any { it != "index" }) { "empty selector" }
    }

    fun find(sel: JsonObject): List<Found> {
        checkSelector(sel)
        val merged = !sel.containsKey("tag")
        val all = nodes(merged).filter { !it.node.isRoot && matches(it.node, sel) }
        val index = sel["index"]?.jsonPrimitive?.intOrNull ?: return all
        return listOfNotNull(all.getOrNull(index))
    }

    /** The node itself if clickable, otherwise its nearest clickable ancestor. */
    fun clickTarget(node: SemanticsNode): SemanticsNode? {
        var n: SemanticsNode? = node
        while (n != null) {
            if (clickable(n)) return n
            n = n.parent
        }
        return null
    }

    fun click(node: SemanticsNode): Boolean {
        val target = clickTarget(node) ?: return false
        return target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
    }

    fun longClick(node: SemanticsNode): Boolean {
        var n: SemanticsNode? = node
        while (n != null) {
            n.config.getOrNull(SemanticsActions.OnLongClick)?.action?.let { return it() }
            n = n.parent
        }
        return false
    }

    fun setText(node: SemanticsNode, value: String): Boolean =
        node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true

    fun imeAction(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsActions.OnImeAction)?.action?.invoke() == true

    /** The scrollable containers, largest first. */
    fun scrollables(): List<Found> = nodes(merged = false)
        .filter { scrollable(it.node) }
        .sortedByDescending { it.node.boundsInRoot.height * it.node.boundsInRoot.width }

    fun scrollBy(node: SemanticsNode, dx: Float, dy: Float): Boolean =
        node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(dx, dy) == true

    /** Changes whenever what is on screen changes. */
    fun fingerprint(): Int = nodes(merged = true).fold(17) { acc, f ->
        val b = f.node.boundsInRoot
        31 * acc + (f.node.id * 7 + text(f.node).hashCode() + desc(f.node).hashCode() + b.top.toInt() + b.left.toInt())
    }

    fun hasPendingLayout() = attachedRoots().any { it.hasPendingMeasureOrLayout }

    fun toJson(f: Found, depth: Int? = null): JsonObject = buildJsonObject {
        val n = f.node
        val loc = IntArray(2).also { f.root.view.getLocationOnScreen(it) }
        val b = n.boundsInRoot
        put("id", n.id)
        put("window", f.rootIndex)
        depth?.let { put("depth", it) }
        text(n).takeIf(String::isNotEmpty)?.let { put("text", it) }
        desc(n).takeIf(String::isNotEmpty)?.let { put("desc", it) }
        tag(n)?.let { put("tag", it) }
        n.config.getOrNull(SemanticsProperties.Role)?.let { put("role", it.toString()) }
        put(
            "bounds",
            buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive((b.left + loc[0]).toInt()))
                add(kotlinx.serialization.json.JsonPrimitive((b.top + loc[1]).toInt()))
                add(kotlinx.serialization.json.JsonPrimitive((b.right + loc[0]).toInt()))
                add(kotlinx.serialization.json.JsonPrimitive((b.bottom + loc[1]).toInt()))
            },
        )
        if (clickable(n)) put("clickable", true)
        if (scrollable(n)) put("scrollable", true)
        if (editable(n)) put("editable", true)
        n.config.getOrNull(SemanticsProperties.Selected)?.let { put("selected", it) }
        n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { put("checked", it == ToggleableState.On) }
        if (n.config.getOrNull(SemanticsProperties.Disabled) != null) put("disabled", true)
    }

    /** Depth-first dump of every attached root. */
    fun tree(merged: Boolean, onlyLabelled: Boolean) = buildJsonArray {
        attachedRoots().forEachIndexed { i, root ->
            val start = if (merged) root.semanticsOwner.rootSemanticsNode else root.semanticsOwner.unmergedRootSemanticsNode
            fun rec(n: SemanticsNode, d: Int) {
                val keep = !onlyLabelled || text(n).isNotEmpty() || desc(n).isNotEmpty() || tag(n) != null ||
                    clickable(n) || scrollable(n)
                if (keep) add(toJson(Found(root, n, i), d))
                n.children.forEach { rec(it, d + 1) }
            }
            rec(start, 0)
        }
    }
}
