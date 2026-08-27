# AI旅游助手 · 零基础学习指南

> 这份指南假设你几乎没写过 Spring Boot / Vue，但会一点 Java 语法。目标是让你**从打开项目到看懂每一行核心代码**，最后能自己改东西、讲得出来（答辩用）。
> 配合本文使用：`README.md`（总览）、`MIGRATION_GUIDE.md`（API 契约与踩坑记录）、本项目的数据库 `schema.sql`。

---

## 第 0 章 这个项目到底在干什么（一句话版）

用户在前端填一张「去成都玩 3 天、2 人、预算 5000」的表单，点"开始规划"：

1. 前端把表单发给后端；
2. 后端用一个 **AI Agent**（ReAct 循环）自动决定"要去查天气、查高德 POI、搜攻略"，并行把数据抓回来；
3. 再把抓到的数据喂给 **大模型（LLM）**，让它生成一份结构化的 JSON 行程；
4. 行程返回前端，展示成：地图、天气、每天行程、预算明细，还能**保存到数据库、导出 PDF/Markdown**。

所以它本质上是一个 **"表单 → 多数据源采集 → LLM 生成 → 展示/存储/导出"** 的系统。

---

## 第 1 章 全局地图（先建立"地图"，再看"街道"）

### 1.1 有哪些部件、分别放哪

```
┌─────────────────────────── 前端 frontend/ (Vue 3 + TypeScript + Ant Design Vue) ───────────────────────────┐
│  main.ts         程序入口：创建 Vue 应用                                            │
│  App.vue         根组件：登录态 + 顶部导航 + 4 个视图的切换（没有用 vue-router！）    │
│  services/api.ts 唯一的"网络层"：封装所有向后端发的请求                              │
│  types/index.ts  前后端共享的"数据形状"（TS 接口）                                   │
│  views/          Home(表单) / AgentProcess(生成过程) / Result(结果) / History(历史) / Login(登录) │
│  components/     AgentTracePanel(推理过程面板) / AmapTripMap(高德地图)               │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                        │ HTTP(axios / fetch+SSE)
                                        ▼
┌─────────────────────────── 后端 backend/ (Spring Boot 3.2 + Java 17) ───────────────────────────┐
│  controller/    HTTP 入口，接收请求、返回 JSON                                          │
│  agent/         ★ 核心：TravelAgent，ReAct 循环（THINK→ACT→OBSERVE→FINAL）              │
│  service/       业务逻辑：ItineraryGenerator(生成行程)、RagService(攻略检索)、           │
│                 CityValidator(城市名校验)、TripRecordService(存取行程)、...              │
│  client/        对外部服务的 HTTP 客户端：LlmClient(大模型)、AmapClient(高德)、          │
│                 BingSearchClient(搜索)、OpenMeteoClient(天气)、EmbeddingClient(向量)、    │
│                 ChromaVectorStore(向量库)                                                │
│  model/         数据类（请求体、行程、用户、天气等）                                      │
│  repository/    MyBatis-Plus 数据访问层（操作 MySQL 表）                                  │
│  security/      JWT 生成/校验 + 拦截器 + 当前登录用户上下文                               │
│  config/        配置类（线程池、CORS、拦截器注册、MyBatis 等）                            │
│  exception/     自定义异常（全局异常处理器统一转成 HTTP 错误）                            │
│  resources/     application.yml(配置) + schema.sql(建表) + guides/（RAG 攻略 md 文件）   │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                        │
        ┌──────────────────┬────────────────────┬───────────────────┬─────────────────┐
        ▼                  ▼                    ▼                   ▼                 ▼
     MySQL 3310        Redis 6379           Chroma 8000        高德/气象/搜索/LLM   浏览器(高德JS地图)
    (用户/行程/轨迹)   (缓存 RAG/地图/天气)  (向量库,可选)       (外部 API)          (前端调)
```

### 1.2 一次完整请求的"旅行"（最重要的一张图，建议对着代码走一遍）

用户在首页点「开始规划」后，代码里实际发生了什么：

```
[前端] Home.vue handleSubmit()
   │  组装 payload（destination、start_date、travelers、预算、偏好…）
   ▼
[前端] App.vue startGenerate(payload) → 切换到 AgentProcess 视图（mode="live"）
   ▼
[前端] AgentProcess.vue runLive()
   │  调 streamGenerateTrip(payload, {onProgress, onStep}, signal)   ← 原生 fetch，POST SSE
   ▼
[后端] TripStreamController.generateStream()
   │  ① CityValidator 校验城市名（假城市直接抛异常→400，不烧钱调 LLM）
   │  ② new SseEmitter(0L)（0 = 不限超时），注册 onCompletion/onTimeout/onError 回调
   │  ③ CompletableFuture.runAsync(..., agentExecutor)  ← 另起线程跑 Agent，不阻塞 HTTP
   ▼
[后端] TravelAgent.execute(request, callback)   ← ★ ReAct 循环，最多 3 轮
   │  每轮：think(制定计划) → executeTools(并行调工具) → reflect(反思够不够)
   │  每个节点通过 callback.onStep/onProgress 往 SSE 推一帧
   ▼
[后端] ItineraryGenerator.generate(request, collectedData)
   │  把收集到的数据拼成 prompt → 调 LLM → 解析 JSON → 高德补图 → 算预算
   ▼
[后端] SSE 推 itinerary + done 事件 → 前端解析 → emit("finished", itinerary, trace)
   ▼
[前端] App.vue handleAgentFinished → 切到 Result 视图展示
   ▼
[前端] Result.vue 可点「保存行程」→ POST /trip/save → TripRecordService 存 MySQL
```

看懂上面这条链路，整个项目就通了 80%。下面逐章解释每一环。

---

## 第 2 章 前置知识（零基础必看，每个概念一句话+项目里在哪用）

