package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.common.SpotTagMapper;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.UserPreference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个性化候选排序服务（个性化阶段三：确定性打分与硬约束过滤）。
 *
 * <p>按 PERSONALIZATION_PLAN 第 6 节：推荐流程应为
 * {@code 检索候选 -> 计算个性化分数 -> 过滤硬约束 -> Agent 规划}。
 * 本服务把「排序 + 过滤」从大模型手里收回到确定性代码：
 * <ul>
 *   <li><b>偏好匹配分 P</b>：候选（景点/餐厅）经 {@link SpotTagMapper} 映射出偏好标签，
 *       与用户画像中同域标签比对，按 权重×置信度 累加（画像越强的标签加分越多）；</li>
 *   <li><b>硬约束过滤 X</b>：命中「近期不感兴趣」回避标签（非问卷来源且权重已跌破阈值
 *       的行为反馈标签）的候选 → 分数归零沉底并说明；点名景点豁免；</li>
 *   <li><b>新颖性 N</b>：候选名称在用户最近历史行程中出现过 → 降权并提示「已体验过，
 *       同类可换新」；</li>
 *   <li>说明文本（推荐原因/过滤原因）写入 {@link CollectedData#getPersonalizedNotes()}，
 *       供生成提示词「候选优先级参考」与结果页回填展示 —— 个性化可见、可解释；</li>
 *   <li>每个候选的完整得分/命中/回避/是否已体验写入 {@link CandidateEvidence} 证据
 *       （口径统一轮：收尾落库 candidate_evidence，供"为什么这样排/为什么没推荐"追溯）。</li>
 * </ul>
 * 打分口径统一走 {@link PersonalizedScoreCalculator}（与推荐日志同源，防两套算法漂移）。
 * 距离/预算惩罚不在此层：候选尚未落入具体某天，缺少成组锚点，留待生成后校验层
 * （ItineraryValidator）做预算与地理合理性兜底，避免与既有校验职责重叠。
 */
@Slf4j
@Service
public class PersonalizedRankingService {

    /** 每次最多读取多少条最近行程用于「已体验」判断 */
    private static final int RECENT_LIMIT = 5;

    /** 总说明条数上限（防撑爆生成提示词） */
    private static final int MAX_NOTES_TOTAL = 10;

    /** 每桶正向匹配说明上限 */
    private static final int MAX_POSITIVE_NOTES_PER_BUCKET = 6;

    /** 排序算法版本（与推荐日志/统一计算器同源，见 PersonalizedScoreCalculator） */
    private static final int RANKING_VERSION = PersonalizedScoreCalculator.RANKING_VERSION;

    private final UserProfileService userProfileService;
    private final TripRecordService tripRecordService;

    public PersonalizedRankingService(UserProfileService userProfileService,
                                      TripRecordService tripRecordService) {
        this.userProfileService = userProfileService;
        this.tripRecordService = tripRecordService;
    }

    /**
     * 对已收集的 POI 候选执行个性化排序 + 硬约束过滤（阶段三入口）。
     *
     * <p>处理 poiResults 中「景点」「餐厅」两个桶：每桶按打分稳定重排（正偏好靠前、回避沉底），
     * 并把候选优先级说明追加到 collectedData.personalizedNotes（供生成提示词与结果页使用），
     * 每个候选的得分证据收集到 collectedData.candidateEvidence（收尾落库）。
     * 无 userId / 无有效画像 → 原样返回（行为与个性化前完全一致）。
     *
     * @return true 表示至少一个桶执行了真实排序（有画像依据），false 表示跳过
     */
    public boolean rankAndFilter(String userId, TripRequest request, CollectedData collectedData) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        List<UserPreference> prefs;
        try {
            prefs = userProfileService.listPreferences(userId);
        } catch (Exception e) {
            log.warn("读取用户偏好失败（跳过个性化排序）: {}", e.getMessage());
            return false;
        }
        if (prefs == null || prefs.isEmpty()) {
            return false;
        }
        Map<String, Object> poiResults = collectedData.getPoiResults();
        if (poiResults == null || poiResults.isEmpty()) {
            return false;
        }
        Set<String> visitedNames = loadVisitedSpotNames(userId);
        Set<String> exempt = new HashSet<>(collectedData.getRequestedSpots() == null
                ? List.of() : collectedData.getRequestedSpots());

        List<String> notes = new ArrayList<>();
        List<CandidateEvidence> evidence = new ArrayList<>();
        boolean applied = false;
        applied |= rankBucket(poiResults, "景点", false, prefs, visitedNames, exempt, notes, evidence);
        applied |= rankBucket(poiResults, "餐厅", true, prefs, visitedNames, exempt, notes, evidence);

        if (applied && !notes.isEmpty()) {
            List<String> existing = collectedData.getPersonalizedNotes();
            if (existing == null) {
                existing = new ArrayList<>();
            }
            int quota = Math.max(0, MAX_NOTES_TOTAL - existing.size());
            if (quota > 0) {
                existing.addAll(notes.subList(0, Math.min(quota, notes.size())));
                collectedData.setPersonalizedNotes(existing);
            }
            log.info("个性化候选排序完成：{}（说明 {} 条）", userId, notes.size());
        }
        // 候选证据收集（口径统一轮：落库 candidate_evidence，供"为什么这样排/为什么没推荐"追溯）
        if (!evidence.isEmpty()) {
            List<CandidateEvidence> existing = collectedData.getCandidateEvidence();
            if (existing == null) {
                existing = new ArrayList<>();
            }
            existing.addAll(evidence);
            collectedData.setCandidateEvidence(existing);
            log.info("候选证据收集：{}（{} 条）", userId, evidence.size());
        }
        return applied;
    }

    /**
     * 对单个 POI 桶重排。只改变顺序、绝不修改 Map 内容（后续数据摘要直接 toString 塞给 LLM，
     * 打任何辅助标记都会污染模型输入）。说明追加到调用方传入的 notes，得分证据写入 evidenceOut。
     *
     * @return true 表示该桶实际执行了重排（候选数 ≥ 2 且含有效候选）
     */
    @SuppressWarnings("unchecked")
    private boolean rankBucket(Map<String, Object> poiResults, String bucketKey,
                               boolean restaurant, List<UserPreference> prefs,
                               Set<String> visitedNames, Set<String> exempt, List<String> notes,
                               List<CandidateEvidence> evidenceOut) {
        Object raw = poiResults.get(bucketKey);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                candidates.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        if (candidates.size() < 2) {
            return false; // 只有 1 个候选无排序意义
        }

        // 第一遍：统一计算器打分（口径与 RecommendationService 完全一致，见 PLAN §8.1）+ 收集候选证据
        List<Scored> scored = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> c : candidates) {
            String name = str(c.get("name"));
            if (name == null || name.isBlank()) {
                order++;
                continue;
            }
            String poiType = str(c.get("type"));
            ScoreDetail d = PersonalizedScoreCalculator.evaluate(
                    name, poiType, restaurant, prefs, visitedNames, exempt);

            CandidateEvidence ev = new CandidateEvidence();
            ev.setBucket(restaurant ? CandidateEvidence.BUCKET_RESTAURANT : CandidateEvidence.BUCKET_SPOT);
            ev.setItemName(name);
            ev.setPoiId(str(c.get("poi_id")));
            ev.setLongitude(num(c.get("longitude")));
            ev.setLatitude(num(c.get("latitude")));
            ev.setOriginalOrder(order);
            ev.setPreferenceScore(d.preferenceScore());
            ev.setNoveltyScore(d.noveltyScore());
            ev.setDistancePenalty(d.distancePenalty());
            ev.setFinalScore(d.finalScore());
            ev.setMatchedTags(d.matchedTags().isEmpty() ? null : String.join("/", d.matchedTags()));
            ev.setAvoidTag(d.avoidTag());
            ev.setHardAvoid(d.hardAvoid() ? 1 : 0);
            ev.setVisited(d.visited() ? 1 : 0);
            ev.setRankingVersion(RANKING_VERSION);
            evidenceOut.add(ev);
            order++;

            scored.add(new Scored(c, name, d.finalScore(), new LinkedHashSet<>(d.matchedTags()),
                    d.visited(), d.avoidTag(), d.hardAvoid()));
        }

        // 稳定排序：同分保持原序（高德返回顺序约等于热度）——插入排序保证稳定性
        List<Scored> sorted = new ArrayList<>(scored);
        for (int i = 1; i < sorted.size(); i++) {
            Scored key = sorted.get(i);
            int j = i - 1;
            while (j >= 0 && sorted.get(j).score < key.score) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, key);
        }

        // 重排后的新列表回填 poiResults（内容与原先完全一致，仅顺序变化）
        List<Map<String, Object>> reordered = new ArrayList<>();
        for (Scored s : sorted) {
            reordered.add(s.candidate);
        }
        poiResults.put(bucketKey, reordered);

        // 收集说明：硬约束（回避）优先提醒，正偏好次之，已体验仅在有正匹配时附带提示
        String label = "景点".equals(bucketKey) ? "景点" : "餐厅";
        int positiveCount = 0;
        for (Scored s : sorted) {
            if (s.hardAvoid) {
                notes.add(String.format("[%s] %s：你近期对「%s」不感兴趣，本次已降低优先级",
                        label, s.name, s.avoidLabel));
            } else if (!s.matched.isEmpty()) {
                if (positiveCount < MAX_POSITIVE_NOTES_PER_BUCKET) {
                    String visitedTip = s.visited ? "（上次行程已体验过，如再访建议同类新地点）" : "";
                    notes.add(String.format("[%s] %s：匹配你的偏好「%s」%s", label, s.name,
                            String.join("/", s.matched), visitedTip));
                    positiveCount++;
                }
            }
        }
        return true;
    }

    /** 最近行程中出现过的景点名集合（新颖性判断；失败降级为空集） */
    private Set<String> loadVisitedSpotNames(String userId) {
        Set<String> names = new HashSet<>();
        try {
            List<TripRecord> recent = tripRecordService.getRecentTrips(userId, RECENT_LIMIT);
            for (TripRecord r : recent) {
                Itinerary it = r.getItinerary();
                if (it == null || it.getDays() == null) {
                    continue;
                }
                for (DayPlan d : it.getDays()) {
                    if (d.getSpots() == null) {
                        continue;
                    }
                    for (SpotItem s : d.getSpots()) {
                        if (s.getName() != null && !s.getName().isBlank()) {
                            names.add(s.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取历史行程景点失败（新颖性降级为空）: {}", e.getMessage());
        }
        return names;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Object → Double（候选 map 里的经纬度可能是 Double 或 "116.4,39.9" 之外的数值字符串；解析失败返回 null） */
    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 候选 + 打分结果（排序载体；candidate 与原 Map 同一引用，不打标） */
    private record Scored(Map<String, Object> candidate, String name, double score,
                          Set<String> matched, boolean visited, String avoidLabel,
                          boolean hardAvoid) {
    }
}
