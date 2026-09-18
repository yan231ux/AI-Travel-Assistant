# 智能旅行助手 ReAct 智能体优化方案

> 目标：把当前"首轮决策 + 规则补轮"的规划器，改造成市面上主流 ReAct 智能体的形态
> （**多轮自主决策 + 原生 tool calling + 失败回灌自纠**），并且做到"能讲清楚、对业务真有用"。
>
> 本文只描述方案，不含已实施代码。**实施前需确认**。

---

## 一、现状（基于真实代码核对，非推测）

核心文件：`backend/.../agent/TravelAgent.java`（1013 行）

| 阶段 | 当前实现 | 实际行为 |
|---|---|---|
| THINK | `think()` → iteration==0 走 `cachedPlanOrBuild()` → `buildPlanFromLLM()` | **只有首轮由 LLM 决策**；LLM 输出文本 JSON `{"tools":[{"tool":"..","query":".."}]}`，代码用 `parseToolCalls()` 正则截取 `{`…`}` 再反序列化 |
| THINK（补轮） | iteration>0 走 `buildGapFillPlan()` | **纯规则**：哪个数据源为空就补那个工具，query 是写死的字符串 |
| ACT | `executeTools()` | 并行执行（`CompletableFuture.runAsync` + `toolExecutor`，8s 总超时） |
| OBSERVE | `reflect()` | **规则是唯一裁决**：`hasPoi && (hasSearch \|\| hasRag) && hasWeather`；连展示文案都已不再调 LLM，改用 `buildRuleSufficientText()` |
| FINAL | `itineraryGenerator.generate()` | 生成结构化行程 |

### 关键发现：LLM 的"自主决策"被三层削弱

1. **只决策一次**——`think()` 里只有 `iteration == 0` 会问 LLM（`TravelAgent.java:310-318`）。
2. **决策结果被强规则覆盖**（`buildPlanFromLLM()`）：
   - 只要 LLM 选了 `amap_poi`，就被强制展开成 `景点 / 餐厅 / 酒店` 三类（`:429-436`），LLM 给的 category 基本被忽略；
   - 目的地是已知城市时，即使 LLM 没选 `rag_guide` 也强制追加（`:439-443`）。
3. **有兜底全量计划**——解析失败直接 `buildDefaultPlan()` 调全部工具（`:312-315`, `:453-468`）。

> **结论**：现在的"自主选工具"名不副实。它更像"LLM 提个建议，代码说了算"。
> 所以之前把"自主选工具"当创新点是站不住的，只能当技术实现说明。

### 客户端能力缺口（改造的硬约束）

`backend/.../client/LlmClient.java` 的 `chat(String prompt)`：

- 只发 **单条 `user` 消息**（`:104`），没有 `messages` 多轮上下文；
- **没有 `tools` 参数**，也没有解析 `choices[0].message.tool_calls`（`:136-146` 只取 `content`）。

也就是说：**当前根本不具备原生 tool calling 的接入条件**，改动 2 必须连客户端一起动。

### 现有测试的约束

- `TravelAgentTest` / `TravelAgentCallbackTest` 用 Mockito mock `LlmClient`，桩是 `when(llmClient.chat(anyString()))`。
- **新增一个 `chatWithTools(...)` 后，旧桩不会命中，默认返回 `null`** → 若不设开关，既有用例会被拖进新链路。
- 当前基线：后端 **612 用例全绿**，前端 `vue-tsc + vite build` EXIT=0。

### 当前配置

```yaml
# application.yml
llm:
  model: ${LLM_MODEL:qwen3-max}
  timeout-seconds: 180
  max-iterations: 3
```

---

## 二、与主流 ReAct 智能体的三点差距

| 维度 | 本项目现状 | 市面主流做法 |
|---|---|---|
| **决策轮次** | 仅首轮决策，后续纯规则补缺 | **每一轮**都把"上一步观察结果"回灌给模型，由模型重新决定下一步 |
| **决策形式** | Prompt 里手写工具目录，模型输出文本 JSON，代码正则解析 | **原生 function calling**：`tools` 用 JSON Schema 声明，模型返回结构化 `tool_calls` |
| **纠错闭环** | 工具失败只写进 `errors` / `gaps`，**从不回灌**；下一轮规则用同一个写死的 query 重试一次 | 把失败原因作为 observation 回灌，模型**自己换关键词 / 换工具** |

