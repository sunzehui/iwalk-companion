package com.iwalk.core.reference

/**
 * MiniRobot 官方资源里的命名与分类表（纯 JVM，无 Android 依赖）。
 *
 * 逐字迁移自 `app_v2/lib/reference/minirobot_reference.dart`，是**词汇表**而不是诊断来源：
 * 官方短码只是同一条目表里的名字，与 BLE payload 里的数值错误码之间的映射未经验证。
 * 界面文案属于 UI 层，这里只放数据本体与来源事实。
 */
object MiniRobotReference {

    /** 官方短码条目：`code` 是 hxz 名字，`note` 是官方表里自带的重复/存疑说明。 */
    data class Code(val code: String, val label: String, val note: String)

    /** 长故障文案按域分组；域名里的 ` / ` 是官方写法，不做本地化。 */
    data class Group(val domain: String, val items: List<String>)

    /** 状态与术语：`label` 是词条，`values` 是该词的取值集合。 */
    data class Term(val label: String, val values: String)

    /** 版本组件与 iWalk 对它的处理方式。 */
    data class Version(val component: String, val handling: String)

    const val SOURCE_APP = "MiniRobot 11.3.8 (303)"
    const val SOURCE_PACKAGE = "com.loby.balance.car.google"
    const val SOURCE_NOTE = "来源：官方 App 内置文案与界面节点。iWalk 只作命名参考，不据此诊断车辆。"

    val shortFaults: List<Code> = listOf(
        Code("hxz1", "Hall-E", "与 hxz2 重复；为何重复未验证"),
        Code("hxz2", "Hall-E", "同上"),
        Code("hxz3", "转向异常 / Steering Abnormality", "转向相关"),
        Code("hxz4", "陀螺仪异常 / Gyro Abnormal", "姿态传感器"),
        Code("hxz5", "突然掉电 / sudden power-down", "电源"),
        Code("hxz6", "大电流保护 / high current protection", "电流保护"),
        Code("hxz7", "应力片异常 / Abnormal stress sheet", "应力传感器"),
        Code("hxz8", "RM-Loss", "缩写含义未验证"),
        Code("hxz9", "LM-Loss", "缩写含义未验证"),
        Code("hxz10", "陀螺仪异常 / Gyro Abnormal", "与 hxz4 重复"),
        Code("hxz11", "Hall-A", "与 hxz12 重复；为何重复未验证"),
        Code("hxz12", "Hall-A", "同上"),
        Code("hxz13", "倾角速度过大 / Tilt speed is too large", "姿态变化过快"),
    )

    val longFaultGroups: List<Group> = listOf(
        Group(
            "温度 / 电源",
            listOf(
                "控制器温度传感器异常",
                "电池温度异常（10–50°C）",
                "系统电压异常",
                "3.3V 电压异常",
                "5V 电压异常",
                "驱动电压异常",
            ),
        ),
        Group(
            "电机 / 驱动",
            listOf(
                "MOS 管上桥故障",
                "MOS 管下桥故障",
                "电机霍尔异常",
                "电机 A/B/C 相电流采样异常",
                "左/右电机电流传感器异常",
                "左/右电机驱动器异常",
            ),
        ),
        Group(
            "电池 / BMS",
            listOf(
                "电池密码错误",
                "电池通讯异常",
                "电池固件版本过低",
                "BMS 序号异常",
                "BMS 密码错误",
                "BMS 通信错误",
            ),
        ),
        Group(
            "传感器 / 踏板",
            listOf(
                "重力传感器异常",
                "陀螺仪初始化异常",
                "脚控转向传感器异常",
                "左/右脚踏板开关异常",
                "刹车霍尔异常",
                "油门霍尔异常",
            ),
        ),
        Group(
            "通信 / 主板",
            listOf(
                "仪表盘通信异常",
                "电控未收到蓝牙面板数据",
                "通信失败（蓝牙模块 / 电控连线）",
                "主板密码错误",
                "Flash 保存错误",
                "程序跳转错误",
                "车辆序列号错误",
            ),
        ),
        Group(
            "操作限制",
            listOf(
                "倾斜角过大保护",
                "卡丁车 / 平衡车切换需竖立",
                "遥控模式禁止站人",
                "休眠模式需厂家激活",
            ),
        ),
    )

    val terms: List<Term> = listOf(
        Term("工作模式", "骑行 / 助力 / 待机（Cycling / Power-assistance / Stand-by）"),
        Term("驾驶模式", "行走 / 舒适 / 普通 / 运动；另有新手 / 普通 / 运动 / 限速"),
        Term("驱动形式", "后驱 / 双驱 / 前驱"),
        Term("能量回收", "自适应 / 强 / 中 / 弱"),
        Term("锁车", "锁车 / 解锁 / 电子锁"),
        Term("灯光", "前灯 / 尾灯 / 氛围灯 / 轮毂灯 / 刹车灯 / 控制杆氛围灯"),
        Term("限速警告", "新手 8 km/h、普通 18 km/h、运动 24 km/h"),
        Term("车辆校准", "官方 App 的姿态传感器 / 平衡点校准；iWalk 不实现，也不下发"),
    )

    val versionComponents: List<Version> = listOf(
        Version("蓝牙版本", "仅在通知中出现时展示"),
        Version("电控版本", "仅在通知中出现时展示"),
        Version("电池版本", "仅在通知中出现时展示"),
        Version("基站版本", "仅在通知中出现时展示"),
        Version("遥控器版本", "仅在通知中出现时展示"),
        Version("协控版本", "仅在通知中出现时展示"),
        Version("蓝牙板版本", "仅在通知中出现时展示"),
        Version("卡丁车套件", "车型相关；不主动查询"),
        Version("油门蓝牙", "车型相关；不主动查询"),
    )

    const val VERSION_LIMIT =
        "标准 GATT 只提供设备名、Appearance 和连接参数；固件版本不在标准 GATT 中。" +
            "iWalk 不会把设备名当作固件版本，也不会主动查询版本。"
}
