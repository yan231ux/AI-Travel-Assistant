package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 攻略片段向量缓存实体（guide_embedding 表）
 *
 * RAG 文本向量的持久化缓存：把每个攻略片段的 embedding 落库，
 * 服务重启后直接加载，避免每次启动对全部片段重新调 embedding API。
 * 以 (source, chunk_title) 唯一；content_hash 检测攻略内容变化触发重算；
 * model 防换 embedding 模型后旧维度向量被误用。
 */
@Data
@TableName("guide_embedding")
public class GuideEmbedding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 攻略文件名，如 chengdu_guide.md */
    @TableField("source")
    private String source;

    /** 片段小节标题 */
    @TableField("chunk_title")
    private String chunkTitle;

    /** 片段内容 SHA-256，内容变化即重新向量化 */
    @TableField("content_hash")
    private String contentHash;

    /** embedding 模型名（换模型时旧向量失效） */
    @TableField("model")
    private String model;

    /** 向量维度 */
    @TableField("dim")
    private Integer dim;

    /** 向量字节（float 序列化，大端，dim*4 字节） */
    @TableField("vector")
    private byte[] vector;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
