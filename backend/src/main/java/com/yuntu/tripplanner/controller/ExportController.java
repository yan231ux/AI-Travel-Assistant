package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.TripDetailResponse;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.PdfExportService;
import com.yuntu.tripplanner.service.TripRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 导出控制器
 */
@Slf4j
@RestController
@RequestMapping("/export")
public class ExportController {

    private final TripRecordService tripRecordService;
    private final PdfExportService pdfExportService;

    public ExportController(TripRecordService tripRecordService, PdfExportService pdfExportService) {
        this.tripRecordService = tripRecordService;
        this.pdfExportService = pdfExportService;
    }
    
    /**
     * 导出Markdown格式
     */
    @GetMapping("/{trip_id}/markdown")
    public ResponseEntity<String> exportMarkdown(@PathVariable("trip_id") String tripId) {
        try {
            log.info("导出Markdown: {}", tripId);
            
            TripDetailResponse tripDetail = tripRecordService.getTripDetail(tripId, UserContext.getUserId());

            if (tripDetail == null || tripDetail.getItinerary() == null) {
                return ResponseEntity.notFound().build();
            }

            String markdown = convertToMarkdown(tripDetail.getItinerary());
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.setContentDispositionFormData("attachment", tripId + ".md");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(markdown);
            
        } catch (Exception e) {
            log.error("导出Markdown失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 导出PDF格式
     */
    @GetMapping("/{trip_id}/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable("trip_id") String tripId) {
        try {
            log.info("导出PDF: {}", tripId);
            
            TripDetailResponse tripDetail = tripRecordService.getTripDetail(tripId, UserContext.getUserId());

            if (tripDetail == null || tripDetail.getItinerary() == null) {
                return ResponseEntity.notFound().build();
            }

            byte[] pdfBytes = convertToPdf(tripDetail.getItinerary());
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", tripId + ".pdf");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);
            
        } catch (Exception e) {
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 转换为Markdown格式
     */
    private String convertToMarkdown(Itinerary itinerary) {
        StringBuilder md = new StringBuilder();
        
        md.append("# ").append(itinerary.getDestination()).append(" 旅行行程\n\n");
        md.append("**行程ID**: ").append(itinerary.getTripId()).append("\n\n");
        md.append("**概述**: ").append(itinerary.getSummary()).append("\n\n");
        md.append("**预估预算**: ¥").append(String.format("%.2f", itinerary.getEstimatedBudget())).append("\n\n");
        
        // 预算分解
        if (itinerary.getBudgetBreakdown() != null) {
            md.append("## 预算分解\n\n");
            md.append("| 类别 | 金额（元） |\n");
            md.append("|------|----------|\n");
            md.append(String.format("| 交通 | %.2f |\n", itinerary.getBudgetBreakdown().getTransport()));
            md.append(String.format("| 住宿 | %.2f |\n", itinerary.getBudgetBreakdown().getHotel()));
            md.append(String.format("| 餐饮 | %.2f |\n", itinerary.getBudgetBreakdown().getMeals()));
            md.append(String.format("| 门票 | %.2f |\n", itinerary.getBudgetBreakdown().getTickets()));
            md.append(String.format("| 其他 | %.2f |\n", itinerary.getBudgetBreakdown().getOther()));
            md.append(String.format("| **总计** | **%.2f** |\n\n", itinerary.getBudgetBreakdown().getTotal()));
        }
        
        // 每日行程
        if (itinerary.getDays() != null) {
            for (var day : itinerary.getDays()) {
                md.append("## 第").append(day.getDayIndex()).append("天 - ").append(day.getDate()).append("\n\n");
                md.append("**主题**: ").append(day.getTheme()).append("\n\n");
                
                // 景点
                if (day.getSpots() != null && !day.getSpots().isEmpty()) {
                    md.append("### 景点安排\n\n");
                    for (var spot : day.getSpots()) {
                        md.append("- **").append(spot.getName()).append("**\n");
                        md.append("  - 时间: ").append(spot.getStartTime()).append(" - ").append(spot.getEndTime()).append("\n");
                        if (spot.getDescription() != null) {
                            md.append("  - 说明: ").append(spot.getDescription()).append("\n");
                        }
                        if (spot.getEstimatedCost() != null) {
                            md.append("  - 门票: ¥").append(spot.getEstimatedCost()).append("\n");
                        }
                        if (spot.getAddress() != null) {
                            md.append("  - 地址: ").append(spot.getAddress()).append("\n");
                        }
                    }
                    md.append("\n");
                }
                
                // 餐饮
                if (day.getMeals() != null && !day.getMeals().isEmpty()) {
                    md.append("### 餐饮安排\n\n");
                    for (var meal : day.getMeals()) {
                        md.append("- **").append(meal.getName()).append("** (").append(meal.getMealType()).append(")\n");
                        if (meal.getNotes() != null) {
                            md.append("  - ").append(meal.getNotes()).append("\n");
                        }
                        if (meal.getEstimatedCost() != null) {
                            md.append("  - 预估: ¥").append(meal.getEstimatedCost()).append("\n");
                        }
                    }
                    md.append("\n");
                }
                
                // 酒店
                if (day.getHotel() != null) {
                    md.append("### 住宿安排\n\n");
                    md.append("- **").append(day.getHotel().getName()).append("** (").append(day.getHotel().getLevel()).append(")\n");
                    if (day.getHotel().getEstimatedCost() != null) {
                        md.append("  - 预估: ¥").append(day.getHotel().getEstimatedCost()).append("\n");
                    }
                    md.append("\n");
                }
                
                // 交通
                if (day.getTransport() != null && !day.getTransport().isEmpty()) {
                    md.append("### 交通安排\n\n");
                    for (var transport : day.getTransport()) {
                        md.append("- ").append(transport.getMode()).append(": ")
                          .append(transport.getFromPlace()).append(" → ").append(transport.getToPlace()).append("\n");
                        if (transport.getDuration() != null) {
                            md.append("  - 时长: ").append(transport.getDuration()).append("\n");
                        }
                        if (transport.getEstimatedCost() != null) {
                            md.append("  - 费用: ¥").append(transport.getEstimatedCost()).append("\n");
                        }
                    }
                    md.append("\n");
                }
                
                // 备注
                if (day.getNotes() != null && !day.getNotes().isEmpty()) {
                    md.append("### 注意事项\n\n");
                    for (var note : day.getNotes()) {
                        md.append("- ").append(note).append("\n");
                    }
                    md.append("\n");
                }
            }
        }
        
        // 旅行建议
        if (itinerary.getTips() != null && !itinerary.getTips().isEmpty()) {
            md.append("## 旅行建议\n\n");
            for (var tip : itinerary.getTips()) {
                md.append("- ").append(tip).append("\n");
            }
            md.append("\n");
        }
        
        return md.toString();
    }
    
    /**
     * 转换为PDF格式（委托 PdfExportService，OpenPDF + 中文字体）
     */
    private byte[] convertToPdf(Itinerary itinerary) {
        return pdfExportService.export(itinerary);
    }
}