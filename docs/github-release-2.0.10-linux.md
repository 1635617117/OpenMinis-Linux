# Minis Ultra 2.0.10

versionCode **210**，包名 `com.openminis.linux`。可覆盖升级已安装的 2.0.9（versionCode 209）。数据库仍是 19，不新增迁移，不改写 18 或 19。

这一版是行为改动。核心是沙箱内核：guest 里跑的命令按一张预算表上膛，调用方传入的超时不再决定上限。计划讨论换成有界的角色图。

**注意**：这个标签曾指向一版有严重缺陷的构建（会话记录在发送后被抹掉、宿主扣了地址空间帽子后 shell 直接 EPERM 死掉）。那一版的发行版已删除，本标签现在指向修好的提交。同一 versionCode 的两个不同构建，装过缺陷版的人需要覆盖安装本版才能拿到修复。

## 地址空间上限交给宿主

宿主（HyperOS 与内存压力策略）会在新进程上压低 hard `RLIMIT_AS`，而且这个帽子跨 App 重启存活。App 侧的 `ulimit -H -v` 因此只有两种结果：多余，或者要抬高一个自己无权抬高的 hard limit 而拿到 EPERM。

2.0.10 第一版在 `GuardianScript` 里无条件下发 `ulimit -H -v ${budget} || exit 1`。宿主把 hard 压到 2 GiB 以下后，SETUP/BATCH 档的 2 GiB 抬高需要特权，EPERM 加上 `|| exit 1` 就是一条死掉的 shell。assets 里两处 `ulimit -v 262144 || exit 1`（`profile.d/minis-limits.sh`、`profile.d/minis.sh`）是同样的形态。

现在：

- shell 前缀不再下发任何 `ulimit -v`。地址空间完全由宿主决定。
- CPU、进程数、文件大小、core dump 一律 `|| true`。设限失败只记录，不再杀 shell。
- assets 两处硬编码的地址上限删除。
- `ProcessBudget` 删掉 `addressBytes` 字段，`ResourceLimits.GUEST_ADDRESS_BYTES` 与 `GuestLimits.addressLimitKiB` 一并删除。留下这个字段会让人以为 App 还在管地址空间。

## 聊天界面不再被改写

会话记录消失的原因是 `spillResident` 挂错了地方。它被 `trimLoadedWindow` 调用，而 `trimLoadedWindow` 在**每一次发送**时都跑（`ChatViewModelSendExt.kt:205`）。凡是工具输出超过 64 KiB 的块，内容被永久替换成 `[CONTEXT OFFLOADED] N chars at ...`，而渲染层没有任何地方还原这个前缀——只有 agent 循环认它。

同时 `trimLoadedWindow` 里新加的字节级丢弃循环会在普通发送路径上 `drop(1)` 丢整条消息，并递减 `loadedMessageOffset`，导致「加载更早」翻不回去。

现在：

- 界面永不重写。工具输出的冷存只作用在 `agentHistory`，那才是送进模型上下文的部分，也是 OOM 报告里 4562 条会话的实际持有者。
- `trimLoadedWindow` 恢复成只有 400 条上限的语义。
- 裁剪规则抽到 `ResidentWindow`，行为不变但可测。

## 字节上限之前是空转的

这一条是测试发现的，不是复盘发现的。

边界规则原本要求「保留的第一条完全不含 ToolResult」，理由是供应商会拒绝 `tool_use` 已被裁掉的 `tool_result`。但工具密集的会话里**每一条都含 ToolResult**，于是没有任何索引能当切点，`byteCut` 恒返回 0，字节上限从未生效。400 条上限同样受影响。

正确规则是：保留的第一条只要**自带对应的 tool_use** 就不算孤儿——agent loop 把一次调用的 use 和 result 放在同一条消息里，这是正常形状。改成 `hasResult && !hasUse` 才算非法切点。

