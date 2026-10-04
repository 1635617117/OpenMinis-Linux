# OpenMinis-Linux 2.0.29-linux

- versionCode **229**
- 数据库仍为 **21**（无迁移），从 2.0.27 / 2.0.28 直接覆盖安装
- 2.0.28 只发过滚动包、未打 tag，所以本版说明覆盖**自 2.0.27 以来的全部变更**：启动页卡死、黑屏死循环、折叠展开后工具卡位置不对应

---

## 1. 启动页卡死：主线程不再被启动工作挡住

**症状**：部分用户卡在启动页进不去。开销随会话数增长，所以只有会话多的用户命中。

**根因**：`Application.onCreate` 里有一段

```kotlin
runBlocking(Dispatchers.IO) { chatRepository.warmupWorkspaceOwners() }
```

注释写着 "must finish before the first file tool or shell"。问题在于 **`runBlocking` 挡住的是调用方线程，而调用方是主线程**。于是进程内**第一次** Room 开库、待执行的 schema 迁移（已注册 20+ 个，版本 21）、以及

```sql
SELECT * FROM sessions ORDER BY updated_at DESC
```

——把 13 列实体（含每个会话的 `last_message` 长文本预览）逐行水合——全部发生在 `subsystemsInitialized = true` 之前、`MainActivity` 创建之前。系统启动页（白色 `windowBackground`）就一直挂着，超过约 5 秒就是 ANR。

而且这几行之后，**同样的 warmup 又被异步跑了一遍**——纯粹重复。

**修复**：

- warmup 只消费 `id` 和 `folder_id`，且只对**已归档**会话有意义——未归档行的 `rememberFolder(id, null)` 是对空 map 的 `remove()`，语义上是 no-op。新增 `listSessionFolderIds()`：两列投影 + `WHERE folder_id IS NOT NULL AND folder_id <> ''`，行为等价、开销骤降。
- 原本靠主线程阻塞换来的顺序保证（"必须在第一个文件工具或 shell 之前完成"）改由 **`SessionWorkspace.awaitWarmup()`** 提供：一个 `CompletableDeferred`，在 `finally` 里释放——warmup 失败必须降级为"该会话看起来未归档"，绝不能让所有 shell 卡死在一个永远等不到的 await 上。启动解析器在挂载任何目的地之前 await，**上限 3 秒**，所以 warmup 再卡也不会挂死启动器。
- 启动解析器原先每次冷启动要跑**最多三次**全表加载（一次 `isNotEmpty()`、每次 `firstOrNull()`），且都来自主线程调度的 `LaunchedEffect`。改为 `hasAnySession()`（`SELECT EXISTS`）与 `newestSession()`（`LIMIT 1`，`updated_at` 已有索引）。

## 2. 黑屏死循环：init 失败现在有屏幕、有原因、有出路

**症状**：卡在启动页，等待一会**黑屏无显示**，app 自己消失，再点还是一样。

**根因**：子系统初始化块里任何异常都被 catch 后 `return`，`subsystemsInitialized = false`。`MainActivity` 在这个分支上**不调用 `setContent`**，然后 `maybeShowOnActivity` 在 `pendingShareFiles == null` 时会**同步**调用 `onClosed` —— 即 `finishAndRestartProcess()`：1.2 秒 Toast、`finish()`、`killProcess`。

而 init 失败是**记 log 不写 crash 文件**的，所以 `pendingShareFiles` 几乎恒为 null。结果：白色启动页 → 无内容窗口变黑 → 进程消失。`Application.onCreate` 对一个进程只跑一次，原因通常是确定性的（读不坏的存储、缺一条迁移路径、某行必然抛异常的数据），所以每次点击都精确重复——屏幕上没有任何原因，应用内没有任何出路。

**修复**：`MinisApp` 新增 `subsystemInitFailure` 记录原因（原先只 `Log.e`，UI 无从展示）；`MainActivity` 在调用 `maybeShowOnActivity` **之前**先读 `pendingShareFiles`（该调用会消费并清空它）：

- **有 crash burst** → 走原有对话框路径，一行未改（它能直接把日志发出去，严格更好）；
- **没有** → `setContent { StartupFailureScreen }`，把原因写在屏幕上（类名 + 消息，可一键复制），提供**重启**、**复制诊断信息**，以及**清除应用数据**（明确标注为最后手段、二次确认后才执行）。

与 `NewerDatabaseGuidanceScreen` 不同，这个屏**提供**清数据：那边数据库完好、重装新版即可恢复一切；这里 app 根本起不来，确定性循环没有别的应用内出口。所以它排在最后、写明会删除本机数据、且有确认。

**安全模式无需新处理**（看似漏洞实则自洽，特意说明）：`setSafeMode(true)` 只在与 `pendingShareFiles` 赋值同一个分支运行，所以 safe-mode ON 必然意味着对话框路径，而那条路径经 `finishClose` 清除它；`_safeMode` 是内存 `AtomicBoolean`，`killProcess` 之后自然归零。

> 对受影响用户：装上本版后如果启动仍然失败，屏幕上会显示具体原因——按「复制诊断信息」发过来即可，那正是下一个根因的直接证据。

## 3. 折叠展开后，运行中工具卡不再出现两次

**症状**：开启「AI 过程折叠」后，展开某个回合的折叠条，**正在运行**的工具卡同时出现在两个位置——列表内按时间顺序的位置，以及贴在视口底部的浮动条上。两者互不对应。

**根因**：`shouldShowProcessToolRow(…, processExpanded = true)` 让列表内按时间顺序恢复工具行；但 `isFloatingProcessTool` 只知道 `foldAiProcess`、不知道展开态，对 in-flight 工具仍返回 true → 视口底部浮动条又贴了一份。浮动条锚定的是**视口**而不是**回合**，这就是"位置不对应"。

**修复**：`isFloatingProcessTool` 增加 `processExpanded` 参数，展开时**列表赢**——浮动条存在的意义是把列表当前**藏起来**的活动浮出来，列表一旦显示，锚定在回合上的那一份才是对的。

- 两个调用点必须一致：`hasFloatingTools` 为浮动条预留 65dp 底部 padding，只修浮动条会让布局为一条不再渲染的 bar 保留空隙。`expandedProcessIds` 因此上移到两个调用点之前（同一 composable 作用域、同一个 `remember(sessionId)` key，是**纯移动**而非第二个状态源），并加入两处 `remember` / `LaunchedEffect` 的 key，展开时布局与浮动条同步重算。
- **折叠关闭（功能关闭）时行为一行未改**：那里的列表行由 `showCompletedToolCards` 决定，与折叠条无关，有测试钉住。

---

## 测试

- 全量 JVM 单测 **2148 个通过，0 失败 0 错误**（较 2.0.27 的 2143 增加 5 个折叠测试）。
- 新增/更新：`ChatFlatItemsProcessFoldTest`（展开态浮动条让位、折叠关闭行为不变、展开后行序 `header → thinking → tool → md → fold bar` 直接断言）。

## 安装与校验

- 包名 `com.openminis.linux`，启动器名称 **Minis Ultra**，架构 arm64-v8a。
- NDK r29 `29.0.14206865`；数据库版本 **21**（无迁移）。
- 签名与校验说明见 `docs/SIGNING.md`。
- 2.0.9 以来的完整中文更新日志见 `docs/CHANGELOG-since-2.0.9.zh.md`。
