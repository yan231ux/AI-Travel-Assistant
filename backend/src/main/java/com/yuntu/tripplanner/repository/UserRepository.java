package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问（MyBatis-Plus，查询用 LambdaQueryWrapper）
 */
@Mapper
public interface UserRepository extends BaseMapper<User> {
}
