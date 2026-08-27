package com.yuntu.tripplanner.model;

/**
 * 城市名校验结果。
 *
 * <p>valid=false 时携带给用户看的 message（含纠错建议 suggestion）；
 * valid=true 时 normalizedCity 为规范化后的标准城市名（无变化则为 null，前端继续用原输入）。
 */
public record CityValidationResult(boolean valid, String normalizedCity, String suggestion) {

    /** 校验失败时面向用户的提示文案（成功时返回 null） */
    public String message(String input) {
        if (valid) {
            return null;
        }
        if (suggestion != null && !suggestion.isBlank()) {
            return "无法识别目的地「" + input + "」，你是不是想找「" + suggestion + "」？";
        }
        return "无法识别目的地「" + input + "」，请检查城市名是否正确";
    }
}