---

## 三、改造清单（三处，按风险从低到高）

### 改动 1：让补轮也由 LLM 决策（把观察结果喂回去）

**现状**：`buildGapFillPlan()` 是纯规则。

**改法**：`think()` 在 `iteration > 0` 时改为调用 LLM，把**本轮观察摘要**拼进 prompt：

```text
上一轮你调用了：web_search("成都 历史景点 美食 推荐")、amap_poi("成都 景点")
观察结果：
- web_search：返回 3 条（可用）
- amap_poi(景点)：返回 0 条（空）
- weather_forecast：未调用（当前无天气数据）
请判断还需要补充哪些工具调用，或直接回答 {"tools": []} 表示数据已足够。
```

- 涉及：`TravelAgent.think()`、新增 `buildGapFillPrompt(request, collectedData, lastPlan)`。
- **成本控制**：只在"上一轮确实有缺口或失败"时才发起补轮 LLM 调用；无缺口时沿用现有 `break`（`:231-234`），不会凭空多烧 token。
- **上限**：`max-iterations = 3`，最坏 3 次 think 调用（现在最坏 1 次）。

### 改动 2：切换原生 tool calling

**现状**：手写工具目录进 prompt + 文本 JSON 解析（`buildToolCatalog()` / `parseToolCalls()` / `extractJson()`）。

**改法**：

1. `LlmClient` 新增（保留旧 `chat(String)` 不动，`ItineraryGenerator` 等仍在用）：

```java
public record ToolCallResult(String id, String name, String arguments,
                             String content, int promptTokens, int completionTokens) {}

public ToolCallResult chatWithTools(List<Map<String,Object>> messages,
                                    List<Map<String,Object>> tools)
```

内部：`body.put("messages", messages)` + `body.put("tools", tools)`，解析 `choices[0].message.tool_calls[*].function.{name, arguments}`。

2. `TravelAgent` 用 JSON Schema 声明 4 个工具（`web_search` / `weather_forecast` / `amap_poi` / `rag_guide`），不再把目录塞进 prompt；
3. 模型返回的 `tool_calls` **直接**映射成 `SearchPlan.ToolCall`，删掉 `parseToolCalls()` / `extractJson()` / `isKnownTool()`。

- **收益**：不再依赖"模型恰好输出合法 JSON"。解析失败率归零，`buildDefaultPlan()` 兜底触发率大幅下降。
- **前提**：`qwen3-max` 在 DashScope OpenAI 兼容模式下支持 `tools`。✅ **2026-09-18 已实测确认支持**，见第十一节。
- **模型无关的硬要求**：不能因为接了 `tools` 就绑死模型。必须做"不支持就自动降级 + 按模型名记住"，见第十一节（使用者会频繁更换免费模型）。
- **保留兜底**：`chatWithTools` 失败/返回空 → 回落到现有 `buildDefaultPlan()`，与旧行为一致。

### 改动 3：失败回灌自纠

**现状**：`executeTools()` 把异常写进 `errors` + `collectedData.getGaps()`，但**没有任何路径把它交回模型**；补轮规则用同一个 query 重试（`buildGapFillPlan()` 里 query 是硬编码的）。

**改法**：把失败明细结构化后拼进下一轮 prompt：

```text
以下调用失败或无结果，请换关键词或换工具重试：
- web_search("成都 历史景点 美食 推荐")：返回 0 条
- amap_poi("成都 景点")：超时（>8s）
```

- 涉及：`TravelAgent.executeTools()` 记录 `ToolFailure(tool, query, reason)` 列表 → `buildGapFillPrompt()` 消费。
- **收益**：工具失败后仍拿到数据的比例上升，直接降低"生成失败 / 数据偏薄"概率。

---

## 四、怎么不破坏现有 612 个用例

