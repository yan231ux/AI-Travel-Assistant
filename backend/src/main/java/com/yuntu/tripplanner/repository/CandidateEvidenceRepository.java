package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.CandidateEvidence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 候选证据仓储（对应 candidate_evidence 表，个性化口径统一轮）。
 */
@Mapper
public interface CandidateEvidenceRepository extends BaseMapper<CandidateEvidence> {
}
