package com.iwalk.companion.ui.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.TransportState
import java.time.Duration
import java.time.Instant

/**
 * 设备页正文（2026-11-13 用户版重排）：分支 A（无会话）与分支 B（会话中）。
 * 参照九号平衡车 App：中央大电量环 + 预计里程卡 + 温度指标条；速度不作为主显示
 * （speed 仍照常解码供锁闸门判定使用）。
 *
 * 用户正式版（0.4.0）起不再显示调试信息：原始状态码 chips、帧数统计、设备与链路
 * 详情（UUID/GATT 树）、数据来源面板均已移除；这些信息仍可经分析页代码路径离线
 * 获取，UI 不再外显。
 */

// —— 逐字文案（测试引用同一批常量，避免两侧各抄一遍再漂移）——
const val TITLE_CONNECT = "连接你的平衡车"
const val SUBTITLE_SCAN =
    "仅扫描 Mini / Plus / M0… 白名单设备\n默认只读；锁车控制需手动开启并确认风险"
const val ACTION_SCAN = "扫描并连接"
const val ACTION_DEMO = "使用演示数据（不连接真车）"
const val VEHICLE_IMAGE_DESCRIPTION = "平衡车示意图"
const val VEHICLE_BADGE = "示意图 · 非实拍"
const val SAFETY_NOTE =
    "本应用只读取车辆状态（电量、预计里程、温度），不发送任何控制指令。可选的锁车/解锁为实验功能，需在设置中手动开启；开启后仅发送两条固定的锁车/解锁指令，且车辆仍在移动时会被阻止。"

// —— 分支 B 文案（§2.3）——
const val LABEL_BATTERY = "电量"
const val RANGE_ESTIMATE_NOTE = "车端估算 · 静止时 ±1 km 波动"
const val BADGE_DEMO = "演示数据"
const val BADGE_MISSING_CALIBRATION = "未标定"
const val BADGE_WAITING_FIRST_FRAME = "等待通知"
const val BADGE_STALE = "等待数据"
const val SUBTITLE_LINK_DOWN = "链路已断开 · 上次数据保留并置灰"
const val SUBTITLE_WAITING_FIRST_FRAME = "已连接，等待首条通知…"
const val SUBTITLE_STALE = "等待数据 · 不再刷新，数值未归零"
const val LINK_ISSUE_TITLE_ERROR = "连接异常"
const val LINK_ISSUE_TITLE_DISCONNECTED = "连接已断开"
const val SESSION_DEMO_PREFIX = "演示"
const val SESSION_REAL_PREFIX = "已连接"
const val ACTION_STOP_DEMO = "结束演示"
const val ACTION_DISCONNECT = "断开连接"
const val METRIC_AGE_LABEL = "更新"
const val ACTION_EXIT_SESSION = "退出会话"
const val DIALOG_STOP_TITLE = "断开当前会话？"
const val DIALOG_STOP_BODY =
    "本次会话的原始通知尚未自动保存。断开后界面将清空当前数据；如需留证，请先在「分析」页复制或导出。"
const val DIALOG_STAY = "留在会话"
const val DIALOG_STOP_CONFIRM = "断开"

/** 链路已断时同一个按钮变成「退出会话」，确认框文案随之改变：没有可断开的连接了。 */
const val DIALOG_EXIT_TITLE = "退出已断开的会话？"
const val DIALOG_EXIT_BODY =
    "链路已断开，不会再有新帧。退出后界面将清空本次会话数据；如需留证，请先在「分析」页复制或导出。"
const val DIALOG_EXIT_CONFIRM = "退出"

/** 3 秒无新快照即视为过期（§2.1，Dart `isStale(3 s)`）。 */
private val STALE_AFTER: Duration = Duration.ofSeconds(3)

@Composable
fun DeviceScreen(
    state: DeviceUiState,
    onStartDemo: () -> Unit,
    onStopSession: () -> Unit,
    onScan: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    lockPanel: (@Composable () -> Unit)? = null,
) {
    if (!state.active) {
        DisconnectedView(
            state = state,
            onStartDemo = onStartDemo,
            onScan = onScan,
        )
    } else {
        ActiveSessionView(
            state = state,
            onStopSession = onStopSession,
            lockPanel = lockPanel,
        )
    }
}

// —————————————————————————— 分支 A ——————————————————————————

