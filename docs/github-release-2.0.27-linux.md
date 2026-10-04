# OpenMinis-Linux 2.0.27-linux

- versionCode **227**
- 数据库仍为 **21**（无迁移），从 2.0.26 直接覆盖安装
- 本版修两个缺陷，都是用户实测报出来的，都有可复现的证据

---

## 1. Content-Type 标错的流式响应不再崩溃

**症状**：与模型对话时报

```
Value data of type java.lang.String cannot be converted to JSONObject
```

**根因**（`OpenAIRawStream.kt`）：流式路径靠 **Content-Type 头**判断"网关忽略了 `stream=true`、返回的是单个 JSON 对象"。实测某公共中继对 `stream=true` 的响应：

```
HTTP 200
content-type: application/json          ← 头说 JSON
data: {"id":"5ddd6597…","choices":[…]}  ← 正文却是标准 SSE
```

**6/6 次都是这样**。于是 SSE 文本被喂给 `JSONObject()`，其分词器读到的第一个值是裸词 `data`，抛出上面那句异常并从 agent loop 逃逸成用户可见的原始报错。

这不是那家中继的怪癖：**任何隐藏或改写上游响应头的代理都会造成同样的错标**（nginx `proxy_hide_header`、各类网关配置、CDN 边缘规则）。

**修复**：**改为嗅探正文字节**，只有"头 AND 字节"都说是 JSON 时才走 JSON 分支。

- 用 `PushbackInputStream` 窥视最多 256 字节，判定后**原样推回**——所以 SSE 路径仍是真流式，不退化成整包缓冲（若改成先 `readText()` 再判断，这家中继的每一次流式回复都会失去增量输出）。
- 窥视的两个坑都处理了：
  - **不做逐字节 socket 读**（那会一个字节一次系统调用），改用批量读；
  - **部分读不会误判**——TCP 可能只交出 `dat`，据此判定就会错判成"不是 SSE"，所以会一直读到足以判定（前缀够长 / 出现换行 / 流结束）为止。
- 兜底：JSON 分支解析失败不再抛裸 `JSONException`，改抛 `LLMError.DecodingError`，消息里带上实际 Content-Type 与正文前 120 字符——把"分词器内部问题"换成"实际的不匹配是什么"。
- `data:` / `event:` / `id:` / `retry:` / `:`（SSE 注释与 keep-alive）都识别；前置空行与 `\r\n` 都能容忍；`{"choices":…}`、`[1,2,3]`、`<html>…`、`database dump`（只是前几个字母相同）都正确判为非 SSE。

**新增 `StreamContentTypeSniffTest`(13)**：SSE 配 `application/json`、配空 Content-Type、配正确的 `text/event-stream`；真 JSON 仍走 JSON 分支（这个分支存在的理由不能被嗅探破坏）；空正文报 `DecodingError` 而非裸异常；`looksLikeSse` 纯函数矩阵；`peekPrefix` 推回完整性（一个字节都不能丢）；以及**按 1/2/3/4/7/64 字节分片投递的流**仍被正确判定。

---

## 2. 401 不再一律说成「你的 API key 无效」

**症状**：选了模型对话，提示 `Invalid API key`——但 key 是好的。

**根因**：实测同一个 key、同一分钟内：

| 模型 | 连续 3 次 |
|---|---|
| `deepseek-v4.1-flash` | **200 · 200 · 200** |
| `deepseek-v4-flash-0731` | 401 · 401 · 401 |
| `deepseek-v4-pro-0813` | 401 · 401 · 401 |
| `glm-5.3` | 401 · 401 · 401 |
| `kimi-k3` | 401 · 401 · 401 |

而 `GET /v1/models` 用同一个 key **一直返回 200**。即：**按模型确定性失败**，5 个模型里 4 个的上游通道是坏的，凭据本身完全有效。

app 把这些 401 全映射成裸 `InvalidApiKey`，UI 显示 "Invalid API key"，提示还叫人去 设置→供应商 检查、或去控制台**重新生成一个没坏的 key**。真正该做的是换一个模型，而 app 无从得知。

