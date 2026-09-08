package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.PostFeedLog;
import org.apache.ibatis.annotations.Mapper;

/** 帖子推荐流曝光日志（post_feed_log）数据访问 */
@Mapper
public interface PostFeedLogRepository extends BaseMapper<PostFeedLog> {
}
