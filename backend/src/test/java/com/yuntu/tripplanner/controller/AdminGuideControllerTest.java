package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CityGuideService;
import com.yuntu.tripplanner.service.CommunityUserService;
import com.yuntu.tripplanner.service.GuideIndexingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 攻略控制器权限回归。
 *
 * <p><b>原缺陷</b>：{@code POST /admin/guides/{id}/reindex} 与其他攻略接口不同 ——
 * 它直连底层 {@link GuideIndexingService}，而该服务既不感知登录用户也没注入权限服务，
 * 控制器又漏了校验，全局拦截器只管"登录没登录"。结果是<b>任何已登录用户</b>都能触发
 * RAG 全量重建（实测普通用户返回 400"攻略不存在"而非 403，说明已进入业务逻辑）。
 *
 * <p>这里守住两条：无权限者被 403 且<b>绝不触达索引服务</b>；有权限者正常委派。
 */
@ExtendWith(MockitoExtension.class)
class AdminGuideControllerTest {

    @Mock
    private CityGuideService guideService;
    @Mock
    private GuideIndexingService indexingService;
    @Mock
    private CommunityUserService communityUserService;

    private AdminGuideController controller;

    @BeforeEach
    void setUp() {
        UserContext.setUserId("u-reviewer");
        controller = new AdminGuideController(guideService, indexingService, communityUserService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void reindex_withoutGuideManagePermission_forbiddenAndNeverTouchesIndexing() {
        doThrow(new ForbiddenException("当前角色（内容审核员）没有「攻略与城市内容」权限"))
                .when(communityUserService)
                .requirePermission("u-reviewer", AdminPermission.GUIDE_MANAGE);

        assertThrows(ForbiddenException.class, () -> controller.reindex(999999L),
                "缺少 GUIDE_MANAGE 必须 403，不能进入索引业务流程");
        verify(indexingService, never()).reindex(any(), anyString());
    }

    @Test
    void reindex_withPermission_delegatesToIndexingService() {
        when(indexingService.reindex(eq(7L), eq("u-reviewer")))
                .thenReturn(Map.of("status", "READY"));

        ResponseEntity<Map<String, Object>> resp = controller.reindex(7L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("success"));
        verify(communityUserService).requirePermission("u-reviewer", AdminPermission.GUIDE_MANAGE);
    }
}
