package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.GuideEmbedding;
import org.apache.ibatis.annotations.Mapper;

/**
 * 攻略片段向量缓存 Repository
 */
@Mapper
public interface GuideEmbeddingRepository extends BaseMapper<GuideEmbedding> {

}