**核心策略：加开关，默认关。**

```yaml
llm:
  agent-mode: legacy          # legacy（现状） | autonomous（新链路）
```

- 新增 `LLMConfig.agentMode`，默认 `legacy`；
- **612 个既有用例默认走旧链路**，行为比特级不变 → 回归风险最低；
- 新链路单独写用例覆盖（`AgentAutonomousModeTest`），并在实施后把开关切到 `autonomous` 跑一遍 `probe_generation_smoke.py`；
- 对外契约（`execute(request, callback)`、`AgentTraceResponse`、SSE 事件名）**完全不变**，前端零改动。

> 这个开关同时是答辩资源：正好支撑"三种方案对比"的实验设计（见第七节）。

---

## 五、与"个性化可复现"的冲突，以及解法

### 冲突从哪来

- **全自主** = 同一输入，模型每轮自由决定调什么工具 → 两次生成可能调不同工具、拿到不同候选池
- **个性化卖点** = "同一画像 + 同一需求 → 结果可复现、变化可归因"
- 全自主给系统引入了**第二个自变量**（工具选择），导致结果变化时说不清是"画像变了"还是"模型这轮选了别的工具"

### 解法：只自主一次，采集结果存档复用

1. **第一次生成**：走完整自主链路（多轮决策 + 自纠），得到最终工具计划与观察结果；
2. **落库存档**：把"最终生效的工具计划 + 关键观察摘要"随行程一起存下（现成的 `candidate_evidence` / trace 落库链路可直接扩展）；
3. **再次生成**（同用户 + 同目的地 + 同偏好）：**直接复用存档计划**，把 trace 里标注为"沿用上次成功的采集方案"。

于是：

- 个性化排序的**输入（候选池）稳定** → "可复现、可归因"仍然成立；
- 演示时观众依然能看到"模型多轮自主决策"的过程；
- 现在的内存 `planCache`（`TravelAgent.java:66-67`，TTL 1h）就是这条路线的雏形，只是要从"内存缓存"升级为"可解释的持久化复用"。

---

## 六、业务收益怎么量化（用户要求：对业务真有用）

| 改造 | 业务问题 | 量化指标 | 对照方式 |
|---|---|---|---|
| 改动 1 每轮决策 | 未收录城市 / 冷门目的地数据总是偏薄 | 生成成功率、POI 覆盖类别数 | 同一批目的地（含 1 个库外城市如大连），`legacy` vs `autonomous` |
| 改动 2 原生 tool calling | 模型输出非法 JSON 导致静默兜底、白烧 token | think 阶段 token 数、think 阶段耗时、`buildDefaultPlan()` 触发次数 | 同一批请求跑两遍对比 |
| 改动 3 失败回灌 | 工具超时/空结果时数据直接缺失，行程质量塌陷 | 工具失败后仍获得有效数据的比例、单次生成失败率 | 注入一个必失败的 query 做对照 |

> ⚠️ **"成本更低"这条不要笼统宣传**。批次 1 实测显示：在有缺口的场景里，autonomous 的 think token 是 legacy 的 **3.3 倍**（详见第十节）。它买到的是"更聪明地绕开失败"，不是"更便宜"。

采集方式：现有 trace（`AgentTraceStep`）已记录每轮 `toolCalls` 与 observation，
`TokenUsage` 已按 planner/rewrite/embedding 分项累计，**无需新增埋点即可统计**。

---

## 七、实施顺序与验收

```text
批次 1（低风险，只动 TravelAgent + prompt 层）
    改动 3 失败回灌  →  改动 1 每轮决策
    验收：mvn.cmd clean test 612+ 全绿；probe_generation_smoke.py 通过

批次 2（结构改动，动 LlmClient）
    改动 2 原生 tool calling（需先探针验证 qwen3-max 支持 tools）
    验收：同上 + 前端 vue-tsc && vite build EXIT=0

批次 3（收尾）
    agent-mode 默认切到 autonomous；三方案对照实验数据采集
```

