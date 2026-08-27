# AI旅游助手：Python → Java 完整改造说明书

> 本文档供 AI 编码助手（Trae）进行 Java 化改造使用。请严格按照本说明实现，前端已就绪。
>
> **2026-08 更新**：新增用户系统（注册/登录 + JWT 鉴权 + 行程按用户隔离），`/trip`、`/export` 系列接口从"可匿名"变为**必须携带 `Authorization: Bearer <token>`**（详情见第十二节）。前端与后端同步改造完成。

---

## 一、项目现状

现有系统是 **Python 版 AI 旅行规划系统**（目录：`../zhilv-yuntu-main/`），核心是一个 **ReAct Agent**：

```
用户输入（目的地/日期/预算/偏好）
    ↓ POST /trip/generate-with-trace
TravelAgent（ReAct 循环）
    ├─ THINK：LLM 分析需求，基于工具目录制定搜索计划
    ├─ ACT：并行调用 Bing搜索 / 高德POI / Open-Meteo天气 / RAG攻略库
    ├─ OBSERVE：LLM 反思数据是否足够，不够则指定补充动作
    ├─ 循环直到 enough 或达到 max_iterations=3
    └─ FINAL：LLM 基于真实数据生成结构化行程 JSON
    ↓
前端渲染：行程概览 / Agent轨迹 / 预算 / 地图 / 天气 / 每日行程
```

**必须保留的能力：**
1. ReAct 多轮循环（THINK→ACT→OBSERVE→反思→FINAL）
2. 多数据源并行调用 + 超时降级
3. RAG 本地攻略检索（6 个城市）
4. 结构化行程生成 + JSON 容错修正
5. Agent 推理轨迹返回前端展示

---

## 二、技术栈映射

| Python 版 | Java 版 | 说明 |
|-----------|---------|------|
| FastAPI | Spring Boot 3.x | REST 层 |
| Pydantic | Java record / Bean Validation | 数据模型 |
| LangChain ChatOpenAI | Spring AI ChatClient | LLM 调用，兼容 OpenAI 格式 |
| asyncio.gather | CompletableFuture / 虚拟线程 | 并行工具调用 |
| SQLite + SQLAlchemy | MySQL + MyBatis-Plus | 持久化 |
| ChromaDB | pgvector 或 Lucene 向量检索 | 攻略向量库 |
| Redis | Redis (Spring Data Redis) | 缓存 |
| Vue 3 前端 | 复用，不改 | 仅改 API base URL |

**LLM 配置（关键）：**
- Base URL：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- Model：`qwen-plus`
- 请求头：`Authorization: Bearer {API_KEY}`

---

## 三、API 契约（前端已就绪，接口路径必须一致）

### 3.1 行程接口 `TripController`（前缀 `/trip`）

> ⚠️ **2026-08 起，本组接口全部需要 `Authorization: Bearer <token>`**（除 `/auth/*` 外）。未带或无效 token 返回 `401 {success:false, message:"未登录或登录已过期"}`。token 通过 `POST /auth/register` 或 `POST /auth/login` 获取（见 3.5）。

> 🚨 **目的地城市名校验（2026-08 新增）**：`destination` 在进入 Agent 前先经 `CityValidator` 校验——
> 1. **拼错/近音**（如「成堵」）→ `400 {success:false, error:"目的地校验失败", message:"无法识别目的地「成堵」，你是不是想找「成都」？"}`（编辑距离 ≤ 阈值才给建议，不会瞎猜）；
> 2. **假城市/乱码**（如「噜啦啦市」）→ `400`，message「无法识别目的地「噜啦啦市」，请检查城市名是否正确」。这里额外要求高德 geocode 返回的 `formatted_address` **完整包含用户输入**——高德会对乱码做模糊子串匹配（实测「噜啦啦市」匹配到福建某县一家同名小店），仅"能解析"会放行假城市；
> 3. **别名**（如「蓉城」「魔都」）→ 映射为标准名（成都/上海）后继续，成功路径不改语义；
> 4. **成功**：正常返回，不走 400。校验失败时**不启动 Agent**（省一轮 LLM token）。
> 相关：`CityValidator`、`CityValidationResult`、`AmapClient.geocodeInfo`、`CityValidationException`（异常由全局处理器转 400）。

