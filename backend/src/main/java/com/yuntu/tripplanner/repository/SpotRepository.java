package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.Spot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 景点主档数据访问（MyBatis-Plus，产品化阶段一）
 */
@Mapper
public interface SpotRepository extends BaseMapper<Spot> {
}
