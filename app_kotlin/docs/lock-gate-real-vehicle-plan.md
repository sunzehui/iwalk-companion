# 阶段三 · 锁车闸门真车验证方案（待执行）

写于 2026-11-12。**这是方案，不是验收记录。** 现状见
`docs/acceptance-2026-11-12-real-vehicle.md` §11（锁帧真车有效）与
`.scratch/iwalk-kotlin-port/issues/09-experimental-lock.md`（闸门收紧）。

## 1. 为什么还差这一步

2026-11-12 晚的锁车实测只证明了「两条固定帧发出去，车确实有反应」，同时暴露了一个
更严重的事实：**当时速度未标定，`lockBlockedReason` 对 `speedKmh == null` 是放行的**。
于是「速度 >1.0 km/h 阻止发送」这道闸门在真车上**一次都没有被执行过**。

当晚已把它改成「速度未知也阻止」（`LockPolicy.kt`），代价是本车锁车控制在速度标定
完成前不可用。于是闸门从「未生效」变成「永远生效」，而它该有的两种状态
（静止放行 / 运动中拦截）仍然没有真车证据。这属于 §9「未覆盖」里的
「运动工况」，不是新问题。

本方案要补的正是这两条：

| 编号 | 工况 | 期望界面 | 期望 HCI 出站写 | 状态 |
|---|---|---|---|---|
| A | 速度未标定（历史情形） | 滑块禁用 +「速度未标定，无法证明车辆静止，已阻止操作」 | 0 | ✅ 已有证据（`captures/device_acceptance/lock4_blocked_unknown_speed.png`） |
| B | 已标定、静止、速度 0.0 | 滑块可用，滑动后「已发送 · 车端状态未确认」 | 锁帧 ×1 | 逻辑已离线证（`SpeedCalibrationGateTest`），真车待做 |
| C | 已标定、推行中、速度 >1.0 | 滑块禁用 +「速度仍高于 1.0 km/h，已阻止操作」 | **0**（关键） | 逻辑已离线证（`SpeedCalibrationGateTest`），真车待做 |
| D | C 之后停下回到 0.0 | 滑块恢复可用，不自动补发 | 0（除非再滑一次） | 待做 |

C 是这套闸门唯一真正的证明：**拦截时要一次写都没有发生**，而不只是界面上变灰。

## 2. 前置条件（缺一不可）

1. 速度字段已按 `analysis/procedure_speed_calibration.md` 标定并**导入**，
   车辆静止时设备页速度显示 `0.0`（不是 `—`）。没到这一步，本方案整体不启动。
2. 设置 → 实验性锁车控制已开启（需通过风险确认框）。
3. 车辆停稳、无人站立、周围无障碍；操作由本人完成，旁人负责录像与记录。
4. 手机开发者选项「启用蓝牙 HCI 信息收集日志」已开启，并在本轮重新连接一次。

## 3. 操作序列

顺序不能反：先做 C（拦截），再做 B（放行），这样同一段 HCI 日志里既有
「滑了但没写」的时间窗，也有「滑了且写了一次」的对照。

1. 连接车辆，确认设备页速度读数 `0.0`、电量正常。
2. **C**：在旁人手扶、场地空旷的前提下低速推行，等到界面速度 **>1.0 km/h**（约 2–3 km/h
   即可，不需要快），保持推行状态，滑动滑块至右缘（关锁方向）。
   - 期望：滑块在速度超过 1.0 的瞬间就已变灰，滑动无效，下方显示
     「速度仍高于 1.0 km/h，已阻止操作」。
   - 截图一张（含速度读数与阻止原因同屏）。
3. 停止推行，等速度回落到 `0.0`（界面读数归零，通常 1–3 s）。
   - 记录归零用了多久（这是官方显示延迟的下界证据）。
4. **B**：滑块滑至右缘 → 关锁。等界面返回「已发送 · 车端状态未确认」，**目视确认车况**。
   截图一张。
5. 立刻再滑至左缘 → 解锁，恢复原状。截图一张。
6. 导出会话（证据包 + raw），并 `adb pull` 出 HCI 日志。

安全约束（与 §11 相同，不放宽）：

- 每次滑动只发一帧，**不重试**、不换方向试探、不猜测其它分支；
- 车辆运动中的滑动**本来就应当无效**，如果它真的发出了帧，立刻停止实验并记录，
  不要继续；
