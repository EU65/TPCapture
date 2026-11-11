package local.appcapture.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.text.KeyboardOptions

private class PairRow(val key: String, val value: String)

@Composable
fun RuleEditScreen(
    initial: Rule,
    onCreate: Boolean,
    onSave: (Rule) -> Unit,
    onCancel: () -> Unit,
) {

    var name by remember { mutableStateOf(initial.name) }
    var enabled by remember { mutableStateOf(!initial.disabled) }
    var uids by remember { mutableStateOf(initial.uids) }
    var hosts by remember { mutableStateOf(initial.hosts) }
    var methods by remember { mutableStateOf(initial.methods) }
    var urls by remember { mutableStateOf(initial.urls) }
    var literal by remember { mutableStateOf(initial.literal) }

    var blockOn by remember { mutableStateOf(initial.block != null) }
    var blockStatus by remember { mutableStateOf(initial.block?.status ?: "403") }
    var blockBody by remember { mutableStateOf(initial.block?.body ?: "") }
    var blockType by remember { mutableStateOf(initial.block?.type ?: "text/plain; charset=utf-8") }

    var reqPattern by remember { mutableStateOf(initial.request.pattern) }
    var reqReplace by remember { mutableStateOf(initial.request.replace) }
    var reqRemove by remember { mutableStateOf(initial.request.removeHeaders) }
    val reqHeaders = remember { mutableStateListOf<PairRow>().apply { initial.request.headers.forEach { add(PairRow(it.first, it.second)) } } }
    val reqBodies = remember { mutableStateListOf<PairRow>().apply { initial.request.body.forEach { add(PairRow(it.first, it.second)) } } }

    var respPattern by remember { mutableStateOf(initial.response.pattern) }
    var respReplace by remember { mutableStateOf(initial.response.replace) }
    var respStatus by remember { mutableStateOf(initial.response.status) }
    var respRemove by remember { mutableStateOf(initial.response.removeHeaders) }
    val respHeaders = remember { mutableStateListOf<PairRow>().apply { initial.response.headers.forEach { add(PairRow(it.first, it.second)) } } }
    val respBodies = remember { mutableStateListOf<PairRow>().apply { initial.response.body.forEach { add(PairRow(it.first, it.second)) } } }

    fun buildSide(
        pattern: String, replace: String, statusCode: String, remove: String,
        headers: List<PairRow>, bodies: List<PairRow>,
    ): Side {
        val side = Side(
            pattern = pattern,
            replace = replace,
            status = statusCode,
            removeHeaders = remove,
        )
        headers.forEach { if (it.key.isNotBlank()) side.headers += it.key.trim() to it.value }
        bodies.forEach { if (it.key.isNotEmpty()) side.body += it.key to it.value }
        return side
    }

    fun save() {
        val rule = Rule(
            name = name,
            disabled = !enabled,
            uids = uids,
            hosts = hosts,
            methods = methods,
            urls = urls,
            literal = literal,
            block = if (blockOn) Block(blockStatus, blockBody, blockType) else null,
            request = buildSide(reqPattern, reqReplace, "", reqRemove, reqHeaders, reqBodies),
            response = buildSide(respPattern, respReplace, respStatus, respRemove, respHeaders, respBodies),
        )
        val noCondition = rule.uids.isBlank() && rule.hosts.isBlank() && rule.methods.isBlank() && rule.urls.isBlank()
        val noAction = rule.block == null && rule.request.isEmpty() && rule.response.isEmpty()
        if (noCondition && noAction) {
            onCancel()
            return
        }
        onSave(rule)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (onCreate) "新建规则" else "编辑规则",
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(MiuixIcons.Useful.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = { save() },
                        minWidth = 0.dp,
                        minHeight = 40.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text("保存", fontSize = 13.sp) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SmallTitle("基础")
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                MiuixField(name, { name = it }, "规则名称")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
            }

            SmallTitle("匹配条件")
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                MiuixField(uids, { uids = it }, "UID，逗号或换行分隔，留空为全部", multiline = true)
                Spacer(Modifier.height(10.dp))
                MiuixField(hosts, { hosts = it }, "主机，支持 *.example.com，留空为全部", multiline = true)
                Spacer(Modifier.height(10.dp))
                MiuixField(methods, { methods = it }, "方法，逗号分隔，留空为全部")
                Spacer(Modifier.height(10.dp))
                MiuixField(urls, { urls = it }, "URL 正则，每行一条，留空为全部", multiline = true)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("按字面文本匹配", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(literal, { literal = it })
                }
            }

            SmallTitle("拦截")
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("拦截请求并直接返回", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(blockOn, { blockOn = it })
                }
                if (blockOn) {
                    Spacer(Modifier.height(10.dp))
                    MiuixField(blockStatus, { blockStatus = it }, "状态码", number = true)
                    Spacer(Modifier.height(10.dp))
                    MiuixField(blockBody, { blockBody = it }, "响应正文", multiline = true)
                    Spacer(Modifier.height(10.dp))
                    MiuixField(blockType, { blockType = it }, "Content-Type")
                }
            }

            SideEditor(
                title = "改写请求",
                pattern = reqPattern,
                onPattern = { reqPattern = it },
                replace = reqReplace,
                onReplace = { reqReplace = it },
                statusCode = null,
                onStatus = {},
                remove = reqRemove,
                onRemove = { reqRemove = it },
                headers = reqHeaders,
                bodies = reqBodies,
            )

            SideEditor(
                title = "改写响应",
                pattern = respPattern,
                onPattern = { respPattern = it },
                replace = respReplace,
                onReplace = { respReplace = it },
                statusCode = respStatus,
                onStatus = { respStatus = it },
                remove = respRemove,
                onRemove = { respRemove = it },
                headers = respHeaders,
                bodies = respBodies,
            )

        }
    }
}

