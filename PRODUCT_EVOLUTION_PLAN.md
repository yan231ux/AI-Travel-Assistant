# AI 旅游助手产品化改造方案

> 文档版本：v1.0  
> 编写日期：2026-09-07  
> 适用项目：AI-Travel-Assistant

## 1. 文档目标

本文档用于指导系统从“AI 行程生成器”升级为具备首页、个性化景点发现、景点详情、用户旅行内容和推荐闭环的旅行平台。

改造目标不是简单增加几个页面，而是建立以下产品闭环：

```text
用户画像与行为
    -> 个性化景点推荐
    -> 景点详情与攻略内容
    -> 用户收藏、点赞、不感兴趣、发帖
    -> 行为反馈更新画像
    -> 首页与推荐流持续优化
```

核心产品定位：

> 以 AI 行程规划为核心，同时提供个性化景点发现和旅行内容社区的智能旅行平台。

---

## 2. 当前系统评估

### 2.1 已有能力

当前项目已经具备较好的后端基础：

- Spring Boot 3.x + MyBatis-Plus；
- Vue 3 + TypeScript + Vite；
- JWT 登录和用户数据隔离；
- ReAct Agent 行程生成；
- 高德 POI、天气、搜索和本地 RAG 攻略；
- 行程结构化生成与校验清洗；
- 用户画像、问卷和历史行程推断；
- 景点/餐厅收藏和不感兴趣反馈；
- 个性化候选排序；
- 新颖性降权和回避标签；
- 推荐理由和候选证据；
- 推荐日志和个性化效果统计；
- 历史行程、结果页、偏好页和 Agent 轨迹展示。

主要相关实现：

- `backend/src/main/java/com/yuntu/tripplanner/service/UserProfileService.java`
- `backend/src/main/java/com/yuntu/tripplanner/service/PersonalizedRankingService.java`
- `backend/src/main/java/com/yuntu/tripplanner/service/PersonalizedScoreCalculator.java`
- `backend/src/main/java/com/yuntu/tripplanner/service/RecommendationService.java`
- `backend/src/main/java/com/yuntu/tripplanner/service/TripGenerationFinalizer.java`

### 2.2 当前边界

目前个性化推荐主要发生在“生成一次行程时召回 POI 候选并重新排序”这一场景，尚未形成独立的推荐内容产品：

- 没有首页 Dashboard；
- 当前 `/plan` 主要是行程生成表单；
- 没有独立的推荐景点列表页；
- 没有景点详情页；
- 没有用户帖子、评论、点赞和收藏内容；
- 没有帖子审核和举报流程；
- 推荐日志和首页内容推荐尚未完全拆分；
- 景点、帖子、行程之间缺少统一的稳定 ID 关联；
- 帖子行为尚未纳入用户画像闭环。

因此下一阶段应从“增加页面”升级为“补齐内容产品和推荐系统边界”。

### 2.3 本轮改造范围决策

本轮不追求一次性完成“首页、景点、社区、审核后台、图片平台和运营分析”。本轮交付范围确定为：

```text
首页 Dashboard
  -> 推荐景点列表
  -> 景点详情
  -> 收藏 / 不感兴趣
  -> 反馈影响后续推荐
```

本轮暂不把完整用户社区作为发布目标。帖子、评论、审核后台和关注关系进入下一阶段，但本轮景点数据模型、稳定 ID、收藏关系和推荐接口必须预留与社区关联的字段。

| 问题 | 本轮决策 | 原因 |
|---|---|---|
| 改造范围 | 只完成 P0：首页 + 推荐景点 + 详情 + 收藏反馈 | 先让已有个性化能力变成可使用产品，控制改造风险 |
| 景点底座 | 高德候选按需同步落库，RAG 作为可信简介和标签增强 | 10 城攻略卡片真实但数量有限，高德适合覆盖规模；两者职责不同 |
| 审核演示 | 社区不进入本轮上线；下一阶段增加 `role` 字段和最小审核页 | 不为尚未交付的社区功能提前建设完整后台 |
| 图片 | 本轮只使用高德图片 URL；帖子封面延后，第一版可复用景点图 | 暂无对象存储和上传服务，先避免图片基础设施拖慢主链路 |
| 热度 | 本轮不建设完整浏览埋点；热门排序使用已有生成记录、收藏和反馈数据 | 先完成推荐主链路，埋点作为下一阶段内容推荐的基础设施 |

本轮的明确非目标：

- 不做私信、关注、粉丝、等级、直播和交易；
- 不做图片上传、裁剪、审核和对象存储；
- 不做完整社区推荐流；
- 不批量抓取所有城市的全部高德 POI；
- 不为了首页重写现有行程生成链路。

