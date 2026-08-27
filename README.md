# AI旅游助手 - Java Spring Boot 实现

基于 Spring Boot 3.x 的 AI 旅行规划系统，使用 ReAct Agent 架构。

## 技术栈

- **框架**: Spring Boot 3.2.1（JDK 17）
- **数据库**: MySQL 8 + MyBatis-Plus
- **缓存**: Redis（可选，作为外部 API 结果缓存层）
- **AI**: 直接调用 DashScope 通义千问 OpenAI 兼容接口（自封装 LlmClient / EmbeddingClient，**未引入 Spring AI**），默认模型 `qwen-plus`、向量模型 `text-embedding-v3`
- **部署**: Docker Compose 编排 MySQL + Redis（`backend/docker-compose.yaml`）
- **前端**: Vue 3 + Vite + Ant Design Vue + TypeScript
- **其他**: Lombok, Jackson, Jsoup

## 项目结构

```
src/main/java/com/yuntu/tripplanner/
├── TripPlannerApplication.java    # 启动类
├── config/                        # 配置类（LLM / 高德 / Redis / JWT / CORS 等）
├── controller/                    # REST 控制器
│   ├── AuthController.java        # 注册/登录（返回 JWT）
│   ├── TripController.java        # 行程保存/查询/删除 + 非流式生成
│   ├── TripStreamController.java  # SSE 流式行程生成（核心入口）
│   ├── WeatherController.java     # 天气查询
│   └── ExportController.java      # Markdown / PDF 导出
├── service/                       # 业务逻辑
│   ├── AuthService.java           # 注册登录 + BCrypt + JWT 签发
│   ├── CityValidator.java         # 城市校验（别名/错别字/假城市拦截，进 Agent 前关卡）
│   ├── ItineraryGenerator.java    # 行程编排
│   ├── ItineraryValidator.java    # 行程结果校验（跨天去重/预算/天气备选）
│   ├── MapEnrichmentService.java  # 高德 POI/路线富化
│   ├── RagService.java            # 本地攻略检索（BM25 + 向量 + RRF）
│   ├── CacheService.java          # Redis 缓存外部 API 结果
│   ├── TripRecordService.java     # 行程落库（按用户隔离）
│   └── PdfExportService.java      # PDF 导出
├── agent/                         # ReAct Agent
│   ├── TravelAgent.java           # think→act→observe 循环（≤3 轮）
│   ├── AgentThought.java          # Agent 思考轨迹数据
│   └── ...
├── client/                        # 外部 API 客户端
│   ├── LlmClient.java             # DashScope 对话补全（OpenAI 兼容）
│   ├── EmbeddingClient.java       # DashScope 文本向量化
│   ├── AmapClient.java            # 高德 POI/地理编码/路线
│   ├── OpenMeteoClient.java       # 天气
│   ├── BingSearchClient.java      # 攻略网页检索
│   └── ChromaVectorStore.java     # Chroma 向量库（不可达自动降级内存余弦）
├── model/                         # 数据模型（Lombok @Data）
└── repository/                    # 数据访问（MyBatis-Plus）
```

## 快速开始

### 1. 环境准备

确保已安装：
- JDK 17+
- Maven 3.6+
- Docker / Docker Compose（用来起 MySQL + Redis；不想用 Docker 也可本机自行安装 MySQL 8、Redis 7）
- Node.js 18+（跑前端）

### 2. 启动基础设施（Docker）

后端通过 `backend/docker-compose.yaml` 用 Docker 编排 MySQL 8 与 Redis 7：

```bash
cd backend
docker compose up -d        # 启动 MySQL(映射 3310) + Redis(6379)
```

> 数据库名 `trip_planner`，MySQL root 密码 `root`，与 `application.yml` 默认配置一致。
> 不用 Docker 的话，本机自行安装 MySQL 8（端口 3310）与 Redis 7（端口 6379）并建库 `trip_planner` 即可。

### 3. 配置环境变量

只需配置两个 API Key（其余有默认值，见 `application.yml`）：

```bash
# LLM API Key（DashScope / 通义千问）
export LLM_API_KEY=your_dashscope_api_key

# 高德地图 API Key
export AMAP_API_KEY=your_amap_api_key
```

（可选覆盖项：`DB_USERNAME` / `DB_PASSWORD`（默认 root/root）、`REDIS_URL`（默认 `redis://localhost:6379/0`）、`JWT_SECRET`（生产必须覆盖，≥32 字节）、`CHROMA_ENABLED`（默认开）。）

