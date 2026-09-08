package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.ReportRequest;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.PostReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容举报控制器（阶段二 §9.7）：普通用户举报；同对象重复举报幂等。
 * 管理员的队列/处理见 PostModerationController。
 */
@Slf4j
@RestController
@RequestMapping("/community/reports")
public class PostReportController {

    private final PostReportService reportService;

    public PostReportController(PostReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) ReportRequest request) {
        String targetType = request == null ? null : request.getTargetType();
        Long targetId = request == null ? null : request.getTargetId();
        String reason = request == null ? null : request.getReason();
        String detail = request == null ? null : request.getDetail();
        boolean existed = reportService.create(UserContext.getUserId(), targetType, targetId, reason, detail);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("existed", existed);
        body.put("message", existed ? "你已举报过该内容，我们会尽快处理" : "举报已提交，感谢反馈");
        return ResponseEntity.ok(body);
    }
}
