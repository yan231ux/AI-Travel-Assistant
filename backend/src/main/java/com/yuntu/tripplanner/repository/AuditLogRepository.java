package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/** 全链路审计日志（audit_log）数据访问 */
@Mapper
public interface AuditLogRepository extends BaseMapper<AuditLog> {
}