**GET `/trip`** — **当前登录用户**的历史行程列表（语义从"全部"改为"本人"）
- 请求头：`Authorization: Bearer <token>`
- 响应：`{ "total": int, "items": [{ "trip_id", "destination", "summary", "created_at", "updated_at" }] }`

**POST `/trip/generate`** — 生成行程（无轨迹）
- 请求体 `TripRequest`：
```json
{
  "destination": "成都",
  "start_date": "2026-05-01",
  "end_date": "2026-05-03",
  "travelers": 2,
  "budget": 4000,
  "preferences": ["历史", "美食"],
  "pace": "适中",
  "dietary_preferences": ["少辣"],
  "hotel_level": "舒适型",
  "special_notes": "想看日落"
}
```
- 响应：完整 `Itinerary`（见 3.3）

**POST `/trip/generate-with-trace`** — 生成行程 + Agent 轨迹（前端主用）
- 请求体：同上 `TripRequest`
- 响应 `AgentTraceResponse`：
```json
{
  "success": true,
  "itinerary": { ...Itinerary... },
  "trace": [
    { "step": 1, "thought": "分析用户需求...", "action": "plan_search",
      "observation": "...", "tool_calls": [{ "tool": "web_search" }] }
  ],
  "collected_data": { "search_results": {}, "poi_results": {}, "weather_data": {}, "rag_data": {} },
  "token_usage": { "prompt_tokens": 0, "completion_tokens": 0 },
  "errors": []
}
```

**POST `/trip/save`** — 保存行程
- 请求体：`{ "trip_id": string, "itinerary": {Itinerary}, "user_id": string }`
- 响应：`{ "message": string, "trip_id": string }`
- ⚠️ **归属以 token 解析的 userId 为准**：请求体里的 `user_id` 会被服务端忽略覆盖（防伪造归属）。

**GET `/trip/{trip_id}`** — 行程详情（仅本人可见，他人行程返回 404）
- 响应：`{ "trip_id", "itinerary": {Itinerary}, "created_at", "updated_at" }`

**DELETE `/trip/{trip_id}`** — 删除行程（仅本人行程有效，他人行程无操作）
- 响应：`{ "message": string, "trip_id": string }`

### 3.2 天气接口 `WeatherController`（前缀 `/weather`）

**GET `/weather/forecast`**
- 参数：`city`（必填）、`start_date`（选填）、`end_date`（选填）
- 天数规则：`max(3, 旅行天数)`，最长 16 天
- 数据源：优先 Open-Meteo，失败降级高德
- 响应：
```json
{
  "city": "大理",
  "province": null,
  "adcode": null,
  "report_time": null,
  "source": "open-meteo",
  "days": [
    { "date": "2026-05-01", "week": "5", "day_weather": "晴",
      "night_weather": "晴", "day_temp": "23", "night_temp": "16",
      "day_wind": null, "night_wind": null }
  ]
}
```

### 3.3 导出接口 `ExportController`（前缀 `/export`）

> ⚠️ **需要 `Authorization: Bearer <token>`**；仅能导出本人行程，他人行程返回 404。前端已改为 blob 下载（`axios` 自动带 token，避免 URL 跳转丢失鉴权头）。

**GET `/export/{trip_id}/markdown`** — 返回 Markdown 文本
**GET `/export/{trip_id}/pdf`** — 返回 PDF 文件流（需支持中文字体）

### 3.5 认证接口 `AuthController`（前缀 `/auth`，白名单，无需 token）

**POST `/auth/register`** — 注册
- 请求体：`{ "username": string(2-50), "password": string(6-100), "nickname": string(≤50, 选填) }`
- 成功 200：`{ "success": true, "message": "注册成功", "token": "<jwt>", "user": { "id": "1", "username": "...", "nickname": "..." } }`
- 用户名已存在 → `409 { "success": false, "message": "用户名已存在" }`；参数校验失败 → `400`

**POST `/auth/login`** — 登录
- 请求体：`{ "username": string, "password": string }`
- 成功 200：同注册（`token` + `user`）；用户名或密码错误 → `401 { "success": false, "message": "用户名或密码错误" }`

> `user.id` 为**字符串**（与 `trip_record.user_id` 同型）。后续所有受保护接口带 `Authorization: Bearer <token>` 即可。

### 3.4 Itinerary 数据模型

