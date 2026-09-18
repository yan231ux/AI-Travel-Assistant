package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TravelEvent;
import com.yuntu.tripplanner.repository.SpotRepository;
import com.yuntu.tripplanner.repository.TravelEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 通用行程事件服务（阶段二数据地基，设计方案 §7）。
 *
 * <p>职责：把「生成采用 / 行程保存 / 景点收藏」等产品动作写入 {@code travel_event} 事件流，
 * 并提供按天预聚合（{@code spot_trending_daily}）。与画像行为表分工：
 * 画像表驱动个性化权重，本事件表服务统计分析（采用率/保存率/收藏热度/城市热度）。
 *
 * <p>关键口径：
 * <ul>
 *   <li><b>去重</b>（§7.2）：同 (user_id, trip_id, item_id, event_type) 只记一次 ——
 *       重复刷新结果页、行程重复保存不会重复统计；</li>
 *   <li><b>item_id</b>：spot_id → poi_id → "name:" 名称兜底（稳定 ID 优先，跨来源可关联）；</li>
 *   <li><b>失败语义</b>：旁路增强层，写入失败仅告警绝不影响生成/保存/收藏主链路
 *       （与候选证据落库同语义）。</li>
 * </ul>
 */
@Slf4j
@Service
public class TravelEventService {

    private final TravelEventRepository travelEventRepository;
    private final SpotRepository spotRepository;

    public TravelEventService(TravelEventRepository travelEventRepository, SpotRepository spotRepository) {
        this.travelEventRepository = travelEventRepository;
        this.spotRepository = spotRepository;
    }

    /**
     * 行程生成完成埋点：TRIP_GENERATED + 每个入选景点的 SPOT_GENERATED（规划采用）。
     * 同一行程 trip_id 唯一，配合去重键天然幂等（重复调用不重复记）。
     */
    public void recordTripGenerated(String userId, Itinerary itinerary, String source) {
        recordTrip(userId, itinerary, source,
                TravelEvent.TYPE_TRIP_GENERATED, TravelEvent.TYPE_SPOT_GENERATED);
    }

    /**
     * 行程保存埋点：TRIP_SAVED + 每个景点的 SPOT_SAVED（保存语义独立于生成，
     * "生成了但没保存"与"保存了"是两个漏斗阶段）。
     */
    public void recordTripSaved(String userId, Itinerary itinerary) {
        recordTrip(userId, itinerary, TravelEvent.SOURCE_SAVE,
                TravelEvent.TYPE_TRIP_SAVED, TravelEvent.TYPE_SPOT_SAVED);
    }

    /** 景点收藏埋点（无行程上下文，trip_id 记 ''） */
    public void recordSpotFavorited(String userId, Spot spot) {
        if (userId == null || userId.isBlank() || spot == null) {
            return;
        }
        String itemId = firstNonBlank(spot.getSpotId(), spot.getPoiId(),
                spot.getName() == null ? null : "name:" + spot.getName().trim());
        insertEvent(event(userId, "", TravelEvent.TYPE_SPOT_FAVORITED,
                TravelEvent.ITEM_SPOT, itemId, spot.getName(), spot.getCity(),
                TravelEvent.SOURCE_FAVORITE, null));
    }

    private void recordTrip(String userId, Itinerary itinerary, String source,
                            String tripType, String spotType) {
        if (userId == null || userId.isBlank() || itinerary == null
                || itinerary.getDays() == null) {
            return;
        }
        String tripId = itinerary.getTripId();
        if (tripId == null || tripId.isBlank()) {
            return; // 无 trip_id 无法去重，宁缺勿滥
        }
        String city = itinerary.getDestination();
        insertEvent(event(userId, tripId, tripType, TravelEvent.ITEM_TRIP, tripId,
                city, city, source, null));

        // 景点事件：同行程内同名/同 id 只记一次（跨天重复景点不应重复统计）
        Map<String, String> itemIdCache = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (DayPlan day : itinerary.getDays()) {
            if (day == null || day.getSpots() == null) {
                continue;
            }
            for (SpotItem s : day.getSpots()) {
                if (s == null || s.getName() == null || s.getName().isBlank()) {
                    continue;
                }
                String itemId = resolveSpotItemId(s.getName(), s.getPoiId(), city, itemIdCache);
                if (!seen.add(itemId)) {
                    continue;
                }
                insertEvent(event(userId, tripId, spotType, TravelEvent.ITEM_SPOT,
                        itemId, s.getName(), city, source, null));
            }
        }
    }

    /**
     * item_id 口径统一（spot_id → poi_id → 名称兜底）：
     * 1) spot 表按 poi_id 反查稳定 spot_id；2) 按 城市+名称 反查；3) poi_id 本身；
     * 4) "name:" 前缀名称兜底。同一次调用内缓存反查结果（同一景点跨天复用）。
     */
    private String resolveSpotItemId(String name, String poiId, String city, Map<String, String> cache) {
        String cacheKey = name + "|" + (poiId == null ? "" : poiId);
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String resolved = resolveSpotItemIdUncached(name, poiId, city);
        cache.put(cacheKey, resolved);
        return resolved;
    }