| 概念 | 一句话解释 | 项目里在哪 |
|---|---|---|
| **Maven** | Java 的"包管理器"，用 `pom.xml` 声明依赖（Spring、MyBatis、JWT 等） | `backend/pom.xml` |
| **Spring Boot** | 一套"启动即用的 Java Web 框架"，`main` 一跑就起 HTTP 服务 | `TripPlannerApplication` |
| **@Component / @Service / @RestController / @Configuration** | 告诉 Spring「把这类交给容器管理」，Spring 自动 new 并注入 | 所有类头 |
| **依赖注入 (DI)** | 类不自己 `new` 依赖，而是在**构造器**里声明，Spring 自动塞进来 | `TravelAgent` 的构造器 |
| **Lombok** | 用注解偷懒：`@Data` 自动生成 getter/setter，`@Slf4j` 自动生成 `log` | 所有 model/类 |
| **Controller** | HTTP 入口：`@GetMapping` 等注解把「URL + 方法」对应起来 | `controller/` |
| **Service** | 业务逻辑层，Controller 调它，它调 Repository/Client | `service/` |
| **Model (POJO)** | 纯数据类，用来在层与层之间传数据 | `model/` |
| **MyBatis-Plus** | 操作 MySQL 的库：`BaseMapper<T>` 自带增删改查，`LambdaQueryWrapper` 写查询条件 | `repository/` + `TripRecordService` |
| **Jackson** | JSON 和 Java 对象互转的库。全局配了 snake_case | `@JsonProperty("start_date")` |
| **JWT** | 登录后发给客户端的一段"签名令牌"，后端验证它就知道是谁 | `security/` |
| **拦截器 (Interceptor)** | 请求进 Controller 前/后执行的钩子，这里用来查 token | `JwtAuthInterceptor` + `WebConfig` |
| **ThreadLocal** | 每个线程私有的小盒子，拦截器把 userId 放进去，同线程后续代码读 | `UserContext` |
| **SSE** | 服务器"一点点"往外吐数据的 HTTP 方式（区别于一次给完），用来流式展示 Agent 思考 | `TripStreamController` |
| **CompletableFuture** | Java 的异步工具，`runAsync` 把任务丢到线程池并行执行 | `TravelAgent.executeTools` |
| **线程池 Executor** | 管理一批线程，任务排队执行，避免每来一个任务就 new 线程 | `AsyncConfig`（三个池） |
| **ReAct Agent** | 一种 AI 用法：让 LLM **思考**(Think) → **行动**(Act 调工具) → **观察**(Observe 看结果) 循环 | `TravelAgent` |
| **RAG** | 检索增强生成：先从"本地攻略库"检索相关片段，再拼给 LLM 让它参考着写 | `RagService` |
| **embedding / 向量** | 把一段文字变成一串数字（向量），语义相近的文字向量也相近，用余弦相似度算"多像" | `EmbeddingClient` + `RagService` |
| **Vue 组件** | 一个 `.vue` 文件 = 一个可复用的界面块，分模板/逻辑/样式三块 | `views/`, `components/` |
| **script setup** | Vue3 组合式写法，`ref`/`computed`/`watch` 管理状态 | 所有 `.vue` |
| **props / emit** | 父组件给子组件传数据(props)，子组件用 emit 通知父组件（事件） | `App.vue` 和各个视图之间 |
| **axios 拦截器** | 每次请求发出前/响应回来后统一做事（附 token、遇 401 跳登录） | `api.ts` |

> 不用背。读到对应代码时回来查这张表即可。

---

## 第 3 章 阅读路线（建议按这个顺序读，从"壳"到"心"）

### 阶段 A：先读"说明书"和"地基"（30 分钟）

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `README.md` | 项目是什么、技术栈、功能列表（全局观） |
| 2 | `backend/pom.xml` | 用了哪些依赖，每个依赖猜一下是干嘛的（看注释/名字） |
| 3 | `backend/src/main/resources/application.yml` | **所有配置**：端口、数据库、Redis、LLM、高德、JWT、城市白名单 |
| 4 | `backend/src/main/resources/schema.sql` | **4 张表**：users、trip_record、agent_trace、guide_embedding |
| 5 | `frontend/src/types/index.ts` | **整个系统流通的数据长什么样**（看字段名就能懂接口协议） |

> `application.yml` + `types/index.ts` 这两份看懂，等于先看到了整个系统的"地基和管道"。

### 阶段 B：跟一条主链路走通（后端）（2~3 小时，最重要）

按「入口 → 核心 → 数据源 → 生成 → 返回」的顺序：

| 顺序 | 文件 | 角色 |
|---|---|---|
| 6 | `TripPlannerApplication.java` | 启动类，三个注解的含义 |
| 7 | `controller/TripController.java` | 看 5 个 HTTP 入口长什么样 |
| 8 | `controller/TripStreamController.java` | SSE 流式入口（新功能，答辩亮点） |
| 9 | `agent/TravelAgent.java` | ★**全项目最核心**：ReAct 循环 |
| 10 | `agent/AgentThought.java` `SearchPlan.java` `CollectedData.java` `AgentCallback.java` | TravelAgent 用到的辅助类 |
| 11 | `client/LlmClient.java` | 怎么调用大模型 |
| 12 | `client/AmapClient.java` `BingSearchClient.java` `OpenMeteoClient.java` | 外部数据源客户端 |
| 13 | `service/ItineraryGenerator.java` | 怎么把数据变成最终行程 JSON |
| 14 | `service/RagService.java` | RAG 检索（答辩亮点，可拆开慢慢看） |
| 15 | `model/` 下的实体 | 数据类（随手翻，不深究） |

### 阶段 C：看"支线"功能（1~2 小时）

| 顺序 | 主题 | 文件 |
|---|---|---|
| 16 | 城市名校验 | `service/CityValidator.java`（算法：别名/geocode/编辑距离） |
| 17 | 用户系统 | `controller/AuthController.java` → `service/AuthService.java` → `security/JwtUtil.java`、`JwtAuthInterceptor.java`、`UserContext.java` |
| 18 | 行程存取与隔离 | `service/TripRecordService.java` + `repository/` 几个接口 |
| 19 | 配置类 | `config/WebConfig.java`(CORS+拦截器)、`AsyncConfig.java`(三个线程池)、`GlobalExceptionHandler.java`(异常→错误响应) |
| 20 | 导出 | `controller/ExportController.java` → `service/PdfExportService.java` |

### 阶段 D：看前端（1~2 小时）

