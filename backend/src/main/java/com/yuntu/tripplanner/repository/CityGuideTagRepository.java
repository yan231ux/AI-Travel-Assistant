package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.CityGuideTag;
import org.apache.ibatis.annotations.Mapper;

/** 攻略标签数据访问（骨架） */
@Mapper
public interface CityGuideTagRepository extends BaseMapper<CityGuideTag> {
}