    private String resolveSpotItemIdUncached(String name, String poiId, String city) {
        try {
            if (poiId != null && !poiId.isBlank()) {
                Spot byPoi = spotRepository.selectOne(new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getPoiId, poiId)
                        .last("LIMIT 1"));
                if (byPoi != null && byPoi.getSpotId() != null && !byPoi.getSpotId().isBlank()) {
                    return byPoi.getSpotId();
                }
            }
            if (name != null && city != null) {
                Spot byName = spotRepository.selectOne(new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getName, name.trim())
                        .eq(Spot::getCity, city)
                        .last("LIMIT 1"));
                if (byName != null && byName.getSpotId() != null && !byName.getSpotId().isBlank()) {
                    return byName.getSpotId();
                }
            }
        } catch (Exception e) {
            log.debug("spot 主档反查失败（退回 poi_id/名称口径）: {}", e.getMessage());
        }
        if (poiId != null && !poiId.isBlank()) {
            return poiId;
        }
        return "name:" + (name == null ? "" : name.trim());
    }

    /** 写入（带去重预查）。唯一键冲突（并发双写）与查询异常一并吞为幂等/告警 */
    private void insertEvent(TravelEvent e) {
        try {
            Long dup = travelEventRepository.selectCount(new LambdaQueryWrapper<TravelEvent>()
                    .eq(TravelEvent::getUserId, e.getUserId())
                    .eq(TravelEvent::getTripId, e.getTripId())
                    .eq(TravelEvent::getItemId, e.getItemId())
                    .eq(TravelEvent::getEventType, e.getEventType()));
            if (dup != null && dup > 0) {
                return;
            }
            travelEventRepository.insert(e);
        } catch (Exception ex) {
            log.warn("行程事件写入失败（不影响主链路）: type={} item={} err={}",
                    e.getEventType(), e.getItemId(), ex.getMessage());
        }
    }

    private TravelEvent event(String userId, String tripId, String eventType, String itemType,
                              String itemId, String itemName, String city, String source,
                              String metadataJson) {
        TravelEvent e = new TravelEvent();
        e.setUserId(userId);
        e.setSessionId(null);
        e.setTripId(tripId == null ? "" : tripId);
        e.setEventType(eventType);
        e.setItemType(itemType);
        e.setItemId(itemId);
        e.setItemName(itemName);
        e.setCity(city);
        e.setSource(source);
        e.setMetadataJson(metadataJson);
        e.setStatDate(LocalDate.now());
        return e;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 按天预聚合景点热度（幂等重算：DELETE + INSERT...SELECT）；空天返回 0。
     *
     * <p>两个触发入口：① 定时任务 {@code TrendingAggregationScheduler} 每日兜底重算，
     * 保证首页/看板随时读到聚合结果；② 管理端「立即重算」接口，近 N 天滚动补跑。
     */
    public int aggregateDaily(LocalDate date) {
        if (date == null) {
            return 0;
        }
        travelEventRepository.deleteTrendingDaily(date);
        int rows = travelEventRepository.insertTrendingDaily(date);
        log.info("景点热度按天预聚合完成：date={} rows={}", date, rows);
        return rows;
    }

    /** 按天预聚合城市热度（幂等重算：DELETE + INSERT...SELECT） */
    public int aggregateCityDaily(LocalDate date) {
        if (date == null) {
            return 0;
        }
        travelEventRepository.deleteCityTrendingDaily(date);
        int rows = travelEventRepository.insertCityTrendingDaily(date);
        log.info("城市热度按天预聚合完成：date={} rows={}", date, rows);
        return rows;
    }

    /** 按天预聚合内容审核漏斗（幂等重算：DELETE + INSERT...SELECT） */
    public int aggregateModerationDaily(LocalDate date) {
        if (date == null) {
            return 0;
        }
        travelEventRepository.deleteModerationDaily(date);
        int rows = travelEventRepository.insertModerationDaily(date);
        log.info("内容审核按天预聚合完成：date={} rows={}", date, rows);
        return rows;
    }

    /**
     * 重算某一天的三个预聚合维度（景点 / 城市 / 审核）。
     *
     * <p>单维度失败不中断其余维度：各自捕获记日志，返回成功的维度数（0~3）。
     * 这样某个维度表结构异常时，不至于把另外两个也一起拖垮。
     */
    public int aggregateAll(LocalDate date) {
        if (date == null) {
            return 0;
        }
        int ok = 0;
        try {
            aggregateDaily(date);
            ok++;
        } catch (Exception e) {
            log.warn("景点热度预聚合失败：date={} {}", date, e.getMessage());
        }
        try {
            aggregateCityDaily(date);
            ok++;
        } catch (Exception e) {
            log.warn("城市热度预聚合失败：date={} {}", date, e.getMessage());
        }
        try {
            aggregateModerationDaily(date);
            ok++;
        } catch (Exception e) {
            log.warn("内容审核预聚合失败：date={} {}", date, e.getMessage());
        }
        return ok;
    }
}
