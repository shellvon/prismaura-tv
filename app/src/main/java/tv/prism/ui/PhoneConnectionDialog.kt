package tv.prism.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.tv.material3.Text
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.remote.qrCodeBitmap
import tv.prism.ui.widgets.PrismQrFrame
import tv.prism.ui.widgets.PrismAction

@Composable
internal fun PhoneConnectionDialog(
    url: String,
    phoneRemoteMode: Boolean,
    phoneRemoteEntry: String,
    phoneFormTitle: String,
    onDismiss: () -> Unit,
    onEndRemote: () -> Unit,
) {
    val c = LocalPrismColors.current
    /* Same reason as the queue panel: a dialog opens with no focus owner, so its
       only button would be dead on a remote until Back. */
    val phoneCloseFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = { onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; phoneCloseFocus.requestFocus() }
        val shape = RoundedCornerShape(18.dp)
        val qrPixels = with(LocalDensity.current) { 216.dp.roundToPx() }
        val link = if (phoneRemoteMode) "$url#$phoneRemoteEntry" else url
        val qr = remember(link, qrPixels) { qrCodeBitmap(link, qrPixels) }
        Row(Modifier.width(620.dp).clip(shape).background(c.panel)
            .border(1.dp, c.accent.copy(alpha = .18f), shape).padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            PrismQrFrame {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                    Image(qr.asImageBitmap(),
                        contentDescription = if (phoneRemoteMode) "手机遥控二维码" else "手机输入二维码",
                        modifier = Modifier.size(216.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (phoneRemoteMode) "手机遥控" else phoneFormTitle, color = c.foreground,
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(if (phoneRemoteMode) "同一网络扫码即可遥控，可多台手机同时使用\n应用保持前台 · 本次有效 30 分钟，随时可结束"
                    else "同一网络扫码代填，只接收一次提交\n有效 5 分钟，填写后回电视确认", color = c.muted, fontSize = 13.sp)
                if (phoneRemoteMode) Text("播放控制、搜索、音源管理与备份恢复", color = c.foreground, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrismAction(if (phoneRemoteMode) "隐藏二维码" else "返回电视输入", {
                        onDismiss()
                    }, Modifier.focusRequester(phoneCloseFocus), primary = true)
                    if (phoneRemoteMode) PrismAction("结束遥控", onEndRemote)
                }
            }
        }
    }
}
