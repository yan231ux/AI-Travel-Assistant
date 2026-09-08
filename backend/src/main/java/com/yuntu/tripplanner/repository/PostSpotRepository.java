package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.PostSpot;
import org.apache.ibatis.annotations.Mapper;

/** 帖子关联景点数据访问（阶段二社区） */
@Mapper
public interface PostSpotRepository extends BaseMapper<PostSpot> {
}
