package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.AgentTraceRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 轨迹 Repository
 */
@Mapper
public interface AgentTraceRepository extends BaseMapper<AgentTraceRecord> {

}
