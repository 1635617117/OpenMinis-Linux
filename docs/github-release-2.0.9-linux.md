# Minis Ultra 2.0.9

versionCode **209**，包名 `com.openminis.linux`。可覆盖升级已安装的 2.0.8（versionCode 208）。数据库仍是 19，不新增迁移，不改写 18 或 19。

这一版只有机械拆分。公开签名不变，调用处仍用不带导入的同包名字。没有温度、准入、压缩或组包行为变化。

## 为什么拆

2.0.8 把温度守卫和绝对字节准入落地后，五个巨型文件还在原处。硬拆会改行为，所以 2.0.8 明确没做。2.0.9 只搬已经能整段搬走的函数：同一包里的扩展，调用处不用改写法。字段归属不确定的留在原类。只被搬走函数读到的私有成员，才从 private 放宽到 internal。只被搬走函数使用的私有常量或提示，跟着走，并保持 private。

每一批都先过 `:app:compileDebugKotlin`，再过全量 `:app:testDebugUnitTest`，然后单独提交。没有使用 `--no-verify`。

## 聊天

`ChatViewModel` 里能整段搬走的路径已经拆到同包文件。发送、重试、恢复、取消、排队注入、工具执行、压缩、标题、附件、斜杠命令、编辑截断、回退候选和行内错误都还是原来的函数，只是换了文件。

跟着走、仍保持 private 的只有压缩摘要提示，以及没有调用方的单步回退 `resolveNextFallbackProvider`。放宽可见性的成员只包括搬走函数真正读到的那些，例如 `visibleUserCutoff`、`effectiveAutoCompact`、`currentModelHasNativeVision`。

下面这些留在原类，不是漏拆：

- `rerunFromToolBlock`、`retryFromMessage`、`revertCompact`、`cancelStream` 是 override，不能改成扩展。
- `toChatMessages` 和 `toLLMMessage` 读 `mediaStore`。`loadOlderMessages` 调用的是类内成员扩展，搬走会改签名。
- `clearChat` 是公开成员，签名保持不动。
- `flush`、`sinceLastCheck`、`liveIncremental` 不是可搬成员。`sessionMemoryRepo` 只有约 8 行，不硬拆。

拆完 `ChatViewModel.kt` 仍约 5367 行，高于 4000 的目标。剩下的是上面这些不能搬的函数，继续切会改行为。

## 供应商与配置

OpenAI 的原始流、请求组包、用量解析、Codex 出图、图片类型识别和 HTTP 错误映射拆到同包扩展。调用处仍是 `parseChatCompletionsUsage`、`mapHttpError` 这种不带接收者的写法。

`generateVideoLocked` 和 `resolveVideoJob` 留在原类。它们调用的是类内成员扩展 `applyKeyAuth` 和 `Call.executeCancellable`，外部扩展看不见，放宽它们等于改签名。`refreshModels`、`editImage`、`rawPassthroughRequest` 是跨包公开成员，保持成员。

`ProviderRepository` 的加载、保存、双写镜像和备份导入导出已拆出。`replaceEntries`、`addInstance`、`ensureVoiceTemplateModels`、`reorderInstances` 以及语音选择相关的公开函数保持成员，避免跨包调用点要改导入。

拆完 `OpenAIProvider.kt` 仍约 1726 行，`ProviderRepository.kt` 仍约 2393 行。剩下的是公开成员和视频成员扩展，不硬拆。

## 界面

流式 Markdown 的解析、缓存、块渲染、表格、音视频和公式已拆到同包文件。`StreamingMarkdownText.kt` 约 1436 行，低于 1500 的目标。

`ChatScreen.kt` 仍约 7284 行。它几乎是一个巨大的 `ChatScreen` 组合函数，内部没有可整段搬走的局部函数。默认参数里的 `{}` 会让花括号扫描提前结束。硬拆这个组合函数会改重组和状态生命周期，这一版不做。

## 这一版没有做的事

- 不升数据库。迁移 19 仍只加 `body_bytes`、`body_ref`、`body_sha`、`preview`，不改写 `parts_json`。
- 不改温度：默认不发送，推理模型族不发，错误只剥字段重发一次，不改成 1。
- 不改绝对字节准入、启动路由、安装校验和无障碍线程约束。这些已在 2.0.8。
- 不把五个文件硬切到行数目标。行数目标让位于“只搬移、签名不变”。
- 不 push。

## 验证

- 每一批拆分：`:app:compileDebugKotlin --offline` 与全量 `:app:testDebugUnitTest --offline` 通过后才提交。
- 发行包：`:app:assembleRelease --offline` 已通过。产物 `src/android/app/build/outputs/apk/release/app-release.apk`，不入库。有 `src/android/release.keystore` 时用仓库上传证书，否则退回 debug 证书。
