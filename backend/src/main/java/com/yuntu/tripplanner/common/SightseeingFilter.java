package com.yuntu.tripplanner.common;

import java.util.List;

/**
 * 业态/游览性判定工具（通用，不依赖具体城市/品牌）。
 *
 * <p>治理"点名景点把商铺/公寓当景点排"问题：高德 POI 自带行业分类 type
 * （如「购物服务;专卖店;家电卖场」「商务住宅;住宅区」「风景名胜;风景名胜;…」），
 * 据此判定一个地点是否属于"可游览景点"，从源头避免把集成灶门店、公寓、写字楼
 * 这类非游览场所收进"点名景点"强制排期。
 *
 * <p>type 缺失时退回名称启发式兜底（住宿/餐饮/商铺/办公词）。例外：购物服务里的
 * 步行街/老街/美食街等街区属可游览对象，不按购物排除。
 */
public final class SightseeingFilter {

    private SightseeingFilter() {
    }

    /** 高德 type 一级分类黑名单：这些业态的地点不是可游览景点（纯消费/居住/办公/过路） */
    private static final List<String> NON_SIGHTSEEING_TYPE_LEVEL1 =
            List.of("购物服务", "商务住宅", "餐饮服务", "住宿服务", "公司企业", "生活服务",
                    "医疗保健服务", "金融保险服务", "汽车服务", "摩托车服务",
                    "道路附属设施", "通行设施", "室内设施");

    /** 住宿类名称词（与校验层口径一致；点名收集阶段提前排除，避免排期后再移除造成口径打架） */
    private static final List<String> LODGING_WORDS =
            List.of("酒店", "客栈", "旅馆", "民宿", "青旅", "青年旅舍", "公寓", "宾馆", "旅舍", "度假村", "招待所");

    /** 餐饮类名称词 */
    private static final List<String> DINING_WORDS =
            List.of("餐厅", "火锅", "饭店", "酒楼", "料理", "自助", "烤肉", "烧烤", "面馆", "餐吧", "食堂", "小吃店");

    /** 商铺/办公/其他非游览名称词（type 缺失时的名称兜底；type 存在时以 type 为准） */
    private static final List<String> SHOP_OFFICE_WORDS =
            List.of("门店", "专卖店", "专营店", "旗舰店", "体验店", "直营店", "授权店",
                    "售后", "维修", "建材", "五金", "家电卖场", "卖场", "批发市场",
                    "写字楼", "办公楼", "大厦", "小区", "售楼", "营销中心", "4S店", "4s店");

    /** 街区类例外词：购物服务里的步行街/老街/美食街是可游览街区，不作为非游览排除 */
    private static final List<String> STREET_LIKE_WORDS =
            List.of("步行街", "老街", "古街", "商业街", "美食街", "夜市", "集市", "古镇", "风情街", "小吃街");

    /**
     * 高德 type 一级分类是否非游览业态。
     * type 为空/无法识别 → false（不武断，由调用方走名称兜底）。
     */
    public static boolean isNonSightseeingType(String amapType, String name) {
        if (amapType == null || amapType.isBlank()) {
            return false;
        }
        // 购物/餐饮类里的街区例外：商业步行街/古镇老街是游客常去的可游览街区。
        // 例外判断走双通道：
        //  1) 名称命中街区词（「北京路步行街」含"步行街"）；
        //  2) 高德细分 type 段命中街区词（「宽窄巷子」名称不含"街"字，但细分
        //     type 常为「购物服务;特色商业街」，已说明它是可游览的特色街区）。
        if (isStreetLike(name) || typeHasStreetSegment(amapType)) {
            return false;
        }
        String level1 = amapType.split(";")[0].trim();
        return NON_SIGHTSEEING_TYPE_LEVEL1.contains(level1);
    }

    /** 高德细分 type 段（如「商业街/步行街/特色商业街/老街/古镇」）命中街区词 → 例外 */
    private static boolean typeHasStreetSegment(String amapType) {
        if (amapType == null || amapType.isBlank()) {
            return false;
        }
        for (String seg : amapType.split(";")) {
            if (isStreetLike(seg.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 名称是否命中"住宿/餐饮/商铺/办公"等非游览词（type 缺失时的兜底） */
    public static boolean isNonSightseeingName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (isStreetLike(name)) {
            return false;
        }
        for (String w : LODGING_WORDS) {
            if (name.contains(w)) {
                return true;
            }
        }
        for (String w : DINING_WORDS) {
            if (name.contains(w)) {
                return true;
            }
        }
        for (String w : SHOP_OFFICE_WORDS) {
            if (name.contains(w)) {
                return true;
            }
        }
        // 去括号后的主干以"店"结尾 → 商铺/分店命名模式（"XX特产店""XX水果店"），不是景点
        String coreName = name.replaceAll("[（(][^）)]*[）)]", "").trim();
        if (coreName.endsWith("店")) {
            return true;
        }
        return false;
    }

    /**
     * 综合判定一个地点是否"非游览场所"：
     * 有高德 type 以 type 为准；type 缺失退回名称兜底。
     */
    public static boolean isNonSightseeing(String amapType, String name) {
        if (amapType != null && !amapType.isBlank()) {
            return isNonSightseeingType(amapType, name);
        }
        return isNonSightseeingName(name);
    }

    /**
     * 非游览场所的"类别"（用于移除/提示文案）：优先 type，其次名称词。
     * 返回 null 表示判定为可游览/无法归类。
     */
    public static String kindOfNonSightseeing(String amapType, String name) {
        if (amapType != null && !amapType.isBlank()) {
            String l1 = amapType.split(";")[0].trim();
            if (l1.contains("住宿")) {
                return "住宿类";
            }
            if (l1.contains("餐饮")) {
                return "餐饮类";
            }
            if (l1.contains("购物")) {
                return "商铺类";
            }
            if (l1.contains("商务住宅")) {
                return "公寓/住宅类";
            }
            if (l1.contains("公司")) {
                return "公司/办公类";
            }
            if (l1.contains("汽车") || l1.contains("摩托")) {
                return "汽车服务类";
            }
            return "非游览场所";
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        for (String w : LODGING_WORDS) {
            if (name.contains(w)) {
                return "住宿类";
            }
        }
        for (String w : DINING_WORDS) {
            if (name.contains(w)) {
                return "餐饮类";
            }
        }
        for (String w : SHOP_OFFICE_WORDS) {
            if (name.contains(w)) {
                return "商铺/办公类";
            }
        }
        String coreName = name.replaceAll("[（(][^）)]*[）)]", "").trim();
        if (coreName.endsWith("店")) {
            return "商铺类";
        }
        return null;
    }

    /** 是否为可游览的街区/古镇（即使高德归到购物/餐饮类也不排除） */
    public static boolean isStreetLike(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (String w : STREET_LIKE_WORDS) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 品牌根名：去括号注释 + 去「店/门店/分店/专营/旗舰」等后缀，
     * 用于"同一品牌多家分店只取一个"（如「火星人集成灶(河东路店)」「火星人集成灶(林旺路店)」
     * 根名都是「火星人集成灶」，只应收一个作为点名对象）。
     */
    public static String brandCore(String name) {
        if (name == null) {
            return "";
        }
        String t = name.replaceAll("[（(][^）)]*[）)]", "").trim();
        t = t.replaceAll("(售后服务中心|服务中心|专卖店|专营店|旗舰店|体验店|直营店|授权店|门店|分店)$", "");
        t = t.replaceAll("店$", "");
        return t;
    }
}
