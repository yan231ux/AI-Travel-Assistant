package com.yuntu.tripplanner.agent;

import lombok.Data;

/**
 * Agent思考记录
 */
@Data
public class AgentThought {
    
    /**
     * 思考内容
     */
    private String thought;
    
    /**
     * 动作类型
     */
    private String action;
    
    /**
     * 观察结果
     */
    private String observation;
    
    /**
     * 是否足够
     */
    private Boolean enough;
    
    /**
     * 下一步动作
     */
    private String nextAction;
}