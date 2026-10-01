# Android SDK 阶段 1 手动验证指南

更新日期：2026-10-01。适用于 Windows、PowerShell 和 Android Studio。

本指南记录从导入项目到模拟器检查的完整过程，方便以后重复验证。阶段 1 仅包含构建、公共模型和 API 契约；页面上的 PASS 不代表网络同步、缓存、用户切换或事件发送已经实现。

## 1. 准备环境

本项目已验证的基础配置：JDK 17、Gradle Wrapper 8.7、Android SDK Platform 34、Build Tools 34.0.0。不要使用系统安装的其他 Gradle 替代项目 Wrapper。

本机项目目录：

```text
D:\Workspace\FeatBit\featbit-android-client-sdk
```

本机已安装的 JDK 17 目录：

```text
C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot
```

这些是本机路径；换电脑后使用对应的实际安装目录。

在 Android Studio 的 Gradle 设置中，将 **Distribution** 设为 **Wrapper**，**Gradle JDK** 选择 JDK 17。如果下拉框没有该选项，通过 Add JDK 或目录选择按钮指定上述 JDK 根目录，不要选择 `bin` 子目录。SDK 工程与消费者工程分别检查此设置。

Android Studio 的 Gradle JDK 设置与 Terminal 中的 Java 环境可能不同。若终端构建提示 Java 版本不兼容，可以在当前 PowerShell 会话中设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot'
```

随后用 `gradlew.bat --version` 检查实际使用的 JVM；以下各步骤根据当前目录使用 `.\gradlew.bat` 或 `..\gradlew.bat`。

## 2. 导入并构建 SDK

在 Android Studio 中选择 **File → Open**，打开项目根目录，不要只打开 `sdk` 子目录。正确目录包含 `settings.gradle.kts`、`build.gradle.kts` 和 `gradlew.bat`。

等待 Gradle Sync 完成，在底部 **Terminal** 中执行：

```powershell
Set-Location 'D:\Workspace\FeatBit\featbit-android-client-sdk'
.\gradlew.bat :sdk:assembleDebug :sdk:assembleRelease
```

预期看到 `BUILD SUCCESSFUL`，生成：

```text
sdk\build\outputs\aar\sdk-debug.aar
sdk\build\outputs\aar\sdk-release.aar
```

需要同时检查 SDK 模型测试、lint 和 API 基线时，在同一目录执行：

```powershell
.\gradlew.bat :sdk:testDebugUnitTest :sdk:lintRelease
python tools/check_api.py
```

API 检查读取上一步生成的 release AAR，需要 Python。出现 API 差异时应检查原因，不要为了通过验证直接覆盖基线。

## 3. 发布到本地测试仓库

仍在 SDK 根目录执行：

```powershell
.\gradlew.bat :sdk:publishReleasePublicationToLocalTestRepository
```

预期 `BUILD SUCCESSFUL`。产物位于 `build\test-repository`，消费者使用坐标：

```text
co.featbit:featbit-client-android:0.1.0-SNAPSHOT
```

这是项目内的测试仓库，不是 Maven Central，也不会上传任何产物。以后修改 SDK 源码后，需要重新执行此步骤，消费者才能使用新的 AAR。

## 4. 独立消费与公共模型检查

通过 **File → Open** 打开以下目录，建议选择 **New Window**：

```text
D:\Workspace\FeatBit\featbit-android-client-sdk\consumer-tests
```

选择 Gradle JDK 17，等待同步完成。这个独立工程有 `java`、`kotlin` 两个模块，通过本地 Maven 仓库引用 AAR，不依赖 SDK 源码模块。

在消费者工程的 Terminal 中执行：

```powershell
Set-Location 'D:\Workspace\FeatBit\featbit-android-client-sdk\consumer-tests'
..\gradlew.bat :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
```

注意这里是 **`..\gradlew.bat`**，Wrapper 在上一级目录。

这条命令同时完成：

| 任务 | 验证内容 |
| --- | --- |
| `assembleDebug` | Java/Kotlin 消费者能引用 AAR 并构建 debug APK |
| `assembleRelease` | release APK 构建及 R8 缩减 |
| `testDebugUnitTest` | 消费者现有的公共模型 smoke 测试 |

预期 `BUILD SUCCESSFUL`。本步骤不需要设备，也不代表 APK 已在设备上运行。默认消费者 Kotlin 编译器是 1.9.25；完整编译器矩阵命令见 [阶段 1 说明](./phase-1.md)。

测试报告可在浏览器中打开：

```text
consumer-tests\java\build\reports\tests\testDebugUnitTest\index.html
consumer-tests\kotlin\build\reports\tests\testDebugUnitTest\index.html
```

以上报告路径相对于 SDK 根目录。

## 5. 创建并启动模拟器

在 Android Studio 中打开 **Tools → Device Manager**：

1. 点击 **＋ / Create Virtual Device**。
2. 选择一款手机，例如 Pixel 6，点击 Next。
3. 选择适合当前电脑的 **API 34** 系统镜像；未安装时先下载。
4. 完成创建后，点击设备旁的 **▶**。
5. 等待模拟器进入 Android 桌面。

也可以使用开启 USB 调试的 Android 手机；连接后，需要在手机上允许这台电脑调试。设备系统至少为 API 21。本次实际验证使用 API 34 模拟器，不代表所有系统版本或真机均已验证。

## 6. 启动 Java / Kotlin 验证页面

保持打开的是 `consumer-tests` 工程：

1. 在顶部运行配置中选择 **java**，目标设备选择刚启动的模拟器。
2. 确保模块使用 **debug** 构建变体，可在 Build Variants 面板检查。
3. 点击绿色 **Run ▶**，等待构建、安装并启动。
4. 查看页面是否显示 `Java · SDK 公共模型检查` 和 `PASS · 检查通过`。
5. 点击 **重新检查**，确认检查次数递增、完成时间更新，并且仍然显示 PASS。
6. 将运行配置换成 **kotlin**，重复上述操作，确认 Kotlin 页面也通过。

如果顶部没有对应配置，打开 **Run → Edit Configurations → ＋ → Android App**，选择列表中的 `java` 或 `kotlin` 模块（显示名称可能带工程前缀），将 **Launch** 设为 **Default Activity**，保存后运行。

页面启动时自动检查一次，因此初始次数为 1。点击一次“重新检查”后应变为 2。次数针对当前 Activity 实例，重新启动或重建页面后可以重置。

页面调用与消费者单元测试相同的 `ModelSmoke.verify()`。两种语言的检查内容略有不同，包含用户与配置、Bootstrap、JSON 值、非法数值、版本以及数据源模型/声明等基本用法，不是完整 SDK 验收套件。

## 7. 常见问题

| 现象 | 处理方式 |
| --- | --- |
| 提示 Gradle JVM 不兼容 | 两个工程分别选 JDK 17；终端构建还需检查 `JAVA_HOME` |
| 显示 Gradle 8.6，而本项目配置为 8.7 | 检查是否打开了正确工程，并确认 Distribution 使用 Wrapper |
| 找不到 `gradlew.bat` | SDK 根目录用 `.\gradlew.bat`；消费者根目录用 `..\gradlew.bat` |
| 无法解析 `co.featbit:featbit-client-android` | 先完成本地发布，再同步消费者工程；不要改成源码模块依赖 |
| 修改 SDK 后消费者仍使用旧产物 | 重新本地发布；必要时在消费者构建命令末尾加 `--refresh-dependencies` |
| 没有可选设备 | 先启动模拟器，或连接并授权 USB 调试设备 |
| 找不到 Default Activity | 使用最新消费者源码，重新同步并选择正确的消费者模块 |
| 点击“重新检查”感觉没有变化 | 检查次数和时间是否更新；旧版本只刷新相同的 PASS 文字，需要重新 Run 安装新版 |
| 页面显示 FAIL | 打开底部 Logcat，选中目标设备，搜索 `FeatBitConsumer`，保存错误堆栈 |
| 修改页面源码后设备上仍是旧页面 | 重新点击 Run，让 Android Studio 构建并安装更新后的 debug APK |

## 8. 验证记录与范围

2026-10-01 已在 API 34 模拟器 `emulator-5554` 上安装并启动 Java、Kotlin debug APK。两个页面均显示 PASS，点击“重新检查”后次数从 1 变为 2，完成时间更新；通过 UI 层级读取确认这些结果。

release 构建和 R8 已通过，但当前 release APK 未配置签名，**尚未进行 release APK 的设备运行验证**。debug APK 的设备结果不能代替 R8 后的运行结果。

后续重复验证时可记录：日期、代码版本/工作区变更、设备/API、模块、构建变体、首次结果、重试次数变化和错误日志。完整已执行/未执行项目见 [验证记录](./verification.md)。

本指南暂不覆盖 SDK 客户端运行时、远端开关读取、用户切换、缓存、事件投递、真机兼容性和正式发布；这些随后续阶段补充。