| 顺序 | 文件 | 角色 |
|---|---|---|
| 21 | `frontend/src/main.ts` | Vue 应用创建 |
| 22 | `frontend/src/App.vue` | 根组件：登录态 + 视图切换（**没有 router**，用 v-if 切） |
| 23 | `frontend/src/services/api.ts` | ★网络层：axios 封装 + SSE 手动解析 |
| 24 | `frontend/src/views/Home.vue` | 表单页 |
| 25 | `frontend/src/views/AgentProcess.vue` | 生成过程页（live 流式 / replay 回放） |
| 26 | `frontend/src/views/Result.vue` | 结果展示页 |
| 27 | `frontend/src/views/History.vue` `Login.vue` | 历史列表 / 登录注册 |
| 28 | `frontend/src/components/` | 子组件：推理面板、高德地图 |

---

## 第 4 章 逐文件精讲（后端）

### 4.0 启动类 `TripPlannerApplication.java`

```java
@SpringBootApplication   // 三大合一：@Configuration(配置) + @EnableAutoConfiguration(自动配置) + @ComponentScan(扫描本包及其子包的 @Component)
@EnableAsync             // 开启异步，配合 AsyncConfig 的线程池使用
@MapperScan("com.yuntu.tripplanner.repository")  // 扫描 repository 包，把每个 Repository 接口注册成 MyBatis 的 Mapper
public class TripPlannerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TripPlannerApplication.class, args);  // 启动内嵌 Tomcat，监听 8080
    }
}
```

### 4.1 HTTP 入口 `controller/TripController.java`

`@RestController` = `@Controller` + `@ResponseBody`（返回的对象自动转成 JSON）。`@RequestMapping("/trip")` 表示类里所有方法都挂在 `/trip` 下。

5 个端点，注意**注解 → URL → 方法**的对应：

| 注解 | URL | 方法做的事 |
|---|---|---|
| `@GetMapping` | `GET /trip` | 查当前用户的历史行程列表 |
| `@PostMapping("/generate")` | `POST /trip/generate` | 生成行程（不带推理轨迹） |
| `@PostMapping("/generate-with-trace")` | `POST /trip/generate-with-trace` | 生成行程（带轨迹） |
| `@PostMapping("/save")` | `POST /trip/save` | 保存行程 |
| `@GetMapping("/{trip_id}")` | `GET /trip/{trip_id}` | 查行程详情（`@PathVariable` 从 URL 取变量） |
| `@DeleteMapping("/{trip_id}")` | `DELETE /trip/{trip_id}` | 删除行程 |

一个方法逐行拆解（以 `generateTrip` 为例）：

```java
@PostMapping("/generate")
public ResponseEntity<?> generateTrip(@Valid @RequestBody TripRequest request) {
    validateDestination(request);              // ① 城市名校验，失败会抛异常（见 4.5）
    AgentTraceResponse response = travelAgent.execute(request);   // ② 跑 Agent
    if (Boolean.TRUE.equals(response.getSuccess()) && response.getItinerary() != null) {
        return ResponseEntity.ok(response.getItinerary());        // ③ 成功 → 200 + 行程 JSON
    }
    String message = response.getErrors().isEmpty() ? "行程生成失败" : response.getErrors().get(0);
    return ResponseEntity.internalServerError().body(...);        // ④ 失败 → 500 + 错误信息
}
```

- `@Valid @RequestBody TripRequest`：把请求体 JSON 反序列化成 `TripRequest` 对象，并做字段校验（见 4.2）。
- `ResponseEntity<?>`：Spring 的"响应对象"，可以同时控制 HTTP 状态码和 body。`<?>` 表示 body 类型任意（可能是行程，也可能是错误 Map）。
- 构造器注入 `travelAgent`、`tripRecordService`、`cityValidator` —— 这是 Spring 推荐的写法（不 `new`，由容器给）。

### 4.2 请求体 `model/TripRequest.java`

```java
@Data                                  // Lombok：自动生成 getter/setter/toString...
public class TripRequest {
    @NotBlank(message = "目的地不能为空")    // 校验：非空。校验失败 → 全局处理器回 400
    @JsonProperty("destination")          // 关键！前端传来的字段叫 destination（蛇形）
    private String destination;
    ...
}
```

- **校验注解**：`@NotBlank`、`@NotNull`、`@Min`、`@AssertTrue`。这些是 Jakarta Bean Validation，`@Valid` 触发。
- **`@JsonProperty("start_date")`**：因为全局 Jackson 配置用了 snake_case，前端传的是 `start_date`，但 Java 字段叫 `startDate`，用这个注解告诉 Jackson「JSON 里的 `start_date` ↔ Java 里的 `startDate`」。
- `@JsonIgnore` 的 `isDateRangeValid()`：`@AssertTrue` 用来做自定义校验（结束日期不能早于开始日期），但这个方法本身不参与 JSON 序列化，所以 `@JsonIgnore`。

### 4.3 SSE 流式入口 `controller/TripStreamController.java`（答辩亮点）

普通接口是"憋一会儿，一次全返回"。SSE（Server-Sent Events）是"服务器往客户端一条条推"。因为 Agent 思考过程可能要十几秒甚至更久，用 SSE 就能让前端**边生成边展示** AI 的思考步骤。

核心代码：

```java
@PostMapping(value = "/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public ResponseEntity<SseEmitter> generateStream(@Valid @RequestBody TripRequest request) {
    CityValidationResult cityResult = cityValidator.validate(request.getDestination());
    if (!cityResult.valid()) {
        throw new CityValidationException(...);   // 校验失败直接抛异常，走全局处理器回 400
    }
    SseEmitter emitter = new SseEmitter(0L);      // 0 = 永不超时（生成可能超 30 秒）
    AtomicBoolean closed = new AtomicBoolean(false);  // 记录"客户端是否已断开"

    emitter.onCompletion(() -> closed.set(true));   // 客户端断开/完成 → 标记 closed
    emitter.onTimeout(() -> closed.set(true));

    // 定义一个回调对象，Agent 每完成一步就通过它推送
    AgentCallback callback = new AgentCallback() {
        public void onStep(AgentTraceStep step)      { send(emitter, closed, "step", step); }
        public void onProgress(String p, String m)   { send(emitter, closed, "progress", Map.of("phase", p, "message", m)); }
        public boolean isClosed()                     { return closed.get(); }
    };

    // 重点：在 agentExecutor 线程池里异步跑 Agent，HTTP 线程立刻返回 SseEmitter
    CompletableFuture.runAsync(() -> {
        AgentTraceResponse resp = travelAgent.execute(request, callback);
        ...发 itinerary / done 或 error 事件...
        emitter.complete();      // 全部发完，关闭流
    }, agentExecutor);

    return ResponseEntity.ok(emitter);   // 立即返回，把"直播通道"交给前端
}
```

