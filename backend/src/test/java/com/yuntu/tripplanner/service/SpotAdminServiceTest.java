package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.repository.CityGuideSpotRepository;
import com.yuntu.tripplanner.repository.PostSpotRepository;
import com.yuntu.tripplanner.repository.SpotFavoriteRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 景点治理服务单测（B组 §5）：编辑人工锁定、flag 自动下线、上线守卫、
 * 合并校验等业务规则；数据落库细节由实机冒烟覆盖。
 */
@ExtendWith(MockitoExtension.class)
class SpotAdminServiceTest {

    private static final String ADMIN = "12";

    @Mock
    private SpotRepository spotRepository;
    @Mock
    private SpotFavoriteRepository spotFavoriteRepository;
    @Mock
    private PostSpotRepository postSpotRepository;
    @Mock
    private CityGuideSpotRepository guideSpotRepository;
    @Mock
    private RecommendationFeedService feedService;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private AuditService auditService;

    private SpotAdminService service;

    @BeforeEach
    void setUp() {
        service = new SpotAdminService(spotRepository, spotFavoriteRepository, postSpotRepository,
                guideSpotRepository, feedService, communityUserService, auditService);
    }

    private Spot spot(long id, String spotId) {
        Spot s = new Spot();
        s.setId(id);
        s.setSpotId(spotId);
        s.setName("外滩");
        s.setCity("上海");
        s.setPoiId("poi_1");
        s.setStatus(Spot.STATUS_ONLINE);
        return s;
    }

    /* ---------- 权限：景点治理按域校验 SPOT_GOVERN（C线角色细化） ---------- */

