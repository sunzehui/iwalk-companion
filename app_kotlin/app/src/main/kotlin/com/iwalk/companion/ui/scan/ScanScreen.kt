package com.iwalk.companion.ui.scan

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ble.BlePermissions
import com.iwalk.companion.ble.ScanController
import com.iwalk.companion.ble.ScanState
import com.iwalk.companion.ble.ScannedDevice
import com.iwalk.companion.ble.maskSerial
import com.iwalk.companion.ble.shortUuid
import com.iwalk.companion.ui.IwColors
import com.iwalk.companion.ui.device.DeviceSessionController
import kotlinx.coroutines.launch

// —— 逐字文案（测试/验收共用同一批常量）——
const val SCAN_TITLE = "选择设备"
const val SCAN_STOP = "停止扫描"
const val SCAN_RESTART = "重新扫描"
const val SCAN_FILTER = "仅白名单"
const val SCAN_STATUS_SCANNING = "正在扫描白名单设备…"
const val SCAN_STATUS_EMPTY_WHITELIST = "没找到 iWalk 设备"
const val SCAN_STATUS_EMPTY_ALL = "附近没有可连接的设备"
const val SCAN_STATUS_READY = "准备扫描"
const val SCAN_CONNECTING = "正在连接，并读取服务树；不会向车辆写入任何控制命令…"
const val SCAN_FOOTER =
    "扫描与连接不改变车辆状态；G1 用两条固定查询读取遥测。MTU 固定 23（单包 20B），长帧需重组。"
const val SCAN_TECH = "技术信息"
const val SCAN_TECH_HIDE = "收起技术信息"
const val SCAN_WHITELIST_TAG = "白名单"
const val SCAN_CONNECT = "连接"
const val SCAN_EMPTY_TITLE_SCANNING = "正在寻找车辆…"
const val SCAN_EMPTY_TITLE_IDLE = "没找到 iWalk 设备"
const val SCAN_EMPTY_BODY = """· 确认车辆已开机，且手机在 10 米内
· 确认蓝牙与“附近的设备”权限已开启
· 名字可能只出现在扫描响应里
· 必要时关闭“仅白名单”观察全部广播"""
const val SCAN_ALL_BROADCASTS_NOTE = "当前显示全部 BLE 广播；连接前请核对设备名。"
const val SCAN_WHITELIST_HELP =
    "官方 App 用 33 个设备名前缀筛选车辆，例如 Mini、Plus、M0、X1、M6。关闭后能看到附近所有 BLE 广播，但仍只进行只读扫描。"
const val SCAN_WHITELIST_HELP_SUB = "名字来自 SCAN RESPONSE；部分车辆只在扫描响应中携带名称。"

