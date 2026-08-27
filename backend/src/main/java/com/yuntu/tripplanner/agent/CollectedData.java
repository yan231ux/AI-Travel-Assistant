package com.yuntu.tripplanner.agent;

import com.yuntu.tripplanner.model.TokenUsage;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集的数据
 */
@Data
public class CollectedData {
    
    /**
     * 搜索结果
     */
    private Map<String, Object> searchResults = new HashMap<>();
    
    /**
     * POI结果
     */
    private Map<String, Object> poiResults = new HashMap<>();
    
    /**
     * 天气数据
     */
    private Map<String, Object> weatherData = new HashMap<>();
    
    /**
     * RAG数据
     */
    private Map<String, Object> ragData = new HashMap<>();
    
    /**
     * 数据缺口
     */
    private List<String> gaps = new ArrayList<>();

    /**
     * 数据摘要
     */
    private String summary;

    /**
     * 本轮收集阶段产生的 token 消耗（如 RAG embedding）
     */
    private TokenUsage tokenUsage = new TokenUsage();
}