每批完成后：`git commit` → 打 `v1.6 / v1.7` tag → `push` 存档。
（本机 `credential.helper=manager`，前缀 `GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never` 可免交互推送。）

### 三方案对照实验（答辩可直接引用）

```text
A. legacy            —— 首轮决策 + 规则补轮（当前线上行为）
B. autonomous        —— 多轮自主决策 + 原生 tool calling + 失败自纠
C. autonomous + 复用 —— B 的基础上，二次生成复用存档计划（验证可复现性）
```

统计：生成成功率、POI 覆盖类别数、think 阶段 token/耗时、工具失败恢复率、**同输入两次生成的结果一致率**。

---

## 八、风险与对策

| 风险 | 对策 |
|---|---|
| `qwen3-max` 不支持或支持不完善 `tools` 参数 | 批次 2 前先跑探针；不支持则停在改动 1+3（仍能显著改善），改动 2 降级为"保留文本 JSON 但优化 schema" |
| 多轮 LLM 调用推高 token 与延迟（延迟已在 180s 读超时边缘） | 只在有缺口/失败时才发起补轮；`max-iterations` 保持 3；批次 1 完成后实测 think 阶段耗时再决定是否放批次 2 |
| 新链路破坏既有 612 用例 | `agent-mode` 默认 `legacy`；对外契约不变；新链路单独用例覆盖 |
| 推理模型长输出超时（本项目已有前车之鉴） | 换模型必跑 `probe_generation_smoke.py`（TROUBLESHOOTING §21） |

---

## 九、答辩口径（三条，尽量口语化）

1. **多轮自主决策**——"不是一上来就把所有工具查一遍，而是先查一批，看缺什么，再决定下一步查什么，最多三轮。"
2. **原生工具调用**——"工具的能力用结构化协议告诉模型，模型直接返回'我要调哪个工具、参数是什么'，不用我们去猜它写的 JSON 对不对。"
3. **失败自纠**——"某个数据源没返回或者超时了，模型下一轮能看到失败原因，自己换个关键词再试，而不是直接放弃或者重复打同一个请求。"

**注意**：不要再说"模型自主选择调用哪些工具"——改造前这句话不成立，改造后也只在多轮决策与自纠这个意义上成立。

---

## 十、批次 1 实测结果（2026-09-18，已实施完成）

### 实施内容

| 项 | 落地 |
|---|---|
| 开关 | `LLMConfig.agentMode` + `application.yml: llm.agent-mode: ${LLM_AGENT_MODE:autonomous}`，**默认 autonomous**（2026-09-18 起；想回退设 `LLM_AGENT_MODE=legacy`） |
| 改动 1 每轮 LLM 决策 | `TravelAgent.think()` 在 `iteration>0` 且 autonomous 时走 `buildGapFillPlanByLlm()`；legacy 仍走 `buildGapFillPlan()` 规则 |
| 改动 3 失败回灌 | 工具调用返回 `ToolOutcome(tool, query, ok, reason)`；`buildGapFillPrompt()` 把"上一轮调用明细 + 失败原因"拼进提示词，并要求换关键词 |
| 不重复采集 | `isSourceSatisfied()` 过滤已满足的数据源；`missingPoiBuckets()` 按标准桶精确补缺口 |
| 护栏 | LLM 补轮不可用 → 回落规则补轮；终止判定仍由 `reflect()` 规则裁决 |
| 未做（批次 2） | 原生 tool calling（`LlmClient.chatWithTools`）——需先验证 qwen3-max 的 `tools` 支持 |

### 验证

- `mvn.cmd -o clean test`：**616 用例全绿**（610 原有 + P0 修复 2 + 本次新增 4），BUILD SUCCESS。
- 新增单测 `TravelAgentAutonomousModeTest`（4 例）：失败原因确实进提示词、已满足数据源被过滤、LLM 不可用回落规则、legacy 补轮不咨询 LLM。
- 新增对比探针 `backend/probe_react_autonomy.py`：8080(legacy) vs 8081(autonomous)，同一请求（北京，日期故意超出天气预报窗口以制造确定性缺口）。

### 实测对比

