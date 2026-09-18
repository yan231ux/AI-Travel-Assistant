package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.SpotNameUtil;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.CityGuideSpot;
import com.yuntu.tripplanner.model.PostSpot;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.SpotFavorite;
import com.yuntu.tripplanner.repository.CityGuideSpotRepository;
import com.yuntu.tripplanner.repository.PostSpotRepository;
import com.yuntu.tripplanner.repository.SpotFavoriteRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 景点数据治理服务（B组，管理员后台与内容运营中心设计方案 §5 景点数据运营中心）。
 *
 * <p>与 {@link RecommendationFeedService}（推荐流/同步）职责分离：本服务只回答
 * 「管理员如何查看与修正景点主档数据质量」——列表/详情、编辑字段（写人工锁定）、
 * 标记异常（flag）、上下架、重复合并、单点重同步、攻略重匹配，所有动作全部审计留痕。
 *
 * <p>数据安全三原则（对齐 §5「管理员手工修正不能被下一次高德同步无条件覆盖」）：
 * <ul>
 *   <li><b>人工锁定</b>：edit 修改的字段并入 manual_override_fields（manual_override=true），
 *       同步/重同步只更新未锁定字段（RecommendationFeedService.applyPoiToSpot）；</li>
 *   <li><b>city 不可直接改</b>：spot_id 是系统稳定 ID（spot_{city}_{poiId}）且 (city,poi_id)
 *       有唯一键，跨城挪动会破坏引用与唯一性 —— 归属错误请走「合并」或标记 ERROR_POI 下线；</li>
 *   <li><b>历史不改写</b>：曝光/推荐/行程证据等日志表保留原始 spot_id（append-only 统计口径），
 *       合并只重定向「用户可见的实时引用」：收藏、帖子关联、攻略关联。</li>
 * </ul>
 */
@Slf4j
@Service
public class SpotAdminService {

    /** 治理标记枚举（与 Spot.FLAG_* 一致；编辑字段锁定名同键） */
    private static final Set<String> KNOWN_FLAGS = Set.of(
            Spot.FLAG_NON_SPOT, Spot.FLAG_CLOSED, Spot.FLAG_OUTDATED, Spot.FLAG_ERROR_POI);

    /** 可编辑字段白名单（key = 请求字段名 = 人工锁定字段名；longitude 同时代表经纬度组） */
    private static final Set<String> EDITABLE_FIELDS = Set.of(
            "name", "address", "category", "description", "tags", "imageUrl", "longitude", "latitude");

    private final SpotRepository spotRepository;
    private final SpotFavoriteRepository spotFavoriteRepository;
    private final PostSpotRepository postSpotRepository;
    private final CityGuideSpotRepository guideSpotRepository;
    private final RecommendationFeedService feedService;
    private final CommunityUserService communityUserService;
    private final AuditService auditService;

