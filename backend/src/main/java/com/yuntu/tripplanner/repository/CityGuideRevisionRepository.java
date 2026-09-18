package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.CityGuideRevision;
import org.apache.ibatis.annotations.Mapper;

/** 攻略版本数据访问（管理后台内容运营，append-only） */
@Mapper
public interface CityGuideRevisionRepository extends BaseMapper<CityGuideRevision> {
}