几个关键点（答辩可以讲）：
1. **为什么返回类型必须是 `ResponseEntity<SseEmitter>`**：Spring 靠泛型识别「这是个 SSE 返回」，写 `ResponseEntity<?>` 会丢失泛型导致 500。
2. **为什么用异步线程**：SSE 连接是长连接，Agent 生成很慢，如果占用 HTTP 线程会把线程池耗尽。所以用专门的 `agentExecutor`。
3. **为什么校验失败要抛异常而不是手工拼 body**：SSE 端点手工拼 400 body 会破坏流式响应，抛异常让 `GlobalExceptionHandler` 统一处理更安全。
4. `send()` 方法里如果 `emitter.send` 抛异常（客户端断了），就 `closed.set(true)` 停止后续推送。

### 4.4 ★核心：Agent ReAct 循环 `agent/TravelAgent.java`

这是全项目的灵魂。它模拟了"一个人工智能旅行规划师"的工作方式。

**什么是 ReAct**：`Reason + Act`。LLM 不像普通问答那样一步答完，而是：

```
THINK（我想找成都的好玩地方）→ ACT（调高德工具查 POI）→ OBSERVE（看到结果）
→ 反思（数据够了吗？）→ 不够就再来一轮 → 够了 → FINAL（生成行程）
```

主流程 `execute(request, callback)`（约 100 行，读熟）：

```java
for (int iteration = 0; iteration < maxIterations; iteration++) {   // 最多 3 轮
    if (callback.isClosed()) return response;   // 客户端断了就提前退出

    // 【THINK】制定本轮搜索计划（工具 + 关键词的清单）
    SearchPlan plan = think(request, collectedData, iteration, agentUsage);

    // 记录本轮开始前的数据量，用来判断这轮有没有新增数据
    int searchBefore = ...; int poiBefore = ...; ...

    if (!plan.getToolCalls().isEmpty()) {
        // 【ACT】并行执行所有工具调用（先全部提交，再统一等，8 秒超时）
        executeTools(plan, request, collectedData, errors);
        // 通过 callback 推一条"plan_search"轨迹 + 一条"tool_execution"轨迹
    }

    boolean newDataCollected = ...;   // 本轮是否真的抓到了新数据？

    // 【OBSERVE】反思数据是否足够
    AgentThought reflection = reflect(request, collectedData, agentUsage);

    if (Boolean.TRUE.equals(reflection.getEnough())) {
        break;   // 数据够了 → 跳出循环
    }
    // 数据不够但本轮啥也没抓到 → 再循环也是空转，直接跳出
    if (iteration > 0 && !newDataCollected) break;
}

// 【FINAL】基于收集到的所有数据，生成最终行程
Itinerary itinerary = itineraryGenerator.generate(request, collectedData);
```

**关键设计（答辩可以讲）**：

1. **THINK 分三种策略**：
   - 首轮：让 LLM 基于"工具目录"自己选工具和关键词（`buildPlanFromLLM`），要求它输出 JSON：`{"tools":[{"tool":"web_search","query":"成都 景点"}]}`；
   - LLM 解析失败 → 用规则兜底 `buildDefaultPlan`（全工具各调一遍）；
   - 后续轮：`buildGapFillPlan` 按"数据缺口"补（没天气就补天气，没 POI 就补 POI）。
   - 工具名都在 `TOOL_CATALOG` 里：`web_search` / `weather_forecast` / `amap_poi` / `rag_guide`。

2. **ACT 并行执行**（`executeTools`）：
   ```java
   List<CompletableFuture<Void>> futures = new ArrayList<>();
   for (SearchPlan.ToolCall tc : plan.getToolCalls()) {
       futures.add(CompletableFuture.runAsync(() -> executeTool(tc, ...), toolExecutor));
   }
   CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
           .get(TOOL_EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);   // 等全部完成，最多 8 秒
   ```
   一个工具一个 future，丢到 `toolExecutor` 线程池并发跑，8 秒后不管完没完都继续。

3. **OBSERVE 用"规则裁决 + LLM 展示"**（`reflect`）：
   ```java
   boolean ruleEnough = hasPoi && (hasSearch || hasRag) && hasWeather;
   if (!ruleEnough) {
       thought.setEnough(false);   // 规则说不够 → 直接返回，不浪费一次 LLM 调用
   }
   // 规则说够 → 才调 LLM 生成一句简短的"反思文本"给前端展示，但不让 LLM 决定"够不够"
   ```
   这样避免了 LLM 反复说"还不够"导致死循环（踩过的坑）。

4. **收集到的数据都装进 `CollectedData`**：`searchResults`（Map）、`poiResults`（Map<类别, POI列表>）、`weatherData`、`ragData`。工具执行结果往里塞。

5. **token 统计**：`agentUsage` 是方法内局部变量，`callLlm` 每次把消耗累加进去，避免多线程并发改共享字段的竞态。

### 4.5 城市名校验 `service/CityValidator.java`（答辩亮点）

你之前要求加的功能。在 Agent 启动前拦截"瞎填的城市"，省 token 也提升体验。三个能力：

```java
public CityValidationResult validate(String destination) {
    String city = destination.trim();
    // 1. 别名映射：蓉城→成都、魔都→上海…（只做"确定"的规范化）
    String aliased = CITY_ALIASES.get(city);
    if (aliased != null) return new CityValidationResult(true, aliased, null);

    // 2. 高德地理编码：能解析且返回地址"完整包含"用户输入 → 有效
    AmapGeocode geo = amapClient.geocodeInfo(city);
    if (geo != null && geo.formattedAddress() != null && geo.formattedAddress().contains(city)) {
        return new CityValidationResult(true, null, null);
    }
    // 3. 以上都过不了 → 在已知城市里做编辑距离模糊匹配，给纠错建议
    String suggestion = findSuggestion(city);
    return new CityValidationResult(false, null, suggestion);
}
```

