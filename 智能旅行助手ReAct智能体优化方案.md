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
| 开关 | `LLMConfig.agentMode` + `application.yml: llm.agent-mode: ${LLM_AGENT_MODE:legacy}`，**默认 legacy** |
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
4. **未验证项**：真实"不支持 tools 的模型"没有条件实测，该路径目前仅由单测覆盖（模拟 400 拒绝 + 5xx 不误判）。换到具体免费模型时，第一次生成看 trace 里是"原生工具调用"还是"基于工具目录制定计划"即可判断走到了哪条路。


