package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.UserBehavior;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户行为反馈数据访问（MyBatis-Plus）
 */
@Mapper
public interface UserBehaviorRepository extends BaseMapper<UserBehavior> {
}