**为什么不按响应体文案区分**：这两种 401 的正文确实不同（坏通道是 `Invalid token (request id: …)`，真错 key 是 `Unauthorized`），但那是**某一家的措辞**。同一类网关（one-api/new-api、LiteLLM、OpenRouter、多上游 nginx 前置）各家写法都不一样，按文案匹配只能覆盖恰好被测过的那家。

**修复用 app 本来就握有的、与厂商无关的证据**：*这个凭据刚刚被这个 host 接受过*。

- 新增 `CredentialAcceptance`：目录（`/models`）拉取返回 2xx 时，按 **host + 凭据指纹**记录（**不含模型**——因为变化的正是模型）。TTL **10 分钟**，有界 512 条；TTL 的意义是：会话中途密钥被吊销时仍会如实报"key 无效"，不会被几小时前的目录成功永久开脱。
- `ProviderKeyGate` 新增 `credentialKey(host, secret)` 与 `credentialScopeOf(gateKey)`。`normalizeModel` 顺手把 `|` 换成 `/`，让"丢掉最后一段"这个推导是**精确的**，而不只是"通常正确"。
- `LLMProvider` 新增带默认实现的 `credentialGateKey`（从既有 `callGateKey` 派生）——因此**没有任何 provider 需要改动**。
- OpenAI / Anthropic / Gemini 三家的 `mapHttpError` 在 401/403 时先查证据：
  - **有证据** → 归因为"模型被拒"。消息不再说 key 无效；提示改为「你的 key 没问题，服务器刚刚还接受过它；它拒绝的是**这个模型**，通常意味着网关为该模型配的上游通道坏了，或该模型不在你的套餐内。**换一个模型**；只有所有模型都失败时才去查 key」。
  - **无证据** → **完全保持原行为**（包括 403 那条"套餐/区域不允许"的既有提示）。
- 仍是 `isFallbackable`（模型组会继续尝试下一个成员），仍**不是** `isRetryable`（重试同一个坏模型没有意义）。

**新增 `CredentialAcceptanceTest`(12)**：注册表语义（TTL 边界、空 key 永不视为已接受、按凭据与 host 隔离、`forget`）、凭据作用域与模型无关、`|` 不能移动作用域边界、错误文案与提示、以及两个**端到端**用例（真实 `OpenAIModelsApi` + `OpenAIProvider` + MockWebServer）：目录 200 之后 chat 401 **必须**归因到模型；没有前置接受证据时**必须**仍归因到 key。

---

## 与 2.0.25 / 2.0.26 的关系

三个版本修的是三个**相互独立**的缺陷，叠加起来才构成"用不了"的完整现象：

| 版本 | 缺陷 | 表现 |
|---|---|---|
| 2.0.25 | 一次 401 采样就被当成密钥无效（目录路径），并清掉缓存 | 间歇性"取不到模型" |
| 2.0.26 | 强制刷新给目录 URL 加查询参数 → 严格路由网关 404；且刷新失败前先清空列表 | 添加时能用、按一次刷新就永久空掉 |
| 2.0.27 | Content-Type 标错的 SSE 被当 JSON 解析；chat 路径 401 归因错误 | 对话直接崩；选到坏模型时被误导去查 key |

## 测试

- 全量 JVM 单测 **2143 个通过，0 失败 0 错误**（较 2.0.26 的 2118 增加 25 个）。
- 本版新增 `StreamContentTypeSniffTest`(13)、`CredentialAcceptanceTest`(12)。

## 安装与校验

- 包名 `com.openminis.linux`，启动器名称 **Minis Ultra**，架构 arm64-v8a。
- NDK r29 `29.0.14206865`；数据库版本 **21**（无迁移）。
- 签名与校验说明见 `docs/SIGNING.md`。
- 2.0.9 以来的完整中文更新日志见 `docs/CHANGELOG-since-2.0.9.zh.md`。
