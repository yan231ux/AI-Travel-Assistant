package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.SpotFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户景点收藏数据访问（MyBatis-Plus，产品化阶段一）
 */
@Mapper
public interface SpotFavoriteRepository extends BaseMapper<SpotFavorite> {
}