```json
{
  "trip_id": "trip_成都_2026-05-01",
  "destination": "成都",
  "summary": "行程概述",
  "days": [
    {
      "day_index": 1,
      "date": "2026-05-01",
      "theme": "当天主题",
      "spots": [{
        "name": "武侯祠", "start_time": "10:00", "end_time": "12:00",
        "description": "推荐理由", "estimated_cost": 50.0,
        "location": "成都", "image_url": "http://...", "address": "武侯祠大街231号",
        "latitude": 30.64, "longitude": 104.04, "poi_id": "B0FF..."
      }],
      "meals": [{ "name": "马旺子", "meal_type": "午餐", "estimated_cost": 90.0, "notes": "..." }],
      "hotel": { "name": "舒适型酒店（参考）", "level": "舒适型", "estimated_cost": 400.0, "location": "成都", "address": "..." },
      "transport": [{ "mode": "打车", "from_place": "成都 出发点", "to_place": "武侯祠", "estimated_cost": 20.0, "duration": "30 分钟", "distance_km": 8.0, "estimated_minutes": 30 }],
      "notes": ["旅行节奏：适中", "天气提示：..."]
    }
  ],
  "estimated_budget": 4000.0,
  "budget_breakdown": { "transport": 0, "hotel": 0, "meals": 0, "tickets": 0, "other": 0, "total": 0 },
  "tips": ["旅行建议1", "旅行建议2"],
  "source_notes": ["由 ReAct Agent 基于真实数据生成", "本地攻略库命中 5 条（RAG）"],
  "token_usage": { "rewrite_prompt_tokens": 0, "rewrite_completion_tokens": 0,
                   "embedding_prompt_tokens": 0, "embedding_completion_tokens": 0,
                   "planner_prompt_tokens": 0, "planner_completion_tokens": 0,
                   "rerank_prompt_tokens": 0, "rerank_completion_tokens": 0 }
}
```

---

## 四、ReAct Agent 移植（核心）

### 4.1 工具目录（必须原样保留，注入 planning prompt）

```
可用工具目录（由你决定调用哪些）：

1. web_search(query)
   联网搜索目的地的最新攻略、景点、餐厅、交通等信息。
   参数 query: 搜索关键词，如 "成都 历史景点 美食 推荐"
   适合场景：获取攻略文章、实时信息、未收录城市的资料。

2. weather_forecast(location)
   查询目的地未来天气预报（温度、降水概率、天气描述）。
   参数 location: 目的地名称，如 "成都"。
   适合场景：需要了解出行期间天气以安排室内/室外活动。

3. amap_poi(destination, category)
   查询高德地图 POI，返回景点/餐厅/酒店的真实名称、地址、坐标、图片。
   参数 destination: 目的地；category: 景点/餐厅/酒店/购物/交通。
   适合场景：获取结构化地点数据（坐标+图片），用于地图展示。

4. rag_guide(destination)
   检索本地攻略知识库（仅支持：北京/大理/成都/三亚/厦门/西安）。
   参数 destination: 目的地名称。
   适合场景：目的地在知识库内时，获得人工整理的精准攻略片段。
```

### 4.2 循环逻辑

```
MAX_ITERATIONS = 3

for iteration in range(MAX_ITERATIONS):
    if iteration == 0:
        plan = LLM制定搜索计划(注入工具目录)      # THINK
        collected = 并行执行(plan)                # ACT
    else:
        collected = 执行补充动作(decision)         # ACT

    decision = LLM反思(collected概况)             # OBSERVE + THINK

    if decision == enough:
        break
    # 否则继续下一轮补充，达到上限强制结束

result = LLM生成行程(collected)                  # FINAL
```

### 4.3 并行调用 + 超时（Java 实现要点）

- Bing 搜索：CompletableFuture + 线程池执行，`get(8, SECONDS)` 超时
- 天气 / 高德 POI：使用 WebClient / RestClient 异步，各自超时
- 全部 `exceptionally` 捕获，失败记入 gaps，不阻塞主流程
- 总体耗时控制在 8~10 秒

### 4.4 结构化输出容错

LLM 返回后：
1. 正则提取 JSON（找第一个 `{` 到最后一个 `}`）
2. Jackson 解析 + 校验
3. **解析失败 → 把原输出 + 正确 schema 回传 LLM 修正一次**
4. 修正仍失败 → 返回错误，前端显示明确提示，**不用脏数据渲染**

---

## 五、各数据源移植

### 5.1 Bing 搜索（免费，国内可用）