- **为什么要求 `formattedAddress().contains(city)`**：高德对乱码会做**模糊子串匹配**，比如"噜啦啦市"能匹配到福建一家"噜啦啦"小店。真城市返回的地址"四川省成都市…"完整包含输入；模糊凑上的不包含 → 靠这条区分真假。
- `findSuggestion`：Levenshtein 编辑距离（`levenshtein(a,b)`），距离 ≤ 阈值（如"成堵"→"成都"距离 1）就建议。阈值规则：`bestDist <= 1 && len <= 4`，或 `dist/maxLen <= 0.33`。
- 校验失败在 Controller 抛 `CityValidationException`，`GlobalExceptionHandler` 统一转成 `400 + {message}`。

### 4.6 调用大模型 `client/LlmClient.java`

所有 LLM 调用都走这里（不再散落裸 RestTemplate）。

```java
public record LlmResult(String content, int promptTokens, int completionTokens) {}  // Java 17 record：一行定义"返回值三件套"

public LlmResult chat(String prompt) {
    if (!StringUtils.hasText(llmConfig.getApiKey())) return null;   // 没配 key 就跳过
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(llmConfig.getApiKey());                    // 鉴权头
    Map<String, Object> body = Map.of(
        "model", llmConfig.getModel(),                              // qwen-plus
        "messages", List.of(Map.of("role", "user", "content", prompt)),
        "temperature", 0.7);
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
    Map<String, Object> response = restTemplate.postForObject(url + "/chat/completions", entity, Map.class);
    // 从 response 里抠 choices[0].message.content 和 usage.prompt_tokens / completion_tokens
    return new LlmResult(content, usage[0], usage[1]);
}
```

- 这是标准的 **OpenAI 兼容 chat/completions 接口**，DashScope 兼容它，所以直接用 RestTemplate 调。
- **失败返回 `null` 而不是抛异常**：让调用方（TravelAgent/ItineraryGenerator）各自决定降级策略（如走规则兜底计划）。
- `record`：Java 17 新特性，一行代码定义"不可变的轻量数据类 + 构造器 + getter"。

### 4.7 外部数据源客户端 `client/`

四个客户端都很直白：一个 `RestTemplate` 调一个外部 HTTP API，解析成自己的数据类。

| 文件 | 调谁 | 干什么 |
|---|---|---|
| `AmapClient.java` | 高德 v3 API | `geocodeInfo`（地理编码：城市→经纬度）、`searchPoi`（POI：景点/餐厅/酒店的真名+坐标+图） |
| `BingSearchClient.java` | Bing 搜索 | `searchAsText`：把搜索结果压成一段文本给 LLM 参考 |
| `OpenMeteoClient.java` | Open-Meteo（免费天气） | `getWeatherForecast`：未来 3~16 天天气 |
| `EmbeddingClient.java` | DashScope embedding | `embed`/`embedAll`：文字→向量 |

它们的共同套路（以 OpenMeteo 为例，答辩可总结成"客户端三部曲"）：
1. 用 `RestTemplate`/`WebClient` 请求外部 URL，带 key/参数；
2. 把外部返回的 JSON `Map` 手动提取成自己的 model；
3. **数据异常时返回 `null` 或空集合**，交给上层降级，绝不把外部错误抛成 500。

### 4.8 生成行程 `service/ItineraryGenerator.java`

Agent 收集完数据后，用它把"素材"变成"成品 JSON"。方法 `generate()` 是流水线，逐行看：

```java
public Itinerary generate(TripRequest request, CollectedData collectedData) {
    // 1. 把收集到的搜索/POI/天气/RAG 数据压缩成一段摘要文本
    String dataSummary = prepareDataSummary(collectedData);

    // 2. 把"用户需求 + 数据摘要 + JSON 输出格式要求"拼成一个巨大的 prompt
    String prompt = buildGenerationPrompt(request, dataSummary);

    // 3. 调 LLM（这就是"生成行程"的 LLM 调用）
    LlmClient.LlmResult result = llmClient.chat(prompt);
    if (result == null) throw new TripGenerationException("LLM 调用失败，无法生成行程");

    // 4. 解析 LLM 输出的 JSON → Itinerary 对象（失败会回传 LLM 修正一次，再失败抛异常）
    Itinerary itinerary = parseItinerary(result.content(), request, usage);

    // 5. 把 Agent 抓到的天气快照存进行程（结果页直接展示，口径一致）
    itinerary.setWeather(...);

    // 6. 补高德地图信息（图片、坐标、地址）
    mapEnrichmentService.enrich(itinerary);   // 失败只 warning，不影响行程

    // 7. 根据每日明细重新计算总预算
    calculateBudget(itinerary);
    return itinerary;
}
```

**prompt 工程**（答辩必讲）：`buildGenerationPrompt` 把用户需求逐字段列成 `## 用户需求`，把真实数据列成 `## 已收集的真实数据`，然后在 `## 输出要求` 里给出一个**严格的 JSON 示例**并要求"景点名称必须来自上面的真实数据"。第 7 条特别要求天气描述必须严格用数据表里的原文——这就是在约束 LLM"别编造"。

**容错**（答辩必讲）：`parseItinerary` 先 `extractJson`（取第一对 `{}` 里的内容，因为 LLM 可能夹带文字）→ `objectMapper.readValue` 解析 → 失败则 `correctJson` 把 JSON 和结构要求**回传 LLM 修一次** → 再失败才抛 `TripGenerationException`。这就是"宁可多花一次 LLM 调用，也不让用户看到解析崩溃"。

### 4.9 RAG 攻略检索 `service/RagService.java`（答辩亮点，也是最"重"的类）

**RAG 是什么**：用户问"成都怎么玩"，系统不直接让 LLM 瞎编，而是先从一个**本地攻略库**里检索出相关的几段攻略文字，连同问题一起给 LLM。LLM 参考真实攻略回答 → 减少幻觉。

这个类做了四件事，按重要度：

