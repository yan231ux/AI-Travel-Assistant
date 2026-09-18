package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.AgentPlanArchive;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采集方案存档仓储（对应 agent_plan_archive 表，ReAct 优化批次 3）。
 */
@Mapper
public interface AgentPlanArchiveRepository extends BaseMapper<AgentPlanArchive> {
}