    @Test
    void spotGovernedActions_requireSpotGovernPermission() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService)
                .requirePermission(anyString(), eq(AdminPermission.SPOT_GOVERN));

        assertThrows(ForbiddenException.class, () -> service.detail(ADMIN, 1L));
        assertThrows(ForbiddenException.class, () -> service.offline(ADMIN, 1L, "违规下架"));
        // 权限校验必须在触库之前失败
        verify(spotRepository, never()).selectById(any());
    }

    @Test
    void edit_nameChange_marksManualOverride_andReturnsRefreshed() {
        when(spotRepository.selectById(1L)).thenReturn(spot(1L, "spot_上海_poi_1"));
        // 编辑后的第二次读取（requireSpot 返回"落库后"状态）
        Spot updated = spot(1L, "spot_上海_poi_1");
        updated.setName("外滩风景区");
        updated.setNormalizedName("外滩");
        updated.setManualOverride(true);
        updated.setManualOverrideFields("name");
        when(spotRepository.selectById(1L)).thenReturn(updated);

        Spot result = service.edit(ADMIN, 1L, Map.of("name", "外滩风景区", "reason", "补全正式名称"));

        assertEquals("外滩风景区", result.getName());
        assertEquals(Boolean.TRUE, result.getManualOverride());
        assertEquals("name", result.getManualOverrideFields());
        verify(spotRepository).update(isNull(), any());
        verify(auditService).record(org.mockito.ArgumentMatchers.eq(ADMIN),
                org.mockito.ArgumentMatchers.eq(com.yuntu.tripplanner.model.AuditLog.CAT_ADMIN),
                org.mockito.ArgumentMatchers.eq("spot_edited"), any(), any(), any());
    }

    @Test
    void edit_cityChange_rejected() {
        when(spotRepository.selectById(1L)).thenReturn(spot(1L, "spot_上海_poi_1"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.edit(ADMIN, 1L, Map.of("city", "北京")));
        assertEquals(true, ex.getMessage().contains("不允许直接修改"));
        verify(spotRepository, never()).update(isNull(), any());
    }

    @Test
    void edit_unknownField_unlockRejected() {
        when(spotRepository.selectById(1L)).thenReturn(spot(1L, "spot_上海_poi_1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.edit(ADMIN, 1L, Map.of("unlockFields", List.of("city"))));
    }

    @Test
    void flag_nonSpot_autoOffline() {
        Spot s = spot(1L, "spot_上海_poi_1");
        when(spotRepository.selectById(1L)).thenReturn(s);
        Spot offlined = spot(1L, "spot_上海_poi_1");
        offlined.setStatus(Spot.STATUS_OFFLINE);
        offlined.setFlag(Spot.FLAG_NON_SPOT);
        when(spotRepository.selectById(1L)).thenReturn(offlined);

        Spot result = service.setFlag(ADMIN, 1L, Spot.FLAG_NON_SPOT, "实为商场，非景点");

        assertEquals(Spot.FLAG_NON_SPOT, result.getFlag());
        assertEquals(Spot.STATUS_OFFLINE, result.getStatus());
        verify(spotRepository).update(isNull(), any());
    }

    @Test
    void flag_unknownValue_rejected() {
        when(spotRepository.selectById(1L)).thenReturn(spot(1L, "spot_上海_poi_1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.setFlag(ADMIN, 1L, "BAD_FLAG", null));
    }

    @Test
    void online_withBlockingFlag_rejected() {
        Spot s = spot(1L, "spot_上海_poi_1");
        s.setStatus(Spot.STATUS_OFFLINE);
        s.setFlag(Spot.FLAG_CLOSED);
        when(spotRepository.selectById(1L)).thenReturn(s);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.online(ADMIN, 1L));
        assertEquals(true, ex.getMessage().contains("治理标记"));
        verify(spotRepository, never()).update(isNull(), any());
    }

    @Test
    void merge_missingTarget_rejected() {
        when(spotRepository.selectById(1L)).thenReturn(spot(1L, "spot_上海_poi_1"));
        when(spotRepository.selectOne(any())).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.merge(ADMIN, 1L, "spot_上海_poi_2", "重复"));
    }

    @Test
    void merge_selfMerge_rejected() {
        Spot s = spot(1L, "spot_上海_poi_1");
        when(spotRepository.selectById(1L)).thenReturn(s);
        when(spotRepository.selectOne(any())).thenReturn(s);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.merge(ADMIN, 1L, "spot_上海_poi_1", "重复"));
        assertEquals(true, ex.getMessage().contains("自己"));
    }

    @Test
    void merge_crossCity_rejected() {
        Spot source = spot(1L, "spot_上海_poi_1");
        Spot target = spot(2L, "spot_北京_poi_2");
        target.setCity("北京");
        when(spotRepository.selectById(1L)).thenReturn(source);
        when(spotRepository.selectOne(any())).thenReturn(target);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.merge(ADMIN, 1L, "spot_北京_poi_2", "重复"));
        assertEquals(true, ex.getMessage().contains("同城"));
    }

    @Test
    void merge_happyPath_offlinesSource_andAudits() {
        Spot source = spot(1L, "spot_上海_poi_1");
        Spot target = spot(2L, "spot_上海_poi_2");
        target.setName("外滩风景区");
        when(spotRepository.selectById(1L)).thenReturn(source);
        when(spotRepository.selectOne(any())).thenReturn(target);
        when(spotFavoriteRepository.selectList(any())).thenReturn(List.of());
        when(guideSpotRepository.selectList(any())).thenReturn(List.of());
        when(postSpotRepository.selectList(any())).thenReturn(List.of());

        Map<String, Object> r = service.merge(ADMIN, 1L, "spot_上海_poi_2", "同地重复");

        assertEquals("spot_上海_poi_2", r.get("target"));
        verify(spotRepository).update(isNull(), any()); // 源行下线 + merged_into
        verify(auditService).record(org.mockito.ArgumentMatchers.eq(ADMIN),
                org.mockito.ArgumentMatchers.eq(com.yuntu.tripplanner.model.AuditLog.CAT_ADMIN),
                org.mockito.ArgumentMatchers.eq("spot_merged"), any(), any(), any());
    }
}
