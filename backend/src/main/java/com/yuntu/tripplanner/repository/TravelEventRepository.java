package com.yuntu.tripplanner.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuntu.tripplanner.model.TravelEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 通用行程事件仓库（阶段二数据地基）。
 * 除 MyBatis-Plus 通用 CRUD 外，提供「按天预聚合」的 DELETE + INSERT...SELECT（设计方案 §7.3），
 * 幂等重算：同一天重复聚合 = 先删后插，结果收敛不重复。
 */
@Mapper
public interface TravelEventRepository extends BaseMapper<TravelEvent> {

    /** 删除某天的预聚合结果（重算前置） */
    @Delete("DELETE FROM spot_trending_daily WHERE stat_date = #{date}")
    int deleteTrendingDaily(@Param("date") LocalDate date);

    /**
     * 按天预聚合景点热度：事件明细 → spot_trending_daily。
     * 口径区分（§7.2）：generated/saved/favorited 为事件次数，user_count 为去重用户数。
     *
     * <p>热度分（首页反哺）另需三项：planning_user_count（规划采用的去重用户）、
     * click_count / dislike_count（读 user_behavior 的 CLICK / DISLIKE）。
     * 注意：点击与负反馈是<b>用户行为表</b>而非事件表，用相关子查询按 item_id 归日取数——
     * 因此「只有点击、从未被任何行程采用」的景点不会出现在本表（对其余指标无影响）。
     */
    @Insert("INSERT INTO spot_trending_daily "
            + "(stat_date, item_id, item_name, city, generated_count, saved_count, favorited_count, "
            + " user_count, planning_user_count, click_count, dislike_count) "
            + "SELECT e.stat_date, e.item_id, MAX(e.item_name), MAX(e.city), "
            + "       SUM(e.event_type = 'SPOT_GENERATED'), "
            + "       SUM(e.event_type = 'SPOT_SAVED'), "
            + "       SUM(e.event_type = 'SPOT_FAVORITED'), "
            + "       COUNT(DISTINCT e.user_id), "
            + "       COUNT(DISTINCT CASE WHEN e.event_type = 'SPOT_GENERATED' THEN e.user_id END), "
            + "       (SELECT COUNT(*) FROM user_behavior b WHERE b.item_id = e.item_id "
            + "          AND b.action_type = 'CLICK' AND DATE(b.created_at) = #{date}), "
            + "       (SELECT COUNT(*) FROM user_behavior b WHERE b.item_id = e.item_id "
            + "          AND b.action_type = 'DISLIKE' AND DATE(b.created_at) = #{date}) "
            + "FROM travel_event e "
            + "WHERE e.item_type = 'SPOT' AND e.stat_date = #{date} "
            + "GROUP BY e.stat_date, e.item_id")
    int insertTrendingDaily(@Param("date") LocalDate date);

    /** 删除某天的城市热度预聚合结果（重算前置） */
    @Delete("DELETE FROM city_trending_daily WHERE stat_date = #{date}")
    int deleteCityTrendingDaily(@Param("date") LocalDate date);

    /**
     * 按天预聚合城市热度：事件明细 → city_trending_daily。
     * 只统计有城市的行程类事件（景点级事件 city 由快照补齐，不在城市维度重复计数）。
     */
    @Insert("INSERT INTO city_trending_daily "
            + "(stat_date, city, trip_generated_count, trip_saved_count, spot_adopt_count, user_count) "
            + "SELECT e.stat_date, e.city, "
            + "       SUM(e.event_type = 'TRIP_GENERATED'), "
            + "       SUM(e.event_type = 'TRIP_SAVED'), "
            + "       SUM(e.event_type = 'SPOT_GENERATED'), "
            + "       COUNT(DISTINCT e.user_id) "
            + "FROM travel_event e "
            + "WHERE e.stat_date = #{date} AND e.city IS NOT NULL AND e.city <> '' "
            + "GROUP BY e.stat_date, e.city")
    int insertCityTrendingDaily(@Param("date") LocalDate date);

    /** 删除某天的审核预聚合结果（重算前置） */
    @Delete("DELETE FROM content_moderation_daily WHERE stat_date = #{date}")
    int deleteModerationDaily(@Param("date") LocalDate date);

    /**
     * 按天预聚合内容审核漏斗：content_moderation_task → content_moderation_daily。
     * 注意：审核任务用 created_at（DATETIME）落库，这里按 DATE(created_at) 归日，
     * 与事件表用 stat_date（DATE）的口径不同，不能混用。
     */
    @Insert("INSERT INTO content_moderation_daily "
            + "(stat_date, total_count, auto_passed_count, review_count, failed_count, "
            + " human_approved_count, human_rejected_count, rule_hit_count) "
            + "SELECT DATE(created_at), COUNT(*), "
            + "       SUM(status = 'PASSED'), SUM(status = 'REVIEW'), SUM(status = 'FAILED'), "
            + "       SUM(decision = 'APPROVE'), SUM(decision = 'REJECT'), SUM(rule_hit_count > 0) "
            + "FROM content_moderation_task "
            + "WHERE DATE(created_at) = #{date} "
            + "GROUP BY DATE(created_at)")
    int insertModerationDaily(@Param("date") LocalDate date);
}
