package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行程记录实体
 */
@Data
@TableName(value = "trip_record", autoResultMap = true)
public class TripRecord {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    @TableField("trip_id")
    private String tripId;
    
    @TableField("destination")
    private String destination;
    
    @TableField(value = "itinerary_json", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Itinerary itinerary;
    
    @TableField("user_id")
    private String userId;
    
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 是否已确认去过（确认去过功能：规划默认 0=仅规划；用户确认后置 1=去过） */
    @TableField("visited_confirmed")
    private Boolean visitedConfirmed;
}