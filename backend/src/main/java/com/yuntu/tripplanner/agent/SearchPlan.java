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
     * 方案来源（ReAct 优化批次 3：存档复用需记录"这份计划是怎么来的"，便于答辩/运营追溯）：
     * autonomous-native（自主 + 原生 tool_calls）/ autonomous-text（自主 + 文本 JSON）/
     * legacy-text（legacy + 文本 JSON）/ rule（规则兜底）。
     */
    private String source;
    
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