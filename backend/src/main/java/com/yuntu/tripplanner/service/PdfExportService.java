package com.yuntu.tripplanner.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.yuntu.tripplanner.model.Itinerary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * PDF 导出服务
 *
 * 基于 OpenPDF 生成中文明行程 PDF。中文字体使用 OpenPDF JAR 自带的
 * STSong-Light（UniGB-UCS2-H），无需外部字体文件；BaseFont 静态缓存，
 * 避免每次导出都触发慢速的 CJK 字体加载。
 */
@Slf4j
@Service
public class PdfExportService {

    /** 字体加载较慢（~4s），懒加载并缓存 */
    private static class FontHolder {
        static final BaseFont CJK;

        static {
            try {
                CJK = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            } catch (Exception e) {
                throw new ExceptionInInitializerError("加载 OpenPDF 中文字体失败: " + e.getMessage());
            }
        }
    }

    private static final Font TITLE_FONT = new Font(FontHolder.CJK, 18, Font.BOLD);
    private static final Font HEADING_FONT = new Font(FontHolder.CJK, 14, Font.BOLD);
    private static final Font SUBHEADING_FONT = new Font(FontHolder.CJK, 12, Font.BOLD);
    private static final Font BODY_FONT = new Font(FontHolder.CJK, 11, Font.NORMAL);
    private static final Font SMALL_FONT = new Font(FontHolder.CJK, 10, Font.NORMAL);

    /**
     * 生成行程 PDF 字节数组
     */
    public byte[] export(Itinerary itinerary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 42, 42, 48, 48);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // 标题与概述
            document.add(new Paragraph(itinerary.getDestination() + " 旅行行程", TITLE_FONT));
            document.add(new Paragraph("行程 ID：" + itinerary.getTripId(), SMALL_FONT));
            document.add(new Paragraph("行程概述：" + itinerary.getSummary(), BODY_FONT));
            document.add(Spacer.small());

            // 预算分解
            if (itinerary.getBudgetBreakdown() != null) {
                document.add(new Paragraph("预算分解", HEADING_FONT));
                document.add(buildBudgetTable(itinerary));
                document.add(Spacer.small());
            }

            // 每日行程
            if (itinerary.getDays() != null) {
                for (var day : itinerary.getDays()) {
                    document.add(new Paragraph("第 " + day.getDayIndex() + " 天 - " + day.getDate(), HEADING_FONT));
                    document.add(new Paragraph("主题：" + day.getTheme(), BODY_FONT));
                    document.add(Spacer.small());

                    if (day.getSpots() != null && !day.getSpots().isEmpty()) {
                        document.add(new Paragraph("景点安排", SUBHEADING_FONT));
                        for (var spot : day.getSpots()) {
                            StringBuilder line = new StringBuilder("• ").append(spot.getName());
                            if (spot.getStartTime() != null) {
                                line.append("（").append(spot.getStartTime());
                                if (spot.getEndTime() != null) {
                                    line.append(" - ").append(spot.getEndTime());
                                }
                                line.append("）");
                            }
                            document.add(new Paragraph(line.toString(), BODY_FONT));
                            if (spot.getDescription() != null) {
                                document.add(new Paragraph("  说明：" + spot.getDescription(), SMALL_FONT));
                            }
                            if (spot.getEstimatedCost() != null) {
                                document.add(new Paragraph("  门票：¥" + spot.getEstimatedCost(), SMALL_FONT));
                            }
                            if (spot.getAddress() != null && !spot.getAddress().isBlank()) {
                                document.add(new Paragraph("  地址：" + spot.getAddress(), SMALL_FONT));
                            }
                        }
                    }

                    if (day.getMeals() != null && !day.getMeals().isEmpty()) {
                        document.add(new Paragraph("餐饮安排", SUBHEADING_FONT));
                        for (var meal : day.getMeals()) {
                            StringBuilder line = new StringBuilder("• ").append(meal.getName());
                            if (meal.getMealType() != null) {
                                line.append("（").append(meal.getMealType()).append("）");
                            }
                            document.add(new Paragraph(line.toString(), BODY_FONT));
                            if (meal.getNotes() != null) {
                                document.add(new Paragraph("  " + meal.getNotes(), SMALL_FONT));
                            }
                        }
                    }

                    if (day.getHotel() != null) {
                        document.add(new Paragraph("住宿安排", SUBHEADING_FONT));
                        StringBuilder line = new StringBuilder("• ").append(day.getHotel().getName());
                        if (day.getHotel().getLevel() != null) {
                            line.append("（").append(day.getHotel().getLevel()).append("）");
                        }
                        if (day.getHotel().getEstimatedCost() != null) {
                            line.append("  ¥").append(day.getHotel().getEstimatedCost());
                        }
                        document.add(new Paragraph(line.toString(), BODY_FONT));
                    }

                    if (day.getTransport() != null && !day.getTransport().isEmpty()) {
                        document.add(new Paragraph("交通安排", SUBHEADING_FONT));
                        for (var transport : day.getTransport()) {
                            StringBuilder line = new StringBuilder("• ").append(transport.getMode());
                            if (transport.getFromPlace() != null || transport.getToPlace() != null) {
                                line.append("：").append(transport.getFromPlace()).append(" → ").append(transport.getToPlace());
                            }
                            if (transport.getDuration() != null) {
                                line.append("（").append(transport.getDuration()).append("）");
                            }
                            document.add(new Paragraph(line.toString(), BODY_FONT));
                        }
                    }

                    if (day.getNotes() != null && !day.getNotes().isEmpty()) {
                        document.add(new Paragraph("注意事项", SUBHEADING_FONT));
                        for (String note : day.getNotes()) {
                            document.add(new Paragraph("• " + note, SMALL_FONT));
                        }
                    }
                    document.add(Spacer.small());
                }
            }

            // 旅行建议
            if (itinerary.getTips() != null && !itinerary.getTips().isEmpty()) {
                document.add(new Paragraph("旅行建议", HEADING_FONT));
                for (String tip : itinerary.getTips()) {
                    document.add(new Paragraph("• " + tip, BODY_FONT));
                }
            }

            // 数据来源
            if (itinerary.getSourceNotes() != null && !itinerary.getSourceNotes().isEmpty()) {
                document.add(Spacer.small());
                document.add(new Paragraph("数据来源", SUBHEADING_FONT));
                for (String note : itinerary.getSourceNotes()) {
                    document.add(new Paragraph("• " + note, SMALL_FONT));
                }
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("生成 PDF 失败", e);
            throw new IllegalStateException("生成 PDF 失败: " + e.getMessage(), e);
        }
    }

    private PdfPTable buildBudgetTable(Itinerary itinerary) {
        var breakdown = itinerary.getBudgetBreakdown();
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);

        addRow(table, "类别", "金额（元）", true);
        addRow(table, "交通", String.format("%.2f", breakdown.getTransport()), false);
        addRow(table, "住宿", String.format("%.2f", breakdown.getHotel()), false);
        addRow(table, "餐饮", String.format("%.2f", breakdown.getMeals()), false);
        addRow(table, "门票", String.format("%.2f", breakdown.getTickets()), false);
        addRow(table, "其他", String.format("%.2f", breakdown.getOther()), false);
        addRow(table, "总计", String.format("%.2f", breakdown.getTotal()), true);
        return table;
    }

    private void addRow(PdfPTable table, String label, String value, boolean header) {
        Font font = header ? HEADING_FONT : BODY_FONT;
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        labelCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    /** 行间距 */
    private static final class Spacer {
        static Paragraph small() {
            return new Paragraph(" ", SMALL_FONT);
        }
    }
}