### 4. 初始化数据库

项目启动时 `application.yml` 中 `spring.sql.init.mode=always` 会自动执行 `schema.sql` 建表，**无需手工初始化**。

### 5. 编译运行后端

```bash
cd AI-Travel-Assistant
mvn clean install
mvn spring-boot:run
```

后端将在 `http://localhost:8080` 启动。

### 6. 运行前端

```bash
cd frontend
npm install
npm run dev          # Vite 开发服务器，默认 http://localhost:5173
```

前端通过 `src/services/api.ts` 中的 `VITE_API_BASE_URL` 指向后端地址（默认 `http://localhost:8080`）。

## 用户系统

**注册/登录 + JWT 鉴权 + 行程按用户隔离**（多用户数据隔离，答辩亮点）：

- `POST /auth/register` — 注册（用户名/密码/昵称），成功返回 JWT token
- `POST /auth/login` — 登录，成功返回 JWT token
- 密码 BCrypt 哈希存储（`users` 表，永不落明文）
- 除 `/auth/*` 外所有接口需携带 `Authorization: Bearer <token>`；`/trip`、`/export` 按用户隔离，他人行程不可见（404）
- 保存行程以服务端 token 解析的 userId 为准，防伪造归属
- 登录失效：前端遇 401 自动清 token 回到登录页；导出改为 blob 下载（URL 跳转无法带鉴权头）

JWT 密钥配置：`application.yml` 中 `jwt.secret`（生产环境务必用 `${JWT_SECRET:...}` 覆盖，密钥 ≥32 字节）。

## API 接口

> ⚠️ 除标注"无需 token"外，以下接口均需请求头 `Authorization: Bearer <token>`。

### 认证接口（无需 token）

- `POST /auth/register` - 注册（body: `{username, password, nickname?}`）→ `{token, user}`
- `POST /auth/login` - 登录（body: `{username, password}`）→ `{token, user}`

### 行程接口

- `GET /trip` - 获取**当前用户**的历史行程列表
- `POST /trip/generate-stream` - **SSE 流式生成行程**（`text/event-stream`，前端实时展示生成过程与 Agent 轨迹，**前端默认走这个入口**）
- `POST /trip/generate` - 生成行程（一次性返回，无轨迹）
- `POST /trip/generate-with-trace` - 生成行程（一次性返回，带 Agent 轨迹）
- `POST /trip/save` - 保存行程（归属以 token 为准）
- `GET /trip/{trip_id}` - 获取行程详情（仅本人）
- `DELETE /trip/{trip_id}` - 删除行程（仅本人）

> **城市名校验**（`destination` 进 Agent 前）：拼错/近音（如「成堵」）→ `400` 并提示「你是不是想找「成都」？」；假城市/乱码（如「噜啦啦市」）→ `400`「请检查城市名是否正确」；别名（「蓉城」「魔都」）→ 自动映射为标准名。校验失败不启动 Agent（省 token）。详见 `MIGRATION_GUIDE.md` 3.1 节。

### 天气接口

- `GET /weather/forecast?city={city}` - 获取天气预报

### 导出接口（需 token，仅本人行程）

- `GET /export/{trip_id}/markdown` - 导出 Markdown
- `GET /export/{trip_id}/pdf` - 导出 PDF

## 核心特性

### ReAct Agent 循环

```
用户输入 → THINK → ACT → OBSERVE → 反思 → FINAL
             ↓        ↓        ↓
          制定计划  并行调用  评估数据
                    工具
```

- **MAX_ITERATIONS**: 3
- **并行调用**: CompletableFuture
- **超时处理**: 8秒

### 数据源

1. **Bing 搜索**: 获取攻略信息
2. **高德地图**: POI 搜索、地理编码、路线规划
3. **Open-Meteo**: 天气预报
4. **RAG 攻略库**: 本地攻略检索（支持6个城市）

### RAG 与向量库（Chroma）

- 攻略片段按 `## / ###` 标题切分，DashScope `text-embedding-v3` 生成 1024 维向量
- **默认检索**：BM25 关键词检索 + 向量余弦相似度 → RRF 融合（无需任何额外服务）
- **加速层（默认开启）**：Chroma 向量数据库（每城市一个集合，`cosine` 距离），向量库不可达时**自动降级**为内存余弦，行为完全一致
- 向量持久化：MySQL `guide_embedding` 表缓存，服务重启免重算

