package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.RecommendationIntervention;
import org.apache.ibatis.annotations.Mapper;

/** 推荐人工干预（recommendation_intervention）数据访问 */
@Mapper
public interface RecommendationInterventionRepository extends BaseMapper<RecommendationIntervention> {
}