@Composable
private fun MiuixField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    multiline: Boolean = false,
    number: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 6 else 1,
        textStyle = MiuixTheme.textStyles.main,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
private fun SideEditor(
    title: String,
    pattern: String,
    onPattern: (String) -> Unit,
    replace: String,
    onReplace: (String) -> Unit,
    statusCode: String?,
    onStatus: (String) -> Unit,
    remove: String,
    onRemove: (String) -> Unit,
    headers: MutableList<PairRow>,
    bodies: MutableList<PairRow>,
) {
    SmallTitle(title)
    Card(
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
        MiuixField(pattern, onPattern, "URL 匹配，留空不改")
        Spacer(Modifier.height(10.dp))
        MiuixField(replace, onReplace, "URL 替换")
        if (statusCode != null) {
            Spacer(Modifier.height(10.dp))
            MiuixField(statusCode, onStatus, "状态码，留空不改", number = true)
        }
        Spacer(Modifier.height(12.dp))
        Text("添加或覆盖的头", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        headers.forEachIndexed { index, item ->
            Spacer(Modifier.height(8.dp))
            PairEditor(item, onChange = { k, v -> headers[index] = PairRow(k, v) }, onRemove = { headers.removeAt(index) })
        }
        Spacer(Modifier.height(8.dp))
        AddButton("添加头") { headers.add(PairRow("", "")) }
        Spacer(Modifier.height(12.dp))
        MiuixField(remove, onRemove, "删除的头，逗号分隔")
        Spacer(Modifier.height(12.dp))
        Text("正文替换", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        bodies.forEachIndexed { index, item ->
            Spacer(Modifier.height(8.dp))
            PairEditor(item, onChange = { k, v -> bodies[index] = PairRow(k, v) }, onRemove = { bodies.removeAt(index) })
        }
        Spacer(Modifier.height(8.dp))
        AddButton("添加替换") { bodies.add(PairRow("", "")) }
    }
}

@Composable
private fun PairEditor(item: PairRow, onChange: (String, String) -> Unit, onRemove: () -> Unit) {
    Column {
        TextField(
            value = item.key,
            onValueChange = { onChange(it, item.value) },
            modifier = Modifier.fillMaxWidth(),
            label = "键",
            useLabelAsPlaceholder = true,
            singleLine = true,
        )
        Spacer(Modifier.height(6.dp))
        TextField(
            value = item.value,
            onValueChange = { onChange(item.key, it) },
            modifier = Modifier.fillMaxWidth(),
            label = "值",
            useLabelAsPlaceholder = true,
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onRemove,
                cornerRadius = 12.dp,
                minWidth = 0.dp,
                minHeight = 34.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) { Text("删除", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        minWidth = 0.dp,
        minHeight = 40.dp,
        insideMargin = PaddingValues(vertical = 8.dp),
    ) { Text(text, fontSize = 13.sp) }
}

