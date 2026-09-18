package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.ContentModerationTask;
import org.apache.ibatis.annotations.Mapper;

/** AI 内容审核任务仓库（通用 CRUD 即够：去重/列表/详情均走 Wrapper） */
@Mapper
public interface ContentModerationTaskRepository extends BaseMapper<ContentModerationTask> {
}
