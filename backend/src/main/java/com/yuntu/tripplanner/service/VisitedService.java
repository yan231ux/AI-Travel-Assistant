package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.repository.TripRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「去过」唯一数据源（确认去过功能）。
 *
 * <p>全系统对"用户去过哪些城市 / 景点"的判断<b>只认这里</b>：仅统计 {@code visited_confirmed=1}
 * 的行程（用户主动确认过）。规划过但未确认的行程不算去过。
 *
 * <p>城市芯片、景点"你曾去过"、推荐去重 / 降权、生成"减少重复"提示全部从本服务取，
 * 从物理上避免多处各自查 trip_record 造成的口径分裂（主页说没去过、推荐却当去过去重）。
 */
@Slf4j
@Service
public class VisitedService {

    private final TripRecordRepository tripRecordRepository;

    public VisitedService(TripRecordRepository tripRecordRepository) {
        this.tripRecordRepository = tripRecordRepository;
    }

    /** 确认去过的城市（去重保序：最近确认的在前） */
    public Set<String> confirmedVisitedCities(String userId) {
        Set<String> cities = new LinkedHashSet<>();
        if (userId == null || userId.isBlank()) {
            return cities;
        }
        try {
            for (TripRecord r : confirmedTrips(userId)) {
                if (r.getDestination() != null && !r.getDestination().isBlank()) {
                    cities.add(r.getDestination());
                }
            }
        } catch (Exception e) {
            log.warn("读取确认去过的城市失败（降级为空）: {}", e.getMessage());
        }
        return cities;
    }

    /** 确认去过的景点名（供推荐 / 详情做"你曾去过"标记与去重降权） */
    public Set<String> confirmedVisitedSpotNames(String userId) {
        Set<String> names = new LinkedHashSet<>();
        if (userId == null || userId.isBlank()) {
            return names;
        }
        try {
            for (TripRecord r : confirmedTrips(userId)) {
                Itinerary it = r.getItinerary();
                if (it == null || it.getDays() == null) {
                    continue;
                }
                for (DayPlan d : it.getDays()) {
                    if (d == null || d.getSpots() == null) {
                        continue;
                    }
                    for (SpotItem s : d.getSpots()) {
                        if (s != null && s.getName() != null && !s.getName().isBlank()) {
                            names.add(s.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取确认去过的景点名失败（降级为空）: {}", e.getMessage());
        }
        return names;
    }

    /** 用户确认去过的行程（按创建时间倒序，全量，不受近期窗口限制） */
    private List<TripRecord> confirmedTrips(String userId) {
        return tripRecordRepository.selectList(new LambdaQueryWrapper<TripRecord>()
                .eq(TripRecord::getUserId, userId)
                .eq(TripRecord::getVisitedConfirmed, Boolean.TRUE)
                .orderByDesc(TripRecord::getCreatedAt));
    }
}
