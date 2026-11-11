package local.appcapture.viewer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class RewriteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installDir = intent.getStringExtra("install_dir") ?: run {
            finish()
            return
        }
        val dataDir = intent.getStringExtra("data_dir") ?: ""
        val seed = intent.getStringExtra("seed") ?: ""
        val seedHost = intent.getStringExtra("seed_host") ?: ""
        val seedPath = intent.getStringExtra("seed_path") ?: ""
        val seedMethod = intent.getStringExtra("seed_method") ?: ""
        val seedUid = intent.getIntExtra("seed_uid", -1)
        val modes = listOf("record", "matched", "rewrite-only")

        setContent {
            val scheme = remember { lightColorScheme() }
            MiuixTheme(colors = scheme) {
                val scope = rememberCoroutineScope()
                var mode by remember { mutableStateOf("record") }
                var rules by remember { mutableStateOf(listOf<Rule>()) }
                var status by remember { mutableStateOf("正在读取规则…") }
                var loading by remember { mutableStateOf(true) }
                var editing by remember { mutableStateOf<Rule?>(null) }
                var editIndex by remember { mutableStateOf(-1) }
                var reloadKey by remember { mutableStateOf(0) }
                var seeded by remember { mutableStateOf(false) }

                fun applyText(text: String) {
                    val o = JSONObject(text)
                    mode = o.optString("mode", "record").takeIf { it in modes } ?: "record"
                    val array = o.optJSONArray("rules") ?: JSONArray()
                    rules = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toRule() }
                    status = "共 ${rules.size} 条规则"
                }

                fun load() {
                    loading = true
                    status = "正在读取规则…"
                    scope.launch {
                        try {
                            applyText(Root.run(scriptCommand(dataDir, installDir, "rewrite-get")))
                            if (seed.isNotEmpty() && !seeded) {
                                seeded = true
                                val rule = seedRule(seed, seedHost, seedPath, seedMethod, seedUid)
                                rules = rules + rule
                                editIndex = rules.size - 1
                                editing = rule
                            }
                        } catch (e: Exception) {
                            status = "读取失败：" + (e.message ?: "未知错误")
                        } finally {
                            loading = false
                        }
                    }
                }

                fun persist(nextMode: String, nextRules: List<Rule>) {
                    val payload = JSONObject()
                        .put("mode", nextMode)
                        .put("rules", JSONArray().apply { nextRules.forEach { put(it.toJson()) } })
                    scope.launch {
                        status = "正在保存…"
                        try {
                            Root.run(scriptCommand(dataDir, installDir, "rewrite-set"), payload.toString().toByteArray())
                            status = "已保存"
                        } catch (e: Exception) {
                            status = "保存失败：" + (e.message ?: "未知错误")
                        }
                    }
                }

                LaunchedEffect(reloadKey) { load() }

                BackHandler(enabled = editing != null) {
                    editing = null
                    editIndex = -1
                }

                val current = editing
                if (current == null) {
                    RuleListScreen(
                        mode = mode,
                        rules = rules,
                        status = status,
                        loading = loading,
                        onMode = { next ->
                            mode = next
                            persist(next, rules)
                        },
                        onReorder = { list -> rules = list },
                        onCreate = {
                            editIndex = -1
                            editing = Rule()
                        },
                        onEdit = { index ->
                            editIndex = index
                            editing = rules[index]
                        },
                        onDelete = { index ->
                            rules = rules.filterIndexed { i, _ -> i != index }
                            persist(mode, rules)
                        },
                        onReload = { reloadKey++ },
                        onClose = { finish() },
                    )
                } else {
                    RuleEditScreen(
                        initial = current,
                        onCreate = editIndex < 0,
                        onSave = { rule ->
                            if (rule.name.isBlank()) rule.name = defaultName(rule)
                            rules = if (editIndex in rules.indices) {
                                rules.toMutableList().also { it[editIndex] = rule }
                            } else {
                                rules + rule
                            }
                            persist(mode, rules)
                            editing = null
                            editIndex = -1
                        },
                        onCancel = {
                            editing = null
                            editIndex = -1
                        },
                    )
                }
            }
        }
    }
}

private fun defaultName(rule: Rule): String = when {
    rule.block != null && rule.hosts.isNotBlank() -> "拦截 ${rule.hosts.lineSequence().first().trim()}"
    rule.hosts.isNotBlank() -> rule.hosts.lineSequence().first().trim()
    rule.name.isNotBlank() -> rule.name
    else -> "规则"
}

private fun seedRule(seed: String, host: String, path: String, method: String, uid: Int): Rule {
    val quoted = if (path.isEmpty()) "" else "^" + java.util.regex.Pattern.quote(path)
    return when (seed) {
        "block-url" -> Rule(
            name = "拦截 $path",
            hosts = host,
            urls = quoted,
            block = Block(status = "403", body = "blocked by TPCapture"),
        )
        "block-host" -> Rule(
            name = "拦截 $host",
            hosts = host,
            block = Block(status = "403", body = "blocked by TPCapture"),
        )
        else -> Rule(
            name = listOf(host, path).filter { it.isNotEmpty() }.joinToString(" "),
            uids = if (uid >= 0) uid.toString() else "",
            hosts = host,
            urls = quoted,
            methods = if (method.startsWith("WS")) "" else method,
        )
    }
}