| 指标 | legacy | autonomous |
|---|---|---|
| `plan_search` 轮数 | 3 | 3 |
| 第 2 轮 | 规则：`web_search("北京 旅行攻略 贴士")` + `weather_forecast("北京")` —— **原样重试失败接口** | LLM：`web_search("北京 10月中旬 天气预报 2026")` —— **改走搜索兜天气** |
| 第 3 轮 | 规则：`weather_forecast("北京")`（第三次撞同一面墙） | LLM：`weather_forecast("北京")` |
| think token | prompt 475 / completion 69 | prompt 1580 / completion 117 |
| 总耗时 | 97.4s | 97.6s |
| 结果 | success，2 天行程 | success，3 天行程 |

### 结论（诚实版）

1. **真实买到的收益是"失败自纠"**：legacy 会拿同一个 query 撞同一面墙三次；autonomous 第 2 轮就换了一条路（用搜索兜天气信息）。这是可演示、可复现的差异。
2. **代价是 think token 上升**（本次 3.3 倍）：有缺口的场景里每轮补轮都多一次模型往返。总耗时几乎不变（被约 90s 的行程生成淹没）。
3. **"覆盖更广 / 成本更低 / 成功率更高"三条里，"成本更低"目前不成立**，不要这么说；另两条需要在更多场景采样后才能给结论（当前样本 n=1）。
4. **默认仍为 legacy**，线上行为零变化；切 autonomous 只需 `LLM_AGENT_MODE=autonomous`。
5. **行程天数 2 vs 3 不可作为质量对比**——两者是同一次 LLM 生成的不同采样，样本量不足以归因。

### 下一步（待确认）

- **可选调优（若嫌 token 贵）**：把 autonomous 的补轮限制为"仅第一次补轮由 LLM 决策，其后回落规则"，用 `max-iterations=3` 的既有上限兜住。需要先明确是否接受"少一次自纠机会"。

---

## 十一、批次 2 实测结果（2026-09-18，已实施完成）

### 核心约束（用户明确要求）

> "我到时候可能会比较频繁的更换模型，因为我都是选免费模型来白嫖，尽量别只适配一种模型。"

因此批次 2 的设计目标不是"接上原生 tool calling"，而是**"接上原生 tool calling，同时保证任何一个 chat 模型都能跑"**。

### 实施内容

| 项 | 落地 |
|---|---|
| 客户端能力 | `LlmClient.chatWithTools(messages, tools)` → `ToolChatResult(toolCalls, content, rejectedTools, tokens)`；解析 `choices[0].message.tool_calls` |
| 识别"不支持" | 4xx 且响应体含 tool / function call / unsupported / not support → `rejectedTools=true` |
| 按模型名记住 | `Map<模型名, 已拒绝>`，**由 LlmClient 自己写入**（调用方忘调也不会每轮白试）；换模型自动重新探测 |
| 不误伤 | 5xx / 超时走重试，失败返回 null 但**不写能力缓存**（故障 ≠ 能力不足） |
| 三态开关 | `llm.tool-calling` = `auto`（默认）/ `native` / `text` |
| 保底路径 | 模型不支持 / 没回 tool_calls / 调用失败 → 一律退回文本 JSON 计划，功能不受影响 |
| 生效范围 | 仅 `agent-mode=autonomous`；legacy 保持改造前行为 |
| 共用归一化 | `fillPlanFromCalls()` 被文本路径与原生路径共用（缺口过滤 / 类别桶 / 强制不变量行为一致） |
| 去重 | `plannedPoiBuckets` 保证"每类只规划一次" |
| 去重复计费 | 原生路径不再把工具目录塞进提示词（描述已由 `tools` schema 承载） |

### 实测发现的真实缺陷（不是推理出来的）

1. **重复规划**：模型一次返回**两个** `amap_poi` 调用，而归一化对每个调用都展开标准三类桶 → 实测打出 **6 次** amap 调用（`景点/餐厅/酒店` × 2）。
2. **工具说明发两遍**：提示词里的工具目录 + 请求体的 `tools` schema → 同一份描述计费两次（该轮 `prompt_tokens=3296`）。

