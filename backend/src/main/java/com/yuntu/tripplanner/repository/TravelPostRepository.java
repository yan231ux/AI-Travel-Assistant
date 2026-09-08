package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.TravelPost;
import org.apache.ibatis.annotations.Mapper;

/** 帖子数据访问（阶段二社区） */
@Mapper
public interface TravelPostRepository extends BaseMapper<TravelPost> {
}
