package com.flux.manga.ui.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FfiProbePage(back: () -> Unit, model: FfiProbeViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = back, modifier = Modifier.testTag("probe-back")) { Text("返回书库") }
        Text("M0-3 · FFI 验证", style = MaterialTheme.typography.headlineSmall)
        Text("本地固定样本，用于检查异步结果与取消。图片传输测量由设备测试执行。")
        Text(state.message, modifier = Modifier.testTag("probe-status"))
        Button(onClick = { model.start(500u, false) }, modifier = Modifier.testTag("probe-success")) {
            Text("异步成功")
        }
        Button(onClick = { model.start(300u, true) }, modifier = Modifier.testTag("probe-failure")) {
            Text("异步失败")
        }
        Button(onClick = { model.start(10_000u, false) }, modifier = Modifier.testTag("probe-slow")) {
            Text("启动慢任务（10 秒）")
        }
        Button(onClick = model::cancel, enabled = state.running, modifier = Modifier.testTag("probe-cancel")) {
            Text("取消任务")
        }
    }
}
