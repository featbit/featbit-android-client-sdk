# Android SDK 开发交接

更新时间：2026-10-01（Europe/Berlin）。

## 当前进度

阶段 1 基础、阶段 2 本地运行时和阶段 3 持久化已实现。阶段 4–7 尚未实施。
本次从阶段 2 交接继续阶段 3，没有提交、推送或远程发布，也未修改相邻仓库。
详细实现、使用方式及边界见 [phase-3.md](./phase-3.md) 和 [phase-2.md](./phase-2.md)，实际验证见
[verification.md](./verification.md)。不要把本地运行时验证解释为完整线上 SDK 已可用。

## 先读

1. [实施计划](../plan.md)：阶段边界；下一步阶段 4。
2. [架构](../architecture.md)：状态、会话、缓存与身份持久化规则。
3. [阶段 3](./phase-3.md) 与 [阶段 2](./phase-2.md)：持久化与当前运行时、未接入能力、输入校验。
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
- `sdk/src/test/kotlin/co/featbit/android/internal/PersistenceTest.kt`：阶段 3 的确定性存储/竞争测试。
- `sdk/src/test/kotlin/co/featbit/android/internal/LocalRuntimeTest.kt` 与 `sdk/src/test/resources/fixtures/v1/`：可控时钟、生命周期、来源、匿名仓库及转换 fixture。
- `consumer-tests/*/.../RuntimeSmoke.*`：实际 AAR 的 Android 运行验证。验证页面仍是测试入口，不是 samples。

## 不可遗漏的边界

- 2026-10-01 按用户决定移除 Flag 输入大小、数量和元数据预算限制，覆盖 Bootstrap、Full/Patch、Custom/TestData 和 JSON 文本。保留重复 key 等有效性检查；JSON 解析深度/节点数及线程、回调、事件、缓存容量约束不属于该变更。`Limits` 已替换为只负责 Bootstrap 校验和转换的 `BootstrapRecords`。当时的 40 个测试是阶段 2 历史证据；阶段 3 本次结果见 verification.md。
- 2026-10-02 后续移除 JSON 的 50,000 节点上限，保留深度 64；移除等待、请求/关闭超时、轮询/flush 间隔及 Flag grace 的固定上限，保留最小值与默认值。截止时间相加溢出时饱和到 `Long.MAX_VALUE`。内部两秒数据源停止预算和运行时容量限制不变。62 个单元测试及 Release lint 通过，未重跑设备测试。

- 真实内置网络和事件尚未接入。在线内置来源返回 DISABLED；Custom 当前需关闭事件。
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

主目录 `D:\Workspace\FeatBit\featbit-android-client-sdk`。本次阶段 3 开始时工作区干净。
当前未提交修改为阶段 3 实现、测试及文档；先检查 git status，保留它们。
用户禁止批量/递归删除文件，只可一次删除一个明确路径文件。

本次读取共享 spec 的 `client-side/spec/storage.md` 和 `client-side/mobile/conformance.md`，
未修改相邻仓库。旧阶段 2 的 spec commit/工作区描述是历史证据；后续使用前重新核对。
规范以英文内容、当前 plan/architecture 及已接受决策为准。

JDK `C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot`；Android SDK
`C:\Users\Falcon\AppData\Local\Android\Sdk`；Gradle 8.7 / AGP 8.5.2，
SDK Kotlin 1.9.25，Java 11 字节码，minSdk 21 / compileSdk 34。
本地 Maven 坐标 `co.featbit:featbit-client-android:0.1.0-SNAPSHOT`，输出到 `build/test-repository`。

## 下一步：阶段 4

按 plan 接入真实网络协议、Streaming/Polling、Identify 与会话隔离、fallback/recovery。
保留阶段 3 的缓存发布仲裁、提交顺序和匿名 revision 约束；网络请求一旦发出，不能
追溯采用后来加载的缓存 cursor。不要提前加入事件发送或真实 Android observers。
完整物理设备、备份恢复和各原子替换阶段的进程终止覆盖仍在阶段 6–7 汇总验证。

继续沿用现有项目、三个工厂和独立消费者，不要重建工程。根据本次实际改动重新验证，
不要继承历史 PASS。阶段 4 的 Track 名称限制、appType 和事件协议兼容问题仍待服务端联调。