- 直接 HTTP GET `https://www.bing.com/search?q={query}&count=5&setlang=zh-cn`
- 需带浏览器 User-Agent 头
- 解析 HTML 里的搜索结果（`b_algo` 结构）
- 中文关键词直接搜索即可

### 5.2 高德地图 POI（需 API Key）

- Base URL：`https://restapi.amap.com/v3`
- 三个接口：
  - `/place/text`（POI 搜索，`extensions=all` 拿图片）
  - `/geocode/geo`（地址转坐标）
  - `/direction/driving`（驾车路线）
- 所有请求带 `key` 参数
- 参数 `city` 用目的地名，`offset` 分页

### 5.3 Open-Meteo 天气（免费）

- URL：`https://api.open-meteo.com/v1/forecast`
- 参数：`latitude`、`longitude`、`start_date`、`end_date`、`daily=temperature_2m_max,weather_code,precipitation_probability_max`
- 需先把城市名转成坐标（用高德地理编码）
- 天气代码映射：0=晴，1=大部分晴朗，2=多云，3=阴天，61=小雨，63=中雨，65=大雨，80=阵雨，95=雷暴等

### 5.4 RAG 攻略检索

- 攻略数据在 `../zhilv-yuntu-main/backend/data/*.md`（6 个城市）
- 已知城市：北京、大理、成都、三亚、厦门、西安
- Java 实现建议：启动时把 md 切分（按二级/三级标题），向量化后存库
- 检索 query = `目的地 + 偏好 + 节奏 + 备注` 拼接
- 建议用 pgvector 或本地 Lucene，向量模型用 DashScope 的 embedding 接口
- 检索不到或向量库不可用 → 降级为关键词匹配，不阻断

---

## 六、配置项（application.yml / application.properties）

```yaml
llm:
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  api-key: ${LLM_API_KEY}
  model: qwen-plus
  timeout-seconds: 60

amap:
  api-key: ${AMAP_API_KEY}
  base-url: https://restapi.amap.com/v3

redis:
  enabled: true
  url: redis://127.0.0.1:6379/0
  map-ttl-seconds: 86400      # 地图缓存 1 天
  weather-ttl-seconds: 1800   # 天气缓存 30 分钟

known-cities: 北京,大理,成都,三亚,厦门,西安
```

**缓存 Key 设计（保持与前端一致，非必须但建议沿用）：**
```
trip_planner:map:route:{lng1},{lat1}:{lng2},{lat2}
trip_planner:map:place:{关键词}:{城市}:{page_size}
trip_planner:weather:forecast:{城市}
```

---

## 七、数据库设计（MySQL）

```
trip_record
├── id BIGINT PK AUTO_INCREMENT
├── trip_id VARCHAR UNIQUE NOT NULL
├── destination VARCHAR NOT NULL
├── itinerary_json JSON NOT NULL     # 完整行程序列化存储
├── user_id VARCHAR                  # 2026-08 起真正生效：存 users.id，列表/详情/删除按此隔离
├── created_at DATETIME
└── updated_at DATETIME

users                             # 2026-08 新增（表名用 users，避开 MySQL 关键字 user）
├── id BIGINT PK AUTO_INCREMENT
├── username VARCHAR(50) UNIQUE NOT NULL
├── password_hash VARCHAR(100) NOT NULL   # BCrypt 哈希，永不落明文
├── nickname VARCHAR(50)
├── created_at DATETIME
├── updated_at DATETIME
└── deleted TINYINT DEFAULT 0             # @TableLogic 逻辑删除
```

---

## 八、前端对接说明

- 前端在 `frontend/`（已复制）
- `frontend/src/services/api.ts` 中 `VITE_API_BASE_URL` 改为 Java 后端地址
- 接口路径、请求体字段、响应结构必须与本文档第三节完全一致
- 前端调用的接口：
  - `POST /auth/register`、`POST /auth/login`（登录页）
  - `POST /trip/generate-with-trace`（主页生成，axios/fetch 自动带 token）
  - `GET /weather/forecast`（结果页天气）
  - `POST /trip/save`、`GET /trip`、`GET /trip/{id}`、`DELETE /trip/{id}`（历史页）
  - `GET /export/{id}/markdown`、`GET /export/{id}/pdf`（导出，blob 下载）
- 登录态：token 存 localStorage（`ai_travel_token`），axios 请求拦截器统一附 `Authorization: Bearer`；响应 401 清登录态并回到登录页