**(1) 加载攻略并切分**（构造器 `loadGuides`）：
- 从 `resources/guides/*.md` 读攻略文件（北京/大理/成都/三亚/厦门/西安各一个）；
- `splitMarkdown` 按 `##`/`###` 标题把一篇攻略切成多个片段（chunk），每个片段 = `{source(文件名), title(小节标题), text(正文), tags(打标)}`。

**(2) 检索主流程** `search()`：
```java
// 1. 先查 Redis 缓存（同一城市+同一关键词+同一 topK 直接返回）
String cacheKey = "rag:guide:" + destination + ":" + query + ":" + topK;
if (cached != null) return cached;

// 2. 按策略排序：生产默认 HYBRID（混合检索）
List<ScoredChunk> ranked = switch (mode) {
    case KEYWORD -> rankKeyword(destination, query);
    case VECTOR  -> rankVector(destination, query, usageSink);
    case HYBRID  -> fuseRrf(destination, query, usageSink);   // ★ 默认
};

// 3. 约束标签过滤（用户说"带娃"→ 只留标了"亲子"的片段）
List<ScoredChunk> filtered = applyTagFilter(ranked, requiredTags);

// 4. 格式化成带来源/标题的文本 → 缓存进 Redis → 返回
```

**(3) 混合检索 fuseRrf（核心算法）**：
- `rankVector`：把查询文字变向量，和每个片段的向量算**余弦相似度**，按相似度排名；embedding 不可用返回空；
- `rankKeyword`：**BM25 关键词检索**，给标题 3 倍权重，正文 1 倍（中文没分词，用子串出现次数近似 tf）；
- `fuseRrf`：两路排名各用 `1/(60 + rank)` 打融合分再排序。这叫 **RRF（Reciprocal Rank Fusion）**，好处是不在意两路分数量纲不同（余弦 0~1，BM25 是几到几十），只用排名。
- 任何一路不可用都自动退化为另一路（`vectorRanks.isEmpty() → 返回 keywordRanks`），所以即使没配 embedding 也能跑。

**(4) 向量持久化 + Chroma 加速（答辩加分项）**：
- `loadOrBuildVectors`：先从 MySQL 的 `guide_embedding` 表读缓存向量，命中且内容哈希没变 → 零 API 调用直接复用；只有缺失/变化的片段才调 embedding API，算完回写库；
- `rankVector` 里如果配置了 Chroma 且可达：首次把城市片段 upsert 进 Chroma 集合，之后查询交给 Chroma（向量检索更快），**任何失败都自动降级**为内存余弦（`rankViaChroma` 返回 null → 走 `embeddingClient.embed + 循环算余弦`）。
- 这就是 README 里说的"Chroma 挂了也能跑，行为完全一致"。

> 读这个类不用一次读懂 BM25/RRF 公式，先看懂"检索→融合→过滤→缓存"的主干，算法细节留到答辩前精读。

### 4.10 用户系统 `security/` + `controller/AuthController.java` + `service/AuthService.java`

**注册/登录流程**：
- `AuthController`：`POST /auth/register`（注册）、`POST /auth/login`（登录），都无需 token。
- `AuthService.register`：先查用户名是否重复（重复 → 409）→ `BCryptPasswordEncoder` 加密密码 → 存 `users` 表；
- `AuthService.login`：按用户名查用户 → `BCrypt.matches(明文, 哈希)` 验证 → 成功则 `JwtUtil` 生成 token 返回。

**JWT 三件套**：
- `JwtUtil`：`Jwts.builder().subject(username).claim("uid", userId).signWith(key)` 生成；`parseSignedClaims` 解析验证。HS256，密钥来自 `application.yml` 的 `jwt.secret`。
- `JwtAuthInterceptor`：实现 `HandlerInterceptor`，在 `preHandle` 里读 `Authorization: Bearer xxx` → 校验 → 把 userId 塞进 `UserContext`。**必须放行 OPTIONS 请求**（CORS 预检不带 token，拦了前端就是 "Failed to fetch"）。
- `UserContext`：`ThreadLocal<String>`，存当前线程的 userId。`afterCompletion` 里 `remove()` 防线程池复用导致串号。

**为什么行程按用户隔离**：`TripRecordService` 的查询条件都加 `.eq(TripRecord::getUserId, userId)`；`saveTrip` 以**服务端 token 解析的 userId 为准**，忽略请求体里的 userId（防伪造）。非本人行程查不到 → 404。

### 4.11 配置类 `config/`

| 文件 | 作用 |
|---|---|
| `WebConfig.java` | 注册 JWT 拦截器（除 `/auth/**` 全拦）+ CORS 跨域（只允许 localhost:5173） |
| `AsyncConfig.java` | **三个线程池**：默认 `trip-async-`；`toolExecutor`（工具并行调用，8 核心）；`agentExecutor`（SSE 生成，2 核心，与 toolExecutor 分离避免长任务占满工具池） |
| `GlobalExceptionHandler.java` | `@ControllerAdvice` + `@ExceptionHandler`：把各异常转成统一 JSON 错误响应（400/500） |
| `LLMConfig` / `AmapConfig` / `RedisConfig` / `ChromaConfig` | 从 `application.yml` 读配置，交给客户端使用 |
| `MyBatisPlusConfig` / `MyMetaObjectHandler` | MyBatis-Plus 配置 + 自动填充 `created_at`/`updated_at` |

### 4.12 其他

- `repository/`：`BaseMapper<实体>` 接口，MyBatis-Plus 自动实现基础 CRUD。`AgentTraceRepository` 存 Agent 轨迹（可选）。
- `exception/`：`CityValidationException`（城市校验失败→400）、`TripGenerationException`（生成失败→500）。
- `model/AgentTraceStep.java`：一步推理轨迹（step 序号、thought 思考、action 动作、tool_calls 工具调用、observation 观察），前端回放就靠它。
- `service/CacheService.java`：Redis 读写封装（map/weather/rag 各有一份 TTL）。
- `service/MapEnrichmentService.java`：拿行程里的地点名去高德查坐标/图片，回填到 `SpotItem`。
- `service/TripRecordService.java`：行程的保存/列表/详情/删除，负责 JSON 序列化 + 用户隔离。
- `service/PdfExportService.java` / `controller/ExportController.java`：把行程转 Markdown / 用工具生成 PDF，走 blob 下载。

