package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.common.SpotTagMapper;
import com.yuntu.tripplanner.model.BehaviorRequest;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.PersonalizationSummary;
import com.yuntu.tripplanner.model.PostTag;
import com.yuntu.tripplanner.model.PreferenceAdjustment;
import com.yuntu.tripplanner.model.ProfileSummary;
import com.yuntu.tripplanner.model.QuestionnaireRequest;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.TripSummaryItem;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.model.UserProfile;
import com.yuntu.tripplanner.repository.UserBehaviorRepository;
import com.yuntu.tripplanner.repository.UserPreferenceRepository;
import com.yuntu.tripplanner.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户画像服务（个性化阶段一：结构化画像）。
 *
 * <p>升级前：仅从最近历史行程统计出一段文本注入生成 Prompt（黑盒，不可解释）。
 * 升级后：偏好落库为两张结构化表——
 * <ul>
 *   <li>user_profile：每用户一行主档（显式偏好域 + 历史推断出的城市/预算/档次）；</li>
 *   <li>user_preference：带 weight/confidence/source 的偏好明细（阶段二行为反馈增量更新、
 *       阶段三个性化打分的数据基座）。</li>
 * </ul>
 * 生成链路对外契约不变：{@link #buildMemoryText(String, TripRequest)} 仍返回注入文本，
 * 但内容改为「用户主动选择的偏好 + 历史行程推断」两类来源标注，画像从此可解释。
 * 所有统计规则确定性、零 LLM 调用、失败静默降级。
 */
@Slf4j
@Service
public class UserProfileService {

    /** 画像最多读取最近多少条行程 */
    private static final int RECENT_LIMIT = 5;
    /** 最近行程摘要最多展示条数 */
    private static final int SUMMARY_SHOW = 3;
    /** 画像文本最大长度（避免撑爆提示词上下文） */
    private static final int MAX_TEXT_LENGTH = 500;

    /** 问卷显式选择：最高可信度 */
    private static final double WEIGHT_QUESTIONNAIRE = 0.90;
    private static final double CONFIDENCE_QUESTIONNAIRE = 0.95;
    /** 历史行程推断：中等可信度 */
    private static final double WEIGHT_HISTORY_INFER = 0.50;
    private static final double CONFIDENCE_HISTORY_INFER = 0.50;
    /** 行为反馈来源行（FEEDBACK）的置信度 */
    private static final double CONFIDENCE_FEEDBACK = 0.60;
    /** 行为 → 画像权重增量（PERSONALIZATION_PLAN 5.3）：点击 +0.05 / 收藏 +0.15 / 不感兴趣 -0.30 / 替换 -0.20；
     *  阶段三起帖子点赞视为弱正反馈 +0.05（帖子互动与景点共用同一增量表，统一行为模型）；
     *  取消收藏 -0.10 为「撤销」增量，幅度严格小于收藏的 +0.15（见 {@link #REVOKE_ACTIONS}）。 */
    private static final Map<String, Double> ACTION_DELTA = Map.of(
            UserBehavior.ACTION_CLICK, 0.05,
            UserBehavior.ACTION_SAVE, 0.15,
            UserBehavior.ACTION_LIKE, 0.05,
            UserBehavior.ACTION_UNSAVE, -0.10,
            UserBehavior.ACTION_DISLIKE, -0.30,
            UserBehavior.ACTION_REPLACE, -0.20);
    /**
     * 撤销类行为（PERSONALIZATION_PLAN §5.4 画像可撤销）：回退此前的正向贡献。
     *
     * <p>与「负反馈」的区别很重要——负反馈是<b>新的态度</b>（用户表达"不喜欢这类"），
     * 撤销只是<b>收回刚才那一下的加成</b>（用户说"我刚才点错了/改主意了"）。因此撤销：
     * <ol>
     *   <li>不受「负反馈粒度」门禁约束（{@link #isGeneralizableNegative}）——它天然只指向被撤销的那一次操作；</li>
     *   <li>行不存在时不新建——无正向贡献 = 无分可退，凭空建出 0.5+delta 会制造假的"回避信号"；</li>
     *   <li>受 {@link #POSITIVE_WEIGHT_MIN} 下限保护——只收回加成，绝不把权重压进"近期不感兴趣"区间。</li>
     * </ol>
     */
    private static final Set<String> REVOKE_ACTIONS = Set.of(UserBehavior.ACTION_UNSAVE);
    /** 允许落库的行为全集（无增量规则的行为如 VIEW/RATE/REGENERATE/SHARE 仅留痕，不产生标签噪声） */
    private static final Set<String> VALID_ACTIONS = Set.of(
            UserBehavior.ACTION_VIEW, UserBehavior.ACTION_CLICK, UserBehavior.ACTION_SAVE,
            UserBehavior.ACTION_LIKE, UserBehavior.ACTION_SHARE,
            UserBehavior.ACTION_UNSAVE, UserBehavior.ACTION_DISLIKE, UserBehavior.ACTION_REPLACE,
            UserBehavior.ACTION_REGENERATE, UserBehavior.ACTION_RATE);
    /** 低于该权重的非问卷偏好行不当作正偏好输出，转为"近期不感兴趣"回避信号 */
    static final double POSITIVE_WEIGHT_MIN = 0.45;

    /** aspects 列表 JSON 序列化（aspect_json 列） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 节奏关键词 → 规范节奏名 */
    private static final Map<String, String> PACE_KEYWORDS = Map.ofEntries(
            Map.entry("紧凑", "紧凑"), Map.entry("充实", "紧凑"),
            Map.entry("轻松", "轻松"), Map.entry("休闲", "轻松"), Map.entry("慢", "轻松"),
            Map.entry("适中", "适中"), Map.entry("标准", "适中"));

    /** 餐饮口味关键词（命中餐厅名/描述即计数） */
    private static final List<String> DIET_KEYWORDS = List.of(
            "火锅", "海鲜", "本帮菜", "川菜", "粤菜", "小吃", "烧烤", "面食", "甜品",
            "咖啡", "辣", "清淡", "烤肉", "日料", "泰餐");

    /** 偏好域 → 展示名 */
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            UserPreference.CATEGORY_TRAVEL_STYLE, "旅行风格",
            UserPreference.CATEGORY_PACE, "节奏",
            UserPreference.CATEGORY_HOTEL, "住宿档次",
            UserPreference.CATEGORY_FOOD, "口味",
            UserPreference.CATEGORY_DIETARY, "饮食要求",
            UserPreference.CATEGORY_BEHAVIOR, "行为约束");

    private final TripRecordService tripRecordService;
    private final UserProfileRepository userProfileRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final UserBehaviorRepository userBehaviorRepository;
    /** 帖子标签读取（阶段三：POST 行为 → 画像标签，惰性补齐，与内容推荐同词表） */
    private final PostTagService postTagService;

    public UserProfileService(TripRecordService tripRecordService,
                              UserProfileRepository userProfileRepository,
                              UserPreferenceRepository userPreferenceRepository,
                              UserBehaviorRepository userBehaviorRepository,
                              PostTagService postTagService) {
        this.tripRecordService = tripRecordService;
        this.userProfileRepository = userProfileRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.userBehaviorRepository = userBehaviorRepository;
        this.postTagService = postTagService;
    }

    /* ================= 画像读取（幂等惰性构建） ================= */

    /**
     * 取用户画像主档；不存在时基于历史行程推断构建一次并落库（新用户/老用户首次访问触发）。
     * 兜底：主档存在但明细为空且其统计时有历史行程 → 补一次推断（兼容升级前存量用户）。
     */
    public UserProfile loadOrInitProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserProfile profile = findProfile(userId);
        if (profile == null) {
            return inferAndSave(userId);
        }
        if (countPreferences(userId) == 0 && profile.getTripCount() != null && profile.getTripCount() > 0) {
            inferAndSave(userId);
            return findProfile(userId);
        }
        return profile;
    }

    /** 纯查询主档（不触发任何写库） */
    public UserProfile findProfile(String userId) {
        return userProfileRepository.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, userId));
    }

    /** 用户全部偏好明细（按 weight 降序） */
    public List<UserPreference> listPreferences(String userId) {
        return userPreferenceRepository.selectList(
                new LambdaQueryWrapper<UserPreference>()
                        .eq(UserPreference::getUserId, userId)
                        .orderByDesc(UserPreference::getWeight));
    }

    private long countPreferences(String userId) {
        Long count = userPreferenceRepository.selectCount(
                new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, userId));
        return count == null ? 0 : count;
    }

    /* ================= 问卷显式偏好（用户主动选择，最高可信度） ================= */

    /**
     * 提交偏好问卷：替换该用户全部「问卷来源」明细，并按问卷覆盖主档对应域。
     * 同标签若已有历史推断记录 → 一并覆盖为问卷记录（用户主动选择优先于推断）。
     *
     * <p>撤销语义：字段提交即代表「该域的最新真相」——列表传空数组、单值传空串都视为
     * <b>用户主动清空该域</b>（明细与主档同步清掉）；字段缺失(null)表示本次不涉及该域。
     * 这样用户「取消勾选之前选的偏好」能真正生效，而不是明细删了、主档仍留着旧值。
     */
    @Transactional
    public UserProfile applyQuestionnaire(String userId, QuestionnaireRequest q) {
        if (q == null) {
            return findProfile(userId);
        }
        // 1) 清除旧问卷明细（用户重填 = 整体替换）
        userPreferenceRepository.delete(new LambdaQueryWrapper<UserPreference>()
                .eq(UserPreference::getUserId, userId)
                .eq(UserPreference::getSource, UserPreference.SOURCE_QUESTIONNAIRE));

        // 2) 写入本次问卷明细（覆盖同标签旧来源行，避免 UNIQUE(user_id,category,tag) 冲突）
        List<UserPreference> toInsert = new ArrayList<>();
        addQuestionnairePrefs(toInsert, userId, UserPreference.CATEGORY_TRAVEL_STYLE, q.getTravelStyles());
        addQuestionnairePrefs(toInsert, userId, UserPreference.CATEGORY_FOOD, q.getFoodPreferences());
        addQuestionnairePrefs(toInsert, userId, UserPreference.CATEGORY_DIETARY, q.getDietaryRestrictions());
        addQuestionnairePrefs(toInsert, userId, UserPreference.CATEGORY_BEHAVIOR, q.getBehaviorNotes());
        addQuestionnairePref(toInsert, userId, UserPreference.CATEGORY_PACE, q.getPace());
        addQuestionnairePref(toInsert, userId, UserPreference.CATEGORY_HOTEL, q.getHotelLevel());
        for (UserPreference pref : toInsert) {
            userPreferenceRepository.delete(new LambdaQueryWrapper<UserPreference>()
                    .eq(UserPreference::getUserId, userId)
                    .eq(UserPreference::getCategory, pref.getCategory())
                    .eq(UserPreference::getTag, pref.getTag()));
            userPreferenceRepository.insert(pref);
        }

        // 3) 主档覆盖（问卷为该域最新真相）
        //    撤销语义（PERSONALIZATION_PLAN §5.4「可撤销但回退幅度小于增加」）：
        //    本轮用户可清掉自己之前勾选的偏好，明细第 1/2 步已整体替换，主档这里必须同步 ——
        //    否则「明细已删、主档仍是旧值」，生成时照旧注入用户已经撤销掉的偏好（两处真相不一致）。
        //    契约：字段缺失(null)=本次不涉及该域；空数组/空串=用户主动清空该域。
        UserProfile profile = loadOrInitProfile(userId);
        if (profile == null) {
            return null;
        }
        boolean changed = false;
        if (q.getTravelStyles() != null) {
            profile.setTravelStyles(q.getTravelStyles().isEmpty() ? null : join(q.getTravelStyles()));
            changed = true;
        }
        if (q.getPace() != null) {
            profile.setPacePreference(trimToNull(q.getPace()));
            changed = true;
        }
        if (q.getHotelLevel() != null) {
            profile.setHotelPreference(trimToNull(q.getHotelLevel()));
            changed = true;
        }
        if (q.getFoodPreferences() != null) {
            profile.setFoodPreferences(q.getFoodPreferences().isEmpty() ? null : join(q.getFoodPreferences()));
            changed = true;
        }
        if (q.getDietaryRestrictions() != null) {
            profile.setDietaryRestrictions(q.getDietaryRestrictions().isEmpty() ? null : join(q.getDietaryRestrictions()));
            changed = true;
        }
        if (q.getBehaviorNotes() != null) {
            profile.setBehaviorNotes(q.getBehaviorNotes().isEmpty() ? null : join(q.getBehaviorNotes()));
            changed = true;
        }
        profile.setFilledFromQuestionnaire(1);
        if (changed) {
            profile.setProfileVersion((profile.getProfileVersion() == null ? 0 : profile.getProfileVersion()) + 1);
        }
        userProfileRepository.updateById(profile);
        log.info("用户画像问卷更新：{}（明细 {} 条）", userId, toInsert.size());
        return profile;
    }

    /* ================= 行为反馈（阶段二：留痕 + 画像增量更新） ================= */

    /**
     * 记录一次行为反馈并驱动画像增量更新（个性化阶段二，事务内完成）。
     *
     * <p>留痕：景点/餐厅/行程级行为写入 user_behavior（阶段四满意度均值、负反馈率的统计口径）。
     * 画像增量：行为对象 → 偏好标签采用确定性映射 {@link SpotTagMapper}（零 LLM），命中标签
     * 按规则加减权重（SAVE +0.15 / UNSAVE -0.10 / CLICK +0.05 / DISLIKE -0.30 / REPLACE -0.20），
     * clamp [0,1]；标签行不存在时按 0.5+delta 起步建 FEEDBACK 行（负反馈行低权重 → 后续生成注入
     * "近期不感兴趣"回避信号）；问卷显式偏好行受负反馈保护（用户可自行在画像页修改，避免一次
     * 误点抹掉主动选择）。U{@link #REVOKE_ACTIONS 撤销类行为}（取消收藏）另有三条专属规则：
     * 不受负反馈粒度门禁约束、不新建权重行、不下探到 {@link #POSITIVE_WEIGHT_MIN} 以下。
     * RATE/VIEW 等无增量规则的行为仅留痕，不产生标签噪声。
     *
     * @return 本次实际影响的偏好调整列表（前端可据此提示画像变化）
     */
    @Transactional
    public List<PreferenceAdjustment> recordBehavior(String userId, BehaviorRequest req) {
        if (req == null) {
            return List.of();
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少登录用户");
        }
        String action = req.getActionType();
        if (action == null || action.isBlank() || !VALID_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不支持的行为类型: " + action);
        }
        String itemType = req.getItemType();
        if (itemType == null || itemType.isBlank()) {
            throw new IllegalArgumentException("缺少行为对象类型(SPOT/RESTAURANT/POST/TRIP/CITY)");
        }
        if (UserBehavior.ACTION_RATE.equals(action)
                && (req.getRating() == null || req.getRating() < 1 || req.getRating() > 5)) {
            throw new IllegalArgumentException("评分需在 1~5 之间");
        }

        // 1) 行为留痕（RATE 低分原因 / 负反馈粒度原因 序列化进 aspect_json）
        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setTripId(trimToNull(req.getTripId()));
        behavior.setItemType(itemType);
        behavior.setItemId(trimToNull(req.getItemId()));
        behavior.setItemName(trimToNull(req.getItemName()));
        behavior.setPoiType(trimToNull(req.getPoiType()));
        behavior.setActionType(action);
        behavior.setRating(req.getRating());
        behavior.setAspectJson(toJson(buildBehaviorContext(req)));
        userBehaviorRepository.insert(behavior);

        // 2) 画像增量更新（仅带增量规则的行为）
        Double delta = ACTION_DELTA.get(action);
        if (delta == null) {
            log.info("用户行为留痕（无权重规则）：{} {} {} {}", userId, action, itemType, req.getItemName());
            return List.of();
        }
        boolean revoke = REVOKE_ACTIONS.contains(action);
        // 负反馈粒度（PLAN §2.2 问题三）：只有明确"不喜欢这类地点(TYPE)/这类标签(TAG)"才降低标签权重；
        // 具体地点(ITEM)、距离、拥挤、价格、节奏等原因只留痕，不把一次误点泛化到整类标签。
        // 撤销类行为例外：它只收回自己刚才那一下的加成，不存在"泛化到整类"的问题。
        if (delta < 0 && !revoke && !isGeneralizableNegative(req)) {
            log.info("负反馈已留痕（具体地点/上下文原因，不改画像权重）：{} {} 原因={}", userId,
                    req.getItemName(), req.getReason());
            return List.of();
        }
        List<TagKey> keys = resolveTags(req);
        if (delta < 0 && !revoke && BehaviorRequest.REASON_TAG.equals(req.getReason())) {
            keys = filterKeysByRequestedTags(keys, req.getTags());
            if (keys.isEmpty()) {
                log.info("REASON_TAG 未命中画像标签，仅留痕：{} tags={}", userId, req.getTags());
                return List.of();
            }
        }
        List<PreferenceAdjustment> adjustments = new ArrayList<>();
        for (TagKey key : keys) {
            UserPreference pref = userPreferenceRepository.selectOne(new LambdaQueryWrapper<UserPreference>()
                    .eq(UserPreference::getUserId, userId)
                    .eq(UserPreference::getCategory, key.category)
                    .eq(UserPreference::getTag, key.tag));

            PreferenceAdjustment adj = new PreferenceAdjustment();
            adj.setCategory(key.category);
            adj.setTag(key.tag);
            if (pref == null) {
                if (revoke) {
                    // 撤销：无正向贡献行 = 无分可退。不新建 —— 否则会凭空造出 0.5-0.10=0.40
                    // 的"近期不感兴趣"信号（用户只是取消收藏，并没有表达不喜欢）
                    log.debug("撤销行为无对应权重行，跳过：{} {}@{}", userId, key.tag, key.category);
                    continue;
                }
                // 新标签行：0.5 + delta 起步（SAVE → 0.65 正偏好；DISLIKE → 0.20 回避信号）
                double createdWeight = clamp(0.5 + delta);
                UserPreference created = new UserPreference();
                created.setUserId(userId);
                created.setCategory(key.category);
                created.setTag(key.tag);
                created.setWeight(createdWeight);
                created.setConfidence(CONFIDENCE_FEEDBACK);
                created.setSource(UserPreference.SOURCE_FEEDBACK);
                userPreferenceRepository.insert(created);
                adj.setDelta(createdWeight - 0.5);
                adj.setWeight(createdWeight);
                adj.setCreated(true);
            } else if (delta < 0 && UserPreference.SOURCE_QUESTIONNAIRE.equals(pref.getSource())) {
                // 问卷显式偏好受负反馈保护：不抹除主动选择（留痕仍已入库）。撤销同样适用 ——
                // 主动在问卷里勾选的偏好，不该被「收藏后又取消」这种间接操作抹掉
                adj.setDelta(0);
                adj.setWeight(pref.getWeight() == null ? 0 : pref.getWeight());
                adj.setProtectedRow(true);
            } else {
                double before = pref.getWeight() == null ? 0.5 : pref.getWeight();
                double after = clamp(before + delta);
                if (revoke) {
                    // 撤销只收回加成、不制造负偏好：不下探到 POSITIVE_WEIGHT_MIN 以下；
                    // 已经更低的权重行（真的不喜欢过）原样保留，避免撤销反过来"洗白"回避信号
                    after = Math.max(after, Math.min(before, POSITIVE_WEIGHT_MIN));
                }
                if (Math.abs(after - before) < 1e-9) {
                    // 权重实际没变（已在下限/已低于回避线）→ 不写库，也不计成"画像变化"
                    adj.setDelta(0);
                    adj.setWeight(after);
                } else {
                    pref.setWeight(after);
                    pref.setLastObservedAt(LocalDateTime.now());
                    userPreferenceRepository.updateById(pref);
                    adj.setDelta(after - before);
                    adj.setWeight(after);
                }
            }
            adjustments.add(adj);
        }
        if (!adjustments.isEmpty()) {
            log.info("用户行为反馈 → 画像更新：{} {} → {}", userId, action,
                    adjustments.stream().map(PreferenceAdjustment::getTag).toList());
            bumpProfileVersionIfChanged(userId, adjustments);
        }
        return adjustments;
    }

    /**
     * 画像版本递增：行为反馈真实改变了画像权重时（有 delta ≠ 0 或新建行）主档 version+1。
     * 结果缓存 key 拼入 version，保证「收藏/不感兴趣后同参数再生成」不会命中旧缓存 ——
     * 负反馈影响后续推荐的闭环必须绕过 30 分钟结果缓存（TripStreamController）。
     */
    private void bumpProfileVersionIfChanged(String userId, List<PreferenceAdjustment> adjustments) {
        boolean changed = adjustments.stream().anyMatch(a ->
                a.isCreated() || Math.abs(a.getDelta()) > 1e-9);
        if (!changed) {
            return;
        }
        try {
            UserProfile profile = findProfile(userId);
            if (profile != null) {
                profile.setProfileVersion((profile.getProfileVersion() == null ? 0 : profile.getProfileVersion()) + 1);
                userProfileRepository.updateById(profile);
                log.info("行为反馈致画像版本递增：{} → v{}", userId, profile.getProfileVersion());
            }
        } catch (Exception e) {
            log.warn("画像版本递增失败（不影响反馈落库）: {}", e.getMessage());
        }
    }

    /** 当前画像版本号（无主档返回 0；结果缓存 key 用） */
    public int getProfileVersion(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        try {
            UserProfile profile = findProfile(userId);
            return profile == null || profile.getProfileVersion() == null ? 0 : profile.getProfileVersion();
        } catch (Exception e) {
            log.debug("读取画像版本失败，按 0 处理: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 用户旅行摘要（Q5 修复：实时聚合，取代过时的 user_profile.trip_count/visited_cities 快照）。
     *
     * <p>user_profile 里的统计字段是画像创建/推断那一刻的快照，之后新增行程不会回写，
     * 曾造成 ProfileView 显示"5 次/4 城"而实际 11 次/8 城。本方法基于未删除 trip_record
     * 全量实时聚合（不受推断用的 RECENT_LIMIT 限制），首页画像摘要与 ProfileView 一律用它；
     * 画像主档的快照字段保留作历史推断输入，不再作为展示事实源（PRODUCT_EVOLUTION_PLAN §18.2）。
     */
    public ProfileSummary buildProfileSummary(String userId) {
        ProfileSummary summary = new ProfileSummary();
        if (userId == null || userId.isBlank()) {
            return summary;
        }
        try {
            List<TripSummaryItem> trips = tripRecordService.getTripList(userId).getItems();
            summary.setTripCount(trips.size());
            summary.setProfileVersion(getProfileVersion(userId));
            // 去重目的地（保序：按行程时间倒序里首次出现顺序 = 最近去过的城市在前）
            List<String> cities = new ArrayList<>();
            for (TripSummaryItem t : trips) {
                String dest = t.getDestination();
                if (dest != null && !dest.isBlank() && !cities.contains(dest)) {
                    cities.add(dest);
                }
            }
            summary.setVisitedCities(cities);
            if (!trips.isEmpty() && trips.get(0).getCreatedAt() != null) {
                summary.setLatestTripAt(trips.get(0).getCreatedAt());
            }
            // 画像字段补充（主档可空）
            UserProfile profile = findProfile(userId);
            if (profile != null) {
                summary.setTravelStyles(profile.getTravelStyles());
                summary.setPacePreference(profile.getPacePreference());
                summary.setHotelPreference(profile.getHotelPreference());
                summary.setFoodPreferences(profile.getFoodPreferences());
                summary.setBudgetPreference(profile.getBudgetPreference());
            }
        } catch (Exception e) {
            log.warn("构建旅行摘要失败（返回空）: {}", e.getMessage());
        }
        return summary;
    }

    /**
     * 构建本次行程的个性化结构化摘要（PLAN §5.5：结果页顶部确定性说明，非 LLM 文案）。
     *
     * <p>生成收尾（TripGenerationFinalizer）在写缓存前调用：内容全部来自画像表与本次请求参数——
     * <ul>
     *   <li>matchedPreferences：travel_style/food 域正偏好标签（问卷恒正；行为/推断权重 ≥ 阈值），去重保序；</li>
     *   <li>appliedConstraints：本次请求明确带上的饮食要求与预算（输入快照，真实生效过）；</li>
     *   <li>noveltyNote：候选证据中存在历史已体验项（visited=1）才给"已减少重复"提示；</li>
     *   <li>evidenceLevel：画像依据强度 PROFILE_AND_BEHAVIOR &gt; QUESTIONNAIRE &gt; HISTORY_INFER_ONLY &gt; NONE。</li>
     * </ul>
     * 无画像且无请求约束 → 返回各字段为空的摘要，由调用方置 null 不挂到行程上。
     */
    public PersonalizationSummary buildPersonalizationSummary(String userId, TripRequest request,
                                                              List<CandidateEvidence> evidence) {
        PersonalizationSummary summary = new PersonalizationSummary();
        if (userId == null || userId.isBlank()) {
            return summary;
        }
        List<UserPreference> prefs;
        try {
            prefs = listPreferences(userId);
        } catch (Exception e) {
            log.warn("读取偏好失败（个性化摘要降级为空）: {}", e.getMessage());
            prefs = new ArrayList<>();
        }

        List<String> matched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean hasFeedback = false;
        boolean hasQuestionnaire = false;
        boolean hasHistoryInfer = false;
        for (UserPreference p : prefs) {
            if (p == null || p.getTag() == null || p.getTag().isBlank()) {
                continue;
            }
            boolean styleOrFood = UserPreference.CATEGORY_TRAVEL_STYLE.equals(p.getCategory())
                    || UserPreference.CATEGORY_FOOD.equals(p.getCategory());
            if (styleOrFood) {
                double weight = p.getWeight() == null ? 0.5 : p.getWeight();
                // 正偏好：问卷来源恒为正；行为/推断来源需权重未跌破回避阈值（与统一计算器一致）
                if (UserPreference.SOURCE_QUESTIONNAIRE.equals(p.getSource()) || weight >= POSITIVE_WEIGHT_MIN) {
                    if (seen.add(p.getTag())) {
                        matched.add(p.getTag());
                    }
                }
            }
            if (UserPreference.SOURCE_FEEDBACK.equals(p.getSource())) {
                hasFeedback = true;
            } else if (UserPreference.SOURCE_QUESTIONNAIRE.equals(p.getSource())) {
                hasQuestionnaire = true;
            } else if (UserPreference.SOURCE_HISTORY_INFER.equals(p.getSource())) {
                hasHistoryInfer = true;
            }
        }
        if (matched.size() > 6) {
            matched = matched.subList(0, 6);
        }
        summary.setMatchedPreferences(matched);

        // 请求侧约束：只列用户明确给过的（饮食/预算），默认节奏/酒店档位不属个性化信号
        List<String> constraints = new ArrayList<>();
        if (request != null) {
            if (request.getDietaryPreferences() != null && !request.getDietaryPreferences().isEmpty()) {
                constraints.addAll(request.getDietaryPreferences());
            }
            if (request.getBudget() != null && request.getBudget() > 0) {
                constraints.add(String.format("预算 ¥%.0f", request.getBudget()));
            }
        }
        if (constraints.size() > 6) {
            constraints = constraints.subList(0, 6);
        }
        summary.setAppliedConstraints(constraints);

        // 新颖性提示：候选证据里有历史已体验项才说明（无证据/无重复 → 空）
        if (evidence != null) {
            boolean anyVisited = evidence.stream()
                    .anyMatch(e -> e != null && e.getVisited() != null && e.getVisited() == 1);
            if (anyVisited) {
                summary.setNoveltyNote("已减少历史行程中出现过的重复景点");
            }
        }

        // 证据等级（画像依据强弱，前端可区分展示）
        summary.setEvidenceLevel(hasFeedback ? "PROFILE_AND_BEHAVIOR"
                : hasQuestionnaire ? "QUESTIONNAIRE"
                : hasHistoryInfer ? "HISTORY_INFER_ONLY" : "NONE");
        return summary;
    }

    /** 行为对象 → 待加权的 (偏好域, 标签)；无匹配返回空（仅留痕，不引入标签噪声）。
     *  景点→旅行风格（高德业态/名称）；餐厅→口味（名称）；帖子→post_tag 画像标签（惰性补齐，
     *  与内容推荐同词表：收藏帖子 = 提升其 风格/节奏/城市 标签，景点与帖子互相影响）；
     *  城市→city 域标签（城市行为统一行为模型，城市专题页后续使用）。 */
    private List<TagKey> resolveTags(BehaviorRequest req) {
        List<TagKey> keys = new ArrayList<>();
        String itemType = req.getItemType();
        if (UserBehavior.ITEM_TYPE_SPOT.equals(itemType)) {
            for (String tag : SpotTagMapper.styleTags(req.getPoiType(), req.getItemName())) {
                keys.add(new TagKey(UserPreference.CATEGORY_TRAVEL_STYLE, tag));
            }
        } else if (UserBehavior.ITEM_TYPE_RESTAURANT.equals(itemType)) {
            for (String tag : SpotTagMapper.foodTags(req.getItemName())) {
                keys.add(new TagKey(UserPreference.CATEGORY_FOOD, tag));
            }
        } else if (UserBehavior.ITEM_TYPE_POST.equals(itemType)) {
            List<PostTag> tags = resolvePostTags(req.getItemId());
            for (PostTag t : tags) {
                if (t.getCategory() != null && t.getTag() != null && !t.getTag().isBlank()) {
                    keys.add(new TagKey(t.getCategory(), t.getTag()));
                }
            }
        } else if (UserBehavior.ITEM_TYPE_CITY.equals(itemType)) {
            String city = trimToNull(req.getItemName());
            if (city == null) {
                city = trimToNull(req.getItemId());
            }
            if (city != null) {
                keys.add(new TagKey(UserPreference.CATEGORY_CITY, city));
            }
        }
        return keys;
    }

    /** 帖子画像标签（itemId = 帖子数字 id；服务端惰性补齐，不信任前端传的标签） */
    private List<PostTag> resolvePostTags(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        try {
            List<PostTag> tags = postTagService.ensure(Long.parseLong(itemId));
            return tags == null ? List.of() : tags;
        } catch (NumberFormatException e) {
            log.debug("帖子行为 itemId 非数字，仅留痕：{}", itemId);
            return List.of();
        }
    }

    /**
     * 负反馈是否泛化到标签权重（PLAN §2.2 问题三）：
     * TYPE/旧版(null) → 是；TAG → 仅当带上了具体标签；ITEM/距离/拥挤/价格/节奏 → 否（只留痕）。
     */
    private boolean isGeneralizableNegative(BehaviorRequest req) {
        String reason = req.getReason();
        if (reason == null || BehaviorRequest.REASON_TYPE.equals(reason)) {
            return true; // 旧版客户端默认="不喜欢这类地点"，行为与个性化前一致
        }
        if (BehaviorRequest.REASON_TAG.equals(reason)) {
            return req.getTags() != null && !req.getTags().isEmpty();
        }
        return false;
    }

    /** REASON_TAG：只保留用户在 tags 中点名的标签键（未点名任何有效标签 → 空表） */
    private List<TagKey> filterKeysByRequestedTags(List<TagKey> keys, List<String> tags) {
        if (keys.isEmpty() || tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<TagKey> out = new ArrayList<>();
        for (TagKey key : keys) {
            if (tags.contains(key.tag)) {
                out.add(key);
            }
        }
        return out;
    }

    /** 行为上下文 → aspect_json 列表：低分方面原样保留，负反馈原因/点名标签以 REASON:/TAG: 前缀追加 */
    private List<String> buildBehaviorContext(BehaviorRequest req) {
        List<String> ctx = new ArrayList<>();
        if (req.getAspects() != null) {
            ctx.addAll(req.getAspects());
        }
        if (req.getReason() != null && !req.getReason().isBlank()) {
            ctx.add("REASON:" + req.getReason());
        }
        if (req.getTags() != null) {
            for (String t : req.getTags()) {
                if (t != null && !t.isBlank()) {
                    ctx.add("TAG:" + t);
                }
            }
        }
        return ctx;
    }

    private static double clamp(double w) {
        return Math.max(0, Math.min(1, w));
    }

    /** aspects → JSON 数组字符串（空列表返回 null 不入库） */
    private static String toJson(List<String> aspects) {
        if (aspects == null || aspects.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(aspects);
        } catch (Exception e) {
            return String.join(",", aspects);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 待加权标签键（(偏好域, 标签) 与 user_preference UNIQUE 约束对齐） */
    private record TagKey(String category, String tag) {
    }

    /* ================= 历史行程推断（旧链路文本 → 结构化落库） ================= */

    /**
     * 基于最近历史行程推断画像：写入主档（城市/节奏/住宿/口味/预算/行程数）
     * 与 HISTORY_INFER 明细。无历史行程 → 建空主档行（等问卷填充）。
     * 幂等：主档已存在时不重复插入（由调用方保证只在该建的时候调）。
     */
    @Transactional
    public UserProfile inferAndSave(String userId) {
        List<TripRecord> recent = tripRecordService.getRecentTrips(userId, RECENT_LIMIT);
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setFilledFromQuestionnaire(0);
        profile.setProfileVersion(1);
        profile.setTripCount(recent.size());
        if (recent.isEmpty()) {
            userProfileRepository.insert(profile);
            log.info("用户画像初始化（无历史）：{}", userId);
            return profile;
        }

        List<String> cities = new ArrayList<>();
        Map<String, Integer> paceCount = new LinkedHashMap<>();
        Map<String, Integer> hotelCount = new LinkedHashMap<>();
        Map<String, Integer> dietCount = new LinkedHashMap<>();
        double budgetSum = 0;
        int budgetN = 0;

        for (TripRecord r : recent) {
            if (r.getDestination() != null && !r.getDestination().isBlank()
                    && !cities.contains(r.getDestination())) {
                cities.add(r.getDestination());
            }
            Itinerary it = r.getItinerary();
            if (it == null) {
                continue;
            }
            if (it.getEstimatedBudget() != null && it.getEstimatedBudget() > 0) {
                budgetSum += it.getEstimatedBudget();
                budgetN++;
            }
            String text = (it.getSummary() == null ? "" : it.getSummary()) + " "
                    + (it.getDays() == null ? "" : it.getDays().stream()
                            .map(d -> d.getTheme() == null ? "" : d.getTheme())
                            .reduce("", (a, b) -> a + " " + b));
            for (Map.Entry<String, String> e : PACE_KEYWORDS.entrySet()) {
                if (text.contains(e.getKey())) {
                    paceCount.merge(e.getValue(), 1, Integer::sum);
                }
            }
            if (it.getDays() != null) {
                for (DayPlan d : it.getDays()) {
                    if (d.getHotel() != null && d.getHotel().getLevel() != null
                            && !d.getHotel().getLevel().isBlank()) {
                        hotelCount.merge(d.getHotel().getLevel(), 1, Integer::sum);
                    }
                    if (d.getMeals() != null) {
                        for (MealItem m : d.getMeals()) {
                            if (m.getName() == null) {
                                continue;
                            }
                            for (String kw : DIET_KEYWORDS) {
                                if (m.getName().contains(kw)) {
                                    dietCount.merge(kw, 1, Integer::sum);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 主档
        profile.setVisitedCities(join(cities));
        String topPace = topKey(paceCount);
        if (topPace != null) {
            profile.setPacePreference(topPace);
        }
        String topHotel = topKey(hotelCount);
        if (topHotel != null) {
            profile.setHotelPreference(topHotel);
        }
        List<String> topDiets = topKeys(dietCount, 3);
        if (!topDiets.isEmpty()) {
            profile.setFoodPreferences(join(topDiets));
        }
        if (budgetN > 0) {
            profile.setBudgetPreference(budgetSum / budgetN);
        }
        userProfileRepository.insert(profile);

        // 明细（HISTORY_INFER：中等可信度；推断的节奏/住宿如与用户后填问卷同标签，
        // 会在问卷提交时被覆盖，不会重复）
        List<UserPreference> prefs = new ArrayList<>();
        if (topPace != null) {
            addInferPref(prefs, userId, UserPreference.CATEGORY_PACE, topPace);
        }
        if (topHotel != null) {
            addInferPref(prefs, userId, UserPreference.CATEGORY_HOTEL, topHotel);
        }
        for (String diet : topDiets) {
            addInferPref(prefs, userId, UserPreference.CATEGORY_FOOD, diet);
        }
        for (UserPreference pref : prefs) {
            userPreferenceRepository.insert(pref);
        }
        log.info("用户画像历史推断：{}（行程 {} 条，偏好 {} 条）", userId, recent.size(), prefs.size());
        return profile;
    }

    /* ================= 生成注入（对外契约不变：返回文本） ================= */

    /**
     * 构建注入生成提示词的画像文本。
     * 数据源升级为结构化画像：主档历史（城市/预算）+ 明细（问卷来源标注"主动选择"、
     * 推断来源标注"历史推断"）+ 最近行程摘要。无任何历史与偏好 → 返回 null（不注入）。
     */
    public String buildMemoryText(String userId, TripRequest request) {
        UserProfile profile = loadOrInitProfile(userId);
        if (profile == null) {
            return null;
        }
        List<UserPreference> prefs = listPreferences(userId);
        List<TripRecord> recent = tripRecordService.getRecentTrips(userId, RECENT_LIMIT);
        if (recent.isEmpty() && prefs.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("该用户的历史旅行与偏好（只供参考，未列出的经历不得编造）：\n");
        String visited = profile.getVisitedCities();
        if (isNotBlank(visited)) {
            sb.append("- 曾去过：").append(visited).append("\n");
        }
        // 行为反馈信号放靠前：负反馈转回避信号，避免被长摘要截断丢失
        appendPrefLines(sb, prefs, UserPreference.SOURCE_FEEDBACK, "行为反馈");
        appendNegativeLines(sb, prefs);
        if (!recent.isEmpty()) {
            sb.append("- 最近行程：\n");
            int shown = 0;
            for (TripRecord r : recent) {
                if (shown >= SUMMARY_SHOW) {
                    break;
                }
                Itinerary it = r.getItinerary();
                String s = it == null ? null : it.getSummary();
                if (s != null && !s.isBlank()) {
                    sb.append("  · ").append(r.getDestination() == null ? "" : r.getDestination())
                            .append("：").append(s.length() > 60 ? s.substring(0, 60) + "…" : s).append("\n");
                    shown++;
                }
            }
        }
        appendPrefLines(sb, prefs, UserPreference.SOURCE_QUESTIONNAIRE, "主动选择");
        appendPrefLines(sb, prefs, UserPreference.SOURCE_HISTORY_INFER, "历史推断");
        if (profile.getBudgetPreference() != null) {
            sb.append(String.format("- 历史平均预算：约 %.0f 元/次%n", profile.getBudgetPreference()));
        }

        String text = sb.toString().trim();
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "…";
        }
        return text;
    }

    /** 按来源分组输出偏好行：`- [来源标注] 节奏：轻松、适中`；低于正偏好阈值的行不在此输出 */
    private void appendPrefLines(StringBuilder sb, List<UserPreference> prefs, String source, String sourceLabel) {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (UserPreference p : prefs) {
            if (!source.equals(p.getSource())) {
                continue;
            }
            if (p.getWeight() != null && p.getWeight() < POSITIVE_WEIGHT_MIN) {
                continue; // 低权重行 = 回避信号，统一由 appendNegativeLines 输出
            }
            byCategory.computeIfAbsent(p.getCategory(), k -> new ArrayList<>()).add(p.getTag());
        }
        for (Map.Entry<String, List<String>> e : byCategory.entrySet()) {
            String label = CATEGORY_LABELS.getOrDefault(e.getKey(), e.getKey());
            sb.append("- [").append(sourceLabel).append("] ").append(label).append("：")
                    .append(String.join("、", e.getValue())).append("\n");
        }
    }

    /**
     * 输出"近期不感兴趣"回避信号：非问卷来源且权重已跌到阈值以下的标签
     * （用户连续对某类点"不感兴趣"/替换 → 权重被压低），提示生成时避开同类，
     * 使负反馈能真正影响后续行程（阶段二闭环的一环，阶段三将由确定性打分替换）。
     */
    private void appendNegativeLines(StringBuilder sb, List<UserPreference> prefs) {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (UserPreference p : prefs) {
            if (UserPreference.SOURCE_QUESTIONNAIRE.equals(p.getSource())) {
                continue;
            }
            if (p.getWeight() == null || p.getWeight() >= POSITIVE_WEIGHT_MIN) {
                continue;
            }
            byCategory.computeIfAbsent(p.getCategory(), k -> new ArrayList<>()).add(p.getTag());
        }
        for (Map.Entry<String, List<String>> e : byCategory.entrySet()) {
            String label = CATEGORY_LABELS.getOrDefault(e.getKey(), e.getKey());
            sb.append("- 近期不感兴趣：").append(label).append("：")
                    .append(String.join("、", e.getValue())).append("（避免安排同类）\n");
        }
    }

    /* ================= 私有工具 ================= */

    private void addQuestionnairePrefs(List<UserPreference> out, String userId, String category, List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : tags) {
            addQuestionnairePref(out, userId, category, tag);
        }
    }

    private void addQuestionnairePref(List<UserPreference> out, String userId, String category, String tag) {
        if (!isNotBlank(tag)) {
            return;
        }
        UserPreference pref = new UserPreference();
        pref.setUserId(userId);
        pref.setCategory(category);
        pref.setTag(tag.trim());
        pref.setWeight(WEIGHT_QUESTIONNAIRE);
        pref.setConfidence(CONFIDENCE_QUESTIONNAIRE);
        pref.setSource(UserPreference.SOURCE_QUESTIONNAIRE);
        out.add(pref);
    }

    private void addInferPref(List<UserPreference> out, String userId, String category, String tag) {
        if (!isNotBlank(tag)) {
            return;
        }
        UserPreference pref = new UserPreference();
        pref.setUserId(userId);
        pref.setCategory(category);
        pref.setTag(tag.trim());
        pref.setWeight(WEIGHT_HISTORY_INFER);
        pref.setConfidence(CONFIDENCE_HISTORY_INFER);
        pref.setSource(UserPreference.SOURCE_HISTORY_INFER);
        out.add(pref);
    }

    private static String join(List<String> items) {
        return String.join(",", items.stream()
                .filter(UserProfileService::isNotBlank)
                .map(String::trim)
                .toList());
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 计数最高的 key（无则 null） */
    private String topKey(Map<String, Integer> map) {
        return map.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 按计数降序取前 n 个 key */
    private List<String> topKeys(Map<String, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }
}