---

## 3. 产品总体结构

建议最终形成以下信息架构：

```text
首页 Dashboard
├── 为你推荐
├── 热门城市
├── 推荐景点
├── 用户旅行帖子
└── 快速生成行程

AI 规划
├── 生成行程
├── Agent 过程
├── 行程结果
└── 历史行程

发现
├── 推荐景点
├── 热门景点
├── 景点搜索
└── 景点详情

社区
├── 推荐帖子
├── 热门帖子
├── 最新帖子
├── 帖子详情
└── 发布帖子

我的
├── 我的收藏
├── 我的帖子
├── 我的偏好
└── 个性化统计
```

### 3.1 推荐路由

```text
/                       首页 Dashboard
/plan                   AI 行程规划
/agent                  Agent 生成过程
/result                 行程结果
/history                历史行程
/recommendations        推荐景点
/spots/:id              景点详情
/community              社区帖子流
/community/posts/:id    帖子详情
/community/create       发布帖子
/favorites              我的收藏
/profile                用户画像和个性化统计
```

当前 `/` 重定向到 `/plan`，建议改为首页；当前行程表单移动到 `/plan`。结果页不再作为长期主导航，而作为一次规划任务完成后的工作区。

### 3.2 顶部导航

建议主导航调整为：

```text
首页 / 规划 / 发现 / 社区 / 我的
```

“结果”不作为固定主导航，因为它依赖当前浏览器内存中的最新行程，刷新后不一定存在；历史行程应从“我的”或历史入口进入。

---

## 4. 首页 Dashboard 方案

### 4.1 首页目标

首页承担四个职责：

1. 让用户快速开始生成行程；
2. 展示个性化景点和内容推荐；
3. 让用户发现新的城市与攻略；
4. 让系统的个性化能力被用户看见。

首页不应只是欢迎页，也不应把完整规划表单全部塞在首屏。

### 4.2 首页模块

#### 4.2.1 用户与画像摘要

展示：

- 用户昵称；
- 当前画像标签；
- 最近去过的城市；
- “完善偏好”入口；
- 搜索城市、景点和帖子。

示例：

```text
早上好，旅行者
轻松节奏 · 偏好自然风景 · 少走路
```

#### 4.2.2 快速入口

提供三个主入口：

```text
生成我的行程
发现景点
看看旅行攻略
```

“生成我的行程”进入 `/plan`；“发现景点”进入 `/recommendations`；“看看旅行攻略”进入 `/community`。

#### 4.2.3 为你推荐

登录用户展示个性化推荐，没有画像的新用户展示热门推荐。

景点卡片至少包含：

- 景点名称；
- 城市；
- 图片；
- 简介；
- 标签；
- 推荐理由；
- 收藏按钮；
- 不感兴趣按钮；
- 加入行程按钮。

推荐理由必须由后端确定性生成，例如：

```text
匹配你的偏好：自然风景、轻松节奏
你还没有去过这里
适合安排在下午，不需要早起
```

#### 4.2.4 热门城市

第一版使用确定性统计：

```text
近 7 天城市浏览量
+ 景点收藏量
+ 帖子互动量
+ 行程生成次数
```

不要在第一版使用 LLM 生成热门榜单。

#### 4.2.5 社区帖子预览

首页展示 3 到 6 篇帖子，显示：

- 封面；
- 标题；
- 摘要；
- 作者；
- 城市；
- 点赞数；
- 收藏数；
- 发布时间。

示例标题：

```text
三亚三日轻松路线，不早起版本
大理适合慢慢逛的 5 个地方
第一次去成都，预算 1500 元怎么安排
```

---

## 5. 推荐景点模块

### 5.1 推荐来源

推荐景点分为两类：

#### 个性化推荐

```text
用户画像
+ 用户行为
+ 历史去过的景点
+ 当前城市
+ 季节与天气
+ 景点数据质量
+ 社区热度
```

#### 公共热门推荐

没有登录用户画像时使用：

```text
景点热度
+ 数据可信度
+ 内容新鲜度
+ 城市关联度
+ 收藏量
```

个性化推荐为空时，不能直接返回空列表，应降级到热门推荐。

### 5.2 推荐接口

建议新增独立的 `RecommendationFeedService`，不要让现有 `RecommendationService` 同时承担行程推荐日志和首页内容推荐。

```http
GET /recommendations/spots
```

参数：

```text
 city                  可选，城市筛选
 page                  页码，默认 1
 pageSize              每页数量
 sort                  personalized / popular / latest
 tags                  可选，标签筛选
```

