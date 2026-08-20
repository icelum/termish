package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import dev.termish.data.VncHost
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.TermLog
import dev.termish.vnc.RfbClient
import dev.termish.vnc.VncBitmap
import dev.termish.vnc.VncStatus
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** VNC 会话 UI 状态（随 tab 存活：断线/缩放视口不因切 tab 重置）。 */
class VncUiState {
    var reconnecting by mutableStateOf(false)
    var disconnected by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    /** 视口：缩放（1=适应宽度）与平移（framebuffer 坐标）。 */
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    /** 指针当前位置（framebuffer 坐标），工具栏按钮作用点。 */
    var pointerX by mutableFloatStateOf(-1f)
    var pointerY by mutableFloatStateOf(-1f)
    var keyboardVisible by mutableStateOf(false)
    /** 粘滞修饰键：按下后修饰下一键。 */
    var stickyCtrl by mutableStateOf(false)
    var stickyAlt by mutableStateOf(false)
    var stickySuper by mutableStateOf(false)
}

/** RFB keysym 常量（常用键）。 */
private object KeySym {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val ENTER = 0xff0d
    const val ESC = 0xff1b
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val CTRL = 0xffe3
    const val ALT = 0xffe9
    const val SUPER = 0xffeb
}

/**
 * VNC 远程桌面会话页（作为终端页的一个 tab 渲染，见 [SessionTab.Vnc]）。
 *
 * 手势：单击=左键 · 双击=双击 · 长按=右键 · 单指拖动=移动指针 ·
 * 双指=捏拉缩放/平移；工具栏提供鼠标三键/滚轮/修饰键/软键盘。
 */
