-- AI旅游助手数据库初始化脚本

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS trip_planner DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE trip_planner;

-- 用户表（注册/登录；表名用 users 避免 MySQL 的 user 关键字）
-- role 列：阶段二社区新增（USER/ADMIN），阶段五扩为管理端角色集
-- （USER/CONTENT_REVIEWER/CITY_EDITOR/RECOMMENDATION_OPERATOR/SUPER_ADMIN）。
-- 新建库直接含此列；存量库由启动期自动迁移补列/加宽（见 config/SchemaAutoUpgrade.java）。
-- 宽度取 32：最长角色码 RECOMMENDATION_OPERATOR 为 23 字符，留余量给后续角色，
-- 避免"新增角色时分配成功但写库截断"这类只在特定角色上才暴露的坑。
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名（登录名）',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希',
    nickname VARCHAR(50) COMMENT '昵称',
    role VARCHAR(32) DEFAULT 'USER' COMMENT '角色：USER/CONTENT_REVIEWER/CITY_EDITOR/RECOMMENDATION_OPERATOR/SUPER_ADMIN',
    account_status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE/SUSPENDED（治理：暂停账号）',
    post_limited TINYINT DEFAULT 0 COMMENT '限制发帖标记（治理）',
    comment_banned TINYINT DEFAULT 0 COMMENT '暂停评论标记（治理）',
    violation_count INT DEFAULT 0 COMMENT '违规次数（举报成立自动累计）',
    last_login_at DATETIME COMMENT '最近登录时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 行程记录表
CREATE TABLE IF NOT EXISTS trip_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    trip_id VARCHAR(100) UNIQUE NOT NULL COMMENT '行程唯一标识',
    destination VARCHAR(50) NOT NULL COMMENT '目的地',
    itinerary_json JSON NOT NULL COMMENT '完整行程序列化数据',
    user_id VARCHAR(50) COMMENT '用户ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_trip_id (trip_id),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行程记录表';