---

## 第 5 章 逐文件精讲（前端）

### 5.0 `frontend/src/main.ts`

```typescript
import { createApp } from "vue";
import Antd from "ant-design-vue";
import "ant-design-vue/dist/reset.css";
import App from "./App.vue";

const app = createApp(App);   // 创建 Vue 应用，根组件是 App.vue
app.use(Antd);                // 全局注册 Ant Design Vue 组件（a-form、a-button 等）
app.mount("#app");            // 挂载到 index.html 里的 <div id="app">
```

### 5.1 根组件 `App.vue`（全前端的总指挥）

**没有引入 vue-router**，页面切换用 `currentView` 变量 + `v-if`：

```vue
const currentView = ref<"home" | "agent" | "result" | "history">("home");
```

模板里按状态渲染对应视图，视图之间靠 **props 传数据、emit 抛事件** 联动：

```vue
<Home v-if="currentView === 'home'" @start-generate="startGenerate" />
<AgentProcess v-else-if="currentView === 'agent'" :payload="agentPayload" @finished="handleAgentFinished" />
<Result v-else-if="currentView === 'result'" :itinerary="latestItinerary" @view-replay="handleViewReplay" />
<History v-else :active="true" @open-trip="openTripWithReplay" />
```

**登录态管理**（记住这个流程，答辩必问）：
- `token`/`user` 两个 `ref`；`!token` 时整个应用只渲染 `Login.vue`；
- `initAuth()` 在 `onMounted` 里从 `localStorage` 恢复登录态；
- 监听全局事件 `auth:unauthorized`（由 api.ts 的响应拦截器在收到 401 时派发）→ `handleUnauthorized` 清态回登录页。

**为什么用事件而不是 router**：这是个刻意设计（不引入路由库，减小依赖）。事件流向：
- `Home` 提交 → `startGenerate` → 进 `agent` 视图 live 生成；
- `AgentProcess` 播完 → `finished` → 存行程 + 进 `result`；
- `Result` 点"动画回放" → `handleViewReplay` → 进 `agent` 视图 replay 模式。

### 5.2 网络层 `frontend/src/services/api.ts`（全前端最重要的文件）

**(1) axios 实例 + 拦截器**：

```typescript
const api = axios.create({ baseURL: API_BASE_URL, timeout: 120000 });

// 请求拦截器：每次请求自动带上 JWT
api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// 响应拦截器：收到 401（登录失效）→ 清登录态 + 发全局事件；/auth/* 的 401 是业务错误不触发
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && !error.config.url.startsWith("/auth/")) {
      dispatchUnauthorized();
    }
    return Promise.reject(error);
  }
);
```

**(2) SSE 手动解析**（`streamGenerateTrip`，最值得精读的函数）：

因为 axios 不支持 SSE 流式读取，这里用原生 `fetch` + `ReadableStream` 手动解析。

```typescript
const resp = await fetch(`${API_BASE_URL}/trip/generate-stream`, {
  method: "POST",
  headers,                      // 注意：SSE 也要手动加 Authorization 头！
  body: JSON.stringify(payload),
  signal,                       // AbortSignal，组件卸载时取消请求
});

const reader = resp.body.getReader();
const decoder = new TextDecoder("utf-8");
let buffer = "";

// 循环读流，按 "\n\n" 切出一个个 SSE 数据块
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });
  let idx;
  while ((idx = buffer.indexOf("\n\n")) !== -1) {
    processBlock(buffer.slice(0, idx));   // 解析一个块
    buffer = buffer.slice(idx + 2);
  }
}
```

SSE 协议格式是 `event: xxx\ndata: {json}\n\n`，`processBlock` 解析它并根据事件名分发：
- `step` → 解析成 `AgentTraceStep` 加入 `result.trace` + 调 `handlers.onStep`（AgentProcess 把它推到面板）；
- `progress` → `handlers.onProgress(phase, message)`；
- `itinerary` → 存进 `result.itinerary`；
- `done` → 存 `token_usage` / `collected_data`；
- `error` → `throw new Error(...)`。

**(3) 导出 blob 下载**：`downloadBlob` 用 `axios.get(url, {responseType:"blob"})`（会自动带 token），拿到文件后创建临时 `<a>` 标签点击下载，并解析 `Content-Disposition` 里的文件名。

### 5.3 数据形状 `frontend/src/types/index.ts`

全是 `interface`，**定义前后端传的 JSON 长什么样**。重点几个：
- `TripRequestPayload`：提交的请求体（`start_date` 蛇形命名，和后端 `@JsonProperty` 对应）。
- `Itinerary`：行程主体（`days: DayPlan[]`、`budget_breakdown`、`tips`、`source_notes`、`weather`）。
- `AgentTraceStep`：一步推理轨迹（`thought`/`action`/`tool_calls`/`observation`）—— 前端推理面板和回放全靠它。
- `AgentTraceResponse`：带轨迹的生成结果（`success`、`itinerary`、`trace`、`collected_data`、`token_usage`、`errors`）。

### 5.4 `views/Home.vue`（表单页）

- `reactive` 管理表单状态；`watch` 让"天数"和"起止日期"联动（改天数自动算结束日期，改日期自动算天数，上限 7 天）。
- `handleSubmit`：组装成 `TripRequestPayload`（**注意 camelCase→snake_case 的字段映射**），`emit("startGenerate", payload)` 让 App.vue 切到生成页。
- 全是样式（iOS 风格 CSS），逻辑很薄，适合作为"第一个读懂的 vue 文件"。

### 5.5 `views/AgentProcess.vue`（生成过程页，双模式）

`mode` 分 `"live"`（实时生成）和 `"replay"`（历史回放）：
- **live**：`onMounted` 里直接 `runLive()` → 调 `streamGenerateTrip`，用回调把每步推给 `steps`，底部 `AgentTracePanel` 实时渲染；收到 `finished` 事件切到结果页。
- **replay**：把历史 `trace` 拿来，用 `setInterval(advance, 1200)` 每 1.2 秒前进一步，`currentStep` 高亮当前步（传给 `AgentTracePanel` 的 `highlight-step`）；控制条有播放/暂停/上一步/下一步/重播。
- `AbortController`：组件卸载（`onBeforeUnmount`）时 `controller.abort()`，断开 SSE，避免请求泄漏。

