# OpenMinis-Linux 2.0.26-linux

- versionCode **226**
- 数据库仍为 **21**（无迁移），从 2.0.25 直接覆盖安装
- 本版修的是**「模型列表拉不到、按刷新之后彻底空掉」**，与 2.0.25 修的是两个不同的缺陷

---

## 1. 强制刷新不再给目录 URL 追加查询参数（根因）

**症状**：自定义供应商添加时能拉到模型，一旦按下**刷新**就再也拉不到，选择器永久为空。

**根因**：`ModelListFetchIsolation.bustUrl()` 在 `forceRefresh = true` 时把 URL 改写成

```
/v1/models?minis_nocache=<instanceId>-<nanoTime>
```

而**严格按路径路由的网关会对任何查询字符串直接 404**。实测某公共中继：

| 请求 | 结果 |
|---|---|
| `GET /v1/models` | **200**，1711 字节，5 个模型 |
| `GET /v1/models?minis_nocache=abc` | **404**，响应体 0 字节 |
| `GET /v1/models?foo=bar`（任意参数） | **404**，响应体 0 字节 |
| `GET /v1/models` + `Cache-Control: no-cache, no-store` + `Pragma: no-cache` | **200** |

即：不是这个参数名的问题，是**查询字符串本身**的问题；而请求头形式的破缓存完全正常。响应头 `cf-cache-status: DYNAMIC` 也说明 CDN 根本没在缓存这个端点。

手动刷新走的正是 `refreshModels(instance, forceRefresh = true, clearFirst = true)`，所以必然命中。

**修复**：**删掉 `bustUrl`，目录 URL 永不被改写**；破缓存只走请求头（`noStoreIf` 原本就在发 `Cache-Control` / `Pragma` / `CacheControl.FORCE_NETWORK`）。6 个调用点全部更新（OpenAI 兼容、Anthropic、OpenRouter、Gemini、Antigravity）。Gemini 非 OAuth 路径的 `?key=<apiKey>` 是端点自身的鉴权参数，保留不动。

**为什么删掉是安全的**（原设计声称的收益经复核站不住）：

- 该参数的理由写在 KDoc 里：「防止按 URL 做键的中间层把并行刷新折叠掉」。但两个并行强制刷新是**完全相同的请求**（同 URL、同凭据），被折叠后拿到的答案对双方都是正确答案；而且它们的磁盘缓存本来就通过 `cacheKey(base|apiKey, instanceId)` 分开。
- 若两个实例共用地址但**凭据不同**，一个忽略 `Authorization` 的中间层本来就在把一方的目录泄漏给另一方——查询参数挡不住这种事，而 `cacheKey` 含凭据，本地缓存无论如何都是对的。
- 代价却是实测到的、彻底的：**严格路由网关上强制刷新永久失败**。

**回归测试**：新增 `ModelListUrlShapeTest`(5)。强制刷新路径断言 `RecordedRequest.path` **恰好**等于 `/v1/models`——`path` 含查询串，所以任何重新引入的 `?…` 都会当场失败；同时钉住破缓存改由请求头承担（`Cache-Control` / `Pragma`），避免以后有人以为"删了参数就没破缓存了"又把参数加回来。另外钉住两条既有不变量：base 已含 `/v1` 时不重复追加（否则 `/v1/v1/models` 同样 404），以及 base 不含 `/v1` 时仍会追加。

---

## 2. 刷新失败不再先清空模型列表

**症状放大原因**：`clearFirst` 在**网络调用之前**就删掉了该实例的全部模型条目。硬刷新确实必须先清——只有先清，`replaceEntries` 才不会把旧的 uuid / overrides / isHidden 继承过来，目录才是干净的——但提前清意味着**任何一次拉取失败都会把选择器清空**。

更糟的是 `clearFirst` 蕴含 `liveForce`，而 `liveForce` 为真时会**跳过 models.dev 兜底**直接返回 `FAILURE`。所以对着一个抖动或严格路由的网关按一次刷新，等于**摧毁**了本来要重新加载的列表，除了删掉供应商重加没有办法恢复。

**修复**：把清空**推迟到确实拿到替换数据的那一刻**，并保证最多执行一次（`hardClearIfNeeded()`）。

- **成功路径逐字节等价**：条目仍在 `replaceEntries` 之前被清掉，所以什么都不继承、`pruned` 依旧为空、`scrubExternalEntryRefs` 照旧按 `clearedIds` 执行。
- **失败路径不再破坏数据**：拉不到就什么都不动，原有目录留在原地，返回 `FAILURE` / `PRESERVED`。
- 覆盖三条会写入目录的路径：OAuth 静态列表、供应商 API 结果。（models.dev 兜底那条在 `liveForce` 下不可达，故无需插入。）

**测试说明（如实标注）**：这一条**没有新增单测**。`refreshModels` 需要 `Context` 与加密 prefs，本仓库既有约定是不为它搭测试台——`EmptyKeyRefreshTest` 的文档里明确写了「`refreshModels` itself needs a Context and encrypted prefs, so this covers the predicate the fix turns on rather than the coroutine around it」。本次改动是纯粹的**顺序调整**（成功路径可证明等价），据此遵循同一约定，不为此伪造测试台。第 1 条的根因则由 `ModelListUrlShapeTest` 端到端钉住。

---

## 与 2.0.25 的关系

2.0.25 修的是**另一件事**：网关间歇性返回 401「Invalid token」时，一次采样就被当成"密钥无效"，从而清缓存、返回空列表。那条修复（有界重试 + 只有最后一次尝试驱动缓存失效）依然有效且必要，但它救不了本版的场景——**404 是确定性客户端错误，按设计不重试**（重发一个字节相同的请求不会改变答案）。

两个缺陷叠加，才构成"添加时能用、一刷新就永久空掉"的完整现象。

---

## 测试

- 全量 JVM 单测 **2118 个通过，0 失败 0 错误**。
- 本版新增 `ModelListUrlShapeTest`(5)。

## 安装与校验

- 包名 `com.openminis.linux`，启动器名称 **Minis Ultra**，架构 arm64-v8a。
- NDK r29 `29.0.14206865`；数据库版本 **21**（无迁移）。
- 签名与校验说明见 `docs/SIGNING.md`。
- 2.0.9 以来的完整中文更新日志见 `docs/CHANGELOG-since-2.0.9.zh.md`。
