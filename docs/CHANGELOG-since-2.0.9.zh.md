# OpenMinis-Linux 更新日志（2.0.9 → 2.0.27）

基线 **2.0.9（versionCode 209）** 只做机械拆分：聊天、OpenAI、配置仓库与流式 Markdown 的可搬函数改为同包扩展，公开签名不变。以下按版本列出其后全部用户可见与工程变更。未列出的 versionCode 为滚动包中间态。

---

## 2.0.10（versionCode 210）— 沙箱预算内核

- 一张预算表（INTERACTIVE / NORMAL / BATCH / SERVICE / SETUP）统一决定挂钟、CPU 秒、进程数与输出速率；调用方自带超时不再各自为政。
- 地址空间上限交给宿主：宿主扣的 hard `RLIMIT_AS` 跨重启存活，App 再下发 `ulimit -H -v` 只会拿到 EPERM；rlimit 失败一律不致命，不再产生死 shell。
- 聊天界面不再被工具输出改写；字节上限的边界规则修正后，在工具密集会话上真正生效。
- 一次性命令走进程组看门狗；持久 shell 不套子 shell、不重复设 rlimit、不挂 EXIT trap。
- 输出经 `StreamSink`、行回调令牌桶、`UIBus` 三处限流；排队默认 120 秒，超时抛 `SlotQueueTimeout` 并让位。
- 安全边界：YOYO 放行宿主 `su`；无界 `find` 交给资源刹车；仅块设备与 `rm -rf /` 硬拒。计划讨论换成有界角色图。

## 2.0.12（212）— `@` 选择器收敛

- `@` 不再列出技能，技能只从 `/` 进入；群聊开启或已选其他模型时，`@` 只列群内模型，点名后仅该模型发言。

## 2.0.13（213）— 魔改 SystemUI 灵动岛止血

- HyperOS / ZUI 等定制 ROM 会把计时器、进度条等实时模板收进自己的岛并在系统界面进程里反复建视图，开久会撑崩 SystemUI。后台灵动岛改为一条稳定通知；原版 Android 16 仍走实时胶囊。状态栏图标改应用内遮罩。

## 2.0.14（214）— 长会话窗口化

- 长会话不再把中间对话静默截掉：界面只保留一段连续窗口，滑到任一端向另一端翻页，数据库仍存全文；超长消息从本地原文还原成可读正文，不再显示空泡。

## 2.0.15（215）— 双向翻页不丢边

- 向上翻不丢较新一侧、向下翻不丢较早一侧；翻页按用户轮次对齐，游标为 sort_order；边缘有加载中提示；滑到一半补上被切断的那一轮；窗口外原文收成摘录再进上下文，不静默丢弃。

## 2.0.16（216）— 翻页架构收口

- sort_order 统一为半开区间；DB 计数不再把聚合 UI 行当消息行；追加分配与写入串行化；加载行与新旧锚点分别保存偏移，滚动中稳定补偿。LLM 摘录只在请求时注入，不绘制、不落库。

## 2.0.17（217）— 会话尾部必带

- 打开会话与向上翻页都带着会话尾部；较新记录若在库中却不在当前窗口，按 sort_order 自动补进列表；右侧向下按钮回到真正的最新处。

## 2.0.18（218）— 群聊记录隔离

- 新开群聊只带当前对话，不再把上一场成员发言与主持汇报当作本轮记录；本轮无新发言时主持不再据此写共识/分歧/建议。

## 2.0.19（219）— 群聊主持收敛

- 主会话只负责开场与结束汇总，不再作为成员持续发言；其他模型按选择顺序轮流发言，后一位能看到前面的发言；单字回复不算发言，思考中有结论时用结论。

## 2.0.20（220）— 群聊讨论化、长会话分页架构、agent 工具链

