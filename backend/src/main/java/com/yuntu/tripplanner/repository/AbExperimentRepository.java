package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.AbExperiment;
import org.apache.ibatis.annotations.Mapper;

/** A/B 实验注册表（ab_experiment）数据访问 */
@Mapper
public interface AbExperimentRepository extends BaseMapper<AbExperiment> {
}
