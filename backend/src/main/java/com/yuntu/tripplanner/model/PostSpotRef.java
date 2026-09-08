package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 帖子关联景点引用（创建/更新请求入参 + 详情返回共用）。
 * 关联优先 spot_id/poi_id；spot_name 为展示快照（POST 正文提到但未入景点库时允许只给名称）。
 */
@Data
public class PostSpotRef {

    @JsonProperty("spot_id")
    private String spotId;

    @JsonProperty("poi_id")
    private String poiId;

    @JsonProperty("spot_name")
    private String spotName;

    @JsonProperty("image_url")
    private String imageUrl;
}
