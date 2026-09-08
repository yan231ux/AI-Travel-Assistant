package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.SpotNotFoundException;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.SpotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 收藏/详情控制器单测（纯 JUnit + Mockito，不起 Spring 容器）：
 * 验证 Service 结果 → HTTP 语义映射：不存在景点 404、收藏成功/幂等 200、内部异常 500。
 */
@ExtendWith(MockitoExtension.class)
class SpotControllerTest {

    @Mock
    private SpotService spotService;

    private SpotController controller;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u1");
        controller = new SpotController(spotService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void favorite_spotMissing_returns404() {
        when(spotService.favorite(anyString(), anyString()))
                .thenThrow(new SpotNotFoundException("spot_不存在"));

        ResponseEntity<Map<String, Object>> resp = controller.favorite("spot_不存在");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
    }

    @Test
    void favorite_newlyCreated_returns200ExistedFalse() {
        when(spotService.favorite(anyString(), anyString()))
                .thenReturn(SpotService.FavoriteResult.created(List.of()));

        ResponseEntity<Map<String, Object>> resp = controller.favorite("spot_上海_外滩");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("success"));
        assertEquals(false, resp.getBody().get("existed"));
        assertEquals("已收藏", resp.getBody().get("message"));
    }

    @Test
    void favorite_alreadyCollected_returnsExistedTrue() {
        when(spotService.favorite(anyString(), anyString()))
                .thenReturn(SpotService.FavoriteResult.existed());

        ResponseEntity<Map<String, Object>> resp = controller.favorite("spot_上海_外滩");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("existed"));
        assertEquals("已在收藏中", resp.getBody().get("message"));
    }

    @Test
    void favorite_internalError_returns500() {
        when(spotService.favorite(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        ResponseEntity<Map<String, Object>> resp = controller.favorite("spot_上海_外滩");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    void unfavorite_spotMissing_returns404() {
        org.mockito.Mockito.doThrow(new SpotNotFoundException("spot_不存在"))
                .when(spotService).unfavorite(anyString(), anyString());

        ResponseEntity<Map<String, Object>> resp = controller.unfavorite("spot_不存在");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
    }

    @Test
    void detail_spotMissing_returns404WithBody() {
        when(spotService.detail(anyString(), anyString())).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.detail("spot_不存在");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
        assertEquals("景点不存在", resp.getBody().get("error"));
    }
}