- 全程保持车辆在可扶范围内，速度不超过步行推行速度。

## 4. 证据与核对

需要落地的东西：

```text
captures/YYYYMMDD_lockgate/
  C_blocked_moving.png       # 速度读数与阻止原因同屏
  B_locked_stationary.png
  B_unlocked.png
  hci_pull/btsnoop_hci.log
  export/raw_*.jsonl
  export/evidence_*.json
  session.md                 # 时间点、读数、目视结论
```

三条独立核对：

1. **应用侧**：`evidence.commands_sent` 应恰好是 2 条（关锁 + 解锁），
   `ok=true`、`simulated=false`、`vehicle_state="unconfirmed"`。
   注意：被闸门挡住的滑动**不会**进 `commands_sent`——滑块禁用时
   `LockSlider` 根本不回调 `onSend`，也就没有记录。这是既有设计，不是缺陷，
   所以「C 没有写」只能靠 HCI 与截图证明。
2. **HCI 侧**：判定器给出，不靠人看

   ```sh
   PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/lock_gate_check.py \
     --btsnoop captures/YYYYMMDD_lockgate/hci_pull/btsnoop_hci.log \
     --calibration downloads/iwk1779887-speed-v1.json \
     --report captures/YYYYMMDD_lockgate/gate_report.md \
     --json captures/YYYYMMDD_lockgate/gate.json
   ```

   为什么不是先前的 `btsnoop_att.py | grep '^# WRITE' | awk` 那一条：它只数得出「一共写了几次」，
   数不出**写那一帧时车在不在动**——而这正是 C 段唯二要证明的事。判定器在同一份日志里
   同时取出站写和 0x14 通知，用标定文件把速度算出来，逐条对齐到写的时刻。

   它做的判定（对应上表）：

   | 检查 | 通过条件 | 不通过的读法 |
   |---|---|---|
   | 关锁/解锁次数 | 各 1 次 | ≠1 → 回到 §11 的双写/重试排查 |
   | 写时速度 | 每条锁帧写出时刻速度 ≤ 1.0 km/h | 速度未知或 >1.0 → 该帧不该发出 |
   | 运动中零写 | 每个 >1.0 km/h 的运动窗口内锁帧 0 条 | 出现任何一条 → **闸门失效** |
   | 有运动窗口 | 至少一段 ≥5 帧且 ≥2 s 的 >1.0 km/h 时段 | 没有 → **C 未被检验**，不是通过 |
   | 写能否解码 | 0 条解不出的写 | 有 → 无法排除它是锁帧，不判通过 |

   退出码 `0` 才算 B/C 都成立；`1` 未通过或 C 未被检验；`2` 输入不可用。

   **真机交叉核对（2026-11-12 锁车日志）**：判定器在 `captures/20261112_locktest/btsnoop_hci.log`
   上清点为「锁 1 + 解锁 1 + 查询 2472 + CCCD 1 + 解不出 0」，锁帧线上字节
   `00ff515f5625545528aa` / `00ff515f5624545529aa` 与本文件写的一致，两次相隔 94.7 s
   与 E66 记录的 95 s 吻合。同一会话没有速度标定，因此在该日志上的判定永远是
   「B 通过、C 未检验」——这正是本方案要补的那半边。

   > 提示：`0x0a/0x01/*` 是**请求**，`0x0d/0x01/*` 是**应答**。标定文件里写的是应答帧组
   （`13/1/20`），写侧分类用的是请求帧组，别把两者混成一个判据。
3. **时间对齐**：截图文件名或 session.md 里写下推行开始/结束的手机时刻，
   与 HCI 写时刻对比；两者相差应在秒级以内。

## 5. 判定

通过需要三条证据同时成立，缺一条都不算：

| 证据 | 覆盖什么 | 现状 |
|---|---|---|
| `lock_gate_check.py` 退出码 0 | HCI 事实：C 段运动窗口内零锁帧、B 段锁帧各 1 次且写时速度 ≤1.0 | 需真车日志 |
| 截图 | 界面事实：确实滑了滑块、滑块变灰、文案出现 | 需真车 |
| `SpeedCalibrationGateTest`（JVM） | 逻辑事实：标定解出的 8.0 km/h 会让 `sendLock` 在**调用 `setLock` 之前**返回拦截；速度回 0 后同一次会话放行 | ✅ 已离线通过（271 项全绿的一部分） |

