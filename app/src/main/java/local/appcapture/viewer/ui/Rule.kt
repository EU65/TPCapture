package local.appcapture.viewer.ui

import org.json.JSONArray
import org.json.JSONObject

class Block(var status: String = "403", var body: String = "", var type: String = "text/plain; charset=utf-8")

class Side(
    var pattern: String = "",
    var replace: String = "",
    var status: String = "",
    var headers: MutableList<Pair<String, String>> = mutableListOf(),
    var removeHeaders: String = "",
    var body: MutableList<Pair<String, String>> = mutableListOf(),
) {
    fun isEmpty(): Boolean = pattern.isEmpty() && status.isEmpty() && headers.isEmpty() &&
        removeHeaders.isBlank() && body.isEmpty()
}

class Rule(
    var name: String = "",
    var disabled: Boolean = false,
    var uids: String = "",
    var hosts: String = "",
    var methods: String = "",
    var urls: String = "",
    var literal: Boolean = false,
    var block: Block? = null,
    var request: Side = Side(),
    var response: Side = Side(),
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts += "UID " + (uids.ifBlank { "全部" })
        parts += "主机 " + (hosts.ifBlank { "全部" })
        if (methods.isNotBlank()) parts += methods.replace("\n", ", ")
        val text = parts.joinToString(" · ")
        val urlsLine = if (urls.isBlank()) "" else "\nURL " + urls.replace("\n", "  ")
        val actions = mutableListOf<String>()
        block?.let { actions += "拦截 ${it.status}" }
        if (!request.isEmpty()) actions += "改写请求"
        if (!response.isEmpty()) actions += "改写响应"
        val actionLine = if (actions.isEmpty()) "" else "\n" + actions.joinToString(" · ")
        return text + urlsLine + actionLine
    }
}

fun Rule.toJson(): JSONObject {
    val o = JSONObject()
    o.put("name", name.trim())
    o.put("disabled", disabled)
    putList(o, "uids", uids, true)
    putList(o, "hosts", hosts, false)
    putList(o, "methods", methods, false)
    putList(o, "urls", urls, false)
    block?.let {
        o.put(
            "block",
            JSONObject()
                .put("status", it.status.trim().toIntOrNull() ?: 403)
                .put("body", it.body)
                .put("type", it.type.trim()),
        )
    }
    if (!request.isEmpty()) o.put("request", request.toJson(literal))
    if (!response.isEmpty()) o.put("response", response.toJson(literal))
    return o
}

private fun Side.toJson(literal: Boolean): JSONObject {
    val o = JSONObject()
    if (pattern.isNotEmpty()) {
        val url = JSONObject().put("pattern", pattern).put("replace", replace)
        if (literal) url.put("regex", false)
        o.put("url", url)
    }
    status.trim().toIntOrNull()?.takeIf { it > 0 }?.let { o.put("status", it) }
    val set = JSONObject()
    headers.forEach { (k, v) -> if (k.isNotBlank()) set.put(k.trim(), v) }
    if (set.length() > 0) o.put("setHeaders", set)
    val remove = JSONArray()
    removeHeaders.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { remove.put(it) }
    if (remove.length() > 0) o.put("removeHeaders", remove)
    val bodyList = JSONArray()
    body.forEach { (p, r) ->
        if (p.isNotEmpty()) {
            val item = JSONObject().put("pattern", p).put("replace", r)
            if (literal) item.put("regex", false)
            bodyList.put(item)
        }
    }
    if (bodyList.length() > 0) o.put("body", bodyList)
    return o
}

private fun putList(o: JSONObject, key: String, text: String, numeric: Boolean) {
    val array = JSONArray()
    text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
        if (numeric) array.put(it.toLongOrNull() ?: 0L) else array.put(it)
    }
    if (array.length() > 0) o.put(key, array)
}

private fun join(array: JSONArray?): String =
    if (array == null || array.length() == 0) "" else (0 until array.length()).joinToString("\n") { array.optString(it) }

private fun listText(array: JSONArray?): String =
    if (array == null || array.length() == 0) "" else (0 until array.length()).joinToString(", ") { array.optString(it) }

fun JSONObject.toRule(): Rule {
    val block = optJSONObject("block")?.let {
        Block(
            status = it.optInt("status", 0).takeIf { s -> s > 0 }?.toString() ?: "403",
            body = it.optString("body", ""),
            type = it.optString("type", "text/plain; charset=utf-8"),
        )
    }
    val literal = optJSONObject("request")?.optJSONObject("url")?.optBoolean("regex", true) == false
    return Rule(
        name = optString("name", ""),
        disabled = optBoolean("disabled", false),
        uids = join(optJSONArray("uids")),
        hosts = join(optJSONArray("hosts")),
        methods = listText(optJSONArray("methods")),
        urls = join(optJSONArray("urls")),
        literal = literal,
        block = block,
        request = optJSONObject("request")?.toSide() ?: Side(),
        response = optJSONObject("response")?.toSide() ?: Side(),
    )
}

private fun JSONObject.toSide(): Side {
    val headers = mutableListOf<Pair<String, String>>()
    optJSONObject("setHeaders")?.let { set ->
        set.keys().forEach { key -> headers += key to set.optString(key, "") }
    }
    val bodies = mutableListOf<Pair<String, String>>()
    optJSONArray("body")?.let { array ->
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { bodies += it.optString("pattern", "") to it.optString("replace", "") }
        }
    }
    val url = optJSONObject("url")
    return Side(
        pattern = url?.optString("pattern", "") ?: "",
        replace = url?.optString("replace", "") ?: "",
        status = optInt("status", 0).takeIf { it > 0 }?.toString() ?: "",
        headers = headers,
        removeHeaders = optJSONArray("removeHeaders")?.let { array ->
            (0 until array.length()).joinToString(", ") { array.optString(it) }
        } ?: "",
        body = bodies,
    )
}
