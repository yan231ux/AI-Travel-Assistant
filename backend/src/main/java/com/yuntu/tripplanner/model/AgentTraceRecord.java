package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 轨迹记录实体（agent_trace 表）
 *
 * 对应一次保存的行程的 ReAct 推理轨迹，逐条落库，
 * 供论文的"轨迹回放"等功能使用。
 */
@Data
@TableName(value = "agent_trace", autoResultMap = true)
public class AgentTraceRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("trip_id")
    private String tripId;

    @TableField("step")
    private Integer step;

    @TableField("thought")
    private String thought;

    @TableField("action")
    private String action;

    @TableField("observation")
    private String observation;

    @TableField(value = "tool_calls", typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> toolCalls;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
