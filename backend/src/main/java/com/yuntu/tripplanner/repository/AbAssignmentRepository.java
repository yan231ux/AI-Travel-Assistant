package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.AbAssignment;
import org.apache.ibatis.annotations.Mapper;

/** A/B 实验用户分桶记录（ab_assignment）数据访问 */
@Mapper
public interface AbAssignmentRepository extends BaseMapper<AbAssignment> {
}