- **AI 群聊**：同页群聊与厂商标记；从"会议纪要"改为多轮讨论（可见队列、辩论轮次）；主持只开场与收尾；按成员独立 token 预算（高 capacity 模型不再被低估）；可正常关闭并显示实时状态；修复 null session id 崩溃、群聊标志同步、安装可见性。
- **长会话**：窗口连续、双向不丢、paging 架构收口；load-older 药丸的死锁（tail-attach 锁阻塞按钮）、死循环（轮次对齐边界）、滚动跳变（翻历史触发尾附）三连修；会话尾加载替代"较新"芯片；forked SystemUI 上停止实时模板。
- **消息与记忆管线**：message transformer 链 + 4 个新 transformer；统一 LLM 错误分类与可操作提示；memory 写路径 poison-window 守卫；context-assembly 快照 UI。
- **工具与沙箱**：修复 `file_edit` 报成功但从未写盘（HasPersistableText）；构建负载 nproc 256→4096、输出速率 64→512 KiB/s；shell 启动 RLIMIT_AS soft-probe；`ui_action` GUI 代理工具与归档答案浏览器；JSON 递归环境变量脱敏；crash-recovery 提示；视频取消修复。
- **供应商**：provider key-pool UI 与粘性轮换、stream-trace replay；移除 shell 审批门。
- **推理标签**：全厂商拼写统一切分 + 未闭合兜底。
- **会话体验**：活跃会话重入钉住、键盘焦点、up-button 扫描；instant-thinking 占位（后因破坏 agent 流式而回滚）。
- **CI/工具链**：Go 1.26.8 钉版（rclone AAR 抗 sumdb 抖动）；NDK r29 钉版且不再把 r28 当已安装。

## 2.0.21（221）— 审查闭环、运行时三修、构建溯源

- **2026-10-02 代码审查全部 P0/P1/P2 闭环**：三处 guest 路径写盘改宿主路径（crash-recovery / stream-trace 从"从未工作"变为工作）；reasoning tag 变体单一来源；被削弱的测试断言恢复且更强；ProviderFactory 用 generation + 同锁复检防"迟到的 put 复活旧 provider"；ProviderKeyGate 只淘汰空闲桶；redact 深度超限降级后继续脱敏；§2.6 幂等改为 REQUEST-side only。
- **聊天结构重构四连**：flat-item key 单源（dedupe 后缀不再改 id）、ChatScrollPolicy 单一滚动权威、TimelineWindow 账本统一窗口游标与边缘、up-button 直接键跳（无 seek 扫描、single-flight）。
- **key-pool 轮换与熔断整体移除**，保留并发闸门；`invalidateAll` 保证删 key / 刷新模型后旧 provider 不存活。
- **运行时三修**：`SuPathPolicy` 改引号感知分词（YOYO+宿主 su 双条件按设计保留）；hard rlimit 先 soft 后 hard（此前 EINVAL 被静默吞掉，硬上限形同虚设）；`resource_class` 透传到实际看门狗（此前 heavy 无效）；双引号内命令替换可见。
- **构建溯源**：`GIT_SHA` / `GIT_DIRTY` 注入 BuildConfig 与环境横幅，"这个 APK 是哪个提交"不再靠安装时间猜；密钥脱敏收敛到单一入口。

## 2.0.22（222）— aarch64 构建链路修复、DNS 韧性、提示词缓存

