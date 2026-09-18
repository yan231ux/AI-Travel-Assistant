package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.TravelPostRevision;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子编辑版本数据访问（审查报告 P1-1：公开版本 / 编辑版本分离）。
 * 同一帖子至多一个 PENDING_REVIEW 版本，由 travel_post.pending_revision_id 指明。
 */
@Mapper
public interface TravelPostRevisionRepository extends BaseMapper<TravelPostRevision> {
}
