package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.RecommendationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 推荐日志数据访问（MyBatis-Plus，个性化阶段四）
 */
@Mapper
public interface RecommendationLogRepository extends BaseMapper<RecommendationLog> {
}
