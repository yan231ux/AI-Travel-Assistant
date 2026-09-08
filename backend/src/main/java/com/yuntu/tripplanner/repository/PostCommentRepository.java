package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.PostComment;
import org.apache.ibatis.annotations.Mapper;

/** 帖子评论数据访问（阶段二社区） */
@Mapper
public interface PostCommentRepository extends BaseMapper<PostComment> {
}