### 5.6 `views/Result.vue`（结果展示页）

`props` 接收 `itinerary` 和 `agentTrace`。全是**计算属性 + 模板渲染**：
- `mapPoints`：把每天的 `spots` 拍平成带经纬度的点数组，喂给 `AmapTripMap`；
- `budgetItems` / `dayBudgetItems`：从 `budget_breakdown` 和每天明细算展示数据；
- `displayTips`：对 `tips` 做**关键词过滤**（去掉"LLM/RAG/演示"这类技术向话术，避免答辩时露馅），并按天气动态追加"带伞"等提示；
- `loadWeather`：优先用行程自带的 `weather` 快照，没有才实时调天气接口；
- 导出：先 `saveTrip` 再 `exportPdf/exportMarkdown`（保证导出的就是最新内容）。

### 5.7 `views/History.vue`（历史列表）

- `listTrips()` 拉当前用户行程列表，卡片展示目的地/摘要/时间；
- `openTrip` → `getTripDetail` → `emit("openTrip", itinerary, trace)` 回传给 App.vue 进结果页（附带 trace 供回放）；
- `removeTrip` → `window.confirm` 二次确认 → `deleteTrip` → 本地从列表移除。

### 5.8 `views/Login.vue`（登录/注册）

- `mode` 切换"登录/注册"tab，注册多一个昵称字段；
- 用 Ant Design 的 `a-form` + 校验规则（用户名 2-50 位、密码 6-100 位）；
- 成功后 `setToken`/`setUser` 存 localStorage，`emit("authed", {token, user})` 让 App.vue 进入应用；
- 错误处理：409 用户名已存在、401 用户名或密码错误，分别给中文提示。

### 5.9 `components/AgentTracePanel.vue`

**纯展示组件**（无逻辑），接收 `steps`，按 `AgentTraceStep` 渲染每步：Step 编号、💭 思考、🔧 工具列表、观察。支持 `highlightStep`（回放高亮当前步）和 `dimOthers`（未播放到的步淡化）。

### 5.10 `components/AmapTripMap.vue`

前端调高德 JS API 的地图组件：
- 动态加载高德脚本（`ensureMapScript`，只在第一次创建 `<script>` 标签，key 来自 `VITE_AMAP_JS_KEY`）；
- 初始化 `AMap.Map` → 为每个有经纬度的点添加 `AMap.Marker`（带 D1/D2 天序号 + 图片气泡）+ 点击弹 `InfoWindow`；
- 按天数顺序画一条虚线 `AMap.Polyline` 路线；
- `watch(validPoints)`：父组件行程数据变了 → 重画标记；
- 组件卸载时 `clearOverlays` + `map.destroy()` 释放资源。

---

## 第 6 章 如何验证你读懂了（自测清单）

1. **讲得出一次完整请求的链路**（不翻代码）：从 Home 表单到 Result 展示，中间经过了哪些类。
2. **讲得出 ReAct 循环**：THINK/ACT/OBSERVE 各自在哪段代码，为什么规则先于 LLM 做裁决。
3. **讲得出 SSE 为什么能边生成边展示**：`SseEmitter` + 异步线程池 + 事件分发。
4. **讲得出"城市名校验"的坑**：高德模糊匹配问题 → `contains` 守卫。
5. **讲得出用户数据怎么隔离**：拦截器 → ThreadLocal → 查询条件带 userId。
6. **讲得出 RAG 不依赖 LLM 也有一套兜底**：向量不可用 → BM25，Chroma 挂了 → 内存余弦。

能对答 1~3 条，答辩基本稳；全过就对这个项目很有把握了。

---

## 第 7 章 动手改一改（把"看懂"变成"会改"）

按难度从低到高，改完重启后端/刷新前端看效果：

| 难度 | 改什么 | 文件 |
|---|---|---|
| ⭐ | 改前端首页文案 / 偏好选项 | `Home.vue` 的 `preferenceOptions` |
| ⭐ | 加一个城市别名（如"姑苏→苏州"） | `CityValidator.CITY_ALIASES` |
| ⭐⭐ | 改生成行程的 prompt 措辞（如要求"每天安排 3 个景点"） | `ItineraryGenerator.buildGenerationPrompt` |
| ⭐⭐ | 给 `TOOL_CATALOG` 加一个新工具说明，看 LLM 会不会调用它 | `TravelAgent.TOOL_CATALOG` |
| ⭐⭐⭐ | 新增一个数据源工具（比如"查美食"），接入 ReAct 循环 | `TravelAgent` + 新 client |
| ⭐⭐⭐ | 给 SSE 端点加一个 `progress` 推送点 | `TripStreamController` / `TravelAgent` |
| ⭐⭐⭐ | 前端加一个"加载中百分比"（根据已收 step 数） | `AgentProcess.vue` |

改前小技巧：先 `mvn test`（后端）跑一遍测试，改完再跑，看有没有把原有功能改坏。测试在 `backend/src/test/` 下（如 `CityValidatorTest`）。

---

## 附录 常用命令与坑

```bash
# 后端编译 + 测试
cd backend && mvn test
# 后端启动
mvn spring-boot:run          # 依赖 MySQL 3310 / Redis 6379（见 application.yml）
# 前端启动
cd frontend && npm install && npm run dev   # 默认 5173

# 数据库在 Docker：端口 3310，库名 trip_planner，密码 root（见 application.yml）
```

**已知的坑（都记录在 MIGRATION_GUIDE.md / memory）**：
- Git Bash 里 curl 直接传中文 JSON body 会乱码 → 写文件用 `--data-binary @file`。
- 全局 snake_case：请求体字段要写 `start_date`，不是 `startDate`。
- Chroma server 必须 `0.6.3`（1.x 不兼容），且启动要用 `python -c "from chromadb.cli.cli import app; app()" run ...`。
- SSE 端点别手工拼错误 body，抛 `CityValidationException` 走全局处理器。
- 前端高德地图 JS key 在 `VITE_AMAP_JS_KEY`（`.env`），不配就显示"地图暂未启用"（不影响其他功能）。