返回结构：

```json
{
  "items": [
    {
      "id": "spot_001",
      "poiId": "B00001",
      "name": "蜈支洲岛",
      "city": "三亚",
      "imageUrl": "https://example.com/image.jpg",
      "description": "适合海岛休闲和水上活动的景点。",
      "tags": ["自然风景", "海岛"],
      "score": 0.86,
      "recommendReason": "匹配你的自然风景偏好，且你还没有去过",
      "source": "AMAP_AND_RAG",
      "isCollected": false
    }
  ],
  "personalized": true,
  "profileVersion": 3,
  "page": 1,
  "pageSize": 12,
  "total": 1
}
```

不要直接把高德原始 `Map` 暴露给前端，应通过稳定的 DTO 返回。

### 5.2.1 景点数据底座决策：按需同步的混合方案

当前没有独立景点库，不能直接把攻略 Markdown 或高德接口响应当作长期产品数据。本轮采用“高德覆盖规模、RAG 提供可信内容”的混合方案：

```text
用户访问某城市推荐
  -> 读取 spot 表缓存
  -> 缓存不足时调用高德景点搜索
  -> 按 poi_id upsert 到 spot 表
  -> 尝试匹配本地 RAG 景点卡片
  -> 生成推荐 DTO 返回前端
```

职责划分：

- 高德：`poi_id`、名称、地址、坐标、类型、图片 URL；
- RAG 攻略卡片：可靠简介、门票提示、适合人群、标签和事实增强；
- 系统规则：名称规范化、图片去重、简介兜底、数据更新时间和可信度标记。

本轮不批量抓取所有城市全部 POI，只在用户访问城市或生成行程时按需同步；每个城市首批同步 30 到 60 个景点，后续分页补充。高德 API 失败时优先返回数据库快照，再降级为空列表或热门缓存。

建议新增 `spot` 表：

```sql
CREATE TABLE spot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  spot_id VARCHAR(100) NOT NULL,
  poi_id VARCHAR(100),
  name VARCHAR(200) NOT NULL,
  normalized_name VARCHAR(200) NOT NULL,
  city VARCHAR(80) NOT NULL,
  address VARCHAR(500),
  longitude DOUBLE,
  latitude DOUBLE,
  category VARCHAR(200),
  image_url VARCHAR(500),
  description TEXT,
  tags VARCHAR(500),
  source VARCHAR(30) NOT NULL DEFAULT 'AMAP',
  data_quality VARCHAR(30) NOT NULL DEFAULT 'POI_ONLY',
  last_synced_at DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_spot_id (spot_id),
  UNIQUE KEY uk_city_poi (city, poi_id),
  INDEX idx_spot_city (city),
  INDEX idx_spot_updated (updated_at)
);
```

约束：

- `spot_id` 是系统内部稳定 ID；
- `poi_id` 是高德来源 ID，允许为空但不能用名称替代唯一键；
- `source` 区分 `AMAP`、`RAG`、`AMAP_AND_RAG`；
- `data_quality` 区分 `POI_ONLY`、`GUIDE_MATCHED`、`VERIFIED`；
- RAG 未匹配时简介必须使用“该景点简介暂未匹配到真实资料，请参考官方介绍”，不能复制其他景点简介；
- 高德数据只做缓存和展示增强，不能把第三方图片 URL 误标为官方图片。

### 5.3 推荐评分

第一版可以继续使用确定性规则，不急于引入机器学习模型：

$$
Score = 0.30P + 0.20B + 0.15N + 0.15T + 0.10Q + 0.10H
$$

其中：

- $P$：用户偏好匹配度；
- $B$：历史行为匹配度；
- $N$：新颖性；
- $T$：季节、天气和时间匹配度；
- $Q$：数据质量和可信度；
- $H$：社区热度。

当前 `PersonalizedScoreCalculator` 已覆盖偏好、新颖性和回避标签，后续可以逐步补充：

- 景点季节适宜性；
- 景点步行强度；
- 是否适合老人或儿童；
- 门票价格；
- 营业时间；
- 当前城市匹配；
- 社区收藏量；
- 数据更新时间。

### 5.4 推荐流分页

推荐接口必须支持分页和稳定排序，避免一次返回所有景点：

```text
默认每页 12 条
最大每页 50 条
相同分数按热度和更新时间稳定排序
```

后续可支持三个 Tab：

```text
为你推荐 / 热门 / 最新
```

---

## 6. 景点详情页

建议路由：

```text
/spots/:id
```

页面内容：