@Composable
fun VncContent(
    host: VncHost,
    client: RfbClient?,
    state: VncUiState,
    theme: TerminalTheme,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val s = LocalAppStrings.current
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // 帧渲染：collectAsState 驱动重组（StateFlow.value 不被 Compose 跟踪）
    val frame = client?.frame?.collectAsState()?.value
    var drawVersion by remember(client) { mutableStateOf(0L) }
    var bitmap by remember(client) { mutableStateOf<VncBitmap?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(client, frame?.version) {
        val f = frame ?: return@LaunchedEffect
        if (f.version == drawVersion) return@LaunchedEffect
        drawVersion = f.version
        val bm = bitmap ?: VncBitmap(f.width, f.height).also {
            bitmap = it
            // 首帧：箭头初始置于画面中心
            if (state.pointerX < 0) {
                state.pointerX = f.width / 2f
                state.pointerY = f.height / 2f
            }
        }
        bm.update(f.pixels)
    }
    // 远端剪贴板 → 本机
    LaunchedEffect(client) {
        client?.clipboard?.collect { c ->
            if (c != null) {
                clipboard.setText(AnnotatedString(c.text))
            }
        }
    }
    // 连接状态观察：断线时置 banner（RfbClient 状态非 Compose state，轮询足够）
    VncStatusWatcher(client, state)

    // 视口换算：屏幕坐标 → framebuffer 坐标
    fun toFrameX(x: Float): Float = x / state.scale - state.offsetX
    fun toFrameY(y: Float): Float = y / state.scale - state.offsetY
    fun sendPointerAt(buttonMask: Int, fx: Float, fy: Float) {
        state.pointerX = fx
        state.pointerY = fy
        client?.pointerEvent(buttonMask, fx.roundToInt().coerceIn(0, 32767), fy.roundToInt().coerceIn(0, 32767))
    }

    fun clickButton(mask: Int) {
        val x = state.pointerX.takeIf { it >= 0 } ?: (frame?.width?.div(2f)) ?: return
        val y = state.pointerY.takeIf { it >= 0 } ?: (frame?.height?.div(2f)) ?: return
        client?.pointerEvent(mask, x.roundToInt(), y.roundToInt())
        client?.pointerEvent(0, x.roundToInt(), y.roundToInt())
    }

    fun wheel(up: Boolean) {
        val mask = if (up) 0x08 else 0x10
        val x = state.pointerX.takeIf { it >= 0 } ?: (frame?.width?.div(2f)) ?: return
        val y = state.pointerY.takeIf { it >= 0 } ?: (frame?.height?.div(2f)) ?: return
        client?.pointerEvent(mask, x.roundToInt(), y.roundToInt())
        client?.pointerEvent(0, x.roundToInt(), y.roundToInt())
    }

    // 虚拟鼠标点击：作用于箭头位置（手指触点无关）
    fun leftClickAtPointer() {
        // 箭头未初始化（首帧前）时先置中
        if (state.pointerX < 0) {
            state.pointerX = (frame?.width ?: 640) / 2f
            state.pointerY = (frame?.height ?: 480) / 2f
        }
        val x = state.pointerX.roundToInt().coerceAtLeast(0)
        val y = state.pointerY.roundToInt().coerceAtLeast(0)
        client?.pointerEvent(1, x, y)
        client?.pointerEvent(0, x, y)
    }

    fun rightClickAtPointer() {
        val x = state.pointerX.roundToInt().coerceAtLeast(0)
        val y = state.pointerY.roundToInt().coerceAtLeast(0)
        client?.pointerEvent(4, x, y)
        client?.pointerEvent(0, x, y)
    }

    fun doubleClickAtPointer() {
        leftClickAtPointer()
        leftClickAtPointer()
    }

    // 键盘：隐藏输入框接收软键盘/硬件键盘
    var inputValue by remember(client) { mutableStateOf(TextFieldValue("", TextRange.Zero)) }
    fun sendChar(ch: Char) {
        val c = client ?: return
        if (state.stickyCtrl) c.keyEvent(true, KeySym.CTRL)
        if (state.stickyAlt) c.keyEvent(true, KeySym.ALT)
        if (state.stickySuper) c.keyEvent(true, KeySym.SUPER)
        c.keyEvent(true, ch.code)
        c.keyEvent(false, ch.code)
        if (state.stickyCtrl) c.keyEvent(false, KeySym.CTRL)
        if (state.stickyAlt) c.keyEvent(false, KeySym.ALT)
        if (state.stickySuper) c.keyEvent(false, KeySym.SUPER)
        state.stickyCtrl = false
        state.stickyAlt = false
        state.stickySuper = false
    }
    fun sendKey(keysym: Int) {
        val c = client ?: return
        if (state.stickyCtrl && keysym != KeySym.CTRL) c.keyEvent(true, KeySym.CTRL)
        if (state.stickyAlt && keysym != KeySym.ALT) c.keyEvent(true, KeySym.ALT)
        if (state.stickySuper && keysym != KeySym.SUPER) c.keyEvent(true, KeySym.SUPER)
        c.keyEvent(true, keysym)
        c.keyEvent(false, keysym)
        if (state.stickyCtrl && keysym != KeySym.CTRL) c.keyEvent(false, KeySym.CTRL)
        if (state.stickyAlt && keysym != KeySym.ALT) c.keyEvent(false, KeySym.ALT)
        if (state.stickySuper && keysym != KeySym.SUPER) c.keyEvent(false, KeySym.SUPER)
        state.stickyCtrl = false
        state.stickyAlt = false
        state.stickySuper = false
    }

    // 隐藏输入框：软键盘接入口（IME committed 文本 → keysym）
    var inputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    fun toggleKeyboard() {
        state.keyboardVisible = !state.keyboardVisible
        if (state.keyboardVisible) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        // ---- 画布区 ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it },
        ) {
            val bm = bitmap
            if (bm != null && frame != null) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(frame.width, frame.height, client) {
                            // 虚拟鼠标模式（VNC 客户端标准手感）：手指当触控板——
                            // 拖动只移动屏幕上的鼠标箭头（相对位移，不发送事件）；
                            // 点按在箭头位置触发（不跳到手指位置）
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                var last = down.position
                                var isPointerEvent = false
                                var sentDown = false
                                var lastPinchDist: Float? = null
                                var lastPinchCentroid = Offset.Zero
                                val downTime = nowMillis()
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        if (ev.changes.all { !it.pressed }) break // up
                                        val change = ev.changes.firstOrNull { it.pressed } ?: continue
                                        val delta = change.position - last
                                        last = change.position
                                        if (delta.getDistance() > tapSlopPx) moved = true
                                        val pressedCount = ev.changes.count { it.pressed }
                                        if (pressedCount >= 2) {
                                            // 双指 = 捏拉缩放 + 双指平移（原位处理：
                                            // 交给 detectTransformGestures 会因第二指
                                            // 中途加入而识别不到，实测捏不动）
                                            if (isPointerEvent) {
                                                client?.pointerEvent(0, state.pointerX.roundToInt(), state.pointerY.roundToInt())
                                                isPointerEvent = false
                                            }
                                            moved = true
                                            if (lastPinchDist == null) {
                                                lastPinchDist = pinchDistance(ev)
                                                lastPinchCentroid = pinchCentroid(ev)
                                            } else {
                                                val dist = pinchDistance(ev)
                                                val centroid = pinchCentroid(ev)
                                                val zoom = if (lastPinchDist > 0f) dist / lastPinchDist else 1f
                                                state.scale = (state.scale * zoom).coerceIn(0.25f, 8f)
                                                // 双指平移（centroid 移动）
                                                val cd = centroid - lastPinchCentroid
                                                state.offsetX -= cd.x / state.scale
                                                state.offsetY -= cd.y / state.scale
                                                clampViewport(state, frame.width, frame.height, canvasSize)
                                                lastPinchDist = dist
                                                lastPinchCentroid = centroid
                                            }
                                            continue
                                        }
                                        lastPinchDist = null
                                        if (moved) {
                                            // 长按后拖动 = 按住左键拖（拖选/拖拽）
                                            if (!isPointerEvent && nowMillis() - downTime > LONG_PRESS_MS) {
                                                isPointerEvent = true
                                                sentDown = true
                                                client?.pointerEvent(1, state.pointerX.roundToInt(), state.pointerY.roundToInt())
                                            }
                                            if (!isPointerEvent) {
                                                // 触控板模式：相对移动虚拟箭头（屏幕 px →
                                                // framebuffer px；fit = 画布宽/帧宽）
                                                val fit = if (canvasSize.width > 0) canvasSize.width.toFloat() / frame.width else 1f
                                                val speed = if (delta.getDistance() > SLOW_SLOP_PX) 1.6f else 1.0f
                                                state.pointerX = (state.pointerX + delta.x / (fit * state.scale) * speed)
                                                    .coerceIn(0f, (frame.width - 1).toFloat())
                                                state.pointerY = (state.pointerY + delta.y / (fit * state.scale) * speed)
                                                    .coerceIn(0f, (frame.height - 1).toFloat())
                                                // 箭头移动不发事件：位置随下一次点击/拖拽发送
                                            } else {
                                                // 按住拖：每帧更新位置+按住状态
                                                client?.pointerEvent(1, state.pointerX.roundToInt(), state.pointerY.roundToInt())
                                            }
                                        }
                                    }
                                    // up：区分点击/拖动
                                    if (!moved) {
                                        val hold = nowMillis() - downTime
                                        if (hold >= LONG_PRESS_MS) {
                                            rightClickAtPointer()
                                        } else {
                                            leftClickAtPointer()
                                        }
                                    } else if (isPointerEvent && sentDown) {
                                        client?.pointerEvent(0, state.pointerX.roundToInt(), state.pointerY.roundToInt())
                                    }
                                } catch (_: kotlinx.coroutines.CancellationException) {
                                    if (isPointerEvent && sentDown) {
                                        client?.pointerEvent(0, state.pointerX.roundToInt(), state.pointerY.roundToInt())
                                    }
                                }
                            }
                        },
                ) {
                    val w = frame.width.toFloat()
                    val h = frame.height.toFloat()
                    // fitScale：等比适应画布（初始 1x 视图）
                    val fit = if (size.width > 0 && w > 0) size.width / w else 1f
                    val drawScale = fit * state.scale
                    val dw = w * drawScale
                    val dh = h * drawScale
                    val ox = state.offsetX * drawScale
                    val oy = state.offsetY * drawScale
                    // 画布居中 + 偏移；小画布（比屏幕小）放大到宽度
                    val baseX = (size.width - dw) / 2f
                    val baseY = (size.height - dh) / 2f
                    drawImage(
                        image = bm.image,
                        dstOffset = IntOffset((baseX + ox).roundToInt(), (baseY + oy).roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    // 指针光标
                    if (state.pointerX >= 0) {
                        val px = baseX + ox + state.pointerX * drawScale
                        val py = baseY + oy + state.pointerY * drawScale
                        drawCircle(Color.White, radius = 7f, center = Offset(px, py), style = Stroke(width = 2.5f))
                        drawCircle(Color.Black, radius = 4.5f, center = Offset(px, py))
                    }
                }
            } else {
                // 无帧：连接中占位
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (state.disconnected) s.vnc.disconnected else s.vnc.connecting,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.disconnected) {
                        Spacer(Modifier.height(Spacing.Md))
                        Button(onClick = onReconnect) { Text(s.vnc.reconnect) }
                    }
                }
            }

            // 只读 badge
            if (host.viewOnly) {
                Text(
                    s.vnc.viewOnlyBadge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.Sm)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = Spacing.Sm, vertical = Spacing.Xs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ---- 工具栏 ----
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF18181E))
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(Spacing.Xs))
            // 两行各 8/9 键等宽（对齐终端 KeyToolbar 密度）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                VncToolKey("←", state, Modifier.weight(1f)) { sendKey(KeySym.LEFT) }
                VncToolKey("↑", state, Modifier.weight(1f)) { sendKey(KeySym.UP) }
                VncToolKey("↓", state, Modifier.weight(1f)) { sendKey(KeySym.DOWN) }
                VncToolKey("→", state, Modifier.weight(1f)) { sendKey(KeySym.RIGHT) }
                VncToolKey(s.vnc.wheelUp, state, Modifier.weight(1f)) { wheel(true) }
                VncToolKey(s.vnc.mouseLeft, state, Modifier.weight(1f)) { leftClickAtPointer() }
                VncToolKey(s.vnc.mouseMiddle, state, Modifier.weight(1f)) { clickButton(2) }
                VncToolKey(s.vnc.mouseRight, state, Modifier.weight(1f)) { rightClickAtPointer() }
            }
            Spacer(Modifier.height(Spacing.Xs))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                VncToolKey("CTRL", state, Modifier.weight(1f), active = state.stickyCtrl) { state.stickyCtrl = !state.stickyCtrl }
                VncToolKey("ALT", state, Modifier.weight(1f), active = state.stickyAlt) { state.stickyAlt = !state.stickyAlt }
                VncToolKey("WIN", state, Modifier.weight(1f), active = state.stickySuper) { state.stickySuper = !state.stickySuper }
                VncToolKey("ESC", state, Modifier.weight(1f)) { sendKey(KeySym.ESC) }
                VncToolKey("TAB", state, Modifier.weight(1f)) { sendKey(KeySym.TAB) }
                VncToolKey("⌫", state, Modifier.weight(1f)) { sendKey(KeySym.BACKSPACE) }
                VncToolKey(s.vnc.wheelDown, state, Modifier.weight(1f)) { wheel(false) }
                VncToolKey("ENT", state, Modifier.weight(1.2f)) { sendKey(KeySym.ENTER) }
                VncToolKey("⌨", state, Modifier.weight(1f)) { toggleKeyboard() }
            }
            Spacer(Modifier.height(Spacing.Xs))
        }
    }

    // 隐藏输入框本体（不可见；对齐终端页：IME committed 文本才发）
    Box(Modifier.size(1.dp).alpha(0f)) {
        BasicTextField(
            value = inputValue,
            onValueChange = { new ->
                // IME 组合中的文本不发送（拼音等）：只发 committed diff
                if (new.composition != null) return@BasicTextField
                val old = inputValue.text
                val diff = if (new.text.length >= old.length) new.text.substring(old.length) else ""
                if (diff.isNotEmpty()) diff.forEach { sendChar(it) }
                inputValue = TextFieldValue("", TextRange.Zero)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
            ),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(inputFocusRequester)
                .onFocusChanged { inputFocused = it.isFocused }
                .onPreviewKeyEvent { ev: KeyEvent ->
                    if (ev.type == KeyEventType.KeyDown) {
                        when (ev.key) {
                            Key.Enter -> { sendKey(KeySym.ENTER); true }
                            Key.Backspace -> { sendKey(KeySym.BACKSPACE); true }
                            Key.Tab -> { sendKey(KeySym.TAB); true }
                            Key.Escape -> { sendKey(KeySym.ESC); true }
                            else -> false
                        }
                    } else false
                },
        )
    }
}

