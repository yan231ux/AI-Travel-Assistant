package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.model.AgentPlanArchive;
import com.yuntu.tripplanner.repository.AgentPlanArchiveRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 采集方案存档服务（ReAct 优化批次 3：只自主一次 + 复用）。
 *
 * <p>职责边界：只管"按 planKey 存取一份工具计划快照 + 复用计数"，<b>不解析计划语义</b>
 * （JSON ↔ SearchPlan 的由 TravelAgent 负责，本服务对 agent 包零依赖，避免 service→agent 反向耦合）。
 *
 * <p><b>降级原则</b>：存档是"优化"而非"依赖"——数据库不可用、表缺失、SQL 异常一律吞掉并记
 * warn，让调用方按"没有存档"继续走正常链路。原因：存档服务出问题绝不能把行程生成干掉，
 * 这也是本服务所有方法都返回"安全默认值"（Optional.empty / void）的原因。
 */
@Slf4j
@Service
public class AgentPlanArchiveService {

    private final AgentPlanArchiveRepository repository;

    /**
     * 存档有效期（天）：超过则不复用，避免拿一周前的 POI/天气候选池当"稳定输入"。
     * 过期不是删除——保留行便于回答"某用户上次用的什么方案"，只是不再命中。
     */
    @Value("${agent.plan-archive-ttl-days:7}")
    private int ttlDays = 7;

    public AgentPlanArchiveService(AgentPlanArchiveRepository repository) {
        this.repository = repository;
    }

    /**
     * 查一份仍可复用的存档。
     *
     * <p>命中时副作用：reuse_count + 1（可解释性——这个方案被沿用了多少次）。
     * 任何异常（含表不存在）→ 返回 empty，调用方按"无存档"处理。
     */
    public Optional<AgentPlanArchive> findReusable(String planKey) {
        if (planKey == null || planKey.isBlank()) {
            return Optional.empty();
        }
        try {
            AgentPlanArchive row = repository.selectOne(new LambdaQueryWrapper<AgentPlanArchive>()
                    .eq(AgentPlanArchive::getPlanKey, planKey));
            if (row == null) {
                return Optional.empty();
            }
            if (isExpired(row)) {
                log.info("采集方案存档已超过 {} 天，不复用（保留行以便追溯）: {}", ttlDays, planKey);
                return Optional.empty();
            }
            row.setReuseCount((row.getReuseCount() == null ? 0 : row.getReuseCount()) + 1);
            repository.updateById(row);
            return Optional.of(row);
        } catch (Exception e) {
            log.warn("读取采集方案存档失败，降级为不复用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写入/更新存档（按 plan_key upsert）。
     *
     * <p>更新时保留既有 reuse_count 与 created_at（复用计数是累计值，不能因重写方案而清零）。
     * 异常吞掉：写不进去最多下次不复用，不影响本次生成。
     */
    public void save(AgentPlanArchive archive) {
        if (archive == null || archive.getPlanKey() == null || archive.getPlanKey().isBlank()) {
            return;
        }
        try {
            AgentPlanArchive existing = repository.selectOne(new LambdaQueryWrapper<AgentPlanArchive>()
                    .eq(AgentPlanArchive::getPlanKey, archive.getPlanKey()));
            if (existing != null) {
                archive.setId(existing.getId());
                archive.setCreatedAt(existing.getCreatedAt());
                archive.setReuseCount(existing.getReuseCount() == null ? 0 : existing.getReuseCount());
                repository.updateById(archive);
            } else {
                archive.setReuseCount(0);
                repository.insert(archive);
            }
        } catch (Exception e) {
            log.warn("写入采集方案存档失败（不影响本次生成）: {}", e.getMessage());
        }
    }

    private boolean isExpired(AgentPlanArchive row) {
        LocalDateTime base = row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt();
        return base != null && base.isBefore(LocalDateTime.now().minusDays(Math.max(1, ttlDays)));
    }
}