1. 景点主图和基本信息；
2. 城市、地址、开放时间和门票；
3. 数据来源和更新时间；
4. AI 景点简介；
5. 相关用户帖子；
6. 相关推荐；
7. 收藏；
8. 不感兴趣；
9. 加入行程；
10. 查看地图。

“为什么推荐给你”模块展示确定性信息：

```text
符合你的自然风景偏好
适合轻松节奏
你过去没有体验过
当前季节适合游览
```

景点详情的简介必须经过与行程结果相同的数据校验和描述去重规则，不能因新增页面而绕开 `ItineraryValidator` 的可信度治理。

---

## 7. 用户帖子和社区模块

### 7.1 第一版边界

第一版只做旅行内容社区的最小闭环：

- 发布旅行攻略；
- 发布景点推荐；
- 发布行程；
- 浏览帖子；
- 点赞；
- 收藏；
- 评论；
- 举报。

暂不做：

- 私信；
- 实时聊天；
- 复杂关注关系；
- 粉丝等级；
- 直播；
- 用户交易；
- 过度复杂的积分体系。

这些功能会显著扩大系统复杂度，但不是第一阶段产品价值的核心。

### 7.2 帖子类型

```text
GUIDE                 城市攻略
SPOT_RECOMMENDATION   景点推荐
ITINERARY             行程分享
NOTE                  旅行随笔
```

### 7.3 帖子状态

```text
DRAFT                 草稿
PENDING_REVIEW        待审核
PUBLISHED             已发布
REJECTED              审核拒绝
HIDDEN                被隐藏
DELETED               已删除
```

---

## 8. 数据库设计

### 8.1 `travel_post`

```sql
CREATE TABLE travel_post (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(50) NOT NULL,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(300),
    content TEXT NOT NULL,
    cover_image VARCHAR(500),
    city VARCHAR(80),
    travel_days INT,
    budget DOUBLE,
    pace VARCHAR(20),
    post_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    like_count INT NOT NULL DEFAULT 0,
    favorite_count INT NOT NULL DEFAULT 0,
    view_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    reject_reason VARCHAR(300),
    published_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_post_status_time (status, published_at),
    INDEX idx_post_user_time (user_id, created_at),
    INDEX idx_post_city (city)
);
```

### 8.2 `post_spot`

记录帖子中关联的景点，避免只把景点名称写在正文里：

```sql
CREATE TABLE post_spot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    spot_id VARCHAR(100),
    poi_id VARCHAR(100),
    spot_name VARCHAR(200) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_post_spot (post_id, spot_id, poi_id),
    INDEX idx_post_spot_spot (spot_id)
);
```

### 8.3 `post_interaction`

```sql
CREATE TABLE post_interaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(50) NOT NULL,
    post_id BIGINT NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_post_action (user_id, post_id, action_type),
    INDEX idx_post_action (post_id, action_type)
);
```

行为类型：

```text
VIEW
CLICK
LIKE
FAVORITE
DISLIKE
SHARE
```

### 8.4 `post_comment`

```sql
CREATE TABLE post_comment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    parent_id BIGINT,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    like_count INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_comment_post_time (post_id, created_at),
    INDEX idx_comment_parent (parent_id)
);
```

### 8.5 `content_report`

```sql
CREATE TABLE content_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reporter_id VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    reason VARCHAR(50) NOT NULL,
    detail VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    handled_by VARCHAR(50),
    handled_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_report_status_time (status, created_at)
);
```

### 8.6 稳定 ID 约束

景点、帖子和行程必须使用稳定 ID：

```text
spot_id    系统景点 ID
poi_id     高德 POI ID
post_id    帖子 ID
trip_id    行程 ID
```

名称只能作为兼容和兜底匹配，不能作为主要关联键。

---

## 9. 社区接口设计

### 9.1 帖子列表

```http
GET /community/posts
```

参数：

```text
page
pageSize
city
postType=GUIDE|SPOT_RECOMMENDATION|ITINERARY|NOTE
sort=personalized|popular|latest
keyword
```

### 9.2 帖子详情

```http
GET /community/posts/{postId}
```

返回：

- 帖子正文；
- 作者信息；
- 城市和标签；
- 关联景点；
- 点赞、收藏、浏览数量；
- 当前用户互动状态；
- 评论列表；
- 相关推荐。

### 9.3 创建帖子

```http
POST /community/posts
```

请求示例：

```json
{
  "title": "三亚三日轻松路线",
  "summary": "不早起、少换酒店的三亚旅行计划。",
  "content": "第一天安排三亚湾和海月广场……",
  "coverImage": "https://example.com/cover.jpg",
  "city": "三亚",
  "travelDays": 3,
  "budget": 3200,
  "pace": "轻松",
  "postType": "GUIDE",
  "spots": [
    {
      "spotId": "spot_001",
      "poiId": "B00001",
      "spotName": "三亚湾"
    }
  ]
}
```