@Composable
private fun DisconnectedView(
    state: DeviceUiState,
    onStartDemo: () -> Unit,
    onScan: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        VehicleImage(height = 224.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = TITLE_CONNECT,
            fontSize = 24.sp,
            fontWeight = FontWeight.W600,
            color = IwColors.Text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = SUBTITLE_SCAN,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = IwColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        // 扫描屏属于工单 03：入口可达但保持禁用，不做任何链路动作
        Button(
            onClick = { onScan?.invoke() },
            enabled = onScan != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(ACTION_SCAN, fontSize = 14.sp, color = IwColors.OnPrimary)
        }
        Spacer(Modifier.height(8.dp))
        // Dart `_startingDemo` 期间用 18×18 忙碌指示替换标签并禁用
        if (state.startingDemo) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(IwColors.Primary),
            )
        } else {
            TextButton(onClick = onStartDemo) {
                Text(ACTION_DEMO, fontSize = 14.sp, color = IwColors.Primary)
            }
        }
        // 演示启动失败后会话立即结束，提示只能落在分支 A（Dart 走 SnackBar）
        state.connectionMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = IwColors.Error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(56.dp))
        HorizontalDivider(color = IwColors.Outline, thickness = 1.dp)
        Spacer(Modifier.height(16.dp))
        SafetyNote()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 沿用 Flutter 资源的车辆示意图，保留「非实拍」标注与无障碍描述。
 */
@Composable
private fun VehicleImage(height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = VEHICLE_IMAGE_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.iwalk.companion.R.drawable.hoverboard),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
        )
        Text(
            text = VEHICLE_BADGE,
            fontSize = 10.5.sp,
            color = IwColors.TextMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(IwColors.Surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SafetyNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IwColors.Warning.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("⛨", color = IwColors.Warning, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = SAFETY_NOTE,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = IwColors.TextMuted,
        )
    }
}

// —————————————————————————— 分支 B ——————————————————————————

@Composable
private fun ActiveSessionView(
    state: DeviceUiState,
    onStopSession: () -> Unit,
    lockPanel: (@Composable () -> Unit)?,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); now = Instant.now() }
    }
    var confirmStop by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        if (state.transportState != TransportState.connected) {
            LinkIssueBanner(state)
            Spacer(Modifier.height(12.dp))
        }
        BatteryHero(state, now)
        RangeBanner(state, now)
        Spacer(Modifier.height(18.dp))
        MetricStrip(state, now)
        Spacer(Modifier.height(18.dp))
        SessionRow(
            state = state,
            now = now,
            onStopRequest = {
                // Dart `_stopSession`：真车有未导出帧时先确认，演示直接结束
                if (!state.isDemo && state.snapshot.framesDecoded > 0) {
                    confirmStop = true
                } else {
                    onStopSession()
                }
            },
        )
        Spacer(Modifier.height(18.dp))
        lockPanel?.let { it(); Spacer(Modifier.height(18.dp)) }
        SafetyNote()
    }

    if (confirmStop) {
        StopConfirmDialog(
            connected = state.transportState == TransportState.connected,
            onDismiss = { confirmStop = false },
            onConfirm = {
                confirmStop = false
                onStopSession()
            },
        )
    }
}

/**
 * B5 确认框：断开后当前数据清空，导出走工单 04（Dart `断开当前会话？`）。
 *
 * [connected] 为 false 时链路已经断了，按钮是「退出会话」——此时再说「断开」会让人
 * 以为还有一条连接在，所以标题/正文/确认键都换成退出语义。
 */
@Composable
private fun StopConfirmDialog(connected: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IwColors.Surface,
        titleContentColor = IwColors.Text,
        textContentColor = IwColors.TextMuted,
        title = {
            Text(
                if (connected) DIALOG_STOP_TITLE else DIALOG_EXIT_TITLE,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
            )
        },
        text = {
            Text(
                if (connected) DIALOG_STOP_BODY else DIALOG_EXIT_BODY,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(DIALOG_STAY, fontSize = 13.sp, color = IwColors.TextMuted)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    if (connected) DIALOG_STOP_CONFIRM else DIALOG_EXIT_CONFIRM,
                    fontSize = 13.sp,
                    color = IwColors.OnPrimary,
                )
            }
        },
    )
}

