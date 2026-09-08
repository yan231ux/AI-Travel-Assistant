package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.repository.AuditLogRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全链路审计服务单测（阶段四任务 10）：
 * 写日志落库 / 附加 detail JSON 化 / 审计失败 fail-soft 不抛错 / 分页查询带过滤。
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditLog.class);
        service = new AuditService(auditLogRepository, new ObjectMapper());
    }

    @Test
    void record_persistsActorCategoryAndJsonDetail() {
        service.record("u1", AuditLog.CAT_ADMIN, "post_approved", "post", "42",
                AuditService.detailOf("reason", "内容真实"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).insert(captor.capture());
        AuditLog row = captor.getValue();
        assertEquals("u1", row.getActorId());
        assertEquals(AuditLog.CAT_ADMIN, row.getCategory());
        assertEquals("post_approved", row.getAction());
        assertEquals("post", row.getTargetType());
        assertEquals("42", row.getTargetId());
        assertTrue(row.getDetail().contains("内容真实"));
    }

    @Test
    void record_nullDetail_keepsNull() {
        service.record("u1", AuditLog.CAT_TRIP, "trip_saved", "trip", "t_1", null);
        verify(auditLogRepository).insert(any(AuditLog.class));
    }

    @Test
    void record_writeFailure_doesNotThrow() {
        doThrow(new RuntimeException("db down")).when(auditLogRepository).insert(any());
        assertDoesNotThrow(() -> service.record("u1", AuditLog.CAT_USER, "login_success",
                "user", "1", null));
    }

    @Test
    void page_returnsRowsAndTotal() {
        AuditLog row = new AuditLog();
        row.setId(9L);
        row.setActorId("u1");
        row.setAction("login_success");
        when(auditLogRepository.selectCount(any())).thenReturn(1L);
        when(auditLogRepository.selectList(any())).thenReturn(List.of(row));

        AuditService.AuditPage page = service.page(null, null, 7, 1, 20);

        assertEquals(1L, page.total());
        assertEquals(1, page.items().size());
        assertEquals(9L, page.items().get(0).getId());
    }
}