### 9.4 提交审核

```http
POST /community/posts/{postId}/submit
```

### 9.5 帖子互动

```http
POST /community/posts/{postId}/like
DELETE /community/posts/{postId}/like
POST /community/posts/{postId}/favorite
DELETE /community/posts/{postId}/favorite
POST /community/posts/{postId}/dislike
```

所有互动接口必须保证幂等，重复点赞不能重复增加计数。

### 9.6 评论

```http
GET  /community/posts/{postId}/comments
POST /community/posts/{postId}/comments
DELETE /community/comments/{commentId}
```

### 9.7 举报

```http
POST /community/reports
```

---

## 10. 内容审核和安全边界

### 10.1 发布流程

```text
编辑内容
    -> 保存草稿
    -> 提交发布
    -> 规则检测
    -> 敏感词和广告检测
    -> 管理员审核
    -> 发布到公开推荐流
```

AI 可以辅助：

- 生成标题；
- 生成摘要；
- 提取城市；
- 提取景点；
- 生成标签；
- 提示内容缺失。

AI 不应直接绕过审核发布用户内容。

### 10.2 需要防止的内容

- 联系方式广告；
- 虚假价格和虚假官方信息；
- 恶意诋毁商家；
- 复制粘贴和低质量灌水；
- 图片侵权；
- 提示词注入；
- 违法或危险活动宣传；
- 伪造“官方推荐”。

### 10.3 权限要求

普通用户只能：

- 管理自己的草稿和帖子；
- 删除自己的帖子；
- 处理自己的评论；
- 举报其他内容。

管理员才能：

- 审核帖子；
- 隐藏帖子；
- 处理举报；
- 删除违规评论；
- 查看审核记录。

### 10.4 审核演示方案

由于当前 `users` 表没有角色字段，本轮社区不作为正式交付，因此不为本轮景点推荐增加审核后台。下一阶段启动社区前，必须先补齐最小管理能力：

1. `users` 增加 `role` 字段，默认 `USER`，可选值为 `USER`、`ADMIN`；
2. 通过一次性初始化 SQL 或环境变量指定首个管理员账号，禁止前端自行把普通用户改成管理员；
3. 增加管理员接口：待审核列表、通过、拒绝、隐藏；
4. 增加最小审核页面，展示标题、正文、作者、关联景点和处理按钮；
5. 所有审核动作记录处理人、处理时间和拒绝原因；
6. 普通用户不能调用管理员接口，服务端必须校验角色，不能只依赖前端隐藏按钮。

演示时使用预置管理员账号登录后台完成审核即可，不采用“默认全部自动通过”冒充审核流程。若下一阶段暂时没有管理页面，至少提供受 `ADMIN` 角色保护的接口和可重复执行的 SQL/HTTP 演示脚本。

### 10.5 图片方案决策

本轮景点卡片只使用 `spot.image_url` 中的高德图片 URL，不引入上传服务。图片加载失败时使用无图占位，不允许把另一景点的图片复制过来。

下一阶段帖子封面按以下顺序演进：

1. 第一版帖子允许不传封面；
2. 用户选择已入库景点时，可使用该景点已有图片作为封面；
3. 需要真实用户上传时，再接入对象存储、文件大小/类型校验、图片审核和 CDN；
4. 数据库只保存对象存储 URL 和资源归属，不保存图片二进制。

在没有对象存储之前，不建议让用户直接填写任意外部图片 URL 作为正式封面。这样既难以控制失效链接，也无法处理版权和恶意资源。

---

## 11. 推荐流设计

推荐流至少提供三个排序标签：

```text
为你推荐 / 热门 / 最新
```

### 11.1 为你推荐

排序因素：

- 用户偏好；
- 收藏和不感兴趣；
- 历史去过内容；
- 当前城市；
- 帖子质量；
- 内容互动；
- 新鲜度。

### 11.2 热门

第一版可以使用：

```text
InteractionScore = 点赞数 + 收藏数 * 2 + 评论数 + 浏览数 * 0.1
```

再加入时间衰减：

$$
HotScore = \frac{InteractionScore}{(AgeHours + 2)^{1.3}}
$$

### 11.3 最新

按发布时间倒序，但必须过滤：

- 未审核内容；
- 已隐藏内容；
- 已删除内容；
- 违规内容；
- 明显重复和低质量内容。

---

## 12. 个性化与社区打通