同时修了 `byteCut` 的第二个缺陷：循环只从 `cut + 1` 找切点，跳过了 `cut` 本身。`[纯文本头, 裸结果, 裸结果]` 这种形状在 `cut = 0` 就合法，却被直接走过。

## 预算表

一张表定死五档，`ShellTimeoutPolicy` 只从 `BudgetClassifier` 取数，调用方的 `resourceClass` 只能往上抬，传入的超时被忽略。地址空间一列已移除。

| 档 | 挂钟 | CPU | 进程数 | 文件大小 | 输出速率 |
| --- | --- | --- | --- | --- | --- |
| INTERACTIVE | 60s | 30s | 64 | 256MiB | 256KiB/s |
| NORMAL | 10min | 300s | 128 | 1GiB | 2MiB/s |
| BATCH | 20min | 600s | 256 | 4GiB | 8MiB/s |
| SERVICE | 30min | 不限 | 256 | 4GiB | 64KiB/s |
| SETUP | 30min | 不限 | 256 | 8GiB | 64KiB/s |

SETUP 只认三个名字：`minis-dev-setup-full`、`minis-android-sdk-setup`、`minis-self-build`。命令里的单个 `&`、`nohup`、`setsid` 归 SERVICE，不是 SETUP。`2>&1` 是重定向，不会被误判成后台任务。

## 一次性命令与持久 shell

一次性命令用 `GuardianScript.oneshot`：进程组看门狗，到期 `kill -TERM -$$` 再 `kill -KILL -$$`，`trap EXIT` 只杀看门狗自己。全程不出现 `kill -0` 和 `kill -TERM 0`。`su -c` 走同一条路径，宿主侧墙钟取 min(档位, HOST_SU_MAX_TIMEOUT_MS)。

持久 shell 启动时设一次 rlimit、起一次监督进程，然后 `exec /bin/bash --noprofile --norc`。之后每条命令只重装看门狗：不再套子 shell，不再设第二次 rlimit，不再挂 `EXIT` trap。调用 `wrapChild` 已经是编译错误。

## 输出、排队与卡顿

输出经 `StreamSink`、持久 shell 行回调令牌桶、`StreamSessionController` 的 `UIBus` 三处限流，工具行更新不能没有令牌就发出去。流结束直接摘掉流式 id。

`queueWaitMs(0)` 是 120 秒，配置范围 1–1800 秒。排队超时抛 `SlotQueueTimeout` 而不是 `CancellationException`，取消和正常释放走同一个 `releaseSlot`。

`HangDetector` 始终计数卡顿，长间隔只影响日志采样。补救循环只杀非 `terminal:` 根的 guest，用户的终端不在这条路径上。

## 权限

YOYO 现在放行宿主 `su`。2.0.10 第一版对 `su` 无条件确认，出现在模式判定之前，和用户选的模式矛盾。

无界 `find` 不再无条件拒绝。它慢，但不可逆的破坏是另一回事：挂钟、输出速率、驻留窗口是慢的刹车。拒绝它等于把一个只读命令变成不可用。块设备写入和 `rm -rf /` 仍是硬拒绝——不可逆，用户没有界面能恢复。

## 计划讨论角色图

`DiscussionGraph` 把共享白板换成有界的角色图：简报 → 方案 → 工程师与测试并行审查 → 最多修订一次 → 只由提出异议的角色复议 → 秘书写执行契约。缺裁决就算异议，含糊的回答不能一路通过。讨论阶段的工具保持只读，主会话按契约执行。

## 验证

- `:app:testDebugUnitTest` 1788 项全过。
- 新增 `ResidentWindowTest`，锁住字节裁剪在工具密集会话上真正生效、以及界面块永不被改写。
- 新增两条 `ResourceBoundaryTest`，锁住不下发地址空间上限、rlimit 失败不 fatal、assets 脚本不再有 `|| exit 1`。
- 尚未在真机重跑当初压垮设备的命令。上面三个缺陷都是真机先撞出来的，单测只证明代码符合当时的理解。