/** B0：链路异常横幅，只在 `state != connected` 时出现（§2.3 B0）。 */
@Composable
private fun LinkIssueBanner(state: DeviceUiState) {
    val isError = state.transportState == TransportState.error
    val tint = if (isError) IwColors.Error else IwColors.Warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 本 BOM 不透传 material-icons：用 19dp 圆点占位 Dart 的 error_outline / link_off
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = if (isError) LINK_ISSUE_TITLE_ERROR else LINK_ISSUE_TITLE_DISCONNECTED,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                color = tint,
            )
            state.connectionMessage?.let { message ->
                Spacer(Modifier.height(3.dp))
                Text(text = message, fontSize = 11.5.sp, color = IwColors.TextMuted)
            }
        }
    }
}

/**
 * B1：中央大电量环（参照九号 App 表盘布局）+ 状态徽标 + 副标题。
 * 电量是唯一的主读数；速度大字已按用户要求移除（speed 仍照常解码，供锁闸门判定）。
 */
@Composable
private fun BatteryHero(state: DeviceUiState, now: Instant) {
    val snapshot = state.snapshot
    val battery = snapshot[TelemetryField.batteryPercent]
    val connected = state.transportState == TransportState.connected
    val stale = snapshot.framesDecoded > 0 && snapshot.isStale(STALE_AFTER, now)

    val badge: Pair<String, Color>? = when {
        state.isDemo -> BADGE_DEMO to IwColors.Demo
        snapshot.framesDecoded == 0 -> BADGE_WAITING_FIRST_FRAME to IwColors.Warning
        stale -> BADGE_STALE to IwColors.Warning
        else -> null
    }
    val faded = battery != null && (!connected || stale)
    val ringColor = when {
        battery == null -> IwColors.TextMuted
        battery < 20 -> IwColors.Error
        battery < 50 -> IwColors.Warning
        else -> IwColors.Primary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Text(LABEL_BATTERY, fontSize = 12.sp, color = IwColors.TextMuted)
            Spacer(Modifier.weight(1f))
            badge?.let { (text, color) ->
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .size(232.dp)
                .alpha(if (faded) 0.42f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            BatteryRing(battery, ringColor, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatFieldValue(battery, digitsOf(TelemetryField.batteryPercent)),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.W300,
                        color = IwColors.Text,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "%",
                        fontSize = 20.sp,
                        color = IwColors.TextMuted,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = batterySubtitle(state, battery != null, connected, stale),
            fontSize = 12.sp,
            color = if (stale || !connected) IwColors.Warning else IwColors.TextMuted,
        )
    }
}

/** 环形电量：底环 + 进度环（电量 <20% 红、<50% 橙、其余主色）。 */
@Composable
private fun BatteryRing(battery: Double?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        val topLeft = Offset(stroke.width / 2, stroke.width / 2)
        drawArc(
            color = IwColors.SurfaceHigh,
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke,
        )
        val frac = (battery?.coerceIn(0.0, 100.0) ?: 0.0) / 100.0
        if (battery != null && frac > 0.0) {
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = (360.0 * frac).toFloat(), useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke,
            )
        }
    }
}

private fun batterySubtitle(state: DeviceUiState, hasBattery: Boolean, connected: Boolean, stale: Boolean): String {
    val snapshot = state.snapshot
    return when {
        !connected -> SUBTITLE_LINK_DOWN
        snapshot.framesDecoded == 0 -> SUBTITLE_WAITING_FIRST_FRAME
        stale -> SUBTITLE_STALE
        hasBattery -> "更新于 ${formatAge(snapshot.values[TelemetryField.batteryPercent]?.at)}"
        else -> "等待电量数据…"
    }
}

/**
 * B1.5：预计里程卡（E71：0x2a off2 = 车端预计剩余行驶里程 km）。
 * 未标定时显示 —，不用猜测值。
 */
@Composable
private fun RangeBanner(state: DeviceUiState, now: Instant) {
    val snapshot = state.snapshot
    val range = snapshot[TelemetryField.estimatedRange]
    val connected = state.transportState == TransportState.connected
    val stale = snapshot.framesDecoded > 0 && snapshot.isStale(STALE_AFTER, now)
    val live = connected && !stale
    val faded = range != null && !live

    Surface(
        color = IwColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                Text(labelOf(TelemetryField.estimatedRange), fontSize = 11.sp, color = IwColors.TextMuted)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.alpha(if (faded) 0.42f else 1f)) {
                    Text(
                        text = formatFieldValue(range, digitsOf(TelemetryField.estimatedRange)),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.W600,
                        color = IwColors.Text,
                    )
                    unitOf(TelemetryField.estimatedRange).takeIf { range != null }?.let {
                        Spacer(Modifier.width(4.dp))
                        Text(it, fontSize = 12.sp, color = IwColors.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(RANGE_ESTIMATE_NOTE, fontSize = 10.5.sp, color = IwColors.TextMuted)
            }
        }
    }
}