    public SpotAdminService(SpotRepository spotRepository,
                            SpotFavoriteRepository spotFavoriteRepository,
                            PostSpotRepository postSpotRepository,
                            CityGuideSpotRepository guideSpotRepository,
                            RecommendationFeedService feedService,
                            CommunityUserService communityUserService,
                            AuditService auditService) {
        this.spotRepository = spotRepository;
        this.spotFavoriteRepository = spotFavoriteRepository;
        this.postSpotRepository = postSpotRepository;
        this.guideSpotRepository = guideSpotRepository;
        this.feedService = feedService;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /* ================= 查询面 ================= */

    /** 景点治理列表：城市/上下架/治理标记/关键词过滤；附全量概览计数（设计方案 §5 列表字段） */
    public Map<String, Object> page(String adminId, String city, String status, String flag,
                                    String keyword, int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<Spot> w = new LambdaQueryWrapper<Spot>()
                .orderByDesc(Spot::getUpdatedAt);
        if (city != null && !city.isBlank()) {
            w.eq(Spot::getCity, city.trim());
        }
        if (status != null && !status.isBlank()) {
            w.eq(Spot::getStatus, status.trim().toUpperCase());
        }
        if (flag != null && !flag.isBlank()) {
            if ("NONE".equalsIgnoreCase(flag)) {
                w.isNull(Spot::getFlag);
            } else if ("ANY".equalsIgnoreCase(flag)) {
                w.isNotNull(Spot::getFlag);
            } else {
                w.eq(Spot::getFlag, flag.trim().toUpperCase());
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(x -> x.like(Spot::getName, kw)
                    .or().like(Spot::getSpotId, kw)
                    .or().like(Spot::getPoiId, kw));
        }
        long total = spotRepository.selectCount(w);
        List<Spot> rows = spotRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.stream().map(this::toView).toList());
        body.put("total", total);
        body.put("page", pageNo);
        body.put("pageSize", size);
        body.put("summary", summary(city));
        return body;
    }

    /** 概览计数（口径与列表一致：按城市可收窄；keyword 不影响总览） */
    private Map<String, Object> summary(String city) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", count(null, city, null));
        s.put("online", count(Spot.STATUS_ONLINE, city, null));
        s.put("offline", count(Spot.STATUS_OFFLINE, city, null));
        s.put("flagged", count(null, city, "ANY"));
        s.put("merged", spotRepository.selectCount(mergedW(city)));
        return s;
    }

    private long count(String status, String city, String flag) {
        LambdaQueryWrapper<Spot> w = new LambdaQueryWrapper<>();
        if (status != null) {
            w.eq(Spot::getStatus, status);
        }
        if (city != null && !city.isBlank()) {
            w.eq(Spot::getCity, city.trim());
        }
        if ("ANY".equals(flag)) {
            w.isNotNull(Spot::getFlag);
        } else if ("NONE".equals(flag)) {
            w.isNull(Spot::getFlag);
        }
        return spotRepository.selectCount(w);
    }

    private LambdaQueryWrapper<Spot> mergedW(String city) {
        LambdaQueryWrapper<Spot> w = new LambdaQueryWrapper<Spot>().isNotNull(Spot::getMergedInto);
        if (city != null && !city.isBlank()) {
            w.eq(Spot::getCity, city.trim());
        }
        return w;
    }

