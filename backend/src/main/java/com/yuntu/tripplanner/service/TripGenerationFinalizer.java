package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.FilteredCandidate;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.PersonalizationSummary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TravelEvent;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.repository.CandidateEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 行程生成统一收尾服务（PLAN §8.4 / 问题六：流式与非流式入口共用同一收尾语义）。
 *
 * <p>在"真实生成成功、写入结果缓存之前"调用一次，统一完成：
 * <ol>
 *   <li><b>推荐理由回填 + 推荐日志</b>（RecommendationService，统一评分口径，复用候选证据真实得分，
 *       并记录画像版本/算法版本）；</li>
 *   <li><b>候选证据落库</b>（candidate_evidence：补 userId/tripId/profileVersion，并按最终行程
 *       spots/meals 多级关联回填 selected —— OPTIMIZATION_TODO P0②：poi_id → 名称 → 经纬度 →
 *       名称包含 兜底，校验层替换/别名不误判；回答"为什么这样排 / 为什么某候选没被推荐"）；</li>
 *   <li><b>个性化结构化摘要</b>（personalization_summary，随行程一起缓存，供结果页顶部展示）；</li>
 *   <li><b>「为什么没有推荐」结构化说明</b>（filtered_candidates：校验层天气替换原因 + 候选证据的
 *       硬约束/历史重复原因，合并去重后挂到行程，随缓存命中也能展示）。</li>
 * </ol>
 * 任一步失败仅告警、绝不抛出 —— 收尾是增强层，不能影响生成结果返回。
 */
@Slf4j
@Service
public class TripGenerationFinalizer {

    private final RecommendationService recommendationService;
    private final CandidateEvidenceRepository candidateEvidenceRepository;
    private final UserProfileService userProfileService;
    private final TravelEventService travelEventService;

    public TripGenerationFinalizer(RecommendationService recommendationService,
                                   CandidateEvidenceRepository candidateEvidenceRepository,
                                   UserProfileService userProfileService,
                                   TravelEventService travelEventService) {
        this.recommendationService = recommendationService;
        this.candidateEvidenceRepository = candidateEvidenceRepository;
        this.userProfileService = userProfileService;
        this.travelEventService = travelEventService;
    }