第三条不能替代前两条，但它把故障范围切开了：真车上若 C 段出现了写，
要么是「速度读数没到达 `snapshot`」（界面/链路问题），要么是判据被改坏——
后者已被这条用例钉住。反过来，前两条也不能替代第三条：离线用例用的是
工具产出的标定文件，它保证的是「文件 → 解码 → 闸门 → 是否真的调了 setLock」这条链。

不通过的条件（任一成立即停）：

- C 段出现了锁帧写入 → 闸门失效，回到代码层面查 `lockBlockedReason` 的输入
  （`snapshot[speed]` 是否真的在刷新、单位与阈值）。
- B 段写入次数 ≠1 → 回到 §11 的双写/重试排查，不要在真车上重复实验。

不通过时先离线复现：`SessionIntegrityTest` 的策略矩阵、`SettingsAndLockTest` 与
`SpeedCalibrationGateTest` 的零写入用例覆盖了纯逻辑层，真车差异只可能来自
「速度读数是否真的到达了 `snapshot`」。

## 6. 判定器自检（不需要真车）

```sh
PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_lock_gate_check.py   # 判定器自身
PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_phase3_flow.py       # 标定 → 判定 串练
cd app_kotlin && ./gradlew :core:test :app:testDebugUnitTest --offline \
  --tests "*SpeedCalibrationContractTest*" --tests "*SpeedCalibrationGateTest*"
```

App 侧那两条读同一份夹具 `app_kotlin/testdata/iwk1779887-speed-v1.sample.json`——
它不是手写的，是 `speed_calibration.py --base-calibration … --emit …` 的产物，
并由 `test_speed_calibration.py` 的 `test_app_fixture_matches_tool_output` 反向盯着：
工具输出的 schema 一变（字段名、类型名、帧组、scale 精度），Python 侧就红；
夹具一被手改，这条同样红。要刷新：`UPDATE_APP_FIXTURE=1 python3 analysis/tools/test_speed_calibration.py`。

`SpeedCalibrationContractTest` 在 `:core` 上验「文件能读进来 + 解码值对 + 串到 `lockBlockedReason`」，
`SpeedCalibrationGateTest` 在 `:app` 上验「导入 → 连接 → 8.0 km/h 被拦且 `setLock` 调用次数为 0 → 归零后放行」。
这两条是能在真车之前做的最后一段，剩下的是读数有没有真的到 `snapshot`，只有上车才知道。

`test_phase3_flow.py` 是当天流程的彩排：用合成日志跑「标定出文件 → 判定器直接吃这份文件」，
再分别验证运动中写出会被判「闸门失效」、静止会话会被判「C 未被检验」、标定帧组对不上会退出 2。
它证明的是两个工具接得上（帧组/字段/单位/profile 结构一致），不是真车结论。

12 条合成用例：正常放行、运动中写出（必须判「闸门失效」）、全程静止（必须判「C 未被检验」
而不是通过）、0.5 s 尖峰不算推行、负速度（倒推）仍算运动、解锁多写一次、有写解不出帧、
缺速度标定、倍率错 50 倍导致速度离谱、app 导出（无出站写）被挡回、
CCCD 不被误判成垃圾写、报告必须写明它证明不了什么。

## 7. 如果速度一直标定不出来

那 B/C/D 永远不会被执行，锁车控制在本车上保持不可用（当前设计）。
这是刻意的保守取舍：一个只对「速度已知」生效的运动闸门，等于没有闸门。
若之后决定改成「未标定时允许发送但每次弹显式风险确认」，那是另一条产品决策，
需要同步改 `LockPolicy`、面板文案、只读与安全页和 issue 09 的矩阵，
不在本方案范围内。

## 8. 与本阶段的关系

阶段三 = 标定 + 锁实验，两半是串联的：

```
速度标定（analysis/procedure_speed_calibration.md）
   └─ 通过 → 导入标定 → 静止速度显示 0.0
        └─ 本方案 B/C/D → 闸门两种状态都有真车证据
   └─ 不通过 → 锁车保持不可用，记录「排除了什么」并另择安全条件补证
```
