# Android SDK 开发交接

更新时间：2026-10-01（Europe/Berlin）。

## 当前进度

阶段 1 基础和阶段 2 本地运行时已实现。阶段 3–7 尚未实施。
本次从旧交接继续阶段 2，没有提交、推送或远程发布，也未修改相邻仓库。
详细实现、使用方式及边界见 [phase-2.md](./phase-2.md)，实际验证见
[verification.md](./verification.md)。不要把本地运行时验证解释为完整线上 SDK 已可用。

## 先读

1. [实施计划](../plan.md)：阶段边界；下一步阶段 3。
2. [架构](../architecture.md)：状态、会话、缓存与身份持久化规则。
3. [阶段 2](./phase-2.md)：当前运行时、未接入能力、输入限额及性能探针。
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
- `internal/RuntimeFactory.kt`：异步验证/创建、自动属性；尚无真实缓存和匿名存储。
- `sdk/src/test/kotlin/co/featbit/android/internal/LocalRuntimeTest.kt` 与 `sdk/src/test/resources/fixtures/v1/`：可控时钟、生命周期、来源、匿名仓库及转换 fixture。
- `consumer-tests/*/.../RuntimeSmoke.*`：实际 AAR 的 Android 运行验证。验证页面仍是测试入口，不是 samples。

## 不可遗漏的边界

- 真实内置网络、事件、缓存及匿名持久化尚未接入。在线内置来源返回 DISABLED；
  Custom 当前需关闭事件；缓存清除/匿名持久化不可用会返回明确结果，不伪造成功。
- TestData 固定关闭 events/cache，不做网络请求；普通 offline 本地客户端可使用 Bootstrap。
- 初建 Bootstrap 优先。阶段 3 的 Identify 应先查目标完整上下文缓存（有效空缓存也是命中），
  miss 才使用 Bootstrap；不能直接沿用现在“缓存不可用”的本地路径来替代缓存仲裁。
- Full 替换、相等 timestamp Patch 接受、旧 Patch 忽略、archived tombstone 保留。
  已覆盖过的 Bootstrap key 不因后续 Full 遗漏而复活；不同 generation 重置遮蔽。
- 每次 Identify、新来源、pause/offline/close 均隔离旧 sink。匿名准备与已提交上下文的 wait 分离。
  内部 AnonymousRepository.withCurrent 在仓库 metadata gate 下验证 revision 再采用；阶段 3 实现不能在该 gate 做 I/O。
- Close 的清理不依赖普通 waiter 或主线程回调容量；超时后不能声称杀死了阻塞的第三方线程。
- 同步读取不做磁盘/网络 I/O；Phase 5 加事件时必须保持同一上下文/记录/资格的线性化边界。
- 日志只输出 SDK 自有诊断码，不转发扩展提供的字符串或 Throwable。

## 工作区和规范

主目录 `D:\Workspace\FeatBit\featbit-android-client-sdk`。本次开始时 HEAD 为 `ab973d1`，
工作区干净（旧交接所写“全部未跟踪”已经过时）。现在的改动均为阶段 2 工作；先检查 git status，保留它们。
用户禁止批量/递归删除文件，只可一次删除一个明确路径文件。

共享 spec HEAD 仍为 `3f08faa77dbf70bea208bd8ab946c2aa0b38ffad`；其英文
conformance/identity/public-api 有预先存在的未提交修改，中文 mobile 文档未跟踪。
本次未修改这些文件。规范以英文内容、当前 plan/architecture 及已接受决策为准。

JDK `C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot`；Android SDK
`C:\Users\Falcon\AppData\Local\Android\Sdk`；Gradle 8.7 / AGP 8.5.2，
SDK Kotlin 1.9.25，Java 11 字节码，minSdk 21 / compileSdk 34。
本地 Maven 坐标 `co.featbit:featbit-client-android:0.1.0-SNAPSHOT`，输出到 `build/test-repository`。

## 下一步：阶段 3

按 plan 完成真实缓存/匿名持久化：backup-excluded 存储、完整上下文 namespace、
有效空缓存、Bootstrap 优先级、迟到缓存与远端/Identify/clear/Close 隔离、
原子替换与按提交顺序写入、epoch 清除、过期/LRU/容量和损坏输入处理。
匿名 key 需持久化成功后采用，跨实例 revision/有序写与失败保留旧身份。
不要提前加入真实网络、事件或 Android observers。

继续沿用现有项目、三个工厂和独立消费者，不要重建工程。根据本次实际改动重新验证，
不要继承历史 PASS。阶段 4 的 Track 名称限制、appType 和事件协议兼容问题仍待服务端联调。
