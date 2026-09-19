package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 关注/粉丝列表条目（对外只暴露 id/nickname + 与当前查看者(viewer)的互关状态）。
 *
 * <p>following 字段含义随列表类型不同：
 * <ul>
 *   <li>「我关注的人」列表：following 恒为 true（这些人就是 viewer 关注的）；</li>
 *   <li>「我的粉丝」列表：following 表示「viewer 是否也关注了这位粉丝」（互关标识）。</li>
 * </ul>
 * 该字段由服务端批量反查，避免前端对每条记录再发一次状态查询（N+1）。
 */
@Data
public class FollowUserVO {

    @JsonProperty("id")
    private String id;

    @JsonProperty("nickname")
    private String nickname;

    /** viewer 是否已关注该用户（互关标识，批量反查填充） */
    @JsonProperty("following")
    private boolean following;

    public FollowUserVO() {
    }

    public FollowUserVO(String id, String nickname, boolean following) {
        this.id = id;
        this.nickname = nickname;
        this.following = following;
    }
}
