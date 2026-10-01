# Android SDK 开发交接

更新时间：2026-10-01（Europe/Berlin）。本文件供新的 session 接续工作；具体行为契约以实施计划、架构和共享 spec 为准。

## 当前状态

**阶段 1 的构建/API 基础已实现并完成对应验证；阶段 2 尚未开始。** 用户已进行 Android Studio 手动操作；我们随后在连接的 API 34 模拟器上确认 Java、Kotlin 验证页面均显示 PASS，重新检查后次数和时间更新。

本次请求仅整理交接，不继续实现阶段 2。新 session 应根据用户的新指令决定继续范围。不要把阶段 1 验证解释为完整 SDK 已可用。

## 先读这些文件

1. [实施计划](../plan.md)：阶段 1–7 的边界和验收条件，下一步重点是 Phase 2。
2. [架构](../architecture.md)：状态协调、会话隔离、数据源、异步操作及持久化契约。
3. [阶段 1 决策](./phase-1.md)：实际 API、工具链、默认参数、初始资源策略、协议待验证事项。
4. [验证记录](./verification.md)：区分实际通过、历史结果和未执行项目。
5. [手动验证指南](./manual-verification.md)：Android Studio、JDK、构建、本地发布、独立消费、模拟器及故障排查。
6. [LaunchDarkly 比较](../launchdarkly-feature-comparison.md)：已接受的范围和差异，仅作参照，不替代 FeatBit spec。

架构中的部分表格保留原始决策清单；已落地的阶段 1 选择及验证边界见 `phase-1.md`、`verification.md` 和当前代码，不要把所有“待验证”条目误判为从未处理，也不要把初始参数当成已完成设备测量。

## 工作区与 Git

主目录：`D:\Workspace\FeatBit\featbit-android-client-sdk`。

本次交接时 HEAD 为 `c0730f3f70d62a76a1a37bcb556783b58d3c6a6e`。**本 session 没有提交或推送**，主要工作仍在工作区：`README.md` 为已跟踪修改，`sdk/`、`consumer-tests/`、`docs/`、Gradle、CI、API 检查工具和设计文档等均显示为未跟踪。它们包含已完成工作，不能当作可清理的临时文件。新 session 先检查 `git status`，保留这些内容。

相邻仓库及此前核对的基线（继续工作时重新核对是否变化）：

| 仓库 | 基线 |
| --- | --- |
| `D:\Workspace\FeatBit\sdk-spec` | `3f08faa77dbf70bea208bd8ab946c2aa0b38ffad`；使用英文 spec，本地中文补充不作为规范 |
| `D:\Workspace\FeatBit\featbit-js-client-sdk` | `210f4e6d4c032fd73d5bf9f16920507d645c2711` |
| `D:\Workspace\FeatBit\featbit\modules\evaluation-server` | `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753` |

用户的文件安全约束：**禁止批量/递归删除文件或目录**。只能一次删除一个明确路径的文件；需要批量删除时请用户手动处理。不要用清理命令丢弃上述工作区成果。

## 已实现的内容与入口

| 位置 | 内容 |
| --- | --- |
| `sdk/build.gradle.kts` | 单一 Android Library、本地 Maven 发布、候选依赖解析 |
| `sdk/src/main/kotlin/co/featbit/android/api/` | 不可变用户、配置、Bootstrap、FbValue、Outcome、状态/评估模型；客户端/异步操作契约 |
| `sdk/src/main/kotlin/co/featbit/android/datasource/` | 自定义源 factory/lifecycle/sink 契约及 Full/Patch/NoChange 模型 |
| `sdk/src/main/kotlin/co/featbit/android/kotlin/` | 协程/Flow 适配接口，尚无实现 |
| `sdk/src/main/kotlin/co/featbit/android/testing/` | TestDataFactory、TestData 契约，尚无实现 |
| `sdk/src/test/` | 8 项模型测试，覆盖不可变输入、普通错误、配置条件等 |
| `sdk/api/public-api.txt`、`tools/check_api.py` | 实际 release AAR 的 JVM API 基线、Java 11 字节码及依赖泄漏检查 |
| `consumer-tests/` | 独立构建，通过本地 Maven AAR 消费，Java/Kotlin 各一个 smoke 测试及最小验证 Activity |
| `.github/workflows/build.yml` | Library、API 检查、三种消费者 Kotlin 版本、R8 的 CI 定义，尚未观察到托管 CI 执行 |

`ClientFactory`、`FeatBitClient`、Operation、数据源协调、协程适配及 TestData 当前为契约，不是可运行 SDK。没有伪造成功的 factory 或抛 `NotImplementedError` 的运行时占位实现。

TestData 接口接受 `BootstrapFlag`，调用方不管理版本；`clientOptions(user)` 的契约固定禁用事件和生产缓存。阶段 2 需实现这些行为，而非仅保留配置开关。

## 已接受的关键决策

- Kotlin **1.9.25** 实现，公共 API 兼容 Java；单一核心 AAR 坐标 **`co.featbit:featbit-client-android`**，包名 `co.featbit.android`。用户拥有 `co.featbit` namespace。
- OpenFeature Provider 是独立产品/仓库，不加入核心 SDK。当前不做 samples；消费者中的最小检查页面只是验证入口。
- `bootstrap(flags)` 提供适用于所有用户的默认值，不引入 `ApplicationDefaults` / `ExactContext` 公共类型。
- 初次创建时：已配置 Bootstrap（包括显式空集合）优先于缓存。Identify/匿名切换后：目标完整上下文的有效缓存优先，包括有效空缓存；只有未命中/不可用才用 Bootstrap，不能按 key 混合补齐。
- 保持 Identify 的会话隔离和完成语义；缓存待决时不能读到旧用户数据，迟到缓存结果不能覆盖远端已提交结果。
- Patch 覆盖当前记录，不保留增量历史。不要因假设历史增长而重新引入 whole-store 容量状态机，超大数据也不能靠 require-full 解决。保留有界输入/队列及原子拒绝。
- 状态核心、缓存、网络、事件、Android 平台集成按阶段推进，不要一次实现阶段 1–3，也不要将所有验证推迟到最后。