/** 工具栏粘滞/普通键：风格对齐 KeyToolbar（暗底、等宽、labelSmall）。 */
@Composable
private fun VncToolKey(
    label: String,
    state: VncUiState,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (active) Color(0xFF101014) else Color.White.copy(alpha = 0.85f)
    val bg = if (active) Color.White else Color.Transparent
    Box(
        modifier
            .height(Sizes.KeyButton)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .clickable {
                if (!state.disconnected) onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** 虚拟鼠标手势参数。 */
private const val LONG_PRESS_MS = 500L
/** 判定为移动的触摸 slop（px）。 */
private const val TAP_SLOP_PX = 24f
/** 快速滑动时的指针加速阈值与倍率（触控板手感）。 */
private const val SLOW_SLOP_PX = 18f
private val tapSlopPx get() = TAP_SLOP_PX

/** 双指间距。 */
private fun pinchDistance(ev: androidx.compose.ui.input.pointer.PointerEvent): Float {
    val ps = ev.changes.filter { it.pressed }
    if (ps.size < 2) return 0f
    return (ps[0].position - ps[1].position).getDistance()
}

/** 双指质心。 */
private fun pinchCentroid(ev: androidx.compose.ui.input.pointer.PointerEvent): Offset {
    val ps = ev.changes.filter { it.pressed }
    if (ps.isEmpty()) return Offset.Zero
    return Offset(
        ps.map { it.position.x }.average().toFloat(),
        ps.map { it.position.y }.average().toFloat(),
    )
}
private fun nowMillis(): Long =
    kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

/** 视口钳制：平移不把画面拖出屏幕。 */
private fun clampViewport(state: VncUiState, frameW: Int, frameH: Int, canvas: IntSize) {
    // offsetX/offsetY 为 framebuffer 坐标偏移；允许范围按 fit 后尺寸推算
    if (canvas.width == 0) return
    val fit = canvas.width.toFloat() / frameW
    val drawW = frameW * fit * state.scale
    val drawH = frameH * fit * state.scale
    val maxX = if (drawW > canvas.width) drawW / 2f else 0f
    val maxY = if (drawH > canvas.height) drawH / 2f else 0f
    val offXpx = (state.offsetX * fit * state.scale).coerceIn(-maxX, maxX)
    state.offsetX = offXpx / (fit * state.scale)
    val offYpx = (state.offsetY * fit * state.scale).coerceIn(-maxY, maxY)
    state.offsetY = offYpx / (fit * state.scale)
}

/** 连接状态观察：置 UI 断线标记（banner/重连入口）。 */
@Composable
fun VncStatusWatcher(client: RfbClient?, state: VncUiState) {
    LaunchedEffect(client) {
        if (client == null) return@LaunchedEffect
        // 连接建立后轮询状态（RfbClient 状态非 Compose state，简单轮询足够）
        while (true) {
            kotlinx.coroutines.delay(500)
            if (client.status == VncStatus.ERROR || client.status == VncStatus.CLOSED) {
                if (client.status == VncStatus.ERROR) {
                    state.disconnected = true
                    state.loadError = client.errorMessage
                    TermLog.w("vnc") { "session error: ${client.errorMessage}" }
                }
                return@LaunchedEffect
            }
        }
    }
}
