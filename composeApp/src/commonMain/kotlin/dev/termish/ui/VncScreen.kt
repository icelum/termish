package dev.termish.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

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
    /** 点击反馈：每次点击/长按递增，坐标（framebuffer）+ 版本驱动画布涟漪。 */
    var feedbackX by mutableFloatStateOf(0f)
    var feedbackY by mutableFloatStateOf(0f)
    var feedbackVersion by mutableStateOf(0L)
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
    // 点击反馈涟漪：版本变化时重播淡出动画
    val feedbackAnim = remember { Animatable(0f) }
    LaunchedEffect(state.feedbackVersion) {
        if (state.feedbackVersion > 0) {
            feedbackAnim.snapTo(1f)
            feedbackAnim.animateTo(0f, tween(360, easing = LinearEasing))
        }
    }
    LaunchedEffect(client, frame?.version) {
        val f = frame ?: return@LaunchedEffect
        if (f.version == drawVersion) return@LaunchedEffect
        drawVersion = f.version
        val bm = bitmap ?: VncBitmap(f.width, f.height).also { bitmap = it }
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

    // 视口换算：屏幕坐标 → framebuffer 坐标（fit 缩放 + 居中偏移 + 视口 offset）
    fun screenToFrame(sx: Float, sy: Float): Pair<Float, Float> {
        val f = frame ?: return 0f to 0f
        if (f.width == 0) return 0f to 0f
        val fit = if (canvasSize.width > 0) canvasSize.width.toFloat() / f.width else 1f
        val ds = fit * state.scale
        val baseX = (canvasSize.width - f.width * ds) / 2f
        val baseY = (canvasSize.height - f.height * ds) / 2f
        return ((sx - baseX) / ds - state.offsetX) to ((sy - baseY) / ds - state.offsetY)
    }

    /** 在屏幕位置点击：直接映射到手指触摸点（buttonMask bit0=左 bit2=右）。 */
    fun clickAt(sx: Float, sy: Float, mask: Int) {
        val f = frame ?: return
        val (fx, fy) = screenToFrame(sx, sy)
        val x = fx.roundToInt().coerceIn(0, f.width - 1)
        val y = fy.roundToInt().coerceIn(0, f.height - 1)
        // 记录最后交互位置：工具栏左/右键在没有触摸时作用于这里
        state.pointerX = fx
        state.pointerY = fy
        // 点击反馈：画布画一个淡出圆圈
        state.feedbackX = fx
        state.feedbackY = fy
        state.feedbackVersion++
        TermLog.d("vnc-ui") { "click mask=$mask at $x,$y client=$client" }
        client?.pointerEvent(mask, x, y)
        client?.pointerEvent(0, x, y)
    }

    /** 视口平移：屏幕 px 增量 → offset 增量（内容跟随手指；fit = 画布宽/帧宽）。 */
    fun panBy(dx: Float, dy: Float) {
        val f = frame ?: return
        if (f.width == 0) return
        val fit = if (canvasSize.width > 0) canvasSize.width.toFloat() / f.width else 1f
        val ds = fit * state.scale
        state.offsetX += dx / ds
        state.offsetY += dy / ds
        clampViewport(state, f.width, f.height, canvasSize)
    }

    /** 工具栏鼠标键：作用于最后交互位置（未交互过则画布中心）。 */
    fun toolbarClick(mask: Int) {
        val f = frame ?: return
        val x = state.pointerX.takeIf { it >= 0 } ?: (f.width / 2f)
        val y = state.pointerY.takeIf { it >= 0 } ?: (f.height / 2f)
        client?.pointerEvent(mask, x.roundToInt(), y.roundToInt())
        client?.pointerEvent(0, x.roundToInt(), y.roundToInt())
    }

    fun wheel(up: Boolean) {
        val f = frame ?: return
        val mask = if (up) 0x08 else 0x10
        val x = state.pointerX.takeIf { it >= 0 } ?: (f.width / 2f)
        val y = state.pointerY.takeIf { it >= 0 } ?: (f.height / 2f)
        client?.pointerEvent(mask, x.roundToInt(), y.roundToInt())
        client?.pointerEvent(0, x.roundToInt(), y.roundToInt())
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
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downTime = nowMillis()
                                var moved = false
                                var lastPos = down.position
                                var lastPinchDist: Float? = null
                                var lastPinchCentroid = Offset.Zero
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        if (ev.changes.all { !it.pressed }) break // up
                                        val pressed = ev.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            // 双指 = 捏拉缩放 + 平移（跟随式）
                                            moved = true
                                            val dist = pinchDistance(ev)
                                            val centroid = pinchCentroid(ev)
                                            if (lastPinchDist != null && lastPinchDist > 0f) {
                                                val zoom = dist / lastPinchDist
                                                state.scale = (state.scale * zoom).coerceIn(0.25f, 8f)
                                                val cd = centroid - lastPinchCentroid
                                                panBy(cd.x, cd.y)
                                            }
                                            lastPinchDist = dist
                                            lastPinchCentroid = centroid
                                            continue
                                        }
                                        lastPinchDist = null
                                        val change = pressed.firstOrNull() ?: continue
                                        val delta = change.position - lastPos
                                        lastPos = change.position
                                        // 用总位移判定（手指轻微抖动不误判点击）
                                        if ((change.position - down.position).getDistance() > tapSlopPx) moved = true
                                        if (moved) {
                                            // 单指拖动 = 拖动画布（内容跟随手指）
                                            panBy(delta.x, delta.y)
                                        }
                                    }
                                    // up：未移动 = 点击（长按右击，短按左击），作用于手指位置
                                    if (!moved) {
                                        val hold = nowMillis() - downTime
                                        clickAt(down.position.x, down.position.y, if (hold >= LONG_PRESS_MS) 4 else 1)
                                    }
                                } catch (_: kotlinx.coroutines.CancellationException) {
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
                    // 点击反馈涟漪：点击处淡出圆圈（位置跟随视口换算）
                    if (feedbackAnim.value > 0.01f && state.feedbackVersion > 0) {
                        val cx = baseX + ox + state.feedbackX * drawScale
                        val cy = baseY + oy + state.feedbackY * drawScale
                        val r = (18f + 26f * (1f - feedbackAnim.value)) * drawScale.coerceAtLeast(0.3f)
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f * feedbackAnim.value),
                            radius = r,
                            center = Offset(cx, cy),
                            style = Stroke(width = 3.dp.toPx()),
                        )
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
            // 两行各 8 键等宽（对齐终端 KeyToolbar 8+8）；滚轮用 ⇞⇟ 区分方向键 ↑↓
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                VncToolKey("←", state, Modifier.weight(1f)) { sendKey(KeySym.LEFT) }
                VncToolKey("↑", state, Modifier.weight(1f)) { sendKey(KeySym.UP) }
                VncToolKey("↓", state, Modifier.weight(1f)) { sendKey(KeySym.DOWN) }
                VncToolKey("→", state, Modifier.weight(1f)) { sendKey(KeySym.RIGHT) }
                VncToolKey(s.vnc.wheelUp, state, Modifier.weight(1f)) { wheel(true) }
                VncToolKey(s.vnc.wheelDown, state, Modifier.weight(1f)) { wheel(false) }
                VncToolKey(s.vnc.mouseLeft, state, Modifier.weight(1f)) { toolbarClick(1) }
                VncToolKey(s.vnc.mouseRight, state, Modifier.weight(1f)) { toolbarClick(4) }
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
                VncToolKey("ENT", state, Modifier.weight(1f)) { sendKey(KeySym.ENTER) }
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
