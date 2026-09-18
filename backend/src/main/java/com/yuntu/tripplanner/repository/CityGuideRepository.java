package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.CityGuide;
import org.apache.ibatis.annotations.Mapper;

/** 攻略主档数据访问（管理后台内容运营） */
@Mapper
public interface CityGuideRepository extends BaseMapper<CityGuide> {
}