-- Agent轨迹表（可选，用于详细记录）
CREATE TABLE IF NOT EXISTS agent_trace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    trip_id VARCHAR(100) NOT NULL COMMENT '行程ID',
    step INT NOT NULL COMMENT '步骤序号',
    thought TEXT COMMENT '思考内容',
    action VARCHAR(50) COMMENT '动作类型',
    observation TEXT COMMENT '观察结果',
    tool_calls JSON COMMENT '工具调用记录',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_trip_id (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent轨迹表';

-- 攻略片段向量缓存表（RAG embedding 持久化，服务重启免重算）
-- 以 (source, chunk_title) 唯一；content_hash 检测攻略内容变化触发重算；
-- model 防换 embedding 模型后旧向量被误用（维度不一致时余弦恒为 0）。
CREATE TABLE IF NOT EXISTS guide_embedding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    source VARCHAR(100) NOT NULL COMMENT '攻略文件名',
    chunk_title VARCHAR(200) NOT NULL COMMENT '片段小节标题',
    content_hash CHAR(64) NOT NULL COMMENT '片段内容SHA-256',
    model VARCHAR(50) NOT NULL COMMENT 'embedding模型名',
    dim INT NOT NULL COMMENT '向量维度',
    vector LONGBLOB NOT NULL COMMENT '向量字节(float序列化,大端)',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_source_title (source, chunk_title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='攻略片段向量缓存';

-- 用户画像主档（个性化阶段一：结构化画像；每用户一行，由偏好问卷与历史行程推断合并维护）
-- 逗号分隔字段存列表（MyBatis 无需 typeHandler）；weight/置信度粒度在 user_preference 明细表。
CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID（与 trip_record.user_id 同型）',
    travel_styles VARCHAR(255) COMMENT '旅行风格（逗号分隔：自然风景/历史文化/美食探索/城市漫游/拍照打卡/户外运动…）',
    pace_preference VARCHAR(20) COMMENT '节奏偏好（轻松/适中/紧凑）',
    hotel_preference VARCHAR(20) COMMENT '住宿偏好（经济型/舒适型/高档型）',
    budget_preference DOUBLE COMMENT '预算参考（历史行程均值，元/次；无历史为NULL）',
    food_preferences VARCHAR(255) COMMENT '口味偏好（逗号分隔：火锅/海鲜/本帮菜/…）',
    dietary_restrictions VARCHAR(255) COMMENT '饮食约束（逗号分隔：少辣/不吃香菜/不吃葱/清真…）',
    behavior_notes VARCHAR(255) COMMENT '行为约束（逗号分隔：不早起/少换酒店/少走路/优先公共交通/避开人多的景点/适合老人或儿童）',
    visited_cities VARCHAR(500) COMMENT '去过的城市（逗号分隔，历史行程统计；新颖性控制用）',
    trip_count INT DEFAULT 0 COMMENT '画像统计时的历史行程数',
    filled_from_questionnaire TINYINT DEFAULT 0 COMMENT '是否填写过偏好问卷',
    profile_version INT DEFAULT 1 COMMENT '画像版本号（每次显式修改+1）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户画像主档';

-- 用户偏好明细（个性化阶段一：带权重/置信度/来源的偏好，阶段二反馈增量更新与阶段三打分均基于此表）
-- (user_id, category, tag) 唯一：同一偏好重复出现时按规则增量更新（见 5.3 画像更新规则）
CREATE TABLE IF NOT EXISTS user_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    category VARCHAR(30) NOT NULL COMMENT '偏好域：travel_style/pace/hotel/food/dietary/behavior',
    tag VARCHAR(50) NOT NULL COMMENT '偏好标签（自然风景/轻松/舒适型/少辣/…）',
    weight DOUBLE NOT NULL DEFAULT 0.5 COMMENT '偏好权重 0~1（个性化打分用）',
    confidence DOUBLE NOT NULL DEFAULT 0.5 COMMENT '置信度 0~1（来源可信度：主动选择最高）',
    source VARCHAR(30) NOT NULL COMMENT '来源：QUESTIONNAIRE/FEEDBACK/HISTORY_INFER/LLM_INFER',
    last_observed_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次被观察到的时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_category_tag (user_id, category, tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户偏好明细';

-- 用户行为反馈表（个性化阶段二：收藏/不感兴趣/替换/评分等行为的留痕，供画像增量更新与实验指标统计）
CREATE TABLE IF NOT EXISTS user_behavior (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    trip_id VARCHAR(100) COMMENT '行程ID（行为发生在某次行程上，可为空）',
    item_type VARCHAR(20) NOT NULL COMMENT '对象类型：SPOT/RESTAURANT/TRIP',
    item_id VARCHAR(100) COMMENT '对象ID（景点高德POI ID或行程trip_id）',
    item_name VARCHAR(200) COMMENT '对象名称（便于留痕追溯）',
    poi_type VARCHAR(200) COMMENT '对象高德业态类型（景点反馈→旅行风格标签用）',
    action_type VARCHAR(20) NOT NULL COMMENT '行为类型：VIEW/CLICK/SAVE/DISLIKE/REPLACE/REGENERATE/RATE',
    rating INT COMMENT 'RATE 行为时的评分 1~5',
    aspect_json VARCHAR(500) COMMENT '附带上下文（如不满意的方面列表 JSON）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_trip (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户行为反馈';
-- 推荐日志表（个性化阶段四：可解释/可追溯 —— 记录每次行程里每个景点的个性化依据与得分）
-- 生成收尾由 RecommendationService 写入：命中偏好标签（hit_preference=1）计入偏好命中率分子；
-- preference_score = 命中画像权重的加权累加；explanation 存可读理由（供结果页与答辩展示）
CREATE TABLE IF NOT EXISTS recommendation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    trip_id VARCHAR(100) COMMENT '行程ID（该推荐发生在哪次行程）',
    item_name VARCHAR(200) NOT NULL COMMENT '推荐对象名称（景点/餐厅）',
    item_type VARCHAR(20) NOT NULL DEFAULT 'SPOT' COMMENT '对象类型：SPOT/RESTAURANT',
    preference_score DOUBLE DEFAULT 0 COMMENT '偏好匹配分（命中画像权重×置信度加权）',
    novelty_score DOUBLE DEFAULT 1 COMMENT '新颖性分（已体验过则降）',
    distance_penalty DOUBLE DEFAULT 0 COMMENT '距离惩罚（预留；候选层无成组锚点暂不计算）',
    final_score DOUBLE DEFAULT 0 COMMENT '最终分（口径见 PersonalizedRankingService）',
    hit_preference TINYINT DEFAULT 0 COMMENT '是否命中正偏好标签（偏好命中率分子）',
    explanation VARCHAR(300) COMMENT '推荐理由（人读："匹配你的偏好：历史文化"）',
    profile_version INT DEFAULT 0 COMMENT '打分时的画像版本（与结果缓存 key 同源，行为反馈失效缓存用）',
    ranking_version INT DEFAULT 1 COMMENT '排序算法版本（与候选证据同源，防规则变更后复用旧日志）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_trip (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推荐日志（个性化阶段四）';
-- 候选证据表（个性化口径统一轮：PLAN §2.2 问题一 —— 保存候选阶段的完整排序证据，
-- 名称/原始顺序/偏好分/新颖性/回避/是否入选，与 recommendation_log 互补：
-- 日志记「最终入选者的依据」，本表记「候选全集的排序过程」，支持结果页与答辩展示"为什么这样排/为什么没推荐"）
CREATE TABLE IF NOT EXISTS candidate_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    trip_id VARCHAR(100) NOT NULL COMMENT '行程ID（该次生成的候选证据）',
    bucket VARCHAR(20) NOT NULL COMMENT '候选桶：景点/餐厅',
    item_name VARCHAR(200) NOT NULL COMMENT '候选名称',
    poi_id VARCHAR(64) COMMENT '高德 POI id（P0②多级关联优先键；餐厅候选/无命中时为空）',
    longitude DOUBLE COMMENT '候选经度（高德坐标，经纬度近似匹配兜底）',
    latitude DOUBLE COMMENT '候选纬度（高德坐标，经纬度近似匹配兜底）',
    original_order INT NOT NULL COMMENT '候选原始顺序（检索返回序，从0起）',
    preference_score DOUBLE DEFAULT 0 COMMENT '偏好匹配分（命中画像 权重×置信度 加权累加）',
    novelty_score DOUBLE DEFAULT 1 COMMENT '新颖性分（历史行程出现过则 0.75）',
    distance_penalty DOUBLE DEFAULT 0 COMMENT '距离惩罚（预留；候选层无成组锚点暂不计算）',
    final_score DOUBLE DEFAULT 0 COMMENT '最终分（统一口径见 PersonalizedScoreCalculator）',
    matched_tags VARCHAR(200) COMMENT '命中偏好标签（/ 分隔，无则空）',
    avoid_tag VARCHAR(100) COMMENT '命中的回避标签（无则空）',
    hard_avoid TINYINT DEFAULT 0 COMMENT '是否触发硬约束沉底（回避标签命中且非点名豁免）',
    visited TINYINT DEFAULT 0 COMMENT '是否历史行程中出现过',
    selected TINYINT DEFAULT 0 COMMENT '最终是否入选行程',
    profile_version INT DEFAULT 0 COMMENT '打分时的画像版本（行为反馈失效缓存用）',
    ranking_version INT DEFAULT 1 COMMENT '排序算法版本（防规则变更后复用旧证据）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_trip (trip_id),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='候选证据（个性化排序口径）';

-- 【已存在库升级提示】schema.sql 只在全新库建表；若 recommendation_log 已是旧表，
-- 需手动补两列后再重启（否则收尾写日志报 Unknown column）：
--   ALTER TABLE recommendation_log
--       ADD COLUMN profile_version INT DEFAULT 0 COMMENT '打分时的画像版本' AFTER explanation,
--       ADD COLUMN ranking_version INT DEFAULT 1 COMMENT '排序算法版本' AFTER profile_version;
-- 存量 candidate_evidence 表（P0② 多级关联）需补三列后再重启（否则收尾插证据报 Unknown column）：
--   ALTER TABLE candidate_evidence
--       ADD COLUMN poi_id VARCHAR(64) COMMENT '高德 POI id（P0②多级关联优先键）' AFTER item_name,
--       ADD COLUMN longitude DOUBLE COMMENT '候选经度' AFTER poi_id,
--       ADD COLUMN latitude DOUBLE COMMENT '候选纬度' AFTER longitude;

-- =====================================================================
-- 产品化改造（PRODUCT_EVOLUTION_PLAN 2026-09-07 批次A：景点底座）
-- =====================================================================

-- 景点主档表（产品化阶段一：推荐景点列表/详情的数据底座）
-- 数据来源=高德按需同步（POI 覆盖规模）+ RAG 攻略卡片回填（可信简介/标签增强）；
-- spot_id 是系统内部稳定 ID，poi_id 是高德来源 ID，名称只做兼容兜底（防同名/别名串联）。
-- data_quality：POI_ONLY（仅高德，无攻略）/ GUIDE_MATCHED（命中本地攻略卡片）/ VERIFIED（攻略+校验）
CREATE TABLE IF NOT EXISTS spot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    spot_id VARCHAR(100) NOT NULL COMMENT '系统景点稳定ID（如 spot_{city}_{poi_id}）',
    poi_id VARCHAR(100) COMMENT '高德POI ID（来源键；允许为空但不能替代唯一键）',
    name VARCHAR(200) NOT NULL COMMENT '景点名称',
    normalized_name VARCHAR(200) NOT NULL COMMENT '标准化名称（去后缀/空白，供名称匹配兜底）',
    city VARCHAR(80) NOT NULL COMMENT '所在城市',
    address VARCHAR(500) COMMENT '地址',
    longitude DOUBLE COMMENT '经度（高德坐标）',
    latitude DOUBLE COMMENT '纬度（高德坐标）',
    category VARCHAR(200) COMMENT '高德业态类型（风景名胜/…）',
    image_url VARCHAR(500) COMMENT '图片URL（高德，仅作展示增强，不标官方）',
    description TEXT COMMENT '简介（优先RAG攻略卡片回填；无则诚实兜底文案）',
    tags VARCHAR(500) COMMENT '标签（逗号分隔：自然风景/历史文化/…，RAG/规则提取）',
    source VARCHAR(30) NOT NULL DEFAULT 'AMAP' COMMENT '数据来源：AMAP/RAG/AMAP_AND_RAG',
    data_quality VARCHAR(30) NOT NULL DEFAULT 'POI_ONLY' COMMENT '可信度：POI_ONLY/GUIDE_MATCHED/VERIFIED',
    last_synced_at DATETIME COMMENT '最近一次从高德同步时间',
    -- 数据治理列（设计方案 §5 景点数据运营中心，B组）：人工修正/下线/标记/合并全部落库留痕，
    -- 高德再同步只更新「未人工锁定」的字段，保证管理员修正不被自动同步无条件覆盖。
    status VARCHAR(20) NOT NULL DEFAULT 'ONLINE' COMMENT '上下架状态：ONLINE/OFFLINE（OFFLINE=管理员下线，不推荐不展示）',
    flag VARCHAR(30) COMMENT '治理标记：NON_SPOT非景点/CLOSED已关闭/OUTDATED已过时/ERROR_POI错误POI',
    flag_reason VARCHAR(500) COMMENT '治理原因（管理员填写，随审计日志留痕）',
    manual_override TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否有人工修正记录（同步时跳过被锁定字段）',
    manual_override_fields VARCHAR(300) COMMENT '人工锁定字段（逗号分隔：name,address,description,tags…）',
    last_verified_by VARCHAR(50) COMMENT '最近人工审核人（用户名）',
    last_verified_at DATETIME COMMENT '最近人工审核时间',
    merged_into VARCHAR(100) COMMENT '重复合并目标 spot_id（本行保留为下线别名指向主行）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_spot_id (spot_id),
    UNIQUE KEY uk_city_poi (city, poi_id),
    INDEX idx_spot_city (city),
    INDEX idx_spot_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='景点主档（推荐/详情数据底座）';

-- 用户景点收藏表（产品化阶段一：独立景点卡收藏，/favorites 我的收藏数据源）
-- 与 user_behavior 分工：本表管「收藏了哪些景点」（幂等唯一键、取消收藏、状态查询）；
-- SAVE/DISLIKE 行为仍写 user_behavior（画像权重更新与实验指标用）。
-- (user_id, spot_id) 唯一：同一用户重复收藏同景点幂等，不重复计数。
CREATE TABLE IF NOT EXISTS user_spot_favorite (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    spot_id VARCHAR(100) NOT NULL COMMENT '系统景点ID',
    poi_id VARCHAR(100) COMMENT '高德POI ID（关联行程/证据用）',
    name VARCHAR(200) NOT NULL COMMENT '景点名称快照（收藏时落库，展示免联表）',
    city VARCHAR(80) COMMENT '景点城市',
    image_url VARCHAR(500) COMMENT '景点图片快照',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_spot (user_id, spot_id),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户景点收藏';

-- =====================================================================
-- 产品化阶段二：社区最小闭环（PRODUCT_EVOLUTION_PLAN §8，2026-09-07 批次）
-- 状态机：DRAFT→PENDING_REVIEW→PUBLISHED；REJECTED 可改后重提；HIDDEN/DELETED 不进公开流。
-- =====================================================================

-- 用户帖子主档（社区：旅行攻略/景点推荐/行程分享/随笔；审核后才进公开流）
CREATE TABLE IF NOT EXISTS travel_post (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '作者用户ID',
    title VARCHAR(120) NOT NULL COMMENT '标题',
    summary VARCHAR(300) COMMENT '摘要',
    content TEXT NOT NULL COMMENT '正文',
    cover_image VARCHAR(500) COMMENT '封面图URL（第一版无上传：可复用景点图或空）',
    city VARCHAR(80) COMMENT '关联城市',
    travel_days INT COMMENT '行程天数',
    budget DOUBLE COMMENT '预算（元）',
    pace VARCHAR(20) COMMENT '节奏：轻松/适中/紧凑',
    post_type VARCHAR(30) NOT NULL DEFAULT 'NOTE' COMMENT '类型：GUIDE/SPOT_RECOMMENDATION/ITINERARY/NOTE',
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/HIDDEN/DELETED',
    like_count INT NOT NULL DEFAULT 0 COMMENT '点赞数（计数器，防重复计数由 post_interaction 唯一键保证）',
    favorite_count INT NOT NULL DEFAULT 0 COMMENT '收藏数',
    view_count INT NOT NULL DEFAULT 0 COMMENT '浏览数',
    comment_count INT NOT NULL DEFAULT 0 COMMENT '评论数',
    reject_reason VARCHAR(300) COMMENT '审核拒绝原因（REJECTED 时）',
    published_at DATETIME COMMENT '发布时间（PUBLISHED 时）',
    -- 阶段四任务 5/8：内容质量分与低质标记（确定性规则，提交审核时计算落库；存量库由 SchemaAutoUpgrade 自动补列）
    quality_score INT DEFAULT 0 COMMENT '内容质量分 0~100（确定性规则，提交时计算）',
    low_quality TINYINT DEFAULT 0 COMMENT '低质标记（内容过短/缺结构，提交时判定 0/1）',
    -- 审查报告 P1-1：已发布帖子编辑走"版本化"，主表始终服务线上公开版本；
    -- 待审的编辑版本 id 落此列（NULL = 无待审修改）
    pending_revision_id BIGINT COMMENT '待审核的编辑版本ID（NULL=无；见 travel_post_revision）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_post_status_time (status, published_at),
    INDEX idx_post_user_time (user_id, created_at),
    INDEX idx_post_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户帖子（社区）';

-- 帖子编辑版本（审查报告 P1-1：公开版本 / 编辑版本分离）
-- travel_post 始终服务线上公开版本；作者编辑已发布帖子时内容落本表待审，
-- 审核通过才原子回写主表（published_at 不变），拒绝则主表仍是原公开版本。
CREATE TABLE IF NOT EXISTS travel_post_revision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    post_id BIGINT NOT NULL COMMENT '帖子ID',
    revision_no INT NOT NULL DEFAULT 1 COMMENT '版本号（同帖内自增，1 起）',
    title VARCHAR(120) NOT NULL COMMENT '标题（版本快照）',
    summary VARCHAR(300) COMMENT '摘要',
    content TEXT NOT NULL COMMENT '正文',
    cover_image VARCHAR(500) COMMENT '封面图URL',
    city VARCHAR(80) COMMENT '关联城市',
    travel_days INT COMMENT '行程天数',
    budget DOUBLE COMMENT '预算（元）',
    pace VARCHAR(20) COMMENT '节奏：轻松/适中/紧凑',
    post_type VARCHAR(30) COMMENT '类型：GUIDE/SPOT_RECOMMENDATION/ITINERARY/NOTE',
    spots_json TEXT COMMENT '关联景点快照JSON：[{spot_id,poi_id,spot_name}]',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/APPROVED/REJECTED/SUPERSEDED',
    reject_reason VARCHAR(300) COMMENT '审核拒绝原因',
    reviewed_by VARCHAR(50) COMMENT '审核人（管理员ID或 system:ai）',
    reviewed_at DATETIME COMMENT '审核时间',
    editor_id VARCHAR(50) COMMENT '提交该版本的用户ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_rev_post (post_id, revision_no),
    INDEX idx_rev_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子编辑版本（公开版本/编辑版本分离）';

-- 帖子关联景点（结构化关联；spot_id/poi_id 优先，名称仅兜底展示）
CREATE TABLE IF NOT EXISTS post_spot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    post_id BIGINT NOT NULL COMMENT '帖子ID',
    spot_id VARCHAR(100) COMMENT '系统景点ID（可为空：帖子提到但未入景点库）',
    poi_id VARCHAR(100) COMMENT '高德POI ID',
    spot_name VARCHAR(200) NOT NULL COMMENT '景点名称（展示快照）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '顺序',
    UNIQUE KEY uk_post_spot (post_id, spot_id, poi_id),
    INDEX idx_post_spot_spot (spot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子关联景点';

-- 帖子互动（点赞/收藏/不喜欢；幂等由 (user,post,action) 唯一键保证，重复点击不重复计数）
CREATE TABLE IF NOT EXISTS post_interaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    post_id BIGINT NOT NULL COMMENT '帖子ID',
    action_type VARCHAR(20) NOT NULL COMMENT '动作：LIKE/FAVORITE/DISLIKE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_post_action (user_id, post_id, action_type),
    INDEX idx_post_action (post_id, action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子互动';

-- 帖子评论（PUBLISHED 可见；作者/管理员可删 → status=DELETED 软删）
CREATE TABLE IF NOT EXISTS post_comment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    post_id BIGINT NOT NULL COMMENT '帖子ID',
    user_id VARCHAR(50) NOT NULL COMMENT '评论用户ID',
    parent_id BIGINT COMMENT '回复的父评论ID（一级评论为空）',
    content VARCHAR(1000) NOT NULL COMMENT '评论内容',
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED',
    like_count INT NOT NULL DEFAULT 0 COMMENT '评论点赞数（预留）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_comment_post_time (post_id, created_at),
    INDEX idx_comment_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子评论';

-- 内容举报（帖子/评论；同一用户对同一对象仅一条有效举报，PENDING→处理）
CREATE TABLE IF NOT EXISTS content_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    reporter_id VARCHAR(50) NOT NULL COMMENT '举报人用户ID',
    target_type VARCHAR(30) NOT NULL COMMENT '对象类型：POST/COMMENT',
    target_id BIGINT NOT NULL COMMENT '对象ID',
    reason VARCHAR(50) NOT NULL COMMENT '举报原因（广告/虚假信息/辱骂/侵权/其他）',
    detail VARCHAR(500) COMMENT '补充说明',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/DISMISSED',
    handled_by VARCHAR(50) COMMENT '处理人用户ID',
    handled_at DATETIME COMMENT '处理时间',
    handle_note VARCHAR(500) COMMENT '处理备注（管理员处理时填写，供追溯）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_reporter_target (reporter_id, target_type, target_id),
    INDEX idx_report_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容举报';

-- =====================================================================
-- 产品化阶段三：内容推荐与个性化闭环（PRODUCT_EVOLUTION_PLAN §12/§15，2026-09-07 批次）
-- 帖子标签：把帖子映射到与景点同词表的画像标签（travel_style/pace/city），
-- 让社区收藏/点赞/不感兴趣能影响景点推荐、帖子推荐与下一次行程生成。
-- =====================================================================

-- 帖子标签表（阶段三：帖子→画像标签的持久化底座）
-- 规则纯词典（PostTagResolver：关联景点→风格、正文关键词→风格/节奏、字段→城市），
-- 帖子可进公开流后惰性补齐；(post_id, category, tag) 唯一幂等。
CREATE TABLE IF NOT EXISTS post_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    post_id BIGINT NOT NULL COMMENT '帖子ID',
    category VARCHAR(30) NOT NULL COMMENT '偏好域：travel_style/pace/city（与 user_preference 同词表）',
    tag VARCHAR(80) NOT NULL COMMENT '偏好标签（自然风景/历史文化/轻松/城市名…）',
    source VARCHAR(20) NOT NULL DEFAULT 'CONTENT' COMMENT '来源：SPOT_LINK（关联景点）/CONTENT（正文关键词）/POST_FIELD（帖子字段）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_post_category_tag (post_id, category, tag),
    INDEX idx_post_tag_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子画像标签（阶段三）';

-- 帖子推荐流曝光日志（阶段三任务 5/8：推荐证据 + 效果统计分母）
-- 「社区为你推荐」每次向登录用户返回一页时写一页曝光（帖子/位置/得分/理由/画像与算法版本）；
-- 与 user_behavior(item_type=POST) 的点击/收藏/不感兴趣相除得到推荐效果率。
CREATE TABLE IF NOT EXISTS post_feed_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    post_id BIGINT NOT NULL COMMENT '帖子ID',
    sort VARCHAR(20) NOT NULL DEFAULT 'recommended' COMMENT '推荐排序类型（recommended，A/B预留）',
    position INT NOT NULL COMMENT '该页内排序位置（0起）',
    final_score DOUBLE DEFAULT 0 COMMENT '个性化最终分（口径见 PersonalizedScoreCalculator#evaluatePost）',
    hit_preference TINYINT DEFAULT 0 COMMENT '是否命中正偏好标签（帖子推荐命中率分子）',
    recommend_reason VARCHAR(300) COMMENT '推荐理由（人读："匹配你的偏好：历史文化"）',
    profile_version INT DEFAULT 0 COMMENT '打分时的画像版本',
    ranking_version INT DEFAULT 2 COMMENT '帖子推荐流算法版本（阶段三起为 2，区别于景点/行程的 1）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_feed_user_created (user_id, created_at),
    INDEX idx_feed_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子推荐流曝光日志（阶段三）';

-- 管理员初始化与存量库升级说明（勿手删）：
-- 1) 存量 users 表缺 role 列：启动期 SchemaAutoUpgrade 自动补（无需手改）；
-- 2) 首个管理员账号：启动期 AdminInitializer 读取 community.admin-username/admin-password
--    （默认 admin/admin123，仅演示用，生产必须用环境变量覆盖）创建并置为 ADMIN。

-- =====================================================================
-- 产品化阶段四：平台化增强（PRODUCT_EVOLUTION_PLAN §15，2026-09-07 批次）
-- 阶段四B：关注关系（任务2/3 用户主页）
-- =====================================================================

-- 用户关注表（阶段四任务 2：关注用户。关注方向：user_id 关注 follow_user_id；
-- 幂等由 (user_id, follow_user_id) 唯一键保证；取消关注删除行即可）
CREATE TABLE IF NOT EXISTS user_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '关注者用户ID',
    follow_user_id VARCHAR(50) NOT NULL COMMENT '被关注者用户ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_follow (user_id, follow_user_id),
    INDEX idx_follow_target (follow_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户关注关系（阶段四）';


-- =====================================================================
-- 产品化阶段四E：推荐 A/B 实验 + 推荐流监控（PRODUCT_EVOLUTION_PLAN §15 任务 6/7）
-- 2026-09-07 批次
-- =====================================================================

-- A/B 实验注册表（任务 6）
-- 实验 = 作用于某条推荐流的一版可对照策略：feed_type 定作用域（SPOT_FEED/POST_FEED）、
-- strategy 定处理组行为（QUALITY_GATE/LOW_QUALITY_FILTER）、traffic/control 定参与与对照比例。
-- 同一 feed_type 只允许一个 ACTIVE 实验；关闭后换参需重建（参数参与哈希，中途换参会让用户换桶）。
CREATE TABLE IF NOT EXISTS ab_experiment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    exp_name VARCHAR(80) NOT NULL COMMENT '实验名（代码引用键，唯一）',
    description VARCHAR(300) COMMENT '实验说明',
    feed_type VARCHAR(20) NOT NULL COMMENT '作用域：SPOT_FEED=推荐景点流 / POST_FEED=帖子推荐流',
    strategy VARCHAR(30) NOT NULL COMMENT '处理组策略：QUALITY_GATE / LOW_QUALITY_FILTER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=进行中 / CLOSED=已关闭',
    traffic_percent INT NOT NULL DEFAULT 100 COMMENT '参与流量 1~100',
    control_percent INT NOT NULL DEFAULT 50 COMMENT '对照组占参与流量比例 0~100',
    started_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    closed_at DATETIME COMMENT '关闭时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_ab_exp_name (exp_name),
    INDEX idx_ab_exp_feed_status (feed_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='A/B 实验注册表（阶段四）';

-- A/B 实验用户分桶记录（任务 6）
-- 同用户同实验只落一桶（确定性哈希 + 幂等落行），保证实验期不变桶、曝光可按变体干净累计。
-- 不参与流量的用户不落行。
CREATE TABLE IF NOT EXISTS ab_assignment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    exp_name VARCHAR(80) NOT NULL COMMENT '实验名',
    variant VARCHAR(20) NOT NULL COMMENT '变体：CONTROL / TREATMENT',
    assigned_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '分桶时间',
    UNIQUE KEY uk_ab_user_exp (user_id, exp_name),
    INDEX idx_ab_assignment_exp (exp_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='A/B 实验用户分桶（阶段四）';

-- 推荐景点流曝光日志（任务 7，镜像 post_feed_log 口径）
-- 仅真正个性化分支写；spot_id/poi_id 双键（poi_id 与 user_behavior(item_type=SPOT).item_id 同键，
-- 供反馈漏斗关联）；ab_variant 记录所属实验变体供对照。
CREATE TABLE IF NOT EXISTS spot_feed_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    spot_id VARCHAR(120) NOT NULL COMMENT '系统景点稳定ID',
    poi_id VARCHAR(100) COMMENT '高德POI ID（行为反馈关联键）',
    city VARCHAR(80) COMMENT '城市',
    sort VARCHAR(20) NOT NULL DEFAULT 'recommended' COMMENT '排序类型',
    position INT NOT NULL COMMENT '页内排序位置（0起）',
    final_score DOUBLE DEFAULT 0 COMMENT '个性化最终分',
    hit_preference TINYINT DEFAULT 0 COMMENT '是否命中正偏好（命中率分子）',
    data_quality VARCHAR(30) COMMENT '候选数据质量：POI_ONLY/GUIDE_MATCHED/VERIFIED',
    recommend_reason VARCHAR(300) COMMENT '推荐理由（人读）',
    profile_version INT DEFAULT 0 COMMENT '打分时画像版本',
    ranking_version INT DEFAULT 1 COMMENT '景点/行程统一评分器版本',
    ab_variant VARCHAR(20) COMMENT '所属A/B变体（CONTROL/TREATMENT；无实验为NULL）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_spot_feed_user_created (user_id, created_at),
    INDEX idx_spot_feed_spot (spot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推荐景点流曝光日志（阶段四）';

-- 存量库升级说明（勿手删）：post_feed_log 缺 ab_variant 列 → 启动期 SchemaAutoUpgrade 自动补，
-- 无需手工 ALTER；ab_experiment / ab_assignment / spot_feed_log 为全新表，由本文件自动创建。

-- 全链路审计日志（阶段四任务 10，2026-09-07 批次）
-- 记录"值得事后追溯"的操作（注册登录/行程保存删除/发帖与审核/举报处理/隐藏下架/
-- 关注取关/画像问卷/A-B实验等），谁/何时/对什么/附加上下文；不存敏感凭据，
-- 高吞吐互动（点赞/浏览）不进审计。写入 fail-soft，审计失败不影响主流程。
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    actor_id VARCHAR(50) COMMENT '操作者用户ID（系统/匿名动作为空）',
    category VARCHAR(20) NOT NULL COMMENT '分类：USER/TRIP/CONTENT/ADMIN/SOCIAL/PROFILE/OPS',
    action VARCHAR(50) NOT NULL COMMENT '动作标识：post_approved/trip_saved…',
    target_type VARCHAR(30) COMMENT '对象类型：post/comment/report/trip/experiment/user',
    target_id VARCHAR(80) COMMENT '对象ID：帖子/举报/行程ID或实验名等',
    detail VARCHAR(1000) COMMENT '附加上下文JSON（≤1000截断）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    INDEX idx_audit_actor_time (actor_id, created_at),
    INDEX idx_audit_category_time (category, created_at),
    INDEX idx_audit_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全链路审计日志（阶段四）';

-- =====================================================================
-- 管理后台内容运营中心（管理员后台与内容运营中心设计方案 2026-09-08：攻略骨架）
-- 数据主权：数据库已发布攻略版本 = 唯一线上主数据；RAG 分片/向量 = 派生数据；
-- classpath guides/*.md 仅作初始化导入与备份载体（导入幂等：source_file+content_hash）。
-- =====================================================================

-- 攻略主档（版本化：current_revision_id 当前编辑版 / published_revision_id 线上可见版；
-- 编辑已发布内容 → 追加新版本并转 PENDING_REVIEW，审核通过才原子切换 published_revision_id）
CREATE TABLE IF NOT EXISTS city_guide (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    city VARCHAR(80) NOT NULL COMMENT '城市名',
    title VARCHAR(200) NOT NULL COMMENT '标题',
    summary VARCHAR(500) COMMENT '摘要',
    content_markdown MEDIUMTEXT COMMENT '当前编辑版正文（镜像 current_revision，列表预览免join）',
    cover_image VARCHAR(500) COMMENT '封面图URL',
    source_type VARCHAR(20) NOT NULL DEFAULT 'SYSTEM' COMMENT '来源：CURATED/COMMUNITY/IMPORTED/SYSTEM',
    source_name VARCHAR(120) COMMENT '来源名称（机构/文件等）',
    source_file VARCHAR(120) COMMENT '初始导入来源文件名（溯源用，不代表线上读该文件）',
    author VARCHAR(80) COMMENT '维护人/作者',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/HIDDEN',
    quality_score INT DEFAULT 0 COMMENT '内容质量分 0~100（确定性规则）',
    reject_reason VARCHAR(300) COMMENT '审核拒绝原因（REJECTED 时）',
    current_revision_id BIGINT COMMENT '当前编辑版本ID',
    published_revision_id BIGINT COMMENT '线上可见版本ID（发布时原子切换）',
    rag_status VARCHAR(20) NOT NULL DEFAULT 'NOT_INDEXED' COMMENT 'NOT_INDEXED/DEFERRED/READY/FAILED',
    rag_indexed_revision BIGINT COMMENT '最后成功进入RAG的版本ID',
    version INT NOT NULL DEFAULT 0 COMMENT '版本计数（当前版本号）',
    submitted_by VARCHAR(50) COMMENT '提交审核人用户ID',
    reviewed_by VARCHAR(50) COMMENT '审核人用户ID',
    published_at DATETIME COMMENT '最近发布时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_guide_source_file (source_file),
    INDEX idx_guide_city_status (city, status),
    INDEX idx_guide_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='攻略主档（管理后台内容运营）';

-- 攻略版本（append-only：每次保存追加 revision_no 递增的不可变版本；
-- content_hash=SHA-256，幂等导入/无变化保存判重依据）
CREATE TABLE IF NOT EXISTS city_guide_revision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    guide_id BIGINT NOT NULL COMMENT '攻略ID',
    revision_no INT NOT NULL COMMENT '版本号（1 起递增）',
    content_hash CHAR(64) NOT NULL COMMENT '正文SHA-256',
    content_markdown MEDIUMTEXT NOT NULL COMMENT '版本正文快照',
    change_summary VARCHAR(300) COMMENT '变更说明',
    editor_id VARCHAR(50) COMMENT '编辑人用户ID',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_rev_guide (guide_id, id),
    INDEX idx_rev_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='攻略版本（append-only）';

-- 推荐人工干预（设计方案 §6.3 /admin/recommendations）：
-- 运营在推荐链路上的低风险干预，一律带原因+生效窗口+审计；与算法分数严格分离
-- （干预不写进任何 score 字段，只在排序层做"黑名单剔除/置顶/降权"，载荷里单独出 interventions meta）。
-- (target_type, target_id, action) 唯一：同对象同动作重复保存 = 覆盖窗口/原因，不留多行历史。
CREATE TABLE IF NOT EXISTS recommendation_intervention (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    target_type VARCHAR(16) NOT NULL COMMENT '对象类型：SPOT(景点) / CITY(城市)',
    target_id VARCHAR(64) NOT NULL COMMENT 'SPOT=spot_id / CITY=城市名',
    action VARCHAR(24) NOT NULL COMMENT 'PIN置顶 / DEMOTE降权 / BLACKLIST推荐黑名单 / FEATURED城市精选',
    reason VARCHAR(255) COMMENT '运营原因（必填，展示与审计）',
    effective_from DATETIME COMMENT '生效时间（空=立即）',
    effective_until DATETIME COMMENT '失效时间（空=长期）',
    created_by VARCHAR(50) COMMENT '创建人用户ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_intervention_target_action (target_type, target_id, action),
    INDEX idx_intervention_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推荐人工干预（运营动作登记，审计留痕）';

-- 攻略-景点关联（骨架：解析"核心景点"卡片写入；spot_id/poi_id 匹配主档待后续接入）
CREATE TABLE IF NOT EXISTS city_guide_spot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    guide_id BIGINT NOT NULL COMMENT '攻略ID',
    revision_id BIGINT NOT NULL COMMENT '版本ID',
    spot_name VARCHAR(200) NOT NULL COMMENT '景点名称（解析自 Markdown）',
    spot_id VARCHAR(100) COMMENT '系统景点ID（待关联）',
    poi_id VARCHAR(100) COMMENT '高德POI ID（待关联）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '出现顺序',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_guide_spot_guide_rev (guide_id, revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='攻略-景点关联（骨架）';

-- 攻略标签（骨架：与画像同词表 travel_style，供后续城市专题/推荐组织）
CREATE TABLE IF NOT EXISTS city_guide_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    guide_id BIGINT NOT NULL COMMENT '攻略ID',
    revision_id BIGINT NOT NULL COMMENT '版本ID',
    category VARCHAR(30) NOT NULL DEFAULT 'travel_style' COMMENT '标签域（travel_style…）',
    tag VARCHAR(80) NOT NULL COMMENT '标签（自然风景/历史文化/…）',
    source VARCHAR(20) NOT NULL DEFAULT 'CONTENT_PARSE' COMMENT '来源',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_guide_tag_guide_rev (guide_id, revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='攻略标签（骨架）';

-- RAG 索引任务（发布后按 revision 派生索引；过渡期标记 DEFERRED——检索源仍为 classpath Markdown）
CREATE TABLE IF NOT EXISTS rag_index_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    guide_id BIGINT NOT NULL COMMENT '攻略ID',
    revision_id BIGINT NOT NULL COMMENT '版本ID（任务按版本绑定，杜绝旧任务覆盖新版本）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DEFERRED/READY/FAILED',
    error_message VARCHAR(500) COMMENT '失败/推迟原因',
    triggered_by VARCHAR(50) COMMENT '触发人用户ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    finished_at DATETIME COMMENT '结束时间',
    INDEX idx_rag_task_guide (guide_id, id),
    INDEX idx_rag_task_revision (revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 索引任务（攻略发布派生索引）';

-- 通用行程事件（设计方案 §7.1，阶段二数据地基）：
-- 产品分析专用事件流，与 user_behavior（画像反馈）分工。统计分析（采用率/保存率/收藏热度）走本表。
-- 去重口径 §7.2：UNIQUE(user_id, trip_id, item_id, event_type) —— 同用户同行程同事件只记一次；
-- trip_id 为空（如收藏）统一存 ''（MySQL NULL 不参与唯一约束）。
-- item_id 口径：spot_id → poi_id → "name:" 前缀名称兜底（稳定 ID 优先，跨来源可关联）。
CREATE TABLE IF NOT EXISTS travel_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    session_id VARCHAR(64) COMMENT '页面会话ID（可空）',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：TRIP_GENERATED/TRIP_SAVED/SPOT_GENERATED/SPOT_SAVED/SPOT_FAVORITED/SPOT_ADD_TO_PLAN',
    item_type VARCHAR(16) NOT NULL COMMENT '对象类型：TRIP/SPOT',
    item_id VARCHAR(100) NOT NULL COMMENT '对象ID（spot_id/poi_id/name:xxx/trip_id）',
    item_name VARCHAR(200) COMMENT '名称快照（当时名称，防主档改名后对不上）',
    city VARCHAR(64) COMMENT '城市',
    trip_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联行程ID（无则空串）',
    source VARCHAR(32) COMMENT '来源：AGENT/SAVE/FAVORITE',
    metadata_json TEXT COMMENT '扩展元数据JSON',
    stat_date DATE NOT NULL COMMENT '统计日期（=created_at日期部分，预聚合分区口径）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    UNIQUE KEY uk_event_dedup (user_id, trip_id, item_id, event_type),
    INDEX idx_event_type_date (event_type, stat_date),
    INDEX idx_event_item_date (item_type, item_id, stat_date),
    INDEX idx_event_city_date (city, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用行程事件（产品分析事件流）';

-- 景点热度按天预聚合（设计方案 §7.3）：事件明细 → 天级汇总，首页/可视化读聚合结果，
-- 不再扫描全量事件表。DELETE+INSERT 幂等重算，同一天重复聚合结果收敛。
CREATE TABLE IF NOT EXISTS spot_trending_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    stat_date DATE NOT NULL COMMENT '统计日期',
    item_id VARCHAR(100) NOT NULL COMMENT '景点ID（spot_id/poi_id/name:xxx，与事件口径一致）',
    item_name VARCHAR(200) COMMENT '名称快照',
    city VARCHAR(64) COMMENT '城市',
    generated_count INT NOT NULL DEFAULT 0 COMMENT '被规划采用次数（SPOT_GENERATED）',
    saved_count INT NOT NULL DEFAULT 0 COMMENT '随行程保存次数（SPOT_SAVED）',
    favorited_count INT NOT NULL DEFAULT 0 COMMENT '被收藏次数（SPOT_FAVORITED）',
    user_count INT NOT NULL DEFAULT 0 COMMENT '去重用户数（任意事件）',
    planning_user_count INT NOT NULL DEFAULT 0 COMMENT '规划采用去重用户数（SPOT_GENERATED，日级）',
    click_count INT NOT NULL DEFAULT 0 COMMENT '详情点击次数（user_behavior CLICK，日级）',
    dislike_count INT NOT NULL DEFAULT 0 COMMENT '负反馈次数（user_behavior DISLIKE，日级）',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_trending_date_item (stat_date, item_id),
    INDEX idx_trending_city_date (city, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='景点热度按天预聚合';

-- 城市热度按天预聚合（设计方案 §7.3）：与 spot_trending_daily 同源（travel_event），
-- 供首页/看板的城市维度读取，避免每次打开都扫描全量事件表。
CREATE TABLE IF NOT EXISTS city_trending_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    stat_date DATE NOT NULL COMMENT '统计日期',
    city VARCHAR(64) NOT NULL COMMENT '城市',
    trip_generated_count INT NOT NULL DEFAULT 0 COMMENT '行程生成次数（TRIP_GENERATED）',
    trip_saved_count INT NOT NULL DEFAULT 0 COMMENT '行程保存次数（TRIP_SAVED）',
    spot_adopt_count INT NOT NULL DEFAULT 0 COMMENT '景点被规划采用次数（SPOT_GENERATED）',
    user_count INT NOT NULL DEFAULT 0 COMMENT '去重活跃用户数',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_city_trending_date_city (stat_date, city),
    INDEX idx_city_trending_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='城市热度按天预聚合';

-- 内容审核按天预聚合（设计方案 §7.3）：读 content_moderation_task（AI 审核任务表），
-- 供看板审核漏斗/风险构成按天读取；与事件表无关，故单独建表。
CREATE TABLE IF NOT EXISTS content_moderation_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    stat_date DATE NOT NULL COMMENT '统计日期（取任务创建时间所在日）',
    total_count INT NOT NULL DEFAULT 0 COMMENT '任务总量',
    auto_passed_count INT NOT NULL DEFAULT 0 COMMENT '自动放行（PASSED）',
    review_count INT NOT NULL DEFAULT 0 COMMENT '转人工复核（REVIEW）',
    failed_count INT NOT NULL DEFAULT 0 COMMENT 'AI 失败（FAILED）',
    human_approved_count INT NOT NULL DEFAULT 0 COMMENT '人工通过（decision=APPROVE）',
    human_rejected_count INT NOT NULL DEFAULT 0 COMMENT '人工拒绝（decision=REJECT）',
    rule_hit_count INT NOT NULL DEFAULT 0 COMMENT '规则命中任务数',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_moderation_daily_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容审核按天预聚合';

-- AI 内容审核任务（设计方案 §4.4，阶段三）：
-- 流水线：规则检查（确定可解释）→ AI 结构化初筛 → 阈值聚合 → 自动放行/人工复核 → 人工决策 → 审计。
-- 任务绑 content_hash（攻略再绑 revision_id）防"旧 AI 结果覆盖新内容"；
-- 同目标同 hash 未终局任务不重复建。AI 只是建议，管理员覆盖结论（decision 列）必须填原因。
CREATE TABLE IF NOT EXISTS content_moderation_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    target_type VARCHAR(16) NOT NULL COMMENT '对象类型：POST/COMMENT/GUIDE/SPOT',
    target_id VARCHAR(64) NOT NULL COMMENT '对象ID',
    revision_id BIGINT COMMENT '攻略版本ID（防旧结果覆盖新版本）',
    content_hash VARCHAR(64) NOT NULL COMMENT '内容指纹 SHA-256(type+标题+正文)',
    task_type VARCHAR(20) NOT NULL DEFAULT 'SAFETY' COMMENT '任务类型：SAFETY/QUALITY/FACT_CHECK/DUPLICATE',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/PASSED/REVIEW/FAILED',
    risk_level VARCHAR(16) COMMENT 'LOW/MEDIUM/HIGH/CRITICAL',
    risk_score DOUBLE COMMENT 'AI 风险分 0~1',
    model_name VARCHAR(64) COMMENT 'AI 模型名',
    prompt_version VARCHAR(20) COMMENT '提示词版本',
    result_json TEXT COMMENT 'AI 结构化输出',
    matched_rules_json TEXT COMMENT '规则命中JSON',
    rule_hit_count INT NOT NULL DEFAULT 0 COMMENT '规则命中条数',
    content_title VARCHAR(200) COMMENT '内容标题快照',
    content_text MEDIUMTEXT COMMENT '内容正文快照（详情页追溯）',
    city VARCHAR(64) COMMENT '城市',
    error_message VARCHAR(500) COMMENT '失败原因',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    decision VARCHAR(16) COMMENT '管理员最终决策：APPROVE/REJECT',
    decision_by VARCHAR(50) COMMENT '决策人',
    decision_reason VARCHAR(255) COMMENT '决策原因（覆盖必填）',
    decided_at DATETIME COMMENT '决策时间',
    created_by VARCHAR(50) COMMENT '触发人（内容作者/管理员）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    finished_at DATETIME COMMENT '流水线结束时间',
    INDEX idx_mod_status_created (status, created_at),
    INDEX idx_mod_target (target_type, target_id),
    INDEX idx_mod_hash (target_type, target_id, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 内容审核任务（规则+AI 初筛+人工复核）';

-- =====================================================================
-- ReAct 优化批次 3：采集方案存档复用（agent_plan_archive）
-- =====================================================================
-- 背景：autonomous 模式下模型每轮自由决定调哪些工具，同一输入两次生成可能拿到不同候选池，
-- 使"个性化结果可复现、变化可归因"被引入第二个自变量（工具选择）而说不清。
-- 解法：同用户+同目的地+同偏好"只自主一次"——首轮走完整自主链路，把最终生效的工具计划
-- 落库存档；再次生成直接复用存档计划，候选池因此稳定，个性化排序的输入被钉死。
-- 副产品：复用命中省掉一次 think LLM 调用（自主模式 think 是大头），是最实在的"压 token"。
-- plan_key 与 TravelAgent#buildPlanCacheKey 同源（只取影响工具选择的参数，排除日期/人数/预算）。
CREATE TABLE IF NOT EXISTS agent_plan_archive (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    plan_key VARCHAR(255) NOT NULL COMMENT '复用键：userId|destination|preferences|pace|hotelLevel|dietary|specialNotes',
    user_id VARCHAR(50) COMMENT '用户ID（从 plan_key 拆出，便于按用户排查/清理）',
    destination VARCHAR(64) COMMENT '目的地（从 plan_key 拆出，便于按城市排查）',
    plan_json TEXT NOT NULL COMMENT '方案快照JSON：{"planDescription":...,"toolCalls":[{"tool":...,"query":...}]}',
    plan_desc VARCHAR(500) COMMENT '计划说明（人读，进 trace 标注"沿用上次成功的采集方案"）',
    source VARCHAR(32) COMMENT '方案来源：autonomous-native/autonomous-text/legacy-text',
    tool_count INT NOT NULL DEFAULT 0 COMMENT '工具调用数量',
    observation_summary VARCHAR(1000) COMMENT '关键观察摘要（各数据源实际规模，复用是否成立的证据）',
    reuse_count INT NOT NULL DEFAULT 0 COMMENT '被复用次数（每次命中+1，可解释性）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_plan_key (plan_key),
    INDEX idx_plan_user (user_id, updated_at),
    INDEX idx_plan_dest (destination, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ReAct 采集方案存档（自主一次+复用，稳定候选池/压 token）';
