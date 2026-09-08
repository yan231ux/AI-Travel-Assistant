package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.PostInteraction;
import org.apache.ibatis.annotations.Mapper;

/** 帖子互动数据访问（阶段二社区；唯一键保证点赞/收藏幂等） */
@Mapper
public interface PostInteractionRepository extends BaseMapper<PostInteraction> {
}
