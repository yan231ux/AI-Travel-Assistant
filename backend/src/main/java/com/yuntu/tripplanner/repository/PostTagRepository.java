package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.PostTag;
import org.apache.ibatis.annotations.Mapper;

/** 帖子标签（post_tag）数据访问 */
@Mapper
public interface PostTagRepository extends BaseMapper<PostTag> {
}
