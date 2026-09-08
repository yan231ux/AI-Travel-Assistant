package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子标签（对应 post_tag 表，产品化阶段三：内容个性化闭环的标签底座）。
 *
 * <p>帖子本身没有天然标签，用户在社区收藏/点赞/不感兴趣的内容要能影响画像
 * （从而影响景点推荐、帖子推荐与下一次行程生成），必须先把帖子映射到
 * 与景点同词表的偏好标签（travel_style/food/pace/city），映射规则纯词典零 LLM，
 * 见 {@code PostTagResolver}（关联景点 → 风格标签；正文关键词 → 风格/节奏；帖子字段 → 城市）。
 * 标签在「帖子可进公开流」后按需落库（浏览/反馈时惰性补齐），(post_id, category, tag) 唯一。
 */
@Data
@TableName("post_tag")
public class PostTag {

    /** 来源：帖子关联景点映射（可信度高） */
    public static final String SOURCE_SPOT_LINK = "SPOT_LINK";
    /** 来源：标题/摘要/正文关键词扫描 */
    public static final String SOURCE_CONTENT = "CONTENT";
    /** 来源：帖子结构化字段（city/pace） */
    public static final String SOURCE_POST_FIELD = "POST_FIELD";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("post_id")
    private Long postId;

    /** 偏好域：travel_style/pace/city（与 user_preference.category 同词表） */
    @TableField("category")
    private String category;

    /** 偏好标签（自然风景/历史文化/轻松/城市名…，与 user_preference.tag 同词表） */
    @TableField("tag")
    private String tag;

    /** 来源：SPOT_LINK / CONTENT / POST_FIELD */
    @TableField("source")
    private String source;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