---

## 九、实施顺序建议

1. **阶段一**：Spring Boot 骨架 + 配置 + 数据模型 + 数据库
2. **阶段二**：高德 POI / 天气 / Bing 三个数据源客户端 + 缓存
3. **阶段三**：ReAct Agent（工具目录 + 循环 + 反思 + 超时）
4. **阶段四**：行程生成 + 结构化输出容错 + Itinerary 组装
5. **阶段五**：控制器 + 保存/历史/导出
6. **阶段六**：RAG 攻略库接入
7. **阶段七**：前端联调 + Docker 部署

---

## 十、验证标准

一个请求从生成到返回，应满足：
- 已知城市（如成都）能生成含真实景点/餐厅的行程，来源说明含"本地攻略库命中 N 条（RAG）"
- 未知城市（如惠州）能降级生成，不报 500
- Agent 轨迹含 4 类 step：plan_search / 工具调用 / assess / generate
- 5 天行程返回 5 天天气（最少 3 天）
- 总体耗时 8~10 秒内

---

## 十一、已修复问题记录（重要！后续改动前必读）

> 以下是在联调过程中发现并修复的问题，**避免重复踩坑**。

### 11.1 端口冲突（本机环境）

| 端口 | 占用者 | 处理 |
|------|--------|------|
| 3306 | 本机 MySQL 服务 | Docker MySQL 映射到 **3310** |
| 3307 | 本机另一个 MySQL 进程 | 别用 3307 |
| 6379 | **本机 Redis 服务**（redis-server.exe） | 不要停它！Java 应用直接用它当缓存 |
| 6380 | Python 版 Docker Redis | Python 版用这个 |

- **Java 版**：Docker 只起 MySQL(3310)，Redis 直接用本机服务(6379)
- **Python 版**：Docker 起全部（前端8080/后端8000/Redis6380）
- **两套不能同时跑**（都抢 8080），切换用 `agent/start-python.ps1` 和 `agent/start-java.ps1`

### 11.2 API Key 加载（spring-dotenv 不可靠，已改用 local profile）

- `application.yml` 里 `spring.profiles.active: local`
- 真实 Key 写在 `src/main/resources/application-local.yml`（该文件已被 .gitignore 排除）
- 需要的 Key：`spring.ai.openai.api-key`（LLM）、`amap.api-key`（高德）、Redis 配置
- ⚠️ 当初 spring-dotenv 读 .env 没生效，导致连错 MySQL，最后放弃 .env 改用 local profile

### 11.3 Spring AI API Key 配置项

Spring AI 自动配置 `openAiChatModel` 要求 `spring.ai.openai.api-key`，不是自定义的 `llm.api-key`。两者都要配（TravelAgent 用自定义 llm，Spring AI 自动配置用它自己的）。

### 11.4 高德 API 返回 30001（RestTemplate 被拒，已改 JDK HttpClient）

- **现象**：`RestTemplate` 调高德地理编码/POI 返回 `{"status":"0","info":"ENGINE_RESPONSE_DATA_ERROR"}`，但 curl 正常
- **根因**：RestTemplate 的请求被高德引擎拒绝（headers 差异）
- **修复**：`AmapClient` 改用 JDK 自带的 `java.net.http.HttpClient`（行为接近 curl），解决
- ⚠️ 如果以后加新的高德接口，**用 HttpClient，别用 RestTemplate**

### 11.5 地图图片/坐标补全（MapEnrichmentService）

- LLM 生成行程后，`MapEnrichmentService.enrich()` 逐个景点调高德 POI，补全 `image_url`/`latitude`/`longitude`/`poi_id`/`address`
- **必须保留**，否则前端地图没图没坐标

### 11.6 交通距离补全（已删除）

- LLM 生成的起终点（如"成都 出发点"）无法可靠地理编码，补全距离全是错的
- **决定删除**交通距离补全，交通显示 LLM 生成的时长文本（如"约20分钟"），不再补 km

### 11.7 Redis 缓存（CacheService + 三个数据源）

- `CacheService` 封装 Redis 读写，Redis 挂了优雅降级（try/catch 跳过，不影响主流程）
- 缓存点：Amap POI/地理编码/路线（TTL 1天）、天气（TTL 30分钟）、RAG（TTL 6小时）
- Key 设计：`trip_planner:map:place:{目的地}:{分类}` 等，统一前缀

