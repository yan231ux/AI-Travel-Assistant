package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.model.CityGuide;
import com.yuntu.tripplanner.model.ContentReport;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.AuditLogRepository;
import com.yuntu.tripplanner.repository.CityGuideRepository;
import com.yuntu.tripplanner.repository.ContentReportRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import com.yuntu.tripplanner.repository.UserRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板汇总单测：核心是 P0-1——统计查询失败必须显式"不可用"（null + errors），
 * 绝不能吞异常返回 0，否则管理员会把数据库故障误读成"系统没有数据"。
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock
    private TravelPostRepository postRepository;
    @Mock
    private ContentReportRepository reportRepository;
    @Mock
    private CityGuideRepository guideRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private CommunityUserService communityUserService;

    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        // 注册实体 TableInfo，否则 LambdaQueryWrapper 的方法引用在无 Spring 环境解析失败
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TravelPost.class);
        TableInfoHelper.initTableInfo(assistant, ContentReport.class);
        TableInfoHelper.initTableInfo(assistant, CityGuide.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
        service = new AdminDashboardService(postRepository, reportRepository, guideRepository,
                userRepository, auditLogRepository, communityUserService);
        // 默认按"有审计权限"处理，让下面既有的 recent_ops 用例保持原语义；
        // "无权限隐藏"由专门的用例覆盖。
        lenient().when(communityUserService.hasPermission(any(), eq(AdminPermission.AUDIT_VIEW)))
                .thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary() {
        return service.summary("admin-1");
    }

    @Test
    void allHealthyReturnsValuesAndNoErrors() {
        lenient().when(postRepository.selectCount(any())).thenReturn(2L);
        lenient().when(reportRepository.selectCount(any())).thenReturn(1L);
        lenient().when(guideRepository.selectCount(any())).thenReturn(3L);
        lenient().when(guideRepository.selectList(any())).thenReturn(List.of());
        lenient().when(userRepository.selectCount(any())).thenReturn(5L);
        lenient().when(auditLogRepository.selectList(any())).thenReturn(List.of());

        Map<String, Object> body = summary();

        assertEquals(false, body.get("degraded"), "全部成功时 degraded 必须为 false");
        assertTrue(((List<?>) body.get("errors")).isEmpty(), "全部成功时 errors 必须为空");
        assertEquals(2L, ((Map<String, Object>) body.get("todo")).get("pending_posts"));
        assertEquals(5L, ((Map<String, Object>) body.get("content")).get("users_total"));
    }

    @Test
    void failedStatIsExplicitlyUnavailableInsteadOfZero() {
        when(postRepository.selectCount(any())).thenReturn(2L);
        when(reportRepository.selectCount(any())).thenThrow(new RuntimeException("db down"));
        lenient().when(guideRepository.selectCount(any())).thenReturn(3L);
        lenient().when(guideRepository.selectList(any())).thenReturn(List.of());
        lenient().when(userRepository.selectCount(any())).thenReturn(5L);
        lenient().when(auditLogRepository.selectList(any())).thenReturn(List.of());

        Map<String, Object> body = summary();

        assertEquals(true, body.get("degraded"), "有失败项时 degraded 必须为 true");
        Map<String, Object> todo = (Map<String, Object>) body.get("todo");
        assertNull(todo.get("pending_reports"), "失败项必须是 null（不可用），绝不能是 0");
        assertEquals(2L, todo.get("pending_posts"), "其余项不受单项失败影响");
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertTrue(errors.stream().anyMatch(e -> "todo.pending_reports".equals(e.get("key"))),
                "errors 必须点名失败的统计项");
    }

    @Test
    void rateIsUnavailableWhenEitherPartFails() {
        lenient().when(postRepository.selectCount(any())).thenReturn(2L);
        when(reportRepository.selectCount(any()))
                .thenReturn(1L)                                   // RESOLVED
                .thenThrow(new RuntimeException("db down"));      // DISMISSED 失败
        lenient().when(guideRepository.selectCount(any())).thenReturn(3L);
        lenient().when(guideRepository.selectList(any())).thenReturn(List.of());
        lenient().when(userRepository.selectCount(any())).thenReturn(5L);
        lenient().when(auditLogRepository.selectList(any())).thenReturn(List.of());

        Map<String, Object> body = summary();

        Map<String, Object> content = (Map<String, Object>) body.get("content");
        assertNull(content.get("report_resolve_rate"),
                "分子或分母不可用时成立率必须整体不可用，不能用残缺数字计算");
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertTrue(errors.stream().anyMatch(e -> "content.report_resolve_rate".equals(e.get("key"))));
    }

    @Test
    void recentOpsFailureIsExplicitNotSilentlyEmpty() {
        lenient().when(postRepository.selectCount(any())).thenReturn(2L);
        lenient().when(reportRepository.selectCount(any())).thenReturn(1L);
        lenient().when(guideRepository.selectCount(any())).thenReturn(3L);
        lenient().when(guideRepository.selectList(any())).thenReturn(List.of());
        lenient().when(userRepository.selectCount(any())).thenReturn(5L);
        when(auditLogRepository.selectList(any())).thenThrow(new RuntimeException("db down"));

        Map<String, Object> body = summary();

        assertTrue(((List<?>) body.get("recent_ops")).isEmpty());
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertTrue(errors.stream().anyMatch(e -> "recent_ops".equals(e.get("key"))),
                "审计查询失败必须进入 errors，不能伪装成\"没有审计记录\"");
    }

    @Test
    void auditDetailHiddenForRolesWithoutAuditPermission_bypassClosed() {
        // 回归：审核员/运营能进看板但没有 AUDIT_VIEW。"最近操作"与 /admin/audit-logs
        // 是同一份审计数据，必须同权限 —— 否则看板成了一条绕过审计权限的旁路
        //（明细里会露出"谁把谁改成了什么角色"这类只该超管可见的治理信息）。
        when(communityUserService.hasPermission(any(), eq(AdminPermission.AUDIT_VIEW))).thenReturn(false);
        lenient().when(postRepository.selectCount(any())).thenReturn(2L);
        lenient().when(reportRepository.selectCount(any())).thenReturn(1L);
        lenient().when(guideRepository.selectCount(any())).thenReturn(3L);
        lenient().when(guideRepository.selectList(any())).thenReturn(List.of());
        lenient().when(userRepository.selectCount(any())).thenReturn(5L);

        Map<String, Object> body = summary();

        assertEquals(false, body.get("recent_ops_visible"), "无审计权限必须显式标记 visible=false");
        assertTrue(((List<?>) body.get("recent_ops")).isEmpty(), "无审计权限不得返回任何审计条目");
        assertTrue(((List<?>) body.get("errors")).isEmpty(), "权限不足不是数据故障，不应报 error");
        verify(auditLogRepository, never()).selectList(any());   // 连查都不查
    }
}
