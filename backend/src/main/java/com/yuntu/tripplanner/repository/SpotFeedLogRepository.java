package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.SpotFeedLog;
import org.apache.ibatis.annotations.Mapper;

/** 推荐景点流曝光日志（spot_feed_log）数据访问 */
@Mapper
public interface SpotFeedLogRepository extends BaseMapper<SpotFeedLog> {
}
