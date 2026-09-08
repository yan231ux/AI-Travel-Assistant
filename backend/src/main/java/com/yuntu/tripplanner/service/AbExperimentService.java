package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.common.AbBucket;
import com.yuntu.tripplanner.model.AbAssignment;
import com.yuntu.tripplanner.model.AbExperiment;
import com.yuntu.tripplanner.repository.AbAssignmentRepository;
import com.yuntu.tripplanner.repository.AbExperimentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A/B 实验服务（阶段四任务 6：推荐 A/B 实验）。
 *
 * <p>职责三件：
 * <ol>
 *   <li><b>实验管理（管理员）</b>：创建 / 关闭 / 列表；同一 feed_type 同时只允许一个
 *       ACTIVE 实验（并发实验会互相污染对照口径）；关闭后参数不可改，换参 = 关闭重建
 *       （参数参与哈希，中途改参会导致同一用户换桶）；</li>
 *   <li><b>粘性分桶</b>：{@link #resolveVariant} 决定某用户在某实验下的变体
 *       —— 确定性哈希（{@link AbBucket}）+ 已分桶用户永不变更（ab_assignment 幂等落行）；
 *       实验不在 ACTIVE / 用户不参与流量 / 匿名 → 一律 null（= 基线行为，不入实验口径）；</li>
 *   <li><b>分桶统计</b>：{@link #summary} 返回每实验 CONTROL/TREATMENT 曝光人数（喂管理后台）。
 *       曝光后的效果对照（收藏率/负反馈率）由 FeedMonitorService 按 ab_variant 聚合。</li>
 * </ol>
 */
@Slf4j
@Service
public class AbExperimentService {

    /** 内置推荐流质量门实验名：处理组只保留有真实攻略（GUIDE_MATCHED/VERIFIED）的候选 */
    public static final String EXP_SPOT_QUALITY_GATE = "spot_feed_quality_gate";
    /** 内置低质过滤实验名：处理组把 quality_score 判定的低质帖子排除出个性化推荐候选 */
    public static final String EXP_POST_LOW_QUALITY = "post_feed_low_quality";

    /** 处理组策略：推荐景点流的攻略质量门（QUALITY_GATE ↔ SPOT_FEED） */
    public static final String STRATEGY_QUALITY_GATE = "QUALITY_GATE";
    /** 处理组策略：帖子推荐流的低质过滤（LOW_QUALITY_FILTER ↔ POST_FEED） */
    public static final String STRATEGY_LOW_QUALITY_FILTER = "LOW_QUALITY_FILTER";

    private final AbExperimentRepository experimentRepository;
    private final AbAssignmentRepository assignmentRepository;

    public AbExperimentService(AbExperimentRepository experimentRepository,
                               AbAssignmentRepository assignmentRepository) {
        this.experimentRepository = experimentRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /* ================= 实验管理（管理员） ================= */

    /**
     * 创建实验（管理员）。校验通过后 INSERT；重名抛 IllegalArgumentException。
     * 同名同 feed_type 已 ACTIVE → 拒绝（防止并行实验污染口径）。
     */
    public AbExperiment create(String name, String description, String feedType, String strategy,
                               Integer trafficPercent, Integer controlPercent) {
        validate(name, feedType, strategy, trafficPercent, controlPercent);
        AbExperiment exists = experimentRepository.selectOne(
                new LambdaQueryWrapper<AbExperiment>().eq(AbExperiment::getExpName, name));
        if (exists != null) {
            throw new IllegalArgumentException("实验名已存在：" + name);
        }
        if (!isSupportedPair(feedType, strategy)) {
            throw new IllegalArgumentException("实验作用域与策略不匹配（QUALITY_GATE→SPOT_FEED，LOW_QUALITY_FILTER→POST_FEED）");
        }
        long active = experimentRepository.selectCount(new LambdaQueryWrapper<AbExperiment>()
                .eq(AbExperiment::getFeedType, feedType)
                .eq(AbExperiment::getStatus, AbExperiment.STATUS_ACTIVE));
        if (active > 0) {
            throw new IllegalArgumentException(feedType + " 已有进行中的实验，请先关闭再创建新实验");
        }
        AbExperiment exp = new AbExperiment();
        exp.setExpName(name);
        exp.setDescription(description);
        exp.setFeedType(feedType);
        exp.setStrategy(strategy);
        exp.setStatus(AbExperiment.STATUS_ACTIVE);
        exp.setTrafficPercent(trafficPercent);
        exp.setControlPercent(controlPercent);
        experimentRepository.insert(exp);
        log.info("创建 A/B 实验: {} feed={} strategy={} traffic={}% control={}%",
                name, feedType, strategy, trafficPercent, controlPercent);
        return exp;
    }

    /** 关闭实验（管理员）：ACTIVE → CLOSED + closed_at。关闭后 resolveVariant 返回 null（回到基线）。 */
    public AbExperiment close(String name) {
        AbExperiment exp = experimentRepository.selectOne(
                new LambdaQueryWrapper<AbExperiment>().eq(AbExperiment::getExpName, name));
        if (exp == null) {
            throw new IllegalArgumentException("实验不存在：" + name);
        }
        if (!AbExperiment.STATUS_ACTIVE.equals(exp.getStatus())) {
            throw new IllegalArgumentException("实验已关闭：" + name);
        }
        exp.setStatus(AbExperiment.STATUS_CLOSED);
        exp.setClosedAt(LocalDateTime.now());
        experimentRepository.updateById(exp);
        log.info("关闭 A/B 实验: {}", name);
        return exp;
    }

    /** 全部实验（按创建倒序），附每变体参与人数 */
    public List<ExperimentWithStat> listAll() {
        List<AbExperiment> exps = experimentRepository.selectList(
                new LambdaQueryWrapper<AbExperiment>().orderByDesc(AbExperiment::getId));
        List<ExperimentWithStat> out = new ArrayList<>();
        for (AbExperiment exp : exps) {
            out.add(statOf(exp));
        }
        return out;
    }

    /** 分桶统计：某实验 CONTROL/TREATMENT 的参与用户数 */
    public ExperimentWithStat statOf(AbExperiment exp) {
        return new ExperimentWithStat(exp,
                assignmentCount(exp.getExpName(), AbBucket.VARIANT_CONTROL),
                assignmentCount(exp.getExpName(), AbBucket.VARIANT_TREATMENT));
    }

    private long assignmentCount(String expName, String variant) {
        try {
            return assignmentRepository.selectCount(new LambdaQueryWrapper<AbAssignment>()
                    .eq(AbAssignment::getExpName, expName)
                    .eq(AbAssignment::getVariant, variant));
        } catch (Exception e) {
            log.debug("分桶统计失败（忽略）: {}", e.getMessage());
            return 0L;
        }
    }

    /* ================= 粘性分桶（推荐流调用） ================= */

    /**
     * 解析用户在某实验下的变体（粘性 + 幂等）：
     * <ol>
     *   <li>匿名 / 实验不存在 / 非 ACTIVE → null（基线行为，不写分桶行）；</li>
     *   <li>已有分桶行 → 直接复用（实验期间用户不变桶，保证对照口径）；</li>
     *   <li>否则按确定性哈希落桶：不参与流量 → null；CONTROL/TREATMENT → 幂等落行
     *       （并发竞争命中唯一键时重读既有行）。</li>
     * </ol>
     */
    public String resolveVariant(String userId, String expName) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        AbExperiment exp = experimentRepository.selectOne(
                new LambdaQueryWrapper<AbExperiment>()
                        .eq(AbExperiment::getExpName, expName)
                        .eq(AbExperiment::getStatus, AbExperiment.STATUS_ACTIVE)
                        .last("LIMIT 1"));
        if (exp == null || !AbExperiment.STATUS_ACTIVE.equals(exp.getStatus())) {
            return null; // 实验不存在或已关闭 → 基线，不参与分桶
        }
        AbAssignment existing = assignmentRepository.selectOne(
                new LambdaQueryWrapper<AbAssignment>()
                        .eq(AbAssignment::getUserId, userId)
                        .eq(AbAssignment::getExpName, expName)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing.getVariant();
        }
        String variant = AbBucket.variantOf(userId, expName,
                nvl(exp.getTrafficPercent(), 100), nvl(exp.getControlPercent(), 50));
        if (variant == null) {
            return null; // 不参与流量：不留行，也不进实验口径
        }
        AbAssignment row = new AbAssignment();
        row.setUserId(userId);
        row.setExpName(expName);
        row.setVariant(variant);
        try {
            assignmentRepository.insert(row);
            return variant;
        } catch (DuplicateKeyException e) {
            // 并发分桶：他人先落行 → 复用既有变体，保证粘性
            AbAssignment raced = assignmentRepository.selectOne(
                    new LambdaQueryWrapper<AbAssignment>()
                            .eq(AbAssignment::getUserId, userId)
                            .eq(AbAssignment::getExpName, expName)
                            .last("LIMIT 1"));
            return raced == null ? variant : raced.getVariant();
        }
    }

    /** 校验并返回实验（供推荐流读取配置细节用；非 ACTIVE → null） */
    public AbExperiment activeOf(String feedType) {
        if (feedType == null) {
            return null;
        }
        return experimentRepository.selectOne(new LambdaQueryWrapper<AbExperiment>()
                .eq(AbExperiment::getFeedType, feedType)
                .eq(AbExperiment::getStatus, AbExperiment.STATUS_ACTIVE)
                .last("LIMIT 1"));
    }

    /* ================= 工具 ================= */

    private static void validate(String name, String feedType, String strategy,
                                 Integer trafficPercent, Integer controlPercent) {
        if (name == null || !name.matches("^[a-z0-9_]{3,60}$")) {
            throw new IllegalArgumentException("实验名需为 3~60 位小写字母/数字/下划线");
        }
        if (!AbExperiment.FEED_SPOT.equals(feedType) && !AbExperiment.FEED_POST.equals(feedType)) {
            throw new IllegalArgumentException("feedType 仅支持 SPOT_FEED / POST_FEED");
        }
        if (!isSupportedPair(feedType, strategy)) {
            throw new IllegalArgumentException("实验作用域与策略不匹配（QUALITY_GATE→SPOT_FEED，LOW_QUALITY_FILTER→POST_FEED）");
        }
        int traffic = nvl(trafficPercent, 100);
        int control = nvl(controlPercent, 50);
        if (traffic < 1 || traffic > 100 || control < 0 || control > 100) {
            throw new IllegalArgumentException("流量比例须在 1~100、对照组比例须在 0~100");
        }
    }

    private static boolean isSupportedPair(String feedType, String strategy) {
        return (AbExperiment.FEED_SPOT.equals(feedType) && STRATEGY_QUALITY_GATE.equals(strategy))
                || (AbExperiment.FEED_POST.equals(feedType) && STRATEGY_LOW_QUALITY_FILTER.equals(strategy));
    }

    private static int nvl(Integer v, int def) {
        return v == null ? def : v;
    }

    /** 实验 + 分桶统计的展示载体 */
    public record ExperimentWithStat(AbExperiment experiment,
                                     long controlUsers, long treatmentUsers) {
    }
}
