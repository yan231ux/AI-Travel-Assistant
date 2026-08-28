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

    /**
     * 用户特殊需求中点名的景点（攻略卡片名匹配 + 高德定向 POI 查询得到）。
     * 供生成提示词强制加入行程、以及校验层反馈"未安排"原因使用。
     */
    private List<String> requestedSpots = new ArrayList<>();
}