- **修复入库即损坏的 `android-sdk-tools-aarch64.zip`**（P0）：512 KiB 插在中段且 EOCD 未回填，5 个 platform-tools 成员偏移错位；`unzip` 报 "possible zip bomb"，设备端流式解包在第 16 个成员抛异常后把已解出的 build-tools/platform-tools 全删 —— 每台真机都没有可用的 aarch64 aapt2。已按规范重建（CRC/大小/Unix 模式逐字节保留），新增 `scripts/repair_vendor_zip.py`（成员真不可恢复时拒绝输出残缺归档），生成脚本每次运行过完整性闸门，`VendoredAssetIntegrityTest` 做回归护栏。
- **运行时加固**：解包后校验 aapt2/zipalign/adb 真落地才写标记与 aapt2 覆盖；失败回滚改为精确（只删本次写出的文件）；留 `.minis-sdk-tools-error` 面包屑。
- **guest 工具链路径不再写死版本**：`RootfsManager` 与 `minis.sh` 原来钉 `build-tools/35.0.2`、`cmake/3.22.1/bin`，而 setup 装的是 3.31.6 → 大多数设备上 PATH 是死的；改为运行时解析（数值版本排序，两边一致）。
- **aarch64 宿主一键构建**：proot 需要 gawk 的 `strtonum`（mawk 必炸，PATH shim 只在 make 期间遮蔽）；CMake 精确钉 3.22.1 在 aarch64 无解（AGP 会去下 x86_64 包；Kitware 包无 ninja）改 `3.22.1+`；AGP 的 x86_64 aapt2 用 `-Pandroid.aapt2FromMavenOverride` 指向可运行的 aarch64 aapt2（SDK 优先、回退自带 zip）；`cmake.dir` 显式解析；废弃 `ndk.dir` 改符号链接进 `$sdk/ndk/<rev>`；自动初始化 `deps/proot`；脚本不再依赖文件执行位（丢执行位曾静默跳过 proot 与资产准备，产出"装得上但每条命令 [Shell not running]"的 APK）。
- **DNS 韧性**：resolv.conf 在系统 DNS 之后恒定追加公共兜底（`MAXNS=3` 截断、去重保首现），单个死 nameserver 不再等于全失败；`ensureResolvConfCurrent()` 挂在 proot 启动 choke point，30 秒节流比对磁盘与系统当前 DNS，不一致才重写 —— 覆盖"网络切换而回调漏一次"的陈旧窗口。
- **提示词缓存命中率**：WorldBook 注入从系统提示词头部移到动态尾组（关键词按轮触发，原先命中那一轮从第 0 字节 bust 整个前缀）；Anthropic 的 system 断点从"整块末尾"改打在稳定头末尾（`systemPromptStablePrefixLen` 穿透 provider），跨轮可命中最大前缀块；断点总数仍 ≤4。

## 2.0.23（223）— 六项产品修复

- **全局规则种子补齐**：`default_global.md` 原是占位符，改为真实跨会话硬约定；`DefaultSeedsTest` 防回空。
- **默认人格中文化**：`default_soul.md` 与兜底 `EMBEDDED_DEFAULT` 改中文；上一版英文种子逐字节登记为 `PREVIOUS_DEFAULT_SOUL`，升级只替换与出厂种子逐字节相等的文件，用户改过一字不动。
- **用量页唯一性**：桶键原来只有 modelId，同 model id 的不同实例账单被合并；查询补 `provider_instance_id`，聚合抽纯函数，桶键 = 实例|模型|归因态；显示名撞车加「· 实例标签」。
- **子代理完成态可见**：顶栏只渲染 RUNNING，而时间线卡片在顶栏开启时无条件隐藏 → 完成后两边都空；改为仅当 run 仍在 tracker roster（按 parentToolId，含 `#sub-`）时隐藏。
- **注入偶现失效（机制）**：MemoryRepository 六处非原子 `writeText` → top-level `writeTextAtomic`（tmp+rename）；四处 `catch{""}` 静默吞 IO 错 → 重试一次 + warning；XSessionDiag 加 personaChars/worldBookChars；SoulStore.load 与 persona 条目读取加重试。
- **子代理偶现启动失败**：`runOneSubAgent` 在重试循环前读 `config.value` 瞬时值，冷启动窗口 modelEntries 空 → 直接 "No model available"；改为空时 bounded 等待 `configLoaded`（5s）。
- 数据库升到 **21**，含 20↔21 双向迁移（装回旧包不炸库）；备份恢复回调允许 suspend、备份游标分配同步化；长会话历史与 agent 上下文保留修复。

## 2.0.24（224）— 会话级覆盖与注入可见性

- **会话页记忆面板改为"注入同源"视图**：旧实现读记忆目录里一个注入器根本不看的 `SOUL.md`，按供应商设置的人格永远显示成默认。现在显示：本会话实际注入的人格（标注生效级别：本会话覆盖 / 供应商选择 / 全局选择 / 默认）、应用级与 sessions 级 GLOBAL.md 分开列、以及本会话最近一次真实发出的系统提示词组装快照（已脱敏）——"注入是否生效"从此有确定答案。
- **会话级人格覆盖**：会话页编辑写入会话记忆目录的 `PERSONA.md`，优先级 **会话页 > 供应商单独设置 > 全局设置（含自定义人格）> 默认**；保存空内容清除覆盖、回落下一级；不碰供应商选择与全局人格文件。
- **会话级全局规则优先语义**：会话级片段注入头明确"与应用级冲突时会话级胜出、未提及处应用级仍生效"；应用级条目在会话页只读并指引 设置→记忆，保证会话页编辑只影响当前会话。
- 解析链抽纯函数 `resolveIdWithSource`，scope 按实际生效的 body 标注（normalize 把失效选择修到 builtin 时标"默认"）；`PersonaScopePriorityTest` 钉死四级优先级与会话级语义。

