# Minis Ultra 2.0.8

versionCode **208**，包名 `com.openminis.linux`。可覆盖升级已安装的 2.0.7（versionCode 207）。数据库 18→19，只加列，不改写已有正文。

## 模型温度

推理模型（o1 / o3 / o4、GPT-5 族，以及中转站背后的同类）只接受“不发送 temperature”。带上任何其他值都会 400，错误原文是 `invalid temperature: only 1 is allowed for this model`。主聊天原先已经默认不发，但压缩、标题、进化和翻译各自硬编码或读错条目，一碰到这类模型就失败。

- 温度加在模型条目的 `ModelOverrides.temperature`，可空。空 = 不发送。不升数据库版本：它存在 `overrides_json` 里，旧配置没有这个键就还是空。
- 界面和组包都拒绝 NaN、Infinity，以及 0.0–2.0 以外的值。留空就是不发送。
- 已知推理模型族即使条目里填了温度也不发送。
- 中转站用自定义模型名，名单匹配不到。这时只剥掉 temperature 重发一次，不改成 1，也不把这次失败当成可重试的网络错误。
- 拒绝记忆只放进程内存，键是供应商实例加模型 id。重启后清空，不落库。
- 压缩回退和标题生成读自己的模型条目，不读聊天当前的 `activeOverrides()`。
- 会发送温度的供应商走同一个助手。聊天三处、Evolution、翻译、OpenAI 组包两处和错误自愈都接上了。

## 大会话不再把进程打满

旧的 400 条尾窗和“按设备内存百分比”都作废。限额是绝对字节，跟手机有多少内存无关。

- 界面预览 8KB。SQL 进 Java 的单格 64KB。解析输出 256KB、最多 256 个节点。
- 正文超过 2048 字节不再留在 SQLite 单元格里。新写入走 `filesDir/bodies/<sha>`，文件头带未压缩大小、压缩大小和编码。
- 不知道体积就拒绝。展开比超过 32，或声明的未压缩体积超过上限，展开前拒绝。失败不把原文留在堆里。磁盘满只存错误 stub，不退回全文。
- 写到一半被杀，下次打开丢掉临时文件，原行还在。
- 启动不压缩、不外置、不删除大会话。历史大行只在用户显式操作，或该行下次写入时外置。
- 迁移 19 只加 `body_bytes`、`body_ref`、`body_sha`、`preview`，用 SQL `length()` 回填，不改写 `parts_json`。`summary_chunks` 超顶时同一迁移加引用列。
- 热路径只取 id、role、时间、`body_bytes`、`body_ref`、preview。遗留大行按 64KB 一块读取。
- 聊天、导出、备份、fork、搜索、Evolution、日志、网络和 native 入口共用 8MB 的绝对准入额度。`onTrimMemory` 只推迟新的重任务并回收缓存，不提高额度。
- 客户进程的 `ulimit -v` 和 Node 的 `--max-old-space-size` 是固定值（256MB 地址空间、192MB 老生代），不是设备内存的 50%。
- 不捕获 OOM 或 `bad_alloc` 后继续。`set_terminate` 只把类型名写进静态缓冲，然后中止。

## 启动与安装不再连环误伤

- 自动进入的路由如果没有活过 60 秒健康 tick，下次不再自动进入。覆盖崩溃、卡死、silent kill，以及没有墓碑的看门狗重启。删掉了“silent kill 清零 restartCount 才允许回家”和“必须超过 3 次才回家”。
- rootfs 和工作区先在 staging 校验，通过才替换。短读不写已安装标记。`ZipException` 不在已安装树上重试。
- 工作区合并冲突写可恢复记录，不再用 `bytes=0` 报成功。
- 无障碍不再在主线程同步调用 `getSource()`。
- 网络抖动只合并连接状态，不清空连接池。
- 可选系统包不存在时，启动路径不抛 `NameNotFoundException`。

## 验证

- 单测覆盖：单格 100MB 拒绝、400 行按总字节停止、百万级短行装不下、压缩炸弹展开前拒绝、未知体积拒绝、并发准入耗尽、磁盘满不退回原文、写到一半被杀后临时文件丢弃、无墓碑重启后不自动重进。
- 源码扫描禁止热路径 `SELECT *`、无界 `readBytes` / `readText`，以及把全文直接交给 `JSONArray`。
- `:app:compileDebugKotlin` 与全量 `:app:testDebugUnitTest` 通过后再打这个版本。

## 这一版没有做的事

五个巨型文件的机械拆分没有放进 2.0.8。`flush`、`sinceLastCheck`、`liveIncremental` 不是可搬成员，`sessionMemoryRepo` 只有约 8 行。硬拆会改行为，所以留在原类。
