package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.RagIndexTask;
import org.apache.ibatis.annotations.Mapper;

/** RAG 索引任务数据访问（攻略发布后按 revision 派生索引的任务记录） */
@Mapper
public interface RagIndexTaskRepository extends BaseMapper<RagIndexTask> {
}