### 验证

- 全量 `mvn.cmd -o clean test`：**626 用例全绿**（批次 1 后为 616，本批新增 10）。
- 新增单测：`TravelAgentAutonomousModeTest` 4→11 例、`LlmClientTest` 4→7 例（覆盖原生生效、降级、按模型名记住、5xx 不误判、强制 text/native、legacy 不碰原生、重复 amap 去重）。
- 真实模型实测（qwen3-max，8081 `autonomous` + `auto`）：
  - trace = `LLM 原生工具调用: rag_guide, weather_forecast, amap_poi`
  - `amap_poi` **正好 3 次**（去重生效）
  - 第 2 轮把天气换成 `weather_forecast("北京 2026年10月")`（自纠仍工作）
  - `prompt_tokens` 3296 → **2429**（修复去重后 -26%）
  - 结果：success，3 天行程，94~95s

### 三种形态的成本对比（同一请求，各一次采样）

| 形态 | think `prompt_tokens` | 说明 |
|---|---|---|
| legacy（文本 JSON，规则补轮） | 475 | 只问一次模型 |
| autonomous + 文本 JSON | 1580 | 每轮补轮多一次模型往返 |
| autonomous + 原生 tool calling | 2429 | 额外承担 `tools` schema 的开销 |

**注意：原生 tool calling 不省 token**。它买到的是**结构可靠性**（不再依赖"模型恰好输出合法 JSON"），不是成本优势。答辩时别把这两件事混着说。

### 结论（诚实版）

1. **"不绑死模型"已落地**：任意 chat 模型都有一条能跑通的路径（`text`），工具调用能力强的模型自动用原生，不支持的自动降级且被记住。
2. **qwen3-max 确认支持 `tools`**（实测，非查文档推断）。
3. **成本排序**：legacy < autonomous+text < autonomous+native。要省钱就别开 autonomous。

---

## 十二、批次 3 实测结果（2026-09-18，已实施完成）

本批次做两件事：**① 采集方案存档复用**（第五节那条冲突的正式落地）+ **② 压掉自主模式的 token 放大**。

### ① 采集方案存档复用：「只自主一次」

**做了啥**：新增 `agent_plan_archive` 表 —— 按 `plan_key`（用户 + 目的地 + 偏好 + 节奏 + 酒店档次 + 饮食 + 特别要求）存"首次生成最终生效的首轮采集方案"，含来源、工具数、观察摘要、复用计数。同条件再次生成**直接复用**，不再问模型。

**为什么这么设计（三条硬决策）**：

| 决策 | 原因 |
|---|---|
| 复用键排除日期/人数/预算 | 这三者不改变"调哪些工具"，纳入只会降低命中率 |
| **规则兜底方案（source=rule）不落库** | 否则一次偶发 LLM 抖动会被复用 7 天，把所有人钉在"默认全采集" |
| 只归档首轮、不归档补轮 | 补轮是失败驱动的自适应层，冻结它等于冻结系统对临时故障的反应 |

**真实链路验证**（8081 autonomous）：

- Phase 1 首次生成 → 落库：`plan_key=111|北京|[自然风景, 拍照]|适中|舒适型|null|null`，`source=autonomous-native`，`tool_count=5`，`reuse_count=0`。
- **重启后端**（清空内存缓存）→ Phase 2 再生成 → trace 第 1 轮 = `LLM 原生工具调用: …；沿用上次成功的采集方案（存档复用第 1 次）`，5 个调用与 Phase 1 **完全一致**，`reuse_count → 1`。
- `mvn.cmd -o clean test` → **633 用例全绿**（626 + 7 新增）。

### ② 压掉 token 放大

自主模式的 token 放大来自"每轮补轮都问一次模型"。加 `llm.llm-gapfill-rounds`（默认 **1**）：**只有第一次补轮需要模型判断**（这时"失败自纠"才有价值——模型看到失败原因换关键词），后续补轮改走规则（同一件事重复付费，收益递减而成本线性上涨）。

### 三方案对照（同一请求：北京，2026-09-28 ~ 09-30，2 人，5000 元，自然风景+拍照）