用户行为应同时影响：

```text
景点推荐
帖子推荐
首页内容
城市推荐
搜索排序
下一次行程生成
```

建议扩展行为对象：

```text
SPOT
RESTAURANT
POST
TRIP
CITY
```

建议扩展行为类型：

```text
VIEW
CLICK
SAVE
DISLIKE
LIKE
SHARE
REPLACE
RATE
```

例如：

```text
用户收藏“大理古镇攻略”
    -> 增加历史文化/慢节奏内容偏好
    -> 推荐相关景点和帖子
    -> 行程生成时提高同类候选优先级
```

用户对帖子点击“不感兴趣”后，应降低相似标签帖子，而不仅仅降低景点标签。

### 12.1 帖子标签

帖子发布时可以由规则和 AI 辅助提取：

```text
城市
旅行风格
节奏
预算区间
适合人群
活动类型
饮食偏好
```

但最终落库的标签应经过格式校验，不能直接相信模型输出。

### 12.2 推荐证据

首页推荐和帖子推荐也需要保留推荐依据：

```text
matchedPreferences
behaviorScore
noveltyScore
contentQualityScore
hotScore
recommendReason
algorithmVersion
```

这样可以回答：

- 为什么推荐这个景点？
- 为什么推荐这篇帖子？
- 为什么没有推荐另一个内容？
- 用户反馈后排序是否发生变化？

---

## 13. 后端模块拆分

建议新增以下模块：

```text
controller/
├── RecommendationController.java
├── SpotController.java
├── PostController.java
├── CommentController.java
├── InteractionController.java
└── ReportController.java

service/
├── RecommendationFeedService.java
├── SpotService.java
├── PostService.java
├── PostModerationService.java
├── PostInteractionService.java
└── ContentTaggingService.java

model/
├── RecommendationItem.java
├── Spot.java
├── TravelPost.java
├── PostComment.java
├── PostInteraction.java
└── ContentReport.java
```

职责边界：

- `RecommendationService`：行程生成后的推荐理由和推荐日志；
- `RecommendationFeedService`：首页、推荐景点和社区内容推荐；
- `SpotService`：景点详情、景点标准化和关联内容；
- `PostService`：帖子 CRUD、分页和发布状态；
- `PostModerationService`：审核、敏感内容和举报处理；
- `PostInteractionService`：点赞、收藏、不感兴趣和行为留痕；
- `ContentTaggingService`：帖子标签提取和景点关联。

不要让 `RecommendationService` 同时承担首页推荐流，否则行程推荐日志与内容推荐流会产生职责混乱。

---

## 14. 前端改造建议

### 14.1 页面拆分

建议将当前行程表单页从 `Home.vue` 调整为：

```text
Home.vue          首页 Dashboard
PlannerView.vue   行程生成表单
```

新增：

```text
Recommendations.vue
SpotDetail.vue
Community.vue
PostDetail.vue
PostEditor.vue
Favorites.vue
```

### 14.2 组件复用

新增公共组件：

```text
SpotCard.vue
PostCard.vue
RecommendationReason.vue
TagList.vue
InteractionBar.vue
EmptyState.vue
LoadingSkeleton.vue
```

其中：

- `SpotCard` 用于首页、推荐列表和景点详情相关推荐；
- `PostCard` 用于首页和社区流；
- `InteractionBar` 统一点赞、收藏、不感兴趣；
- `RecommendationReason` 统一展示后端返回的推荐依据。

### 14.3 交互要求

- 收藏状态刷新页面后不能丢失；
- 点赞和收藏必须有请求中状态；
- 重复点击不能重复提交；
- 网络失败要恢复按钮状态；
- 推荐列表分页加载时显示骨架屏；
- 空画像用户展示热门内容，不显示空白页面；
- 帖子审核中显示明确状态；
- 不感兴趣操作允许撤销。

---

## 15. 分阶段实施计划

### 阶段一：产品骨架和景点发现

目标：让现有个性化能力被用户直接使用。

任务：

1. 将 `/` 改为首页 Dashboard；
2. 将当前行程生成表单移动到 `/plan`；
3. 新增 `RecommendationFeedService`；
4. 新增推荐景点 API；
5. 新增推荐景点列表页；
6. 新增景点详情页；
7. 实现景点收藏和不感兴趣；
8. 使用现有画像和评分器生成推荐理由；
9. 新用户无画像时降级到热门推荐；
10. 建立稳定的 `spot_id` 和 `poi_id` 关联。

阶段验收：