### 11.8 真实 token 追踪

- `ItineraryGenerator.callLLM` 解析 LLM 响应的 `usage`，写入 itinerary 的 `planner_prompt_tokens`/`planner_completion_tokens`
- **别丢**：后台 token 统计依赖这个

### 11.9 RAG 命中数来源说明

- `addRagSourceNote` 统计攻略片段数，加入 source_notes：`本地攻略库命中 N 条（RAG）`
- 面试展示 RAG 参与的痕迹

### 11.10 攻略数据位置

- RAG 用的真实攻略在 `src/main/resources/guides/*.md`（从 Python 版 data/ 复制）
- 新增/修改城市攻略后，改这个目录的 md 文件即可，重启生效（RagService 启动时加载）

---

## 十二、用户系统（2026-08 新增）：注册/登录 + JWT + 行程隔离

> 让系统从"单机 demo"变成"完整应用"，多用户数据隔离。答辩价值 ★★★★。

### 12.1 架构决策

| 项 | 方案 |
|----|------|
| 鉴权 | **轻量 JWT + HandlerInterceptor**（`jjwt` 0.12.6 + `spring-security-crypto` 的 `BCryptPasswordEncoder`，**不引入完整 Spring Security 链**） |
| token 存储 | 前端 `localStorage`（`ai_travel_token` / `ai_travel_user`）；无 refresh token |
| 请求鉴权 | axios 请求拦截器统一附 `Authorization: Bearer <token>`；SSE `generate-stream` 是原生 fetch，**显式手动加头** |
| 登录失效 | axios 响应拦截器遇 401 → 清 token + 派发 `auth:unauthorized` 全局事件，App.vue 监听回登录页（`/auth/*` 自身的 401/409 属业务错误，不触发） |
| 导出 | URL 跳转无法带 header，**前端改 blob 下载**（`api.get(url, {responseType:'blob'})` → 生成 Blob URL → a.click()），保留先 save 后导出逻辑 |
| 隔离 | `trip_record.user_id` 真正生效：列表/详情/删除按 userId 过滤；**保存以服务端 token 解析的 userId 为准**（覆盖请求体 `user_id`，防伪造归属） |

### 12.2 关键文件

```
backend/src/main/java/com/yuntu/tripplanner/
├── config/JwtProperties.java      # jwt.secret / expiration-minutes / header / prefix
├── security/JwtUtil.java          # 生成/解析（HS256，secret ≥32 字节）
├── security/UserContext.java      # ThreadLocal 存 userId，afterCompletion 必须 remove（防泄漏）
├── security/JwtAuthInterceptor.java # 解析 Bearer → 校验 → set UserContext；失败写 401 JSON；⚠️ preHandle 必须先放行 OPTIONS（CORS 预检无 Authorization 头，被拦 → 浏览器 fetch 报 Failed to fetch）
├── service/AuthService.java       # register（查重名）/ login（BCrypt.matches）
├── controller/AuthController.java # POST /auth/register、/auth/login
└── model/User.java + repository/UserRepository.java  # users 表实体/DAO
```

`WebConfig` 注册拦截器：路径 `/**`，白名单 `/auth/**` + OPTIONS（CORS 预检）。`application.yml` 新增：

```yaml
jwt:
  secret: ${JWT_SECRET:ai-travel-assistant-demo-jwt-secret-key-change-in-production}
  expiration-minutes: ${JWT_EXPIRATION_MINUTES:10080}
```

### 12.3 契约变更（有意为之，已同步前端）

- **新增** `POST /auth/register`、`POST /auth/login`（见 3.5）
- **`/trip`、`/export` 全部要求 `Authorization: Bearer <token>`**；无 token → 401
- **GET `/trip` 语义**：从"全部行程" → "当前登录用户的行程"
- **非本人行程**：detail → 404，delete → 无操作，export → 404

### 12.4 数据兼容

- 库里已有 `user_id=null` 或 `"frontend_demo_user"` 的旧行程，登录后**不可见**（隔离的自然结果，不迁移）
- 演示时若需看到旧数据：注册同名用户或清空 `trip_record`

### 12.5 明确不做

- 不引入 Spring Security 完整链；无 refresh token / 角色权限 / 记住我 / 找回密码 / 邮箱验证 / 用户资料编辑
- 不改 `trip_record` DDL 与既有 API 路径/响应结构（只加鉴权头与隔离语义）
