package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.ContentReport;
import org.apache.ibatis.annotations.Mapper;

/** 内容举报数据访问（阶段二社区） */
@Mapper
public interface ContentReportRepository extends BaseMapper<ContentReport> {
}