    /** 治理详情：完整字段（同列表视图，description 不截断）+ 收藏数/别名行/攻略关联/帖子关联数 */
    public Map<String, Object> detail(String adminId, long id) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        Map<String, Object> d = toView(spot);
        d.put("description", spot.getDescription());
        d.put("favoriteCount", spotFavoriteRepository.selectCount(
                new LambdaQueryWrapper<SpotFavorite>().eq(SpotFavorite::getSpotId, spot.getSpotId())));
        d.put("aliasCount", spotRepository.selectCount(
                new LambdaQueryWrapper<Spot>().eq(Spot::getMergedInto, spot.getSpotId())));
        d.put("guideLinkCount", guideSpotRepository.selectCount(
                new LambdaQueryWrapper<CityGuideSpot>().eq(CityGuideSpot::getSpotId, spot.getSpotId())));
        d.put("postLinkCount", postSpotRepository.selectCount(
                new LambdaQueryWrapper<PostSpot>().eq(PostSpot::getSpotId, spot.getSpotId())));
        return d;
    }

    /** 列表展示视图（description 截断避免列表过大） */
    private Map<String, Object> toView(Spot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("spot_id", s.getSpotId());
        m.put("poi_id", s.getPoiId());
        m.put("name", s.getName());
        m.put("city", s.getCity());
        m.put("address", s.getAddress());
        m.put("longitude", s.getLongitude());
        m.put("latitude", s.getLatitude());
        m.put("category", s.getCategory());
        m.put("image_url", s.getImageUrl());
        m.put("description", truncate(s.getDescription(), 160));
        m.put("tags", s.getTags());
        m.put("source", s.getSource());
        m.put("data_quality", s.getDataQuality());
        m.put("status", s.getStatus());
        m.put("flag", s.getFlag());
        m.put("flag_reason", s.getFlagReason());
        m.put("manual_override", s.getManualOverride());
        m.put("manual_override_fields", s.getManualOverrideFields());
        m.put("last_verified_by", s.getLastVerifiedBy());
        m.put("last_verified_at", s.getLastVerifiedAt());
        m.put("merged_into", s.getMergedInto());
        m.put("last_synced_at", s.getLastSyncedAt());
        m.put("created_at", s.getCreatedAt());
        m.put("updated_at", s.getUpdatedAt());
        return m;
    }

    /* ================= 编辑（人工修正 + 字段锁定） ================= */

    /**
     * 编辑景点字段（白名单）：只允许 name/address/category/description/tags/imageUrl/
     * longitude/latitude。修改的字段并入人工锁定集（sync 不再覆盖）；unlockFields 可显式解锁。
     * 传 null 的字段不动；空字符串 = 清空（仅限可空字段）；city 不允许直接修改（见类注释）。
     */
    public Spot edit(String adminId, long id, Map<String, Object> body) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        if (body == null || body.isEmpty()) {
            return spot;
        }
        if (body.containsKey("city")) {
            String newCity = body.get("city") == null ? null : String.valueOf(body.get("city")).trim();
            if (newCity != null && !newCity.isBlank() && !newCity.equals(spot.getCity())) {
                throw new IllegalArgumentException(
                        "景点归属城市不允许直接修改（spot_id 稳定 ID 与 (city,poi_id) 唯一键约束）。"
                                + "若城市归属错误：跨城重复请用「合并到」处理，错误 POI 请标记 ERROR_POI 并下线。");
            }
        }
        Set<String> locked = new LinkedHashSet<>(RecommendationFeedService.lockedFields(spot));
        Set<String> changed = new LinkedHashSet<>();
        String name = valueOf(body.get("name"));
        String address = valueOf(body.get("address"));
        String category = valueOf(body.get("category"));
        String description = valueOf(body.get("description"));
        String tags = valueOf(body.get("tags"));
        String imageUrl = valueOf(body.get("imageUrl"));

        if (body.containsKey("name")) {
            // null=不改；空串=不允许（名称是必填主展示字段）
            if (name != null && name.isBlank()) {
                throw new IllegalArgumentException("景点名称不能为空");
            }
            if (name != null && !name.equals(spot.getName())) {
                changed.add("name");
            }
        }
        if (body.containsKey("address") && address != null) {
            changed.add("address");
        }
        if (body.containsKey("category") && category != null) {
            changed.add("category");
        }
        if (body.containsKey("description") && description != null) {
            changed.add("description");
        }
        if (body.containsKey("tags") && tags != null) {
            changed.add("tags");
        }
        if (body.containsKey("imageUrl") && imageUrl != null) {
            changed.add("imageUrl");
        }
        if (body.containsKey("longitude") || body.containsKey("latitude")) {
            Double lng = body.get("longitude") == null ? spot.getLongitude()
                    : Double.valueOf(String.valueOf(body.get("longitude")).trim());
            Double lat = body.get("latitude") == null ? spot.getLatitude()
                    : Double.valueOf(String.valueOf(body.get("latitude")).trim());
            if (lng == null || lat == null) {
                throw new IllegalArgumentException("经度与纬度需成对提供（可只改其中一个，另一个沿用原值）");
            }
            changed.add("longitude");
        }

        // 显式解锁（unlockFields 仅移除锁定，不改数据）。
        // 注意：请求里同时传了某字段的值 → 该字段视为「本次修改」会在解锁后重新入锁；
        // 想「把值还原并解除锁定」需分两步：先改值，再单独发一次仅带 unlockFields 的请求。
        List<String> unlock = body.get("unlockFields") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        for (String f : unlock) {
            if (!EDITABLE_FIELDS.contains(f)) {
                throw new IllegalArgumentException("未知的解锁字段：" + f + "（可选：" + EDITABLE_FIELDS + "）");
            }
            locked.remove(f);
        }
        locked.addAll(changed);

        // 落库：显式 wrapper SET（updateById 忽略 null，清空类字段必须走 wrapper 显式写 NULL）
        LambdaUpdateWrapper<Spot> w = new LambdaUpdateWrapper<Spot>().eq(Spot::getId, id);
        if (changed.contains("name")) {
            w.set(Spot::getName, name)
                    .set(Spot::getNormalizedName, SpotNameUtil.normalize(name));
        }
        if (changed.contains("address")) {
            w.set(Spot::getAddress, emptyToNull(address));
        }
        if (changed.contains("category")) {
            w.set(Spot::getCategory, emptyToNull(category));
        }
        if (changed.contains("description")) {
            w.set(Spot::getDescription, emptyToNull(description));
        }
        if (changed.contains("tags")) {
            w.set(Spot::getTags, emptyToNull(tags));
        }
        if (changed.contains("imageUrl")) {
            w.set(Spot::getImageUrl, emptyToNull(imageUrl));
        }
        if (changed.contains("longitude")) {
            w.set(Spot::getLongitude, body.get("longitude") == null ? spot.getLongitude()
                            : Double.valueOf(String.valueOf(body.get("longitude")).trim()))
                    .set(Spot::getLatitude, body.get("latitude") == null ? spot.getLatitude()
                            : Double.valueOf(String.valueOf(body.get("latitude")).trim()));
        }
        w.set(Spot::getManualOverride, !locked.isEmpty())
                .set(Spot::getManualOverrideFields, String.join(",", locked));
        stampVerified(w, adminId);
        spotRepository.update(null, w);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "spot_edited", "spot", spot.getSpotId(),
                AuditService.detailOf("fields", changed, "locked", String.join(",", locked),
                        "reason", body.get("reason")));
        log.info("景点人工修正：id={} spot={} changed={} locked={}",
                id, spot.getSpotId(), changed, String.join(",", locked));
        return requireSpot(id);
    }

    /* ================= 治理标记（flag）与上下架 ================= */

    /**
     * 设置/清除治理标记：flag 空串或 null = 清除；NON_SPOT/CLOSED/ERROR_POI 自动同步下线
     * （这些语义下绝不再对外推荐展示），OUTDATED 仅标记（等待重同步核验，保持可展示）。
     */
    public Spot setFlag(String adminId, long id, String flag, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        LambdaUpdateWrapper<Spot> w = new LambdaUpdateWrapper<Spot>().eq(Spot::getId, id);
        boolean clearing = flag == null || flag.isBlank();
        if (clearing) {
            w.set(Spot::getFlag, null).set(Spot::getFlagReason, null);
        } else {
            String f = flag.trim().toUpperCase();
            if (!KNOWN_FLAGS.contains(f)) {
                throw new IllegalArgumentException("未知治理标记：" + f
                        + "（可选 NON_SPOT/CLOSED/OUTDATED/ERROR_POI，传空清除）");
            }
            w.set(Spot::getFlag, f)
                    .set(Spot::getFlagReason, trimTo(reason, 500));
            if (!Spot.FLAG_OUTDATED.equals(f)) {
                // 非景点/已关闭/错误 POI → 强制下线，杜绝继续外露
                w.set(Spot::getStatus, Spot.STATUS_OFFLINE);
            }
        }
        stampVerified(w, adminId);
        spotRepository.update(null, w);
        auditService.record(adminId, AuditLog.CAT_ADMIN,
                clearing ? "spot_unflagged" : "spot_flagged", "spot", spot.getSpotId(),
                AuditService.detailOf("flag", clearing ? null : flag.trim().toUpperCase(),
                        "reason", reason, "spot_id", spot.getSpotId()));
        return requireSpot(id);
    }

    /** 下线（管理员人工；保留 flag 现状，原因写入 flag_reason） */
    public Spot offline(String adminId, long id, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        LambdaUpdateWrapper<Spot> w = new LambdaUpdateWrapper<Spot>().eq(Spot::getId, id)
                .set(Spot::getStatus, Spot.STATUS_OFFLINE);
        if (reason != null && !reason.isBlank()) {
            w.set(Spot::getFlagReason, trimTo(reason, 500));
        }
        stampVerified(w, adminId);
        spotRepository.update(null, w);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "spot_offlined", "spot", spot.getSpotId(),
                AuditService.detailOf("reason", reason, "spot_id", spot.getSpotId()));
        return requireSpot(id);
    }

    /** 重新上线：存在未清除的治理标记（非景点/已关闭/错误 POI）时拒绝，避免治理对象复活 */
    public Spot online(String adminId, long id) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        if (spot.getFlag() != null && !Spot.FLAG_OUTDATED.equals(spot.getFlag())) {
            throw new IllegalArgumentException("该景点存在治理标记 " + spot.getFlag()
                    + "（" + spot.getFlagReason() + "）。请先清除标记确认数据有效后再重新上线。");
        }
        LambdaUpdateWrapper<Spot> w = new LambdaUpdateWrapper<Spot>().eq(Spot::getId, id)
                .set(Spot::getStatus, Spot.STATUS_ONLINE)
                .set(Spot::getFlagReason, null);
        if (Spot.FLAG_OUTDATED.equals(spot.getFlag())) {
            w.set(Spot::getFlag, null);
        }
        stampVerified(w, adminId);
        spotRepository.update(null, w);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "spot_onlined", "spot", spot.getSpotId(),
                AuditService.detailOf("spot_id", spot.getSpotId()));
        return requireSpot(id);
    }

    /* ================= 重复合并 ================= */

    /**
     * 重复景点合并：把 source 并入 target —— source 下线并指向 target（merged_into），
     * 用户可见的实时引用（收藏/帖子关联/攻略关联）重定向到 target 并去重；
     * 曝光/推荐等历史日志保留原 spot_id（append-only 统计口径不改写历史）。
     * 同城才允许合并（跨城重复请先判断归属，错误 POI 用 ERROR_POI 处理）。
     */
    @Transactional
    public Map<String, Object> merge(String adminId, long sourceId, String targetSpotId, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot source = requireSpot(sourceId);
        if (source.getMergedInto() != null && !source.getMergedInto().isBlank()) {
            throw new IllegalArgumentException("该景点已是合并别名（merged_into="
                    + source.getMergedInto() + "），不能再作为合并源");
        }
        if (targetSpotId == null || targetSpotId.isBlank()) {
            throw new IllegalArgumentException("缺少合并目标 spot_id");
        }
        Spot target = spotRepository.selectOne(new LambdaQueryWrapper<Spot>()
                .eq(Spot::getSpotId, targetSpotId.trim()).last("LIMIT 1"));
        if (target == null) {
            throw new IllegalArgumentException("合并目标景点不存在：" + targetSpotId);
        }
        if (target.getId().equals(source.getId())) {
            throw new IllegalArgumentException("不能与自己合并");
        }
        if (target.getMergedInto() != null && !target.getMergedInto().isBlank()) {
            throw new IllegalArgumentException("合并目标本身是别名行（merged_into="
                    + target.getMergedInto() + "），请以主行为合并目标");
        }
        if (!target.getCity().equals(source.getCity())) {
            throw new IllegalArgumentException("仅支持同城景点合并（" + source.getCity()
                    + " ≠ " + target.getCity() + "）。跨城疑似重复请先核验归属。");
        }

        int favMoved = repointFavorites(source, target);
        int guideMoved = repointGuideLinks(source, target);
        int postMoved = repointPostLinks(source, target);

        String note = reason == null || reason.isBlank() ? "" : "；" + reason.trim();
        LambdaUpdateWrapper<Spot> w = new LambdaUpdateWrapper<Spot>().eq(Spot::getId, source.getId())
                .set(Spot::getStatus, Spot.STATUS_OFFLINE)
                .set(Spot::getMergedInto, target.getSpotId())
                .set(Spot::getFlagReason, trimTo("重复景点，已合并至 " + target.getSpotId()
                        + "（" + target.getName() + "）" + note, 500));
        stampVerified(w, adminId);
        spotRepository.update(null, w);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "spot_merged", "spot", source.getSpotId(),
                AuditService.detailOf("source", source.getSpotId(), "target", target.getSpotId(),
                        "reason", reason, "favorites_moved", favMoved,
                        "guide_links_moved", guideMoved, "post_links_moved", postMoved));
        log.info("景点合并完成：{} → {}（收藏 {} 攻略 {} 帖子 {}）",
                source.getSpotId(), target.getSpotId(), favMoved, guideMoved, postMoved);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", source.getSpotId());
        r.put("target", target.getSpotId());
        r.put("favorites_moved", favMoved);
        r.put("guide_links_moved", guideMoved);
        r.put("post_links_moved", postMoved);
        return r;
    }

    /** 收藏引用重定向：同用户已收藏 target 则删除 source 行（去重），否则改指向 target */
    private int repointFavorites(Spot source, Spot target) {
        int moved = 0;
        List<SpotFavorite> favs = spotFavoriteRepository.selectList(
                new LambdaQueryWrapper<SpotFavorite>().eq(SpotFavorite::getSpotId, source.getSpotId()));
        for (SpotFavorite f : favs) {
            try {
                boolean hasTarget = spotFavoriteRepository.selectCount(
                        new LambdaQueryWrapper<SpotFavorite>()
                                .eq(SpotFavorite::getUserId, f.getUserId())
                                .eq(SpotFavorite::getSpotId, target.getSpotId())) > 0;
                if (hasTarget) {
                    spotFavoriteRepository.deleteById(f.getId());
                } else {
                    spotFavoriteRepository.update(null, new LambdaUpdateWrapper<SpotFavorite>()
                            .eq(SpotFavorite::getId, f.getId())
                            .set(SpotFavorite::getSpotId, target.getSpotId())
                            .set(SpotFavorite::getPoiId, target.getPoiId())
                            .set(SpotFavorite::getName, target.getName())
                            .set(SpotFavorite::getCity, target.getCity())
                            .set(SpotFavorite::getImageUrl, target.getImageUrl()));
                }
                moved++;
            } catch (Exception e) {
                log.warn("收藏引用重定向失败（跳过该行）: favId={} - {}", f.getId(), e.getMessage());
            }
        }
        return moved;
    }

    /** 攻略-景点关联重定向：同攻略已关联 target 则删 source 行，否则改指 target */
    private int repointGuideLinks(Spot source, Spot target) {
        int moved = 0;
        List<CityGuideSpot> links = guideSpotRepository.selectList(
                new LambdaQueryWrapper<CityGuideSpot>().eq(CityGuideSpot::getSpotId, source.getSpotId()));
        for (CityGuideSpot g : links) {
            try {
                boolean hasTarget = guideSpotRepository.selectCount(
                        new LambdaQueryWrapper<CityGuideSpot>()
                                .eq(CityGuideSpot::getGuideId, g.getGuideId())
                                .eq(CityGuideSpot::getSpotId, target.getSpotId())) > 0;
                if (hasTarget) {
                    guideSpotRepository.deleteById(g.getId());
                } else {
                    guideSpotRepository.update(null, new LambdaUpdateWrapper<CityGuideSpot>()
                            .eq(CityGuideSpot::getId, g.getId())
                            .set(CityGuideSpot::getSpotId, target.getSpotId())
                            .set(CityGuideSpot::getPoiId, target.getPoiId()));
                }
                moved++;
            } catch (Exception e) {
                log.warn("攻略关联重定向失败（跳过该行）: linkId={} - {}", g.getId(), e.getMessage());
            }
        }
        return moved;
    }

    /** 帖子-景点关联重定向：同帖已关联 target 则删 source 行，否则改指 target（快照名同步） */
    private int repointPostLinks(Spot source, Spot target) {
        int moved = 0;
        List<PostSpot> links = postSpotRepository.selectList(
                new LambdaQueryWrapper<PostSpot>().eq(PostSpot::getSpotId, source.getSpotId()));
        for (PostSpot p : links) {
            try {
                boolean hasTarget = postSpotRepository.selectCount(
                        new LambdaQueryWrapper<PostSpot>()
                                .eq(PostSpot::getPostId, p.getPostId())
                                .eq(PostSpot::getSpotId, target.getSpotId())) > 0;
                if (hasTarget) {
                    postSpotRepository.deleteById(p.getId());
                } else {
                    postSpotRepository.update(null, new LambdaUpdateWrapper<PostSpot>()
                            .eq(PostSpot::getId, p.getId())
                            .set(PostSpot::getSpotId, target.getSpotId())
                            .set(PostSpot::getPoiId, target.getPoiId())
                            .set(PostSpot::getSpotName, target.getName()));
                }
                moved++;
            } catch (Exception e) {
                log.warn("帖子关联重定向失败（跳过该行）: linkId={} - {}", p.getId(), e.getMessage());
            }
        }
        return moved;
    }

    /* ================= 同步与攻略重匹配 ================= */

    /**
     * 手动触发单点重新同步（强刷高德，绕过缓存；人工锁定字段不被覆盖；治理状态不动）。
     * 同步未命中同名 POI → 返回提示（建议人工标记 OUTDATED/CLOSED 核验）。
     */
    public Map<String, Object> resync(String adminId, long id) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        int result = feedService.resyncSpot(spot.getSpotId());
        stampVerifiedRow(adminId, id);
        String message;
        if (result == RecommendationFeedService.SYNC_UPDATED) {
            message = "已从高德刷新该景点（人工锁定字段未覆盖；OUTDATED 标记已清除）";
        } else if (result == RecommendationFeedService.SYNC_NO_MATCH) {
            message = "高德未找到同名 POI（可能已更名/下线）。建议标记 CLOSED/ERROR_POI 或人工核验";
        } else {
            throw new IllegalArgumentException("景点不存在或缺少城市/名称，无法同步");
        }
        auditService.record(adminId, AuditLog.CAT_ADMIN, "spot_resynced", "spot", spot.getSpotId(),
                AuditService.detailOf("result", result, "message", message));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", result);
        r.put("message", message);
        return r;
    }

    /** 手动重新匹配 RAG 攻略卡片（命中回填简介/升 quality；描述/标签被锁定则跳过） */
    public Map<String, Object> rematchGuide(String adminId, long id) {
        communityUserService.requirePermission(adminId, AdminPermission.SPOT_GOVERN);
        Spot spot = requireSpot(id);
        boolean hit = feedService.rematchGuide(spot.getSpotId());
        stampVerifiedRow(adminId, id);
        String message = hit
                ? "已命中本地攻略卡片，回填简介并升级数据质量"
                : "未命中攻略卡片（保持现有内容；若描述/标签被人工锁定也会跳过）";
        auditService.record(adminId, AuditLog.CAT_ADMIN, "spot_rematched", "spot", spot.getSpotId(),
                AuditService.detailOf("hit", hit));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hit", hit);
        r.put("message", message);
        return r;
    }

    /* ================= 私有工具 ================= */

    private Spot requireSpot(long id) {
        Spot spot = spotRepository.selectById(id);
        if (spot == null) {
            throw new IllegalArgumentException("景点不存在（id=" + id + "）");
        }
        return spot;
    }

    /** 审核人戳记（wrapper 更新路径） */
    private void stampVerified(LambdaUpdateWrapper<Spot> w, String adminId) {
        w.set(Spot::getLastVerifiedBy, communityUserService.nicknameOf(adminId))
                .set(Spot::getLastVerifiedAt, LocalDateTime.now());
    }

    /** 审核人戳记（按行主键补一条轻量更新；feed 类动作已自行落库后调用） */
    private void stampVerifiedRow(String adminId, long id) {
        spotRepository.update(null, new LambdaUpdateWrapper<Spot>()
                .eq(Spot::getId, id)
                .set(Spot::getLastVerifiedBy, communityUserService.nicknameOf(adminId))
                .set(Spot::getLastVerifiedAt, LocalDateTime.now()));
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 原始值（null 保持 null；其余 trim）——「字段是否出现」由调用方 containsKey 判断 */
    private static String valueOf(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