## 工具链与本机运行

| 项目 | 当前配置 |
| --- | --- |
| Gradle / AGP | 8.7 / 8.5.2 |
| 构建 JDK / 字节码 | 17 / Java 11 |
| minSdk / compileSdk / Build Tools | 21 / 34 / 34.0.0 |
| 核心编译器 / stdlib | Kotlin 1.9.25 / 1.9.25 |
| 当前开发产物 | `co.featbit:featbit-client-android:0.1.0-SNAPSHOT` |
| 本地发布目录 | `build/test-repository/`，不上传远端 |

本机 JDK：`C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot`。
Android SDK：`C:\Users\Falcon\AppData\Local\Android\Sdk`。
用户已安装 Android Studio。IDE 的 Gradle JDK 和 Terminal 的 `JAVA_HOME` 独立；两个工程都应检查 JDK 设置。

当前 Gradle/AGP 组合超出 Kotlin 1.9.25 官方完整支持范围，但实际构建已通过；不能把实测成功说成官方完整支持。JDK 21 消费者未验证。

只有 Coroutines core 为当前公开 Flow 契约所需运行依赖；OkHttp、Serialization、Coroutines Android、Lifecycle 在候选配置中解析，尚未由 SDK 运行时采用。解析成功不代表对应子系统已验证。

SDK 根目录常用命令：

```powershell
.\gradlew.bat :sdk:assembleDebug :sdk:assembleRelease :sdk:testDebugUnitTest :sdk:lintRelease :sdk:publishReleasePublicationToLocalTestRepository :sdk:resolveCandidateRuntime
python tools/check_api.py
.\gradlew.bat -p consumer-tests '-PconsumerKotlinVersion=1.9.25' :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
```

PowerShell 中版本属性参数整体加引号；曾发生未加引号时版本被错误解析。API 改动经审查后才使用 `python tools/check_api.py --update`，不要只为通过检查覆盖基线。

## 验证证据与限制

- Library debug/release AAR、8 项模型测试、release lint、API/字节码检查通过。
- 初始 API 基础的 Java、Kotlin 1.9.24/1.9.25/2.2.10 独立 AAR 消费、模型测试和 R8 构建通过。1.9.24 消费者实际 stdlib 由依赖解析为 1.9.25；2.2.10 消费者使用 stdlib 2.2.10。
- 增加 Activity 后，Java 与 Kotlin 1.9.25 debug/release、R8、模型测试通过；其他编译器未为该 UI 更新重跑。
- 最近增加“检查次数/完成时间”反馈后，只重跑了两者 debug 构建，并在 API 34 模拟器 `emulator-5554` 实际安装、启动、点击验证。两者 PASS 且次数 1 → 2；模拟器连接状态是当时快照，新 session 需重新检查。
- 按钮之前不是没有调用检查，而是重复显示同样文字；当前 `SmokeActivity` 显示次数与毫秒时间。检查从 Application 启动移到 Activity，失败可显示并写入 `FeatBitConsumer` Logcat。
- release APK 当前未签名，未进行 R8 后的设备运行；真机、服务端联调、Android 内存测量、托管 CI 和 Maven Central 发布都未完成。
- SDK 模型测试与消费者 smoke 测试不等于完整 spec conformance。默认参数/资源限额部分是待实现测量的初始策略。

阶段 4 已记录的协议问题：当前服务端对 Track 名称限制为最多 128 个 ASCII 字母/数字/下划线/连字符，而共享 spec 要求非空；不要静默收紧公共 API 或修改服务端。`Android-Client-SDK` appType 已作源代码级兼容检查，未验证部署后的端到端接收。

## 下一阶段如何接续

用户先前已询问下一步，我们建议阶段 2；本次没有要求开始实施。收到阶段 2 指令后，从现有构建/API 基础继续，不重建工程。严格逐项核对 `plan.md` 的 Phase 2，以下是工作重点，不替代完整清单：

1. 实现客户端创建、状态协调及有界 Operation/回调完成路径；补齐实现所需的可测试时钟、调度和生命周期输入。
2. 实现本地记录提交、Bootstrap、类型/详细/全量/通用/JSON 读取，以及与 spec 一致的错误和回退。
3. 实现本地 Identify/匿名上下文边界、online/offline 意图、等待结果与基本 Close，验证并发及迟到工作隔离；不接入真实网络或事件发送。
4. 实现受控数据源 sink、TestData、全局/单 key/状态订阅及协程适配；不可用接口不能静默成功。
5. 实现安全诊断，并用有意义的测试覆盖顺序、取消、不可变快照、回调重入、资源限制等。
6. 扩展独立消费者的真实 API 用例，必要时扩展最小验证页面；运行对应测试并更新 API 基线、状态说明和验证记录。

阶段 3 才加入真实缓存/匿名身份持久化；阶段 4 接入在线同步；阶段 5 事件；阶段 6 平台观察；阶段 7 综合验收/发布准备。正式发布是另行授权的任务。

不要继承上次“验证通过”作为新改动的证明；根据本次影响范围重新运行检查，并明确未执行项。
