# Android SDK 开发交接

更新时间：2026-10-02（Europe/Berlin）。

## 当前进度

阶段 1–4 已实现，阶段 5–7 尚未实施。本次接入真实 Streaming/Polling、Identify 会话隔离、
持续重试、fallback/recovery 和受控生命周期下的后台轮询。没有提交、推送或远程发布。
相邻服务端源码未修改，仅构建并运行其 Fake provider 做真实协议联调。
详细实现和服务端差异见 [phase-4.md](./phase-4.md)，实际验证见 [verification.md](./verification.md)。
事件发送和真实 Android observers 仍未接入；在线使用必须设置 disableEvents(true)。

## 先读

1. [实施计划](../plan.md)：阶段边界；下一步阶段 5。
2. [架构](../architecture.md)：状态、会话、缓存与身份持久化规则。
3. [阶段 4](./phase-4.md)、[阶段 3](./phase-3.md) 与 [阶段 2](./phase-2.md)：网络、持久化和运行时边界。
4. [验证记录](./verification.md)：本次结果与历史结果分开。
5. [阶段 1](./phase-1.md)：工具链、API 决策及初始默认参数。

## 当前代码入口

- `ClientFactory.getDefault()`：异步创建本地客户端；只保留 application context。
- `TestDataFactory.getDefault()`：可用的本地 TestData 工厂。
- `ClientAdapters.getDefault()`：Operation suspend 与订阅 Flow 适配。
- `sdk/src/main/kotlin/co/featbit/android/internal/LocalClient.kt`：原子视图、读取、Identify、模式、来源会话、订阅与 Close。
- `internal/Execution.kt`：有界 worker、结果回调、elapsed deadline 和诊断。
- `internal/Values.kt`：输入保护、decimal/JSON 转换与不可变结果。
- `internal/LocalTestData.kt`：全量本地提交、单客户端绑定、暂停时保存、时间回退处理。
- `internal/RuntimeFactory.kt`：异步验证/创建、自动属性、真实缓存与匿名存储接入。
- `internal/Persistence.kt`：AtomicFile、完整上下文指纹、namespace coordinator、匿名仓库。
- `internal/OnlineSync.kt`：与 LocalClient 共用 gate 的内置网络状态机、请求授权、重试和单 candidate 接管。
- `internal/SyncProtocol.kt`：请求、token、精确 Long cursor、消息解析和安全 HTTP 分类。
- `internal/SyncTransport.kt`：自有 OkHttp、取消晚挂接句柄、禁止重定向和隐式重试、最多四个物理 exchange。
- `OnlineSyncTest` / `OnlineCacheTest` / `SyncProtocolTest` / `SyncTransportTest`：受控竞争与真实本地网络测试。
- `LiveSyncIntegrationTest`：显式 -PliveIntegration 联调；服务端未启动会失败，不会自动跳过。
- `consumer-tests/*/.../NetworkSmoke.*`：实际 AAR 网络检查，以 Activity extra `phase4=true` 显式启用。
- `sdk/src/test/kotlin/co/featbit/android/internal/PersistenceTest.kt`：阶段 3 的确定性存储/竞争测试。
- `sdk/src/test/kotlin/co/featbit/android/internal/LocalRuntimeTest.kt` 与 `sdk/src/test/resources/fixtures/v1/`：可控时钟、生命周期、来源、匿名仓库及转换 fixture。
- `consumer-tests/*/.../RuntimeSmoke.*`：实际 AAR 的 Android 运行验证。验证页面仍是测试入口，不是 samples。

## 不可遗漏的边界

- 2026-10-01 按用户决定移除 Flag 输入大小、数量和元数据预算限制，覆盖 Bootstrap、Full/Patch、Custom/TestData 和 JSON 文本。保留重复 key 等有效性检查；JSON 解析深度/节点数及线程、回调、事件、缓存容量约束不属于该变更。`Limits` 已替换为只负责 Bootstrap 校验和转换的 `BootstrapRecords`。当时的 40 个测试是阶段 2 历史证据；阶段 3 本次结果见 verification.md。
- 2026-10-02 后续移除 JSON 的 50,000 节点上限，保留深度 64；移除等待、请求/关闭超时、轮询/flush 间隔及 Flag grace 的固定上限，保留最小值与默认值。截止时间相加溢出时饱和到 `Long.MAX_VALUE`。内部两秒数据源停止预算和运行时容量限制不变。62 个单元测试及 Release lint 通过，未重跑设备测试。

