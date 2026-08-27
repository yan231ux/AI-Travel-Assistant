-- AI旅游助手数据库初始化脚本

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS trip_planner DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE trip_planner;

-- 用户表（注册/登录；表名用 users 避免 MySQL 的 user 关键字）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名（登录名）',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希',
    nickname VARCHAR(50) COMMENT '昵称',
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