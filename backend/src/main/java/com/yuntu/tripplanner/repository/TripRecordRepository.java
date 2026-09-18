package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.TripRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 行程记录Repository
 */
@Mapper
public interface TripRecordRepository extends BaseMapper<TripRecord> {

    /**
     * 按 (trip_id, user_id) 取行的主键与逻辑删除标记，**包含已被逻辑删除的行**。
     *
     * 为什么需要它：TripRecord 上有 @TableLogic，常规 selectOne 会自动追加 deleted=0，
     * 于是"用户删除行程后又保存同一 trip_id"时查不到旧行 → 走 INSERT → 撞 UNIQUE(trip_id) 报 500。
     * 这里显式不带 deleted 条件，让上层能把旧行"复活"而不是重复插入。
     *
     * 只取 id/deleted 两列：不涉及 itinerary_json 的 JSON 映射，避免依赖自定义结果映射。
     */
    @Select("SELECT id, deleted FROM trip_record WHERE trip_id = #{tripId} AND user_id = #{userId} LIMIT 1")
    TripRecord selectKeyIncludingDeleted(@Param("tripId") String tripId, @Param("userId") String userId);

    /**
     * 统计某 trip_id 是否已被任何用户占用（含逻辑删除行）。
     * 用于插入前的冲突预判：trip_id 是全局唯一键，若已被他人占用则必须改派服务端 id，
     * 否则 INSERT 会撞唯一键报 500（历史遗留的"目的地_日期"式确定性 id 就会造成这种冲突）。
     */
    @Select("SELECT COUNT(*) FROM trip_record WHERE trip_id = #{tripId}")
    int countByTripId(@Param("tripId") String tripId);

    /**
     * 复活逻辑删除行（deleted: 1 → 0）。配合 {@link #selectKeyIncludingDeleted} 使用：
     * 复活后该行重新满足 @TableLogic 的 deleted=0 条件，后续 updateById / selectOne 均可正常命中。
     */
    @Update("UPDATE trip_record SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