| 方案 | think `prompt_tokens` | plan_search 轮数 | 复用 | 耗时 | 说明 |
|---|---|---|---|---|---|
| A. legacy | 475 | 1 | 否 | 84.6s | 只问一次模型；两次采样都是 475，与第十节交叉一致 |
| B. autonomous | 1575 | 2 | 否 | 129.6s | 首轮原生 tool calling + 一次补轮自纠 |
| C. autonomous + 复用 | **0** | 1 | **是** | 99.7s | 首轮直接命中存档，**think 的模型调用为 0 次** |

**怎么念这组数（诚实版）**：

1. **C 的 0 是真的**：复用命中后首轮不问模型；`reflect` 早已是纯规则（不调 LLM）；本轮又没出现缺口 → think 阶段模型调用为 0。**这是"压 token"最实在的一刀**，比在 prompt 里抠字有效得多。
2. **legacy 仍然最省**（475）。自主模式买到的是"更聪明地绕开失败"，**不是更便宜**；复用只是把它的成本拉回接近 legacy，同时**换来候选池稳定**。
3. **C 的 0 不能宣传成"永久 0"**：只有"同条件重复生成"才命中；新用户 / 新目的地 / 新偏好仍要走一次完整自主决策。
4. **残余变量要如实说**：B 走 2 轮、C 走 1 轮，差异来自"当轮是否出现数据缺口触发补轮"。复用冻结的是**首轮主干方案**，补轮保持自适应。所以准确表述是"**候选池主干稳定 + 排序输入可归因**"，不要说成"两次结果逐字节一致"。
5. **附带发现（已定位并修复，2026-09-18 第二次复查）**：legacy 采样出现过一次「生成行程超时」导致 `success=false`。初次记录时判断为"主生成逼近 180 秒阈值"，**复查证明这个判断是错的**——真凶是**模型在 JSON 字符串值里输出了一个裸换行**（`CTRL-CHAR code 10`）→ 解析失败被判"格式坏了"→ 回传 LLM 修正 → 而**修正步的预算是 20 秒**，对"重出一整份行程 JSON"这种工作量结构性不够 → 修正步超时 → 整个请求失败（主生成其实只用约 68 秒就成功了）。"重试即成功"只是因为下一轮模型恰好没夹带裸换行，属于**随机复现的雷**。修法两层：① 本地按状态机转义双引号内的裸控制字符（`escapeRawControlChars`），第一次解析就通过、修正步不再触发；② 修正步预算 20 → 60 秒兜底。详见 TROUBLESHOOTING §36。
6. **未验证项**：真实"不支持 tools 的模型"没有条件实测，该路径目前仅由单测覆盖（模拟 400 拒绝 + 5xx 不误判）。换到具体免费模型时，第一次生成看 trace 里是"原生工具调用"还是"基于工具目录制定计划"即可判断走到了哪条路。

### ③ 默认模式切到 autonomous（2026-09-18）

批次 3 收官时 `agent-mode` 默认仍是 `legacy`（有意保留，因为切默认属于产品决策）。现确认常用模型 `qwen3-max` 的原生工具调用链路稳定，**默认值已切为 `autonomous`**：

- `application.yml`：`llm.agent-mode: ${LLM_AGENT_MODE:autonomous}`，`LLMConfig.agentMode` 字段兜底值同步改为 `autonomous`（避免配置项缺失时静默回退成旧行为）；
- **回退成本为零**：设环境变量 `LLM_AGENT_MODE=legacy` 即可，无需改代码重打包；
- 切换前已确认三件事：`llm-gapfill-rounds=1` 给补轮封顶、采集方案存档复用把同条件二次生成压到 0 token、`tool-calling=auto` 保证换模型时不支持 tools 也能自动降级。

> ⚠️ 答辩时**别把这次切换说成"变便宜了"**：默认走 autonomous 后单次生成成本确实比 legacy 高（think token 约 3.3 倍，表格里的 A/B 对照就是证据）。它换来的是"失败自纠"和"更完整的候选池"；成本靠 `llm-gapfill-rounds` + 存档复用压回来，而不是靠模式本身。

