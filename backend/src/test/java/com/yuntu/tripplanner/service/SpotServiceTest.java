package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.common.SpotNotFoundException;
import com.yuntu.tripplanner.common.SpotText;
import com.yuntu.tripplanner.model.BehaviorRequest;
import com.yuntu.tripplanner.model.PreferenceAdjustment;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.SpotFavorite;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.repository.SpotFavoriteRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 景点详情/收藏服务单测（Review P1-1/2/3 修复后）：
 * - 收藏不存在景点 → SpotNotFoundException（Controller 映射 404）；
 * - 重复收藏幂等（不重复 SAVE 升权、不重复插行）；
 * - 并发重复收藏命中唯一键 → 幂等返回"已收藏"（不抛 500）；
 * - 画像更新失败 → 异常向上传播（真实回滚由 @Transactional + DB 事务保证，
 *   此处验证 Service 不吞异常，避免"已收藏但画像未学"的脏状态被静默接受）。
 */
@ExtendWith(MockitoExtension.class)
class SpotServiceTest {

    @Mock
    private SpotRepository spotRepository;
    @Mock
    private SpotFavoriteRepository spotFavoriteRepository;
    @Mock
    private TripRecordService tripRecordService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private TravelEventService travelEventService;

    private SpotService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Spot.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SpotFavorite.class);
        service = new SpotService(spotRepository, spotFavoriteRepository,
                tripRecordService, userProfileService, travelEventService);
    }

    private Spot spot(String spotId) {
        Spot s = new Spot();
        s.setSpotId(spotId);
        s.setPoiId("poi_1");
        s.setName("外滩");
        s.setCity("上海");
        s.setCategory("风景名胜;风景名胜");
        return s;
    }

    @Test
    void favorite_spotMissing_throwsNotFound() {
        when(spotRepository.selectOne(any())).thenReturn(null);

        assertThrows(SpotNotFoundException.class, () -> service.favorite("u1", "spot_不存在"));
    }

    @Test
    void favorite_twice_isIdempotent_noDoubleInsert_noDoubleBehavior() {
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩"));
        when(spotFavoriteRepository.selectCount(any())).thenReturn(0L, 1L);
        when(userProfileService.recordBehavior(anyString(), any())).thenReturn(List.of());

        SpotService.FavoriteResult first = service.favorite("u1", "spot_上海_外滩");
        SpotService.FavoriteResult second = service.favorite("u1", "spot_上海_外滩");

        assertTrue(first.isNewly());
        assertFalse(second.isNewly());
        // 只插了一次收藏行、只触发了一次 SAVE 升权
        verify(spotFavoriteRepository, times(1)).insert(any(SpotFavorite.class));
        verify(userProfileService, times(1)).recordBehavior(anyString(), any());
    }

    @Test
    void favorite_concurrentDuplicateKey_treatedAsExisted_noBehavior() {
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩"));
        when(spotFavoriteRepository.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk_user_spot"))
                .when(spotFavoriteRepository).insert(any(SpotFavorite.class));

        SpotService.FavoriteResult result = service.favorite("u1", "spot_上海_外滩");

        assertFalse(result.isNewly()); // 并发另一请求先插入 → 幂等
        assertTrue(result.getAdjustments().isEmpty());
        verify(userProfileService, never()).recordBehavior(anyString(), any());
    }

    @Test
    void favorite_profileUpdateFails_exceptionPropagates() {
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩"));
        when(spotFavoriteRepository.selectCount(any())).thenReturn(0L);
        when(userProfileService.recordBehavior(anyString(), any()))
                .thenThrow(new RuntimeException("画像更新失败"));

        // Service 不吞异常：Controller 会映射 500，事务（真实 DB 场景）整体回滚收藏行
        assertThrows(RuntimeException.class, () -> service.favorite("u1", "spot_上海_外滩"));
    }

    @Test
    void unfavorite_spotMissing_throwsNotFound() {
        when(spotRepository.selectOne(any())).thenReturn(null);

        assertThrows(SpotNotFoundException.class, () -> service.unfavorite("u1", "spot_不存在"));
    }

    /* ================= 画像可撤销：取消收藏回退（回退幅度 < 收藏增加） ================= */

    @Test
    void unfavorite_removesRowAndRollsBackProfile() {
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩"));
        when(spotFavoriteRepository.delete(any())).thenReturn(1);
        when(userProfileService.recordBehavior(anyString(), any()))
                .thenReturn(List.of(new PreferenceAdjustment()));

        List<PreferenceAdjustment> adjustments = service.unfavorite("u1", "spot_上海_外滩");

        // 确实删掉了收藏行 → 才触发画像撤销，行为类型为 UNSAVE（不是 DISLIKE：不是"不喜欢"）
        ArgumentCaptor<BehaviorRequest> reqCaptor = ArgumentCaptor.forClass(BehaviorRequest.class);
        verify(userProfileService).recordBehavior(eq("u1"), reqCaptor.capture());
        assertEquals(UserBehavior.ACTION_UNSAVE, reqCaptor.getValue().getActionType());
        assertEquals("spot_上海_外滩", reqCaptor.getValue().getItemId());
        assertEquals(1, adjustments.size());
    }

    @Test
    void unfavorite_whenNotFavorited_doesNotTouchProfile() {
        // 幂等空删（本来就没收藏）→ 无正向贡献可退，不能反复调接口扣分
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩"));
        when(spotFavoriteRepository.delete(any())).thenReturn(0);

        List<PreferenceAdjustment> adjustments = service.unfavorite("u1", "spot_上海_外滩");

        assertTrue(adjustments.isEmpty());
        verify(userProfileService, never()).recordBehavior(anyString(), any());
    }

    @Test
    void detail_spotMissing_returnsNull() {
        when(spotRepository.selectOne(any())).thenReturn(null);

        assertNull(service.detail("u1", "spot_不存在"));
    }

    @Test
    void detail_honestFallbackDescription_whenNoGuide() {
        Spot poiOnly = spot("spot_上海_无名景点");
        poiOnly.setDescription(null);
        poiOnly.setDataQuality(Spot.QUALITY_POI_ONLY);
        when(spotRepository.selectOne(any())).thenReturn(poiOnly);
        when(spotRepository.selectList(any())).thenReturn(List.of()); // 无同城相关
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        var detail = service.detail("u1", "spot_上海_无名景点");

        assertNotNull(detail);
        // Review 复查 #2：与行程校验层同一常量（产品约定文案），不出现页面间漂移
        assertEquals(SpotText.NO_GUIDE_DESC, detail.getDescription());
        assertEquals(Spot.QUALITY_POI_ONLY, detail.getDataQuality());
    }

    /** Review 复查 #5：详情相关推荐对每个候选不再重复查询画像（listPreferences 只调一次） */
    @Test
    void detail_relatedSpots_queriesProfileOnce() {
        Spot current = spot("spot_上海_外滩");
        Spot otherA = spot("spot_上海_东方明珠");
        Spot otherB = spot("spot_上海_豫园");
        when(spotRepository.selectOne(any())).thenReturn(current);
        when(spotRepository.selectList(any())).thenReturn(List.of(otherA, otherB));
        when(spotFavoriteRepository.selectList(any())).thenReturn(List.of());
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var detail = service.detail("u1", "spot_上海_外滩");

        assertNotNull(detail.getRelatedSpots());
        assertEquals(2, detail.getRelatedSpots().size());
        // 画像只查一次（外层），而不是每个相关推荐查一次（N+1）
        verify(userProfileService, times(1)).listPreferences("u1");
    }
}
