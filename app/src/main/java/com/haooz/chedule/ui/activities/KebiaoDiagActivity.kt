/** 课表直连诊断页：一键真·刷新 + 把断在哪一步原样报出来。 */
package com.haooz.chedule.ui.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.data.AutoKebiaoSync
import com.haooz.chedule.data.BuiltinCourseSync
import com.haooz.chedule.data.DirectKebiaoFetcher
import com.haooz.chedule.ui.theme.CourseScheduleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

class KebiaoDiagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CourseScheduleTheme {
                KebiaoDiagScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun KebiaoDiagScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val cred = remember { DirectKebiaoFetcher.loadCredential(context) }
    var userNo by remember { mutableStateOf(cred.userNo) }
    var pwd by remember { mutableStateOf(cred.pwdEnc) }
    var sch by remember { mutableStateOf(cred.schoolCode) }
    var cookie by remember { mutableStateOf(DirectKebiaoFetcher.loadJwCookie(context)) }
    var report by remember { mutableStateOf("点下方按钮开始。") }
    var busy by remember { mutableStateOf(false) }
    var last by remember {
        mutableStateOf(
            AutoKebiaoSync.lastResult(context).ifBlank { "（暂无记录：打开 App 后会自动同步一次）" }
        )
    }

    fun copy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diag", text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
    ) {
        Text(
            text = "课表直连 · 诊断",
            fontSize = 22.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "构建 ${DirectKebiaoFetcher.BUILD_TAG} · App 直连 jw.hnsoftedu.com，不经过任何外国服务器",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = userNo,
                    onValueChange = { userNo = it },
                    label = { Text("学号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("密码串（加密后）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sch,
                    onValueChange = { sch = it },
                    label = { Text("学校代码") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text("教务 Cookie（JSESSIONID=...）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )

                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    TextButton(
                        text = if (busy) "处理中…" else "保存并刷新课表",
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            report = "正在直连教务…"
                            DirectKebiaoFetcher.saveCredential(
                                context,
                                DirectKebiaoFetcher.Credential(userNo, pwd, sch),
                            )
                            DirectKebiaoFetcher.saveJwCookie(context, cookie)
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { AutoKebiaoSync.force(context) }
                                        .getOrElse { "同步抛异常：${it.javaClass.simpleName}: ${it.message?.take(200)}" }
                                }
                                report = r
                                busy = false
                                last = AutoKebiaoSync.lastResult(context)
                            }
                        },
                    )
                    TextButton(
                        text = "只跑自检",
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            report = "自检中…"
                            scope.launch {
                                val t = withContext(Dispatchers.IO) {
                                    runCatching { DirectKebiaoFetcher.diagnose(context) }
                                        .getOrElse { "自检本身异常：${it.message?.take(200)}" }
                                }
                                report = t
                                busy = false
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = report,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "复制结果",
                    onClick = { copy(report) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = "自动同步记录（启动即静默同步，无需手动点）",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = last,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }

        TextButton(
            text = "返回",
            onClick = onBack,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
