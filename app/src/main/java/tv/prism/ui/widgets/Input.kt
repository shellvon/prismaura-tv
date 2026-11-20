package tv.prism.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.requestIfAttached

@Composable internal fun Input(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, secret: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, manualKeyboard: Boolean = false, onSubmit: (() -> Unit)? = null, downTarget: FocusAnchor? = null) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(!manualKeyboard) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val c = LocalPrismColors.current
    LaunchedEffect(value) {
        if (textFieldValue.text != value) textFieldValue = TextFieldValue(value, selection = TextRange(value.length))
    }
    LaunchedEffect(editing) { if (manualKeyboard && editing) { withFrameNanos { }; keyboardController?.show() } }
    /* A read-only field can still be handed the IME the moment it gains focus, which is exactly
       when the remote is travelling through with direction keys. Keep it closed until OK asks. */
    LaunchedEffect(focused, editing) {
        if (manualKeyboard && focused && !editing) { withFrameNanos { }; keyboardController?.hide() }
    }
    BasicTextField(value = textFieldValue, onValueChange = {
        val previousText = textFieldValue.text
        textFieldValue = it
        if (it.text != previousText) onChange(it.text)
    }, singleLine = true, readOnly = manualKeyboard && !editing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboardController?.hide()
            if (manualKeyboard) editing = false
            /* Where the next control lives depends on the page: an inline button sits to the
               right (导入/搜索), a stacked form continues below (保存/扫码输入). */
            if (onSubmit != null) onSubmit() else if (!focusManager.moveFocus(FocusDirection.Right)) focusManager.moveFocus(FocusDirection.Down)
        }),
        textStyle = TextStyle(color = c.foreground, fontSize = 16.sp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.onPreviewKeyEvent { event ->
            val down = event.type == KeyEventType.KeyDown
            if (editing && imeVisible) false
            else if (down && manualKeyboard && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                textFieldValue = textFieldValue.copy(selection = TextRange(textFieldValue.text.length), composition = null)
                editing = true
                keyboardController?.show()
                true
            }
            // 键盘收起后，方向键交回页面导航；键盘显示期间留给文字编辑。
            else if (down && event.key == Key.DirectionRight) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Right); true
            }
            else if (down && event.key == Key.DirectionLeft) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Left); true
            }
            /* BasicTextField eats Down to move its caret, which would trap the remote in the
               field while suggestions sit right below it. A single-line field has no caret line
               to move to, so Down always leaves — closing the IME first if it is still up. */
            else if (down && event.key == Key.DirectionDown) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                /* 页面点名了落点就照点名走，几何搜索只认正下方最近的控件。 */
                if (!downTarget.requestIfAttached()) focusManager.moveFocus(FocusDirection.Down)
                true
            }
            /* Up is eaten the same way (caret line movement), which would make the nav bar
               unreachable from the field. Single line, so there is nowhere to move to. */
            else if (down && event.key == Key.DirectionUp) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Up); true
            }
            else false
        }.onFocusChanged { focused = it.isFocused; if (!it.isFocused && manualKeyboard) editing = false }.border(1.dp, if (focused) c.accent else c.line, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp),
        decorationBox = { inner ->
            val longHint = hint.length > 22
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (longHint) 4.dp else 0.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    /* Masked fields show a fixed-length placeholder and the character count when
                       idle — a full dot grid of a long cookie reads as nothing; the real input
                       appears only while editing. */
                    val maskedIdle = secret && value.isNotEmpty() && !editing
                    if (maskedIdle) Text("••••••••  ${value.length} 字符", color = c.muted, fontSize = 15.sp, maxLines = 1)
                    else {
                        if (value.isEmpty()) Text(if (longHint) "请输入" else hint, color = c.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
                if (longHint) Text(hint, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        })
    }
