# AI旅游助手 - Java Spring Boot 实现

基于 Spring Boot 3.x 的 AI 旅行规划系统，使用 ReAct Agent 架构。

## 技术栈

- **框架**: Spring Boot 3.2.1
- **数据库**: MySQL + MyBatis-Plus
- **缓存**: Redis
- **AI**: Spring AI + DashScope/Qwen
- **其他**: Lombok, Jackson, Jsoup

## 项目结构

```
src/main/java/com/yuntu/tripplanner/
├── TripPlannerApplication.java    # 启动类
├── config/                        # 配置类
│   ├── LLMConfig.java
│   ├── AmapConfig.java
│   ├── RedisConfig.java
│   └── ...
├── controller/                    # REST 控制器
│   ├── TripController.java
│   ├── WeatherController.java
│   └── ExportController.java
├── service/                       # 业务逻辑
│   ├── ItineraryGenerator.java
│   └── TripRecordService.java
├── agent/                         # ReAct Agent
│   ├── TravelAgent.java
│   ├── AgentThought.java
│   └── ...
├── client/                        # 外部 API 客户端
│   ├── AmapClient.java
│   ├── OpenMeteoClient.java
│   └── BingSearchClient.java
├── model/                         # 数据模型
│   ├── TripRequest.java
│   ├── Itinerary.java
│   └── ...
└── repository/                    # 数据访问
    └── TripRecordRepository.java
```

## 快速开始

### 1. 环境准备

确保已安装：
- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis

### 2. 配置环境变量

```bash
# LLM API Key（DashScope/Qwen）
export LLM_API_KEY=your_dashscope_api_key

# 高德地图 API Key
export AMAP_API_KEY=your_amap_api_key

# 数据库配置
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=trip_planner
export DB_USERNAME=root
export DB_PASSWORD=your_password

# Redis 配置
export REDIS_URL=redis://localhost:6379/0
```

### 3. 初始化数据库

项目会自动执行 `schema.sql` 创建表结构。

### 4. 编译运行

```bash
cd AI-Travel-Assistant
mvn clean install
mvn spring-boot:run
```

应用将在 `http://localhost:8080` 启动。

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
- `POST /trip/generate` - 生成行程（无轨迹）
- `POST /trip/generate-with-trace` - 生成行程（带Agent轨迹）
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

## 配置说明

详见 `src/main/resources/application.yml`

## 前端对接

前端代码位于 `frontend/` 目录，需要修改 `src/services/api.ts` 中的 `VITE_API_BASE_URL` 指向后端地址。

## License

MIT