/**
 * 扫描页（notes/ui-device-scan.md §3）：进入即扫，15 s 超时；只做发现与连接，
 * 连接成功后由宿主关掉此页，设备页直接进入会话分支。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    controller: DeviceSessionController,
    onClose: () -> Unit,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanController = remember { ScanController(context.applicationContext, scope) }
    val state by scanController.state.collectAsState()

    var connectingAddress by remember { mutableStateOf<String?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var showWhitelistHelp by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 无论授予与否都交给控制器：未授予时它会写入与 Dart 一致的提示。
        scanController.start()
    }

    DisposableEffect(scanController) {
        onDispose { scanController.close() }
    }

    BackHandler {
        if (connectingAddress == null) { scanController.stop(); onClose() }
    }

    LaunchedEffect(Unit) {
        val missing = BlePermissions.missing(context)
        if (missing.isEmpty()) {
            scanController.start()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun connect(device: ScannedDevice) {
        if (connectingAddress != null) return
        scanController.stop()
        connectingAddress = device.address
        connectError = null
        scope.launch {
            val ok = controller.connectReal(device)
            connectingAddress = null
            if (ok) {
                onConnected()
            } else {
                val reason = controller.ui.value.connectionMessage ?: "未匹配到已知传输组（G1–G5）"
                connectError = "连接失败：$reason"
            }
        }
    }

    ScanScreenContent(
        state = state,
        connectingAddress = connectingAddress,
        connectError = connectError,
        onClose = {
            if (connectingAddress == null) {
                scanController.stop()
                onClose()
            }
        },
        onStartScan = { scanController.start() },
        onStopScan = { scanController.stop() },
        onToggleWhitelist = { scanController.setOnlyWhitelisted(it) },
        onConnect = ::connect,
        onShowWhitelistHelp = { showWhitelistHelp = true },
    )

    if (showWhitelistHelp) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showWhitelistHelp = false },
            sheetState = sheetState,
            containerColor = IwColors.Surface,
            contentColor = IwColors.Text,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("白名单过滤", fontSize = 20.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
                Spacer(Modifier.height(10.dp))
                Text(SCAN_WHITELIST_HELP, fontSize = 13.sp, lineHeight = 20.sp, color = IwColors.TextMuted)
                Spacer(Modifier.height(8.dp))
                Text(SCAN_WHITELIST_HELP_SUB, fontSize = 12.sp, color = IwColors.TextMuted)
            }
        }
    }
}

/** 无 Android 依赖的正文，便于 Robolectric/JVM Compose 测试直接驱动状态。 */
@Composable
fun ScanScreenContent(
    state: ScanState,
    connectingAddress: String?,
    connectError: String?,
    onClose: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onToggleWhitelist: (Boolean) -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onShowWhitelistHelp: () -> Unit,
) {
    val visible = state.visibleDevices
    val status = when {
        state.scanning -> SCAN_STATUS_SCANNING
        visible.isEmpty() && state.finished ->
            if (state.onlyWhitelisted) SCAN_STATUS_EMPTY_WHITELIST else SCAN_STATUS_EMPTY_ALL
        visible.isEmpty() -> SCAN_STATUS_READY
        else -> "找到 ${visible.size} 台设备 · 按信号强度排序"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IwColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScanTopBar(onClose = onClose, enabled = connectingAddress == null)
        if (state.scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = IwColors.Primary,
                trackColor = IwColors.SurfaceHigh,
            )
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.scanning) {
                    TextButton(onClick = onStopScan) {
                        Text(SCAN_STOP, fontSize = 12.sp, color = IwColors.Primary)
                    }
                } else {
                    OutlinedButton(onClick = onStartScan, enabled = connectingAddress == null) {
                        Text(SCAN_RESTART, fontSize = 12.sp, color = IwColors.Primary)
                    }
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = state.onlyWhitelisted,
                    onClick = { onToggleWhitelist(!state.onlyWhitelisted) },
                    enabled = !state.scanning && connectingAddress == null,
                    label = { Text(SCAN_FILTER, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = IwColors.Primary.copy(alpha = 0.16f),
                        selectedLabelColor = IwColors.Primary,
                        labelColor = IwColors.TextMuted,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onShowWhitelistHelp) {
                    Text("?", fontSize = 15.sp, color = IwColors.TextMuted)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(status, fontSize = 12.sp, color = IwColors.TextMuted)
            connectError?.let { message ->
                Spacer(Modifier.height(12.dp))
                ErrorCard(message)
            }
            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                ErrorCard(message)
            }
            if (connectingAddress != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = IwColors.Primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(SCAN_CONNECTING, fontSize = 12.sp, color = IwColors.TextMuted)
                }
            }
        }

        if (visible.isEmpty()) {
            EmptyView(
                scanning = state.scanning,
                onlyWhitelisted = state.onlyWhitelisted,
                onRescan = onStartScan,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        connecting = connectingAddress == device.address,
                        anyConnecting = connectingAddress != null,
                        onConnect = { onConnect(device) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("⛨", color = IwColors.TextMuted, fontSize = 15.sp)
            Spacer(Modifier.width(7.dp))
            Text(SCAN_FOOTER, fontSize = 11.sp, lineHeight = 16.sp, color = IwColors.TextMuted)
        }
    }
}

@Composable
private fun ScanTopBar(onClose: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SCAN_TITLE,
            fontSize = 20.sp,
            fontWeight = FontWeight.W600,
            color = IwColors.Text,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose, enabled = enabled) {
            Text("✕", fontSize = 18.sp, color = if (enabled) IwColors.TextMuted else IwColors.Outline)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Text(
        text = message,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = IwColors.Error,
        modifier = Modifier
            .fillMaxWidth()
            .background(IwColors.Error.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    )
}

@Composable
private fun EmptyView(
    scanning: Boolean,
    onlyWhitelisted: Boolean,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(IwColors.Surface)
                .then(Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (scanning) "◎" else "✕",
                fontSize = 30.sp,
                color = if (scanning) IwColors.Primary else IwColors.TextMuted,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (scanning) SCAN_EMPTY_TITLE_SCANNING else SCAN_EMPTY_TITLE_IDLE,
            fontSize = 17.sp,
            fontWeight = FontWeight.W600,
            color = IwColors.Text,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = SCAN_EMPTY_BODY,
            fontSize = 12.5.sp,
            lineHeight = 22.sp,
            color = IwColors.TextMuted,
            textAlign = TextAlign.Start,
        )
        if (!scanning) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onRescan) {
                Text(SCAN_RESTART, fontSize = 12.sp, color = IwColors.Primary)
            }
        }
        if (!onlyWhitelisted) {
            Spacer(Modifier.height(12.dp))
            Text(SCAN_ALL_BROADCASTS_NOTE, fontSize = 11.sp, color = IwColors.Warning)
        }
    }
}

@Composable
private fun DeviceCard(
    device: ScannedDevice,
    connecting: Boolean,
    anyConnecting: Boolean,
    onConnect: () -> Unit,
) {
    var expanded by remember(device.address) { mutableStateOf(false) }
    Surface(color = IwColors.Surface, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !anyConnecting) { onConnect() }
                    .padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(IwColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("◔", fontSize = 22.sp, color = IwColors.TextMuted)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.baseName, fontSize = 15.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(maskSerial(device.serial), "${device.rssi} dBm").joinToString(" · ")
                    Text(meta, fontSize = 12.sp, color = IwColors.TextMuted)
                }
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = IwColors.Primary,
                    )
                } else {
                    TextButton(onClick = onConnect, enabled = !anyConnecting) {
                        Text(SCAN_CONNECT, fontSize = 12.sp, color = IwColors.Primary)
                    }
                }
            }
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) SCAN_TECH_HIDE else SCAN_TECH,
                        fontSize = 11.sp,
                        color = IwColors.TextMuted,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (device.whitelisted) {
                    Text(SCAN_WHITELIST_TAG, fontSize = 11.sp, color = IwColors.TextMuted, modifier = Modifier.padding(end = 8.dp))
                }
            }
            if (expanded) {
                HorizontalDivider(color = IwColors.Outline, thickness = 1.dp)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                    TechLine("完整名称", device.name.ifEmpty { "—" })
                    TechLine("MAC", device.maskedMac)
                    TechLine("序列号", device.serial ?: "—")
                    TechLine(
                        "广播服务",
                        device.advertisedServices.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { shortUuid(it) } ?: "—",
                    )
                }
            }
        }
    }
}

@Composable
private fun TechLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 11.5.sp, color = IwColors.TextMuted, modifier = Modifier.width(68.dp))
        SelectionContainer {
            Text(
                text = value,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = IwColors.Text,
            )
        }
    }
}
