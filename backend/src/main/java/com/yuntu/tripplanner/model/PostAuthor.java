package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 帖子作者信息（对外只暴露 id/nickname，不泄露用户名体系细节） */
@Data
public class PostAuthor {

    @JsonProperty("id")
    private String id;

    @JsonProperty("nickname")
    private String nickname;

    public PostAuthor() {
    }

    public PostAuthor(String id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }
}
