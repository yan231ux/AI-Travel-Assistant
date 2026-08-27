package com.yuntu.tripplanner.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索计划
 */
@Data
public class SearchPlan {
    
    /**
     * 需要调用的工具列表
     */
    private List<ToolCall> toolCalls = new ArrayList<>();
    
    /**
     * 计划说明
     */
    private String planDescription;
    
    /**
     * 工具调用
     */
    @Data
    public static class ToolCall {
        
        /**
         * 工具名称
         */
        private String tool;
        
        /**
         * 工具参数
         */
        private String query;
        
        /**
         * 调用原因
         */
        private String reason;
    }
}