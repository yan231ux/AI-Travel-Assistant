package com.yuntu.tripplanner.agent;

import com.yuntu.tripplanner.model.CandidateEvidence;
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

    /**
     * 用户记忆画像文本（controller 基于历史行程构建，注入生成提示词实现个性化）。
     * 空/null = 该用户暂无历史，不注入。
     */
    private String userMemory;

    /**
     * 个性化候选排序说明（个性化阶段三：PersonalizedRankingService 按画像对 POI 候选
     * 确定性打分/过滤后产生，形如「[景点] 故宫：匹配你的偏好"历史文化"」）。
     * 供生成提示词「候选优先级参考」使用；空 = 未触发个性化排序。
     */
    private List<String> personalizedNotes = new ArrayList<>();

    /**
     * 候选阶段排序证据（口径统一轮：排序服务为每个候选记录 原序/得分/命中/回避/是否已体验，
     * 收尾落库 candidate_evidence 表；空 = 未触发个性化排序或候选不足）。
     */
    private List<CandidateEvidence> candidateEvidence = new ArrayList<>();
}