- 登录后首页不再直接进入规划表单；
- 首页可以看到推荐景点；
- 推荐内容可以进入详情页；
- 收藏和不感兴趣会持久化；
- 用户反馈后推荐结果发生可验证变化；
- 后端返回推荐理由；
- 无画像用户也能看到热门内容。

### 阶段二：社区最小闭环

任务：

1. 新增帖子表和帖子关联景点表；
2. 实现草稿保存；
3. 实现发帖和提交审核；
4. 实现帖子列表；
5. 实现帖子详情；
6. 实现点赞和收藏；
7. 实现评论；
8. 实现举报；
9. 增加管理员审核状态；
10. 未审核帖子不得进入公开推荐流。

阶段验收：

- 用户可以发布攻略；
- 用户可以编辑和删除自己的帖子；
- 帖子经过审核后才能公开；
- 点赞和收藏幂等；
- 帖子可以关联景点；
- 景点详情可以查看相关帖子。

### 阶段三：内容推荐和个性化闭环

任务：

1. 帖子行为进入 `UserBehavior`；
2. 帖子标签进入用户画像；
3. 首页加入个性化帖子推荐；
4. 社区加入“为你推荐/热门/最新”；
5. 推荐系统记录帖子推荐证据；
6. 统一景点、帖子和行程的行为模型；
7. 增加推荐流算法版本；
8. 记录推荐点击、收藏和不感兴趣率。

阶段验收：

- 帖子收藏可以影响后续推荐；
- 用户对内容的不感兴趣可以降低同类内容；
- 首页推荐和社区推荐使用画像；
- 推荐结果可解释、可追踪；
- 推荐行为可以进入效果统计。

### 阶段四：平台化增强

任务：

1. 城市专题页；
2. 关注用户；
3. 用户旅行主页；
4. 管理后台；
5. 内容质量评分；
6. 推荐 A/B 实验；
7. 推荐流监控；
8. 内容去重和低质内容识别；
9. 图片上传和存储治理；
10. 全链路审计日志。

---

## 16. 测试和验收标准

### 16.1 后端单元测试

必须覆盖：

- 个性化推荐排序；
- 无画像降级热门推荐；
- 回避标签过滤；
- 已体验景点降权；
- 推荐理由生成；
- 推荐流分页；
- 帖子状态流转；
- 重复点赞幂等；
- 重复收藏幂等；
- 删除权限；
- 审核权限；
- 未审核内容过滤；
- 举报状态流转。

### 16.2 接口测试

至少覆盖：

- 首页推荐接口；
- 景点列表接口；
- 景点详情接口；
- 帖子发布接口；
- 帖子审核接口；
- 帖子点赞接口；
- 帖子收藏接口；
- 评论接口；
- 举报接口。

### 16.3 前端测试

重点检查：

- 未登录跳转；
- 首页首屏加载；
- 推荐卡片操作；
- 景点详情跳转；
- 帖子发布表单校验；
- 分页和空状态；
- 移动端布局；
- 网络失败后的恢复；
- 收藏状态刷新持久化。

### 16.4 最终产品验收

系统应满足：

- 登录后进入真正首页；
- 首页包含个性化景点推荐；
- 没有画像时展示热门推荐；
- 用户可以收藏和不感兴趣；
- 景点可以进入详情页；
- 景点详情可以查看相关攻略；
- 用户可以发布旅行帖子；
- 帖子经过审核才进入公开流；
- 推荐理由由后端生成；
- 行为反馈可以影响后续推荐；
- 重复互动不会重复计数；
- 删除和审核具有权限控制；
- 未审核、隐藏和删除内容不会出现在公开推荐中。

---

## 17. 风险和控制措施

### 17.1 功能范围过大

风险：同时开发首页、景点、社区、评论、关注、私信，导致每个模块都不完整。

控制：先完成“首页 + 推荐景点 + 景点详情 + 收藏反馈”，再做社区最小闭环。

### 17.2 推荐结果不可解释

风险：推荐完全依赖大模型，用户无法知道为什么推荐。

控制：推荐分数、标签命中、回避标签和推荐理由由确定性代码生成；LLM 仅辅助摘要和标签提取。

### 17.3 用户内容污染推荐

风险：低质量或违规帖子进入首页，降低系统可信度。

控制：状态审核、敏感词检测、举报、管理员处理和公开流过滤必须先于推荐接入。

### 17.4 名称匹配导致数据串联

风险：同名景点、别名和名称互含导致收藏、帖子和推荐关联错误。

控制：优先使用 `spot_id` 和 `poi_id`，名称只做兼容兜底。

### 17.5 缓存造成反馈不生效

风险：用户点了不感兴趣，但首页仍返回旧推荐。

控制：用户画像版本或推荐版本纳入缓存键；收藏、不感兴趣和画像更新后主动失效用户推荐缓存。

### 17.6 社区功能带来安全问题

风险：广告、联系方式、恶意文本、图片侵权、提示词注入。

控制：规则审核 + 状态机 + 举报机制 + 管理员后台；不允许模型直接代替最终发布决策。

---

## 18. 热度数据和遗留问题决策

### 18.1 热度数据：本轮从简

本轮不新增完整的城市浏览埋点平台，也不把“热门”包装成精确实时榜单。推荐景点的第一版排序使用已有可追踪数据：

```text
高德候选顺序
+ 已有行程生成中出现次数
+ 用户收藏次数
+ 用户不感兴趣次数的负向修正
+ RAG 命中和数据质量加分
```

城市热门区第一版使用：

```text
最近行程目的地数量
+ 景点收藏关联城市数量
+ 已发布攻略关联城市数量（社区上线后）
```

这些指标只作为“近期热门”近似值，不宣称代表全站真实浏览热度。社区阶段开始后，再新增统一 `content_event` 表记录 `VIEW`、`CLICK`、`LIKE`、`FAVORITE`、`SHARE`，并通过时间衰减计算热门分。不要在 P0 为首页装饰性榜单提前建设复杂分析系统。

### 18.2 遗留问题处理边界

Q1~Q5 不全部塞进本轮，但与首页和景点底座直接相关的项目必须处理：

| 项目 | 本轮处理 | 原因 |
|---|---|---|
| Q1 餐厅区域/距离约束 | 暂缓，列入行程质量专项 | 不阻塞首页景点推荐，但会影响后续“加入行程”质量 |
| Q2 交通费漏算 | 暂缓，列入预算专项 | 不属于发现页主链路，避免同时修改费用模型 |
| Q3 预算超标不收敛 | 暂缓，列入预算专项 | 现有提示和降级先保持稳定，后续单独做生成约束 |
| Q4 攻略命中不足 | 本轮在 `spot` 底座中记录 `data_quality`，无 RAG 时诚实兜底 | 景点详情必须能区分真实攻略和 POI-only 数据 |
| Q5 ProfileView 历史统计过时 | 本轮修复 | 首页画像摘要和个人中心都会直接暴露错误统计 |

Q5 的修复方式采用后端实时聚合或统一 summary 接口，不继续把 `user_profile.trip_count` 和 `visited_cities` 当作实时事实源：

```http
GET /user/profile/summary
```

服务端基于未删除的 `trip_record` 实时计算：

- 历史行程总数；
- 去重目的地数量和列表；
- 最近行程时间；
- 已完成画像字段。

画像表中的统计字段可以保留作缓存或历史推断输入，但首页和 `ProfileView` 展示必须使用 summary 结果。这样不会把 Q5 的过时快照继续扩散到首页。

## 19. 第一批开发任务建议

建议按以下顺序开始编码：

### P0：真正首页

- 新增 Dashboard 页面；
- 调整根路由；
- 将规划表单迁移到 `/plan`；
- 重构顶部导航；
- 首页接入画像摘要。

### P1：推荐景点

- 新增 `RecommendationItem` DTO；
- 新增 `RecommendationFeedService`；
- 新增 `RecommendationController`；
- 实现个性化推荐和热门降级；
- 实现景点推荐列表；
- 实现景点详情；
- 实现收藏和不感兴趣。

### P2：社区最小闭环

- 新增 `travel_post`；
- 新增 `post_spot`；
- 新增 `post_interaction`；
- 新增 `content_report`；
- 实现帖子 CRUD；
- 实现审核状态；
- 实现帖子列表和详情。

### P3：推荐打通

- 帖子行为进入画像；
- 首页增加个性化帖子；
- 社区增加推荐排序；
- 增加帖子推荐日志；
- 增加推荐效果统计。

---

## 20. 最终建议

下一步最适合先实现：

```text
Dashboard 首页
    + RecommendationFeedService
    + 推荐景点列表
    + 景点详情
    + 收藏/不感兴趣
```

这条链路可以最大程度复用现有的画像、POI、RAG、评分器和推荐理由能力，风险和改动面都相对可控。

社区功能建议紧接着实现，但第一版只做：

```text
发帖
审核
列表
详情
点赞
收藏
评论
举报
```

暂时不要优先开发私信、关注、等级、直播和交易功能。先把“发现景点 -> 查看攻略 -> 收藏反馈 -> 影响下一次推荐 -> 生成行程”的核心闭环跑通，系统才会真正从行程工具成长为旅行平台。