/**
 * B4：三格指标条（温度 / 里程或电压 / 更新时间），§2.3 B4。
 * 电量已升为主环（B1）、预计里程已有独立卡（B1.5），这两格不再重复出现。
 */
@Composable
private fun MetricStrip(state: DeviceUiState, now: Instant) {
    val snapshot = state.snapshot
    val connected = state.transportState == TransportState.connected
    val stale = snapshot.framesDecoded > 0 && snapshot.isStale(STALE_AFTER, now)
    val live = connected && !stale

    val temperature = snapshot[TelemetryField.temperature]

    Surface(
        color = IwColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(104.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricCell(
                label = labelOf(TelemetryField.temperature),
                value = formatFieldValue(temperature, digitsOf(TelemetryField.temperature)),
                unit = unitOf(TelemetryField.temperature).takeIf { temperature != null },
                valueColor = IwColors.Text,
                dim = !live,
                onClick = null,
                modifier = Modifier.weight(1f),
            )
            MetricDivider()
            val third = thirdColumnField(state)
            if (third != null) {
                MetricCell(
                    label = labelOf(third),
                    value = formatFieldValue(snapshot[third], digitsOf(third)),
                    unit = unitOf(third).takeIf { snapshot[third] != null },
                    valueColor = IwColors.Text,
                    dim = !live,
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
            } else {
                MetricCell(
                    label = METRIC_AGE_LABEL,
                    value = metricAgeCell(
                        at = snapshot.updatedAt.takeIf { snapshot.framesDecoded > 0 },
                        frames = snapshot.framesDecoded,
                        now = now,
                    ),
                    unit = null,
                    valueColor = IwColors.Text,
                    dim = !live,
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 第三格优先级：里程 → 电压 → 更新时间（§2.3 B4）。 */
private fun thirdColumnField(state: DeviceUiState): TelemetryField? = when {
    state.calibration.hasField(TelemetryField.odometer) -> TelemetryField.odometer
    state.calibration.hasField(TelemetryField.batteryVoltage) -> TelemetryField.batteryVoltage
    else -> null
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(56.dp)
            .background(IwColors.Outline),
    )
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    unit: String?,
    valueColor: Color,
    dim: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, fontSize = 11.sp, color = IwColors.TextMuted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.alpha(if (dim) 0.42f else 1f)) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.W600, color = valueColor)
            unit?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, fontSize = 11.sp, color = IwColors.TextMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** B5：会话行——来源与时长、结束入口（§2.3 B5；帧数等统计不外显）。 */
@Composable
private fun SessionRow(state: DeviceUiState, now: Instant, onStopRequest: () -> Unit) {
    HorizontalDivider(color = IwColors.Outline, thickness = 1.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val elapsed = state.startedAtMillis?.let { (now.toEpochMilli() - it) / 1000 } ?: 0
            val deviceName = if (state.isDemo) null else state.linkFacts?.advertisementName
            val prefix = if (state.isDemo) SESSION_DEMO_PREFIX else SESSION_REAL_PREFIX
            Text(
                text = if (deviceName != null) "$deviceName · ${formatElapsed(elapsed)}" else "$prefix ${formatElapsed(elapsed)}",
                fontSize = 13.sp,
                color = IwColors.Text,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (state.isDemo) "演示数据，不代表真实车辆" else "只读连接 · 电量与预计里程实时更新",
                fontSize = 11.5.sp,
                color = IwColors.TextMuted,
            )
        }
        // 演示：结束演示；真车：已连接「断开连接」、断链后「退出会话」（§2.3 B5）
        val label = when {
            state.isDemo -> ACTION_STOP_DEMO
            state.transportState == TransportState.connected -> ACTION_DISCONNECT
            else -> ACTION_EXIT_SESSION
        }
        TextButton(onClick = onStopRequest) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (state.isDemo) IwColors.TextMuted else IwColors.Error,
            )
        }
    }
}