- 真实内置网络已接入。事件仍未接入；内置来源和 Custom 在线创建当前均需关闭事件。
- 请求一旦捕获 baseline，不允许追溯采用晚到缓存；304 和 Patch 均验证实际请求基线。
  Full 可正常替换较高 cursor 的旧数据，缓存继续使用阶段 3 的逻辑提交顺序。
- 服务端无增量可能返回空 200 或不发 Streaming 数据；不能作为确认。无效 Polling/初次数据超时后
  请求 cursor 0 完整快照。恢复 candidate 直接请求完整快照，保留冻结基线校验与单次 15 秒预算。
- 持续恢复不受 awaitReady/Identify 等待超时影响。4003 和 HTTP 401/403 终止该实例同步；
  旧 attempt 的拒绝不能终止新会话。候选失败与权威 Polling 错误分开。
- AAR 增加 INTERNET 权限；不放宽宿主 cleartext 策略。仅消费者测试 manifest 放行本机 HTTP fixture。
- 缓存及匿名身份已接入 noBackupFilesDir/AtomicFile。缓存为每 namespace 一个原子文件，
  含最多 5 个上下文；2026-10-02 按用户决定移除缓存大小上限和时间过期，时间回拨也不使缓存失效；匿名文件独立。读缓存不写回，LRU 访问时间随下次写入持久化。
- Custom 禁止内置端点配置；REMOTE Custom 以包含部署/来源/格式的 cacheDiscriminator 加 sdkKey 确定缓存 namespace。
  缺少这些信息时仅用内存。LOCAL/TestData 不用生产缓存。
- TestData 固定关闭 events/cache，不做网络请求；普通 offline 本地客户端可使用 Bootstrap。
- 初建 Bootstrap 优先。Identify 先查目标完整上下文缓存（有效空缓存也是命中），
  miss 才使用 Bootstrap；等待查找时读 fallback。缓存不能确认在线 readiness。
- namespace epoch 与最终缓存发布在同一 metadata gate 内仲裁。clear 保留内存与匿名身份，
  清除边界后的新提交允许重新落盘。超时/关闭不承诺撤销已进入磁盘队列的工作。
- Full 替换、相等 timestamp Patch 接受、旧 Patch 忽略、archived tombstone 保留。
  已覆盖过的 Bootstrap key 不因后续 Full 遗漏而复活；不同 generation 重置遮蔽。
- 每次 Identify、新来源、pause/offline/close 均隔离旧 sink。匿名准备与已提交上下文的 wait 分离。
  内部 AnonymousRepository.withCurrent 在仓库 metadata gate 下验证 revision 再采用；该 gate 不做 I/O。
- Close 的清理不依赖普通 waiter 或主线程回调容量；超时后不能声称杀死了阻塞的第三方线程。
- 同步读取不做磁盘/网络 I/O；Phase 5 加事件时必须保持同一上下文/记录/资格的线性化边界。
- 日志只输出 SDK 自有诊断码，不转发扩展提供的字符串或 Throwable。

## 工作区和规范

主目录 `D:\Workspace\FeatBit\featbit-android-client-sdk`。本次阶段 4 开始时工作区干净。
当前未提交修改为阶段 4 实现、测试及文档；先检查 git status，保留它们。
用户禁止批量/递归删除文件，只可一次删除一个明确路径文件。

阶段 4 核对共享同步规范、协议参考、mobile 约束、JS SDK 与 evaluation-server 实现。
源码基线及实际联调范围见 phase-4.md；后续使用前重新核对。
规范以英文内容、当前 plan/architecture 及已接受决策为准。

JDK `C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot`；Android SDK
`C:\Users\Falcon\AppData\Local\Android\Sdk`；Gradle 8.7 / AGP 8.5.2，
SDK Kotlin 1.9.25，Java 11 字节码，minSdk 21 / compileSdk 34。
本地 Maven 坐标 `co.featbit:featbit-client-android:0.1.0-SNAPSHOT`，输出到 `build/test-repository`。

## 下一步：阶段 5

按 plan 实现事件、Flush 和 Close 最终发送。保持读取所选上下文/记录/事件资格的线性化边界，
disableEvents、offline、隐私过滤和事件发送独立终止状态不能由网络恢复绕过。
先核对不含 sendToExperiment 的约定事件负载与实际服务端兼容性，以及 Android appType 和 Track 名称。
不要通过恢复旧字段或复制 Browser appType 来掩盖协议差异。真实 Android observers 留在阶段 6。

继续沿用现有项目、三个工厂和独立消费者，不要重建工程。重新验证本次实际改动，不能继承历史 PASS。
完整物理设备、部署数据库/MQ、备份恢复和各原子替换阶段的进程终止覆盖仍属阶段 6–7 汇总验收。
