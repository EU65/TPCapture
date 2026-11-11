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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val MODES = listOf("record", "matched", "rewrite-only")
private val MODE_NAMES = listOf("全部记录", "仅记录命中", "仅重写不记录")
private val MODE_HINTS = listOf(
    "所有命中目标的请求都会解密并写入数据库。",
    "只有命中规则的请求才会写入数据库，其余请求仍会解密转发。",
    "仅命中规则才解密处理，未命中的连接直接透传，也不写数据库。",
)

@Composable
fun RuleListScreen(
    mode: String,
    rules: List<Rule>,
    status: String,
    loading: Boolean,
    onMode: (String) -> Unit,
    onReorder: (List<Rule>) -> Unit,
    onCreate: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val index = MODES.indexOf(mode).coerceIn(0, MODES.size - 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "重写规则",
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(MiuixIcons.Useful.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SmallTitle("记录模式")
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(MODE_NAMES[index], fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(MODE_HINTS[index], fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    Spacer(Modifier.height(12.dp))
                    SmallButton("切换模式") { onMode(MODES[(index + 1) % MODES.size]) }
                }
            }
            item {
                SmallTitle("规则")
                if (rules.isEmpty()) {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            if (loading) "正在读取规则…" else "还没有规则。点下方按钮新建，或从请求页长按快速添加。",
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        if (!loading) {
                            Spacer(Modifier.height(6.dp))
                            Text(status, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            Spacer(Modifier.height(10.dp))
                            SmallButton("重新读取") { onReload() }
                        }
                    }
                }
            }
            itemsIndexed(rules) { position, rule ->
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    onClick = { onEdit(position) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rule.name.ifBlank { "规则 ${position + 1}" },
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (rule.disabled) {
                            Text("未启用", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(rule.summary(), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("编辑") { onEdit(position) }
                        SmallButton("上移", enabled = position > 0) {
                            val list = rules.toMutableList()
                            val item = list.removeAt(position)
                            list.add(position - 1, item)
                            onReorder(list)
                        }
                        SmallButton("下移", enabled = position < rules.size - 1) {
                            val list = rules.toMutableList()
                            val item = list.removeAt(position)
                            list.add(position + 1, item)
                            onReorder(list)
                        }
                        SmallButton("删除") { onDelete(position) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                        Text("新建规则")
                    }
                    if (rules.isNotEmpty() || loading) {
                        Spacer(Modifier.height(10.dp))
                        Text(status, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        cornerRadius = 12.dp,
        minWidth = 0.dp,
        minHeight = 34.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 12.sp, maxLines = 1)
    }
}
