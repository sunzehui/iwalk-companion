package com.iwalk.companion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iwalk.companion.R
import com.iwalk.companion.di.AppContainer
import com.iwalk.companion.ui.device.DeviceScreen
import com.iwalk.companion.ui.device.DeviceSessionController
import com.iwalk.companion.ui.scan.ScanScreen
import com.iwalk.companion.ui.secondary.SecondaryPage
import com.iwalk.companion.ui.secondary.SecondaryPageScreen
import com.iwalk.companion.ui.settings.SettingsTabBody

/** 一个底部页签的静态描述：只有标签资源；正文由首页按索引选择。 */
private data class IwTab(@param:StringRes val labelRes: Int)

/**
 * 骨架外壳（规格 §6 的 `HomeShell` 精简版）：深色 Scaffold + 设备/分析/设置三个页签。
 * 设备正文接演示会话与真车会话（工单 02/03）；分析正文是两段式实时分析视图（工单 05，
 * 离线段待工单 06）；设置正文是三个静态二级页的入口，打开二级页时整屏覆盖
 * （对齐 Dart `Navigator.push`），返回即回到当前页签正文。
 *
 * 分析页与设备页共用同一个 [DeviceSessionController]：会话只有一份，切换页签不重建。
 * 扫描屏是另一个整屏路由（工单 03），连接成功即关闭并回到设备页。
 *
 * 图标位用一个随选中态变色的圆点占位（本 BOM 不再透传 material-icons，也不引新库）；
 * 选中态取主色青绿，未选中标 muted 灰。
 */
@Composable
fun IwApp() {
    IwTheme {
        val context = LocalContext.current
        // AppContainer 只建一次；宿主销毁时统一收尾（真车链路断开、演示 tick 终止）
        val container = remember { AppContainer(context.applicationContext) }
        DisposableEffect(container) {
            onDispose { container.close() }
        }
        val deviceController = container.deviceController
        val sessionState by deviceController.ui.collectAsState()

        val tabs = remember {
            listOf(
                IwTab(R.string.tab_device),
                IwTab(R.string.tab_settings),
            )
        }
        // 默认选中设备页
        var selectedIndex by remember { mutableIntStateOf(0) }
        // 本工单的返回栈：单一二级页状态，页签切换前先返回（工单 07 引入路由库时替换）
        var secondaryPage by remember { mutableStateOf<SecondaryPage?>(null) }
        // 扫描屏：整屏覆盖，连接成功即关闭
        var showScan by remember { mutableStateOf(false) }

        val openPage = secondaryPage
        when {
            showScan -> {
                ScanScreen(
                    controller = deviceController,
                    onClose = { showScan = false },
                    onConnected = { showScan = false },
                )
            }
            openPage != null -> {
                SecondaryPageScreen(page = openPage, onBack = { secondaryPage = null }, state = sessionState)
            }
            else -> {
                Scaffold(
                    containerColor = IwColors.Background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = IwColors.Surface,
                            modifier = Modifier.height(72.dp),
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                val selected = selectedIndex == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { selectedIndex = index },
                                    alwaysShowLabel = true,
                                    icon = {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(
                                                    color = if (selected) IwColors.Primary else IwColors.TextMuted,
                                                    shape = CircleShape,
                                                ),
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = IwColors.Primary,
                                        selectedTextColor = IwColors.Primary,
                                        unselectedIconColor = IwColors.TextMuted,
                                        unselectedTextColor = IwColors.TextMuted,
                                        indicatorColor = IwColors.Primary.copy(alpha = 0.14f),
                                    ),
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 仅渲染当前页签，切换即替换正文：设备页接演示/真车会话，设置为锁车与边界
                        when (selectedIndex) {
                            0 -> {
                                val deviceState by deviceController.ui.collectAsState()
                                DeviceScreen(
                                    state = deviceState,
                                    onStartDemo = deviceController::startDemo,
                                    onStopSession = deviceController::stopSession,
                                    onScan = { showScan = true },
                                    lockPanel = { com.iwalk.companion.ui.device.LockControlPanel(deviceController) },
                                )
                            }
                            else -> SettingsTabBody(onOpenPage = { secondaryPage = it }, controller = deviceController)
                        }
                    }
                }
            }
        }
    }
}
