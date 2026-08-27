package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.TripRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 行程记录Repository
 */
@Mapper
public interface TripRecordRepository extends BaseMapper<TripRecord> {
    
}