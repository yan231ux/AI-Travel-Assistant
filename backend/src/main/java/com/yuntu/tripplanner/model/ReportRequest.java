package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 举报请求（POST /community/reports）。
 * target_type: POST / COMMENT；reason 限定枚举（广告/虚假信息/辱骂/侵权/其他）。
 */
@Data
public class ReportRequest {

    @JsonProperty("target_type")
    private String targetType;

    @JsonProperty("target_id")
    private Long targetId;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("detail")
    private String detail;
}