## 2.0.25（225）— 目录拉取韧性、流式卡死有界化、面板收敛、备份凭据

- **模型目录：一次失败不再等于「密钥无效」**：四个 `*ModelsApi`（OpenAI / OpenRouter / Gemini / Anthropic）此前把第一个非 2xx 当终局——401/403 即清本地模型缓存并返回空列表。已有供应商被 `refreshModels` 的 `isNotEmpty()` 闸门保住，所以看不出来；**新添加**的供应商则「保住已有的」等于什么都没有，密钥明明是好的选择器却永久为空，手动刷新走同一段代码同样无效。新增 `ModelListFetchRetry`：401/403 重试 1 次（700ms），429/5xx/传输失败重试至 3 次（500ms→1500ms，±20% 抖动），400/404/422 等确定性错误**不重试**（重发字节相同的请求不会改变答案，只会把配置笔误变成 25 秒卡死）；25 秒总预算耗尽即交回最后一次响应而非抛合成超时，调用方原有 `!isSuccessful`／缓存失效／`PRESERVED` 语义不变；**只有最后一次尝试**驱动缓存失效与用户可见报错。Anthropic 的候选 base 循环（最多 4 个）按候选数平分预算，避免最坏 4×25 秒。
- **`SharedHttpClients.default` 补显式超时**：此前是裸 `OkHttpClient()`——只有默认 10s 连接／读超时且**没有整调用上限**，与重试循环组合后最坏情况无界。改为连接 10s／读 20s／整调用 25s，并注明「这个客户端永远不做流式生成」（流式客户端各自持有 600s 读超时与流看门狗）。
- **xAI 动态目录测试按新契约更新 + 反向回归**：原测试断言「一次 401 即回落内置列表」，正是本次有意改掉的旧契约；改为耗尽预算后回落，并新增「一次 401 后恢复 200 必须拿到 live 目录而非退回手写种子列表」——退回种子列表即 GH#265（构建后发布的型号永远不可见）复发。
- **流式中途卡死有界化**：`firstEventWatchdog` 只守首个 chunk（其文档自陈「一旦有内容到达，看门狗退役，流跑到结束」），而全仓库**没有任何 chunk 间空闲看门狗**，于是唯一界限是传输读超时——`OpenAIProvider` 600 秒、Anthropic/Gemini 客户端 10 分钟；界面卡在「streaming」，且因**从不抛异常**，重试与回退一次都不触发。新增两阶段 `streamStallWatchdog`阶段 1 沿用 45s/90s；阶段 2 在流已存活后，chunk 间静默超过 **2× 首事件阈值**（90s/180s）即取消上游并抛 `TransientError` 交给既有重试链。阈值从既有常量派生，不引入第二个按厂商调的魔数；`idleTimeoutMs = 0` 关闭阶段 2，`firstEventWatchdog` 即如此委托，其契约测试原样通过。采用 1 秒刻度轮询重算截止点（`min(remaining, slice)` 封顶）而非逐 chunk 重启定时器：高吞吐流每秒数百 chunk，逐 chunk 取消重启的代价远高于每秒一次唤醒，短超时依然精确。已核对重试路径本就回滚本轮半成品块到 `turnStartBlockIndex` 并保留已读文本（`[T-android-fallback-text-rewind]`），中途卡死重试**不会重复内容**。
- **会话记忆面板收敛为「人格 / 规则」两行**：2.0.24 把 6 个条目平铺成同级（人格、应用级 GLOBAL.md、会话级 GLOBAL.md、提示词快照、今天日记、昨天日记）。改为分组而非删信息：主区只剩人格与规则两行，**规则可展开**显示原先那两个 GLOBAL.md（应用级 · 只读 / 本会话 · 可编辑，顺序与注入拼接顺序一致），折叠摘要由子条目派生因而不可能与展开内容不一致；记忆日记与诊断（提示词快照）各自独立分区。2.0.24 建立的注入同源语义完整保留。未配置文件在摘要中省略而非显示 0 行（`"".lines().size == 1`，直接数行会让从未动过的 GLOBAL.md 看起来像已配置），两者都未配置只显示一次「未设置」。
- **修掉 2.0.24 的 i18n 回归**：面板行标题此前**硬编码中文**，17 个非中文 locale 用户看到中文。改为字符串资源（英文默认 + 中文），其余 locale 回落英文。分组逻辑抽为纯函数 `groupAutoItems` / `rulesSummary`，不依赖 Compose 即可断言信息架构（旧的平铺渲染没有这样的接缝，行数只能靠肉眼检查）。
- **备份恒定包含凭据，去掉「必须加密」前置条件**：「包含凭据」此前是独立开关且与加密**双向互锁**（关加密强制关凭据、开凭据强制开加密），手动备份默认**不含**凭据；而 `BackupExporter.Options.includeCredentials` 本就默认 `true`，定时与 agent 备份一直带凭据——互锁只让手动导出成了例外，从这种备份恢复后所有供应商连不上，形同数据丢失。现去掉该开关与互锁，备份恒定包含凭据（API 密钥、OAuth 令牌、环境变量值、MCP 授权头）；加密开关回归「只决定包是否加密」，未加密时的明文警告保留且仍按同样三类（供应商 / 环境变量 / MCP 服务器）触发。清理失效偏好键与两条死符串（其副标题「Requires encryption.」在去掉互锁后已是错误陈述）。
- 新增 31 个测试：`ModelListFetchRetryTest`(13，含 MockWebServer 端到端)、`StreamStallWatchdogTest`(6)、`SessionMemorySheetGroupingTest`(11)，以及 `XAIDynamicCatalogTest` 的反向回归 1 个（401 后恢复 200 必须拿到 live 目录）。全量 JVM 单测 2118 个通过。

