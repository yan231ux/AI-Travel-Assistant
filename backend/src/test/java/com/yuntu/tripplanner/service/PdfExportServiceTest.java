package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PdfExportService 单元测试：生成合法 PDF（%PDF 头）且非空。
 */
class PdfExportServiceTest {

    @Test
    void exportProducesValidPdf() {
        Itinerary itinerary = new Itinerary();
        itinerary.setTripId("trip_测试_2026-05-01");
        itinerary.setDestination("测试城市");
        itinerary.setSummary("测试行程概述");

        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        day.setDate("2026-05-01");
        day.setTheme("文化");
        SpotItem spot = new SpotItem();
        spot.setName("武侯祠");
        spot.setDescription("三国文化");
        spot.setEstimatedCost(50.0);
        spot.setStartTime("10:00");
        spot.setEndTime("12:00");
        day.setSpots(List.of(spot));
        day.setMeals(List.of());
        day.setTransport(List.of());
        day.setNotes(List.of("带伞"));
        itinerary.setDays(List.of(day));

        BudgetBreakdown breakdown = new BudgetBreakdown();
        breakdown.setTickets(50.0);
        breakdown.setTotal(50.0);
        itinerary.setBudgetBreakdown(breakdown);
        itinerary.setTips(List.of("建议提前订票"));
        itinerary.setSourceNotes(List.of("由测试生成"));

        byte[] pdf = new PdfExportService().export(itinerary);

        assertTrue(pdf.length > 0);
        String header = new String(pdf, 0, Math.min(5, pdf.length), StandardCharsets.ISO_8859_1);
        assertEquals("%PDF-", header);
    }
}
