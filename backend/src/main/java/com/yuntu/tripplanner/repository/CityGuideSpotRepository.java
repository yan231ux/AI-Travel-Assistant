package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.CityGuideSpot;
import org.apache.ibatis.annotations.Mapper;

/** 攻略-景点关联数据访问（骨架：导入/保存时写解析结果） */
@Mapper
public interface CityGuideSpotRepository extends BaseMapper<CityGuideSpot> {
}
