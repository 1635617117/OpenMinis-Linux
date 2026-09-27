# Minis Ultra 2.0.10

versionCode **210**，包名 `com.openminis.linux`。可覆盖升级已安装的 2.0.9（versionCode 209）。数据库仍是 19，不新增迁移，不改写 18 或 19。

这一版是行为改动，不是拆分。核心是沙箱内核：guest 里跑的命令现在按一张预算表上膛，调用方传入的超时不再决定上限。计划讨论换成有界的角色图。

## 沙箱预算表

一张表定死五档，`ShellTimeoutPolicy` 只从 `BudgetClassifier` 取数，调用方的 `resourceClass` 只能往上抬、不能往下压，传入的超时被忽略：

| 档 | 挂钟 | CPU | 地址空间 | 进程数 | 输出速率 |
| --- | --- | --- | --- | --- | --- |
| INTERACTIVE | 60s | 30s | 512MiB | 64 | 256KiB/s |
| NORMAL | 10min | 300s | 1GiB | 128 | 2MiB/s |
| BATCH | 20min | 600s | 2GiB | 256 | 8MiB/s |
| SERVICE | 30min | 不限 | 2GiB | 256 | 64KiB/s |
| SETUP | 30min | 不限 | 不限 | 256 | 64KiB/s |

SETUP 只认三个名字：`minis-dev-setup-full`、`minis-android-sdk-setup`、`minis-self-build`。命令里的单个 `&`、`nohup`、`setsid` 归 SERVICE，不是 SETUP。`2>&1` 是重定向，不会被误判成后台任务——这是这一版修掉的一个分类错误，它让普通的 `python3 x.py 2>&1 | tee log | head` 被抬到了 30 分钟。

## 一次性命令与持久 shell

一次性命令用 `GuardianScript.oneshot`：硬 `ulimit`，一个看门狗子 shell 在到期后 `kill -TERM -$$` 再 `kill -KILL -$$`，`trap EXIT` 只杀看门狗自己。全程不出现 `kill -0` 和 `kill -TERM 0`。`su -c` 走同一条路径，宿主侧墙钟取 min(档位, HOST_SU_MAX_TIMEOUT_MS)。

持久 shell 启动时设一次 `ulimit`、起一次监督进程，然后 `exec /bin/bash --noprofile --norc`。之后的每条命令只重装看门狗：不再套子 shell，不再设第二次 `ulimit`，不再挂 `EXIT` trap。调用 `wrapChild` 已经是编译错误。这一条直接修掉了「每条命令的执行结果只能从子 shell 的管道里读到」这个结构问题。

## 输出与界面

一次性命令的输出经 `StreamSink` 限流；持久 shell 的行回调走 20/s、突发 40 的令牌桶；`StreamSessionController` 的发布也走 `UIBus`，同一个桶。工具行更新不能没有令牌就发出去——2000 行 shell 输出曾经变成 45000 个主线程任务。流结束直接摘掉流式 id，不排队。流式结束不依赖行回调把消息喂完。

## 会话排队

`queueWaitMs(0)` 是 120 秒，配置范围 1–1800 秒。排队超时抛 `SlotQueueTimeout`，不是 `CancellationException`，所以它不会被当成用户取消吞掉。取消和正常释放走同一个 `releaseSlot`，超时的等待者会让位给下一个。

## 卡顿与补救

`HangDetector` 始终计数卡顿。原先的 FREEZE_GAP_CEILING 只影响 `writeStallSample` 的采样率，不再让长间隔漏计。`StallSignal` 每个 tick 都观察，低于 3 秒清零。补救循环只杀非 `terminal:` 根的 guest；用户的终端不在这条路径上。卡顿补救和内存回收都不再调用 `SandboxWorkload.stopAll`。

## 磁盘与驻留

驻留窗口按字节算，`HotWindow.RESIDENT_BYTES` 是 8MiB。溢出的仍是终端工具块（SUCCESS / FAILED / CANCELLED / TIMEOUT），落库走现有的 `ContextOffload`，不改写 `partsJson`。压力下先冻结再驱逐被 pin 的内容，但除最新一条外；`IdleSessionBudget.victims()` 仍然永不返回被 pin 的项。

## 宿主提权与 SecurityGate

`SecurityGateImpl` 对 shell 工具先过 `GuestWorkloadPolicy.hostRefusal`，宽范围的查找直接拒绝；宿主 `su` 和无作用域的 walk 不再沿用本会话的 YOLO 或同类工具放行，`su` 每次都问。

## 计划讨论角色图

`DiscussionGraph` 把共享白板换成有界的角色图：简报 → 方案 → 工程师与测试并行审查 → 最多修订一次 → 只由提出异议的角色复议 → 秘书写执行契约。缺裁决就算异议，含糊的回答不能一路通过。讨论阶段的工具保持只读，主会话按契约执行。秘书留在主模型上，因为执行契约的就是主模型。

## Rootfs SDK 工具安装

`RootfsManager` 抽成 `extractSdkZip` 加标记文件，版本相同就直接跳过。写 `source.properties` 时统一 `Pkg.UserSrc=false`（不再用 `Pkg.Desc`），`aapt2` 覆盖改由 `writeAaptOverride` 单独负责。失败重试两次，每次都清掉半成品目录和标记。

## 这一版没有做的事

- 不升数据库，迁移 19 不变。
- 不动 cgroup v2、PSI。
- 不引入第二种消息格式。
- 不从卡顿补救或内存回收里杀用户终端。

## 验证

- `:app:testDebugUnitTest` 38 项全过，含 `GuestWorkloadPolicyTest`、`KernelContractTest`、`ResourceBoundaryTest`、`SuCommandTest`、`ShellTimeoutPolicyTest`、`IdleSessionBudgetTest`。
- 发行包由 GitHub Actions 构建，产物挂在 `android-latest` 滚动预发布与本版本标签下。
- 尚未在真机重跑当初把主线程打满的那条命令。
