package com.yuntu.tripplanner.model;

/**
 * 高德地理编码结果（/geocode/geo 首个 geocode 字段）。
 *
 * <p>除坐标外额外携带 level / city / province / formatted_address，
 * 主要用途是<b>存在性校验</b>（geocode 返回 null = 不是真地方）；level / city 字段保留，
 * 便于排查/演示时打印解析结果，但不再参与规范化（规范化只走别名映射，见 {@code CityValidator}）。
 */
public record AmapGeocode(
        double longitude,
        double latitude,
        String province,
        String city,
        String level,
        String formattedAddress) {
}
