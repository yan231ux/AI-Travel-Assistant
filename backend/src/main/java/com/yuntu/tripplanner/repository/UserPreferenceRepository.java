package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.UserPreference;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户偏好明细数据访问（MyBatis-Plus）
 */
@Mapper
public interface UserPreferenceRepository extends BaseMapper<UserPreference> {
}