## 2.0.26（226）— 强制刷新不再破坏模型列表

- **根因：目录 URL 不再被改写**。`ModelListFetchIsolation.bustUrl()` 在强制刷新时追加 `?minis_nocache=<instanceId>-<nanoTime>`，而严格按路径路由的网关对**任何查询字符串**都返回 404（实测：`/v1/models` → 200；`/v1/models?foo=bar` → 404 空响应体；同样的请求只带 `Cache-Control: no-cache, no-store` + `Pragma: no-cache` → 200，且 `cf-cache-status: DYNAMIC` 说明 CDN 并未缓存该端点）。手动刷新走的正是 `forceRefresh = true, clearFirst = true`，所以必然命中——表现为"添加时能拉到模型、按一次刷新就永久空掉"。删掉 `bustUrl`，破缓存只走请求头；6 个调用点全部更新（OpenAI 兼容 / Anthropic / OpenRouter / Gemini / Antigravity），Gemini 非 OAuth 路径自身的 `?key=` 保留。原设计声称的收益（防中间层折叠并行刷新）经复核站不住：并行强制刷新是完全相同的请求，折叠后答案对双方都正确，且磁盘缓存本就按 `cacheKey(base|apiKey, instanceId)` 分开。
- **放大器：清空推迟到拿到替换数据之后**。`clearFirst` 原本在网络调用**之前**删掉该实例全部条目，而 `clearFirst` 蕴含 `liveForce`、`liveForce` 又跳过 models.dev 兜底直接返回 `FAILURE` → 一次失败刷新即摧毁列表，只能删供应商重加。改为 `hardClearIfNeeded()`，最多执行一次；**成功路径逐字节等价**（条目仍在 `replaceEntries` 前清掉，不继承 uuid/overrides/isHidden，`pruned` 依旧为空），失败路径不再破坏数据。此条无新增单测——`refreshModels` 需要 Context 与加密 prefs，遵循本仓库既有约定（见 `EmptyKeyRefreshTest` 文档），且改动是纯顺序调整。
- 新增 `ModelListUrlShapeTest`(5)：强制刷新路径断言 `RecordedRequest.path` **恰好**为 `/v1/models`（`path` 含查询串，任何重新引入的 `?…` 当场失败），并钉住破缓存由请求头承担、`/v1` 不重复追加也不漏追加。全量 JVM 单测 **2118 个通过**。
- 与 2.0.25 的关系：2.0.25 修的是间歇性 401 被当成"密钥无效"，本版修的是 404。**404 按设计不重试**（重发字节相同的请求不会改变答案），所以 2.0.25 的重试救不了本版的场景；两个缺陷叠加才构成完整现象。