    /**
     * 统一收尾入口：仅真实生成成功（success && itinerary != null && 有 userId）才执行。
     * 幂等前提：同一行程只调用一次（两个生成入口各自只调一次；缓存命中路径不经过此方法）。
     */
    public void finalizeGeneration(String userId, TripRequest request, AgentTraceResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.getSuccess())
                || response.getItinerary() == null || userId == null || userId.isBlank()) {
            return;
        }
        Itinerary itinerary = response.getItinerary();
        List<CandidateEvidence> evidence = response.getCandidateEvidence();

        // 1) 推荐理由回填 + 推荐日志（统一口径；无相关画像自动跳过，不打扰）
        try {
            recommendationService.logTripRecommendations(userId, itinerary, evidence);
        } catch (Exception e) {
            log.warn("推荐日志写入失败（不影响生成）: {}", e.getMessage());
        }

        // 2) 候选证据落库（补画像版本/入选标记；无证据则无事可做）
        try {
            persistCandidateEvidence(userId, itinerary, evidence);
        } catch (Exception e) {
            log.warn("候选证据落库失败（不影响生成）: {}", e.getMessage());
        }

        // 3) 个性化结构化摘要（内容全部来自画像与请求参数，确定性构建；空则不挂载）
        try {
            attachPersonalizationSummary(userId, request, itinerary, evidence);
        } catch (Exception e) {
            log.warn("个性化摘要构建失败（不影响生成）: {}", e.getMessage());
        }

        // 4) 「为什么没有推荐」结构化说明（基于候选证据：硬约束沉底/历史重复且未入选才给原因）
        try {
            attachFilteredCandidates(itinerary, evidence);
        } catch (Exception e) {
            log.warn("过滤候选说明构建失败（不影响生成）: {}", e.getMessage());
        }

        // 5) 行程事件埋点（阶段二数据地基）：TRIP_GENERATED + SPOT_GENERATED（规划采用）；
        //    事件表去重幂等，重复调用不重复记
        try {
            travelEventService.recordTripGenerated(userId, itinerary, TravelEvent.SOURCE_AGENT);
        } catch (Exception e) {
            log.warn("行程事件埋点失败（不影响生成）: {}", e.getMessage());
        }
    }

    /**
     * 候选证据补全并落库：每条补 userId/tripId/profileVersion/selected 后 insert。
     * selected 判定升级多级关联（OPTIMIZATION_TODO P0②）：景点按 poi_id 精确 → 名称匹配 →
     * 经纬度近似 → 名称互相包含 兜底（校验层替换/别名场景不靠名称硬匹配误判）；
     * 餐厅无 poi_id/坐标，仍按名称匹配。与 {@link #attachFilteredCandidates} 同一判定，保证状态一致。
     */
    private void persistCandidateEvidence(String userId, Itinerary itinerary,
                                          List<CandidateEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return;
        }
        List<SelectedSpot> spots = collectSelectedSpots(itinerary);
        Set<String> mealNames = collectNames(itinerary, true);
        int profileVersion = userProfileService.getProfileVersion(userId);
        String tripId = itinerary.getTripId();

        int count = 0;
        for (CandidateEvidence ev : evidence) {
            if (ev == null || ev.getItemName() == null || ev.getItemName().isBlank()) {
                continue;
            }
            ev.setUserId(userId);
            ev.setTripId(tripId);
            ev.setProfileVersion(profileVersion);
            ev.setSelected(isSelected(ev, spots, mealNames) ? 1 : 0);
            candidateEvidenceRepository.insert(ev);
            count++;
        }
        if (count > 0) {
            log.info("候选证据落库：{}（行程 {}，{} 条，画像 v{}）", userId, tripId, count, profileVersion);
        }
    }

    /** 候选最终入选判定（与 attachFilteredCandidates 共用，保证两处 selected 口径一致） */
    private boolean isSelected(CandidateEvidence ev, List<SelectedSpot> spots, Set<String> mealNames) {
        if (CandidateEvidence.BUCKET_RESTAURANT.equals(ev.getBucket())) {
            return mealNames.contains(ev.getItemName());
        }
        // 1) poi_id 精确匹配（最强证据：校验层替换后若换成了同 id 的候选，名称可能变但 id 不变）
        if (ev.getPoiId() != null && !ev.getPoiId().isBlank()) {
            for (SelectedSpot s : spots) {
                if (ev.getPoiId().equals(s.poiId())) {
                    return true;
                }
            }
        }
        // 2) 名称相等（最常用）
        for (SelectedSpot s : spots) {
            if (ev.getItemName().equals(s.name())) {
                return true;
            }
        }
        // 3) 经纬度近似（同名变体/分店/入口：候选与最终落点距离小于 ~0.01 度 ≈ 1km）
        if (ev.getLatitude() != null && ev.getLongitude() != null) {
            for (SelectedSpot s : spots) {
                if (s.latitude() != null && s.longitude() != null
                        && Math.abs(s.latitude() - ev.getLatitude()) < 0.01
                        && Math.abs(s.longitude() - ev.getLongitude()) < 0.01) {
                    return true;
                }
            }
        }
        // 4) 名称互相包含兜底（"故宫博物院" ↔ "故宫"；别名/后缀差异）
        for (SelectedSpot s : spots) {
            if (containsEither(ev.getItemName(), s.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEither(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.length() >= 2 && b.length() >= 2 && (a.contains(b) || b.contains(a));
    }

    /** 最终行程中的景点身份（名称 + 高德 id + 坐标；供候选证据多级关联） */
    private record SelectedSpot(String name, String poiId, Double latitude, Double longitude) {
    }

    private List<SelectedSpot> collectSelectedSpots(Itinerary itinerary) {
        List<SelectedSpot> spots = new ArrayList<>();
        if (itinerary == null || itinerary.getDays() == null) {
            return spots;
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day == null || day.getSpots() == null) {
                continue;
            }
            for (SpotItem s : day.getSpots()) {
                if (s == null || s.getName() == null || s.getName().isBlank()) {
                    continue;
                }
                spots.add(new SelectedSpot(s.getName(), s.getPoiId(),
                        s.getLatitude(), s.getLongitude()));
            }
        }
        return spots;
    }

    /** 收集最终行程中的景点名（restaurant=false）或餐厅名（restaurant=true） */
    private Set<String> collectNames(Itinerary itinerary, boolean restaurant) {
        Set<String> names = new HashSet<>();
        if (itinerary == null || itinerary.getDays() == null) {
            return names;
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day == null) {
                continue;
            }
            if (restaurant) {
                if (day.getMeals() != null) {
                    for (MealItem m : day.getMeals()) {
                        if (m.getName() != null && !m.getName().isBlank()) {
                            names.add(m.getName());
                        }
                    }
                }
            } else if (day.getSpots() != null) {
                for (SpotItem s : day.getSpots()) {
                    if (s.getName() != null && !s.getName().isBlank()) {
                        names.add(s.getName());
                    }
                }
            }
        }
        return names;
    }

    /** 结构化摘要挂载到行程：画像与请求都没有任何可说明内容 → 置 null（前端整块隐藏） */
    private void attachPersonalizationSummary(String userId, TripRequest request, Itinerary itinerary,
                                              List<CandidateEvidence> evidence) {
        PersonalizationSummary summary = userProfileService.buildPersonalizationSummary(userId, request, evidence);
        // 「已减少重复」必须描述结果而不是意图（详见 resultAwareNoveltyNote）
        summary.setNoveltyNote(resultAwareNoveltyNote(itinerary, evidence, summary.getNoveltyNote()));
        boolean empty = (summary.getMatchedPreferences() == null || summary.getMatchedPreferences().isEmpty())
                && (summary.getAppliedConstraints() == null || summary.getAppliedConstraints().isEmpty())
                && (summary.getNoveltyNote() == null || summary.getNoveltyNote().isBlank());
        itinerary.setPersonalizationSummary(empty ? null : summary);
    }

    /**
     * 「已减少历史行程中出现过的重复景点」这句必须描述<b>结果</b>，不能只描述<b>意图</b>。
     *
     * <p>原实现只要"候选证据里存在 visited=1 的项"就写上这句话。于是实测三亚出现自相矛盾：
     * 凤凰岛桥头公园、大东海广场因"去过"被降级排除，鹿回头风景区同样"去过"却照样排进第 4 天，
     * 而页面顶部还宣称「已减少重复」——用户看到的不是规则，是"规则时灵时不灵"。
     *
     * <p>现在按最终行程实算：一个都没安排 → 说清避开了几个；安排了若干 → 直接点名是哪几个，
     * 并说明原因是"候选不足或你点名"（与校验层 excludeVisitedSpots 的处理一一对应）。
     */
    private String resultAwareNoveltyNote(Itinerary itinerary, List<CandidateEvidence> evidence,
                                          String original) {
        if (evidence == null || evidence.isEmpty()) {
            return original;
        }
        List<SelectedSpot> spots = collectSelectedSpots(itinerary);
        Set<String> mealNames = collectNames(itinerary, true);
        int visitedTotal = 0;
        List<String> kept = new ArrayList<>();
        for (CandidateEvidence ev : evidence) {
            if (ev == null || ev.getVisited() == null || ev.getVisited() != 1
                    || ev.getItemName() == null || ev.getItemName().isBlank()) {
                continue;
            }
            visitedTotal++;
            if (isSelected(ev, spots, mealNames)) {
                kept.add(ev.getItemName());
            }
        }
        if (visitedTotal == 0) {
            return original;
        }
        if (kept.isEmpty()) {
            return String.format("已避开历史行程中出现过的 %d 个地点，本次全部安排新地点", visitedTotal);
        }
        int capped = Math.min(kept.size(), 3);
        String list = String.join("、", kept.subList(0, capped)) + (kept.size() > capped ? " 等" : "");
        return String.format("历史行程出现过 %d 个候选，本次已换掉 %d 个；「%s」因候选不足或你点名保留",
                visitedTotal, visitedTotal - kept.size(), list);
    }

    /** 「为什么没有推荐」最多展示多少条（防刷屏；HARD 优先，SOFT 兜底） */
    private static final int MAX_FILTERED_SHOW = 6;

    /**
     * 「为什么没有推荐某些内容」结构化说明（PLAN §5.6/§8.3）挂载到行程。
     *
     * <p>只解释「候选阶段被个性化规则降级/沉底、且最终未入选」的候选：
     * <ul>
     *   <li><b>HARD</b>（硬约束沉底）：命中用户近期不感兴趣的回避标签 → 原因指向用户行为反馈；</li>
     *   <li><b>SOFT</b>（软降权）：候选在最近历史行程中出现过（visited）→ 原因指向历史行程。</li>
     * </ul>
     * 普通未入选候选（无个性化依据）不解释 —— 避免把 LLM 的规划取舍全部罗列成噪音。
     * reason/evidence/severity 全部由代码确定性生成，与候选证据列同源。
     * 无依据候选 → 置 null（前端整块隐藏），不展示空卡片。
     */
    private void attachFilteredCandidates(Itinerary itinerary, List<CandidateEvidence> evidence) {
        if (itinerary == null) {
            return;
        }
        // 校验层可能已写入天气替换类过滤原因（applyPlanB → addWeatherFiltered），须先保留再合并，不能覆盖
        List<FilteredCandidate> merged = new ArrayList<>();
        if (itinerary.getFilteredCandidates() != null) {
            merged.addAll(itinerary.getFilteredCandidates());
        }
        if (evidence == null || evidence.isEmpty()) {
            trimAndSet(itinerary, merged);
            return;
        }
        List<SelectedSpot> spots = collectSelectedSpots(itinerary);
        Set<String> mealNames = collectNames(itinerary, true);

        List<FilteredCandidate> hard = new ArrayList<>();
        List<FilteredCandidate> soft = new ArrayList<>();
        for (CandidateEvidence ev : evidence) {
            if (ev == null || ev.getItemName() == null || ev.getItemName().isBlank()) {
                continue;
            }
            if (isSelected(ev, spots, mealNames)) {
                continue; // 已入选的景点/餐厅不需要"为什么没推荐"（与落库 selected 同一判定）
            }
            FilteredCandidate fc = new FilteredCandidate();
            fc.setName(ev.getItemName());
            fc.setBucket(ev.getBucket());
            if (ev.getHardAvoid() != null && ev.getHardAvoid() == 1) {
                fc.setTag(ev.getAvoidTag());
                fc.setReason(String.format("你近期对「%s」不感兴趣，同类候选本次已降低优先级",
                        ev.getAvoidTag() == null ? "该类型" : ev.getAvoidTag()));
                fc.setEvidence("USER_BEHAVIOR");
                fc.setSeverity("HARD");
                hard.add(fc);
            } else if (ev.getVisited() != null && ev.getVisited() == 1) {
                fc.setReason("该地点在最近的历史行程中出现过，为避免重复本次优先安排新地点");
                fc.setEvidence("HISTORY_TRIP");
                fc.setSeverity("SOFT");
                soft.add(fc);
            }
        }
        // 合并顺序：校验层天气原因（HARD）→ 画像硬约束（HARD）→ 历史重复（SOFT）；同名称只保留最先一条
        merged.addAll(hard);
        merged.addAll(soft);
        trimAndSet(itinerary, merged);
    }

    /** 同名称去重（保序）+ 截断上限 + 空则置 null（前端整块隐藏） */
    private void trimAndSet(Itinerary itinerary, List<FilteredCandidate> merged) {
        List<FilteredCandidate> dedup = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FilteredCandidate fc : merged) {
            if (fc == null || fc.getName() == null || fc.getName().isBlank()) {
                continue;
            }
            if (seen.add(fc.getName())) {
                dedup.add(fc);
            }
            if (dedup.size() >= MAX_FILTERED_SHOW) {
                break;
            }
        }
        itinerary.setFilteredCandidates(dedup.isEmpty() ? null : dedup);
        if (!dedup.isEmpty()) {
            log.info("「为什么没有推荐」说明生成：{} 条（含天气替换 {} 条）", dedup.size(),
                    itinerary.getFilteredCandidates().stream()
                            .filter(f -> "WEATHER_API".equals(f.getEvidence())).count());
        }
    }
}