### 答辩可直接用的一句话

> 全自主让 Agent 更聪明，但也让它"每轮可能选不同工具"，这会破坏个性化"可复现、可归因"的卖点。我们的做法是**只自主一次**：首次生成把胜出的采集方案存下来，同条件下的第二次生成直接沿用，候选池就稳了；顺带把 think 阶段的模型调用从 1575 token 打到 0。**换句话说，自主性留给了"第一次"，确定性留给了"复现"。**


## 十三、批次 4（2026-09-18，主题不是 ReAct）：生成后一致性修复

**起因**：拿一份真实生成结果（三亚 · 5 天 · ¥8000）逐屏连读，发现的问题**没有一条是"事实错误"**——景点真、餐厅真、价格对量级；错的是**行程内部的互相引用**。五条现象、两条根因、四处修复，详见 **TROUBLESHOOTING §37**（那里是完整叙事版，本节只留要点，避免两处口径分叉）。

### 现象与根因（一句话版）

| 现象 | 根因 |
|---|---|
| 旅行提示「780 元/晚、占 39%」vs 预算明细「¥200/晚、10%」 | **数据被改了，引用它的文案没跟着改** |
| 交通段 4 次拿「绿茶餐厅」当端点，而它从来不是任何一餐 | 餐次改名后，**引用旧名字的交通段端点没人改** |
| 亚龙湾那天没排午餐、第 3/4 天没排晚餐 | **约束只在候选排序阶段生效，生成后没人校验结构是否闭合** |
| 「鹿回头」同样"去过"却照样排进，页面还宣称"已减少重复" | 去重只降权重不改结果，**文案在邀功** |
| 5 段交通只有机场段有里程，页面却写「合计 11.2 km」 | 把"部分段之和"标成"合计" |

### 修复清单（全部确定性本地修复，不回传 LLM）

1. `1.7 历史去重落地为"不安排"`：候选池先剔历史去过的 → 逐天替换 → **点名的景点不排除** → 换不到就如实说明原因；
2. `2.6 每日餐次完整性`：缺午餐/晚餐就用**离当天活动区最近**的真实餐厅补位（复用就餐就近的同一套判定，所以补出来必然空间自洽），返程日不补晚餐；金额按全程餐费均值给，无数据**不编价格**；
3. `4.2 交通段端点对齐`：端点必须是当天真实落地的地点或机场/车站；够像就改写，找不到就**删段并注明原标注**；
4. `6.2 住宿口径收口`：清掉 LLM 写的住宿金额/档次断言，改由**系统按最终数据重述一条**（必须放在 4.5/5/6.1 之后）；
5. 配套：**改名传播**（换位时同步改交通段端点与备注引用，"幽灵餐厅"的正解）+ **换位清旧数据**（简介/图片/推荐菜等描述被换掉那个地方的内容一律清空）；
6. 收尾层「已减少重复」改为**描述结果**：全避开就说避开几个，保留了就**点名是哪几个**并说明原因（文案与机制同源）；
7. 前端交通合计：**每段都有该字段才显示**，否则整项不展示——口径与游览时长一致，宁可不显示也不给错数。

### 验收

- `mvn.cmd -o clean package`：**648 用例全绿**（635 + 13，新增校验层 7 例 + 收尾层 4 例 + 其它 2 例）；前端 `vue-tsc --noEmit` / `vite build` 均 EXIT=0；
- 真实链路探针 `backend/probe_consistency_verify.py`：三亚 5 天 / ¥8000，逐条核对 ①住宿口径 ②端点落地 ③每日餐次 ④简介不串用 → 全部 PASS。

### 答辩可直接用的一句话

> 一份行程"每天单看都像真的"不难，难的是**连起来不说假话**。我们加了四道生成后的一致性校验：改了数据就重算文案、换了地点就换掉所有引用它的地方、每天必须闭合成"有景点也有饭吃"、约束没生效就如实说明原因——**宁可少显示一条信息，也不展示一条不存在的路线**。