## 2.0.27（227）— 流式 Content-Type 嗅探与 401 正确归因

- **Content-Type 标错的 SSE 不再崩溃**：`OpenAIRawStream` 原先只凭响应头判断"网关忽略了 `stream=true`、返回的是单个 JSON 对象"。实测某公共中继对 `stream=true` 返回 `content-type: application/json`、正文却是标准 `data: {…}` SSE（6/6 次），SSE 文本被喂给 `JSONObject()`，分词器读到的第一个值是裸词 `data` → `Value data of type java.lang.String cannot be converted to JSONObject` 从 agent loop 逃逸成用户可见报错。改为**嗅探正文字节**，只有头与字节都说是 JSON 才走 JSON 分支；用 `PushbackInputStream` 窥视最多 256 字节后原样推回，SSE 路径仍是真流式而非整包缓冲。窥视避免了两类坑：不逐字节读 socket（改批量读）；**TCP 部分读**（只交出 `dat`）不会误判为"非 SSE"，会读到足以判定为止。JSON 分支解析失败改抛 `LLMError.DecodingError`（带实际 Content-Type 与正文前 120 字符），不再抛裸 `JSONException`。识别 `data:`/`event:`/`id:`/`retry:`/`:`（注释与 keep-alive），容忍前置空行与 `\r\n`。任何隐藏或改写上游响应头的代理都会造成同样错标，故这是普适修复。
- **401 不再一律归因为「API key 无效」**：实测同一 key、同一分钟，`deepseek-v4.1-flash` 连续 3 次 200，而 `deepseek-v4-flash-0731` / `deepseek-v4-pro-0813` / `glm-5.3` / `kimi-k3` 各连续 3 次 401，且 `GET /v1/models` 一直 200 —— **按模型确定性失败**，5 个模型里 4 个上游通道是坏的，凭据完全有效。但三家 provider 的 `mapHttpError` 把 401/403 全映射成裸 `InvalidApiKey`，UI 显示 "Invalid API key"，提示叫人去检查/重新生成一个没坏的 key；真正该做的是换模型。**不按响应体文案区分**（`Invalid token (request id: …)` vs `Unauthorized` 只是某一家的措辞，同类网关各家写法不同），改用 app 本来就握有的厂商无关证据：新增 `CredentialAcceptance`，目录拉取 2xx 时按 **host + 凭据指纹**记录（不含模型，因为变化的正是模型），TTL 10 分钟（会话中途吊销仍如实报 key 无效）、有界 512 条。`ProviderKeyGate` 加 `credentialKey` / `credentialScopeOf`，`normalizeModel` 把 `|` 换成 `/` 使"丢掉最后一段"的推导精确而非仅通常正确；`LLMProvider` 加**带默认实现**的 `credentialGateKey`，故无任何 provider 需改动。有证据时归因为"模型被拒"并提示换模型，无证据时完全保持原行为（含 403 的套餐/区域提示）；仍 `isFallbackable`、仍不 `isRetryable`。
- 新增 `StreamContentTypeSniffTest`(13) 与 `CredentialAcceptanceTest`(12，含两个走真实 `OpenAIModelsApi`+`OpenAIProvider`+MockWebServer 的端到端用例)。全量 JVM 单测 **2143 个通过**。

---

完整逐提交历史见 `git log 074ecc5..HEAD`；各版本的发布说明另见 `docs/RELEASE-NOTES.zh.md` 与 `docs/github-release-<版本>.md`。