**Chroma 默认开启**，不启动 server 也能跑（自动降级内存余弦，零感知）。想用真实向量库加速，只需启动 server：

```bash
# 1. 安装 chromadb（版本有讲究！见下方说明）
pip install "chromadb==0.6.3"

# 2. 启动 Chroma server（Windows/Linux 均可）
#    ⚠️ 不要用 `chroma run` 或 `python -m chromadb.cli.cli run`（本机实测静默退出）
python -c "from chromadb.cli.cli import app; app()" run --host 127.0.0.1 --port 8000 --path ./chroma-data
# 或 Docker: docker run -d -p 8000:8000 chromadb/chroma:0.6.3

# 3.（可选）彻底关闭 Chroma
CHROMA_ENABLED=false
```

**版本兼容说明（重要）：** `chromadb-java-client` 0.1.x 只支持 Chroma **v1 HTTP API**，而 chromadb **1.x 已移除 v1**（返回 `Gone`/`Unimplemented`）。因此 server 必须用 **0.6.3**（最后一个 v1 版本）。0.6.3 还依赖 `chroma-hnswlib==0.7.6`（无 Windows/py3.12 预编译包），若源码编译失败（缺 MSVC），可先 `pip install chroma-hnswlib==0.7.5` 再装 0.6.3。另外 0.6.3 存在两处已知 bug，本仓库已通过集成测试验证可用，但需注意：
- `GET /api/v1/collections/{name}` 因 auth 协程调用 bug 返回 400 → 需手工 patch 一行（`auth_and_get_tenant_and_database_for_request` 内改为调用 `sync_` 版本），否则 Java 客户端 getCollection 失败；
- `DELETE` 集合端点异常（`cannot unpack coroutine`），清理数据建议直接删 `--path` 数据目录。

未启动 Chroma → 连接立即被拒（非超时等待），自动走内存余弦检索，结果完全一致。Chroma 不可达时每次检索自动降级，服务恢复即自动恢复（不永久缓存失败）。`CHROMA_ENABLED=false` 时则不发起任何连接尝试。

## 扩展攻略库（加城市）

本地攻略是 RAG 检索的内容来源，直接决定"非大模型硬编"的行程质量。**支持的城市完全由 `backend/src/main/resources/guides/` 目录下的 Markdown 文件决定，不需要改任何配置**——这就是"目录即真相源"的设计。

**加一个城市 = 往 `guides/` 放一个文件，重启后端即生效：**

1. 复制 `guides/_TEMPLATE.md`，文件名改为 `{城市拼音}_guide.md`（如 `guangzhou_guide.md`）。
2. 把文件第一行的 `# 城市名旅行攻略` 改成 `# 广州旅行攻略`（H1 标题里的城市名会被自动提取为规范城市名，去掉末尾的"旅行攻略/旅游攻略/攻略"等后缀）。
3. 按模板的小节（`## 1. 目的地简介` / `## 2. 核心景点` / `## 3. 餐饮推荐` / `## 4. 住宿建议` / `## 5. 注意事项` / `## 6. 特殊场景贴士`）补全内容。
4. 重启后端。日志会打印 `RAG攻略库加载完成，共 N 个片段；支持城市 M 个：...`，确认新城市出现在列表里即可。

**约定与注意**

- 文件名以 `_` 开头的文件（如 `_TEMPLATE.md`）会被自动跳过，不纳入攻略库，可放心用作模板/说明。
- 每个攻略文件必须有且仅有一个 H1（`# 标题`），否则该文件不会被识别为支持城市（启动日志会告警）。
- 检索按"城市"聚合：同一城市的内容放一个文件即可，无需拆分。
- 当前已内置 10 个城市攻略：北京、大理、成都、三亚、厦门、西安、上海、杭州、重庆、丽江。超出这些城市的行程会降级为模型生成（仍可正常出结果，只是不享受私有攻略的准确度）。

## 配置说明

详见 `src/main/resources/application.yml`

## 前端对接

前端代码位于 `frontend/` 目录（Vue 3 + Vite + Ant Design Vue + TypeScript），需要修改 `src/services/api.ts` 中的 `VITE_API_BASE_URL` 指向后端地址（默认 `http://localhost:8080`）。

## License

MIT