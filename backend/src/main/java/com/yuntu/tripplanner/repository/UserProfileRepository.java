package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户画像主档数据访问（MyBatis-Plus，查询用 LambdaQueryWrapper）
 */
@Mapper
public interface UserProfileRepository extends BaseMapper<UserProfile> {
}
