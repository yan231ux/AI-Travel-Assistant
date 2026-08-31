package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.AmapGeocode;
import com.yuntu.tripplanner.model.CityValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 城市名校验：白名单主判定 + 别名映射 + 编辑距离纠错 + 地理编码兜底。
 *
 * <p>判定优先级（答辩叙事"输入健壮性"）：
 * <ol>
 *   <li><b>白名单主判定（C，默认权威）</b>：标准城市名（含别名、并可剥离"市/省/区/县"后缀）直接放行，
 *       不消耗任何外部调用；</li>
 *   <li><b>编辑距离纠错</b>：不在白名单但形近/音近（如"成堵""广洲"）→ 返回"你是不是想找「成都/广州」"建议，不启动 Agent；</li>
 *   <li><b>地理编码兜底（A，仅作补充）</b>：白名单未覆盖的真实城市，若高德能解析且
 *       {@code formatted_address} 完整包含输入、且解析级别属于<b>行政区划级</b>（市/区/县/镇/乡/街道/村/商圈），才放行；
 *       兴趣点、道路、自然地名（如"不赖"小店、"珠穆朗玛峰""太平洋"）一律拒绝——
 *       避免高德模糊匹配把任意含该字符串的真实小地点误判为旅游目的地。</li>
 * </ol>
 * 规范化只来自<b>别名映射</b>；geocode 结果不参与改名（避免把具体地点误改写成市级名）。
 */
@Slf4j
@Service
public class CityValidator {

    /** 常见城市别名 → 标准名（演示口径：别名也能被识别并规范化） */
    private static final Map<String, String> CITY_ALIASES = Map.ofEntries(
            Map.entry("蓉城", "成都"), Map.entry("锦官城", "成都"), Map.entry("天府之国", "成都"),
            Map.entry("帝都", "北京"), Map.entry("京城", "北京"),
            Map.entry("魔都", "上海"), Map.entry("申城", "上海"),
            Map.entry("羊城", "广州"), Map.entry("花城", "广州"),
            Map.entry("鹏城", "深圳"),
            Map.entry("山城", "重庆"), Map.entry("雾都", "重庆"),
            Map.entry("春城", "昆明"),
            Map.entry("冰城", "哈尔滨"), Map.entry("星城", "长沙"),
            Map.entry("泉城", "济南"), Map.entry("鹭岛", "厦门"),
            // 繁体/异写容错
            Map.entry("汕頭", "汕头"), Map.entry("臺北", "台北"), Map.entry("臺中", "台中"),
            Map.entry("臺灣", "台湾"), Map.entry("澳門", "澳门")
    );

    /**
     * 权威目的地白名单（C 的主判定来源）。覆盖常见旅游城市；配置项 known-cities 会追加合并进来。
     * 顺序用于编辑距离<b>平局</b>裁决：把易冲突的近形城市前置，确保"洲州→郑州""西按→西安""长纱→长沙"等指向正确。
     */
    private static final List<String> DEFAULT_DESTINATIONS = List.of(
            "北京", "上海", "天津", "重庆", "香港", "澳门", "台湾", "台北", "台中",
            "深圳", "佛山", "东莞", "郑州", "惠州", "珠海", "中山", "汕头", "湛江", "梅州",
            "杭州", "苏州", "南京", "武汉", "长沙", "广州", "青岛", "厦门",
            "三亚", "成都", "西安", "大连", "昆明", "大理",
            "哈尔滨", "济南", "沈阳", "长春", "石家庄", "太原", "合肥", "南昌",
            "福州", "贵阳", "南宁", "海口", "兰州", "西宁", "银川", "乌鲁木齐",
            "呼和浩特", "桂林", "丽江", "拉萨", "泉州", "温州", "宁波", "无锡",
            "常州", "嘉兴", "金华", "台州", "绍兴", "烟台", "潍坊", "洛阳",
            "黄山", "张家界", "徐州", "唐山", "保定", "秦皇岛", "南通", "扬州", "镇江"
    );

    /** 地理编码兜底（A）只认这些"行政区划级"别；其余（兴趣点/道路/山峰/海洋…）一律拒绝 */
    private static final List<String> SETTLEMENT_LEVEL_KEYWORDS = List.of("市", "区", "县", "镇", "乡", "街道", "村", "商圈", "省");

    /**
     * 常见形近/音近错别字 → 正确字。仅用于编辑距离<b>平局</b>时的决胜：
     * 「夏门」与「澳门」「厦门」距离同为 1，白名单顺序（澳门在前）会误指「澳门」；
     * 借助"夏→厦"映射让「厦门」胜出；同理「奥门」靠"奥→澳"仍正确指向「澳门」，两全。
     */
    private static final Map<Character, Character> TYPO_FIX = Map.ofEntries(
            Map.entry('夏', '厦'), Map.entry('奥', '澳'), Map.entry('堵', '都'),
            Map.entry('洲', '州'), Map.entry('诲', '海'), Map.entry('惊', '京'),
            Map.entry('进', '津'), Map.entry('亲', '庆'), Map.entry('度', '都'),
            Map.entry('按', '安'), Map.entry('汗', '汉'), Map.entry('纱', '沙'),
            Map.entry('到', '岛'), Map.entry('丫', '亚'), Map.entry('三', '山'),
            Map.entry('上', '山'), Map.entry('宛', '莞'), Map.entry('镇', '圳'));

    private static final List<String> KNOWN_CITIES_FALLBACK = DEFAULT_DESTINATIONS;

    private final AmapClient amapClient;
    private final List<String> knownCities;

    public CityValidator(AmapClient amapClient,
                         @Value("${known-cities:}") String knownCitiesConfig) {
        this.amapClient = amapClient;
        this.knownCities = parseKnownCities(knownCitiesConfig);
    }

    /**
     * 校验目的地：成功返回 valid=true（别名会带规范化名），失败返回 valid=false + 建议。
     *
     * <p>判定优先级：别名 → 白名单 → 编辑距离纠错建议 → 地理编码兜底（仅行政区划级）。
     * 地理编码兜底除"能否解析"，还要求 {@code formatted_address} <b>完整包含用户输入</b>且解析级别属行政区划级，
     * 以此区分"解析到的就是用户输的地方"与高德对乱码的模糊凑名（如"不赖"小店、"珠穆朗玛峰"自然地名）。
     */
    public CityValidationResult validate(String destination) {
        if (destination == null || destination.isBlank()) {
            return new CityValidationResult(false, null, null);
        }
        String raw = destination.trim();
        String norm = stripAdminSuffix(raw);

        // 1. 别名映射（不消耗地理编码调用）
        String aliased = CITY_ALIASES.get(norm);
        if (aliased == null) {
            aliased = CITY_ALIASES.get(raw);
        }
        if (aliased != null) {
            return new CityValidationResult(true, aliased, null);
        }

        // 2. 白名单主判定（C）：标准城市名直接放行，无需调用高德
        if (knownCities.contains(norm) || knownCities.contains(raw)) {
            return new CityValidationResult(true, null, null);
        }

        // 3. 编辑距离纠错：形近/近音 → 给建议（不直接放行，避免静默改写用户输入）
        String suggestion = findSuggestion(norm);
        if (suggestion != null) {
            return new CityValidationResult(false, null, suggestion);
        }

        // 4. 地理编码兜底（A）：仅当解析到"行政区划级"真实地点才放行，拒绝兴趣点/道路/自然地名
        AmapGeocode geo = amapClient.geocodeInfo(raw);
        if (geo != null && geo.formattedAddress() != null
                && geo.formattedAddress().contains(raw) && isSettlementLevel(geo.level())) {
            return new CityValidationResult(true, null, null);
        }
        return new CityValidationResult(false, null, null);
    }

    /** 剥离末尾的行政区划后缀（"湛江市"→"湛江"、"北京市"→"北京"），用于白名单/别名/纠错匹配；地理编码仍用原串 */
    private String stripAdminSuffix(String s) {
        if (s.length() > 1 && (s.endsWith("市") || s.endsWith("省"))) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 地理编码级别是否属行政区划级（市/区/县/镇/乡/街道/村/商圈）；兴趣点/道路/山峰/海洋等返回 false */
    private boolean isSettlementLevel(String level) {
        if (level == null) {
            return false;
        }
        return SETTLEMENT_LEVEL_KEYWORDS.stream().anyMatch(level::contains);
    }

    /** 编辑距离（Levenshtein，按字符）模糊匹配已知城市，距离过远不瞎建议 */
    String findSuggestion(String input) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestGlyph = -1;
        for (String c : knownCities) {
            int d = levenshtein(input, c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
                bestGlyph = glyphOverlap(input, c);
            } else if (d == bestDist && d != Integer.MAX_VALUE) {
                // 距离平局：优先"形近"候选（如「夏门」→ 厦/门 与 澳/门 距离同为 1，
                // 但「厦」包含「夏」，应指「厦门」而非白名单顺序更靠前的「澳门」）
                int g = glyphOverlap(input, c);
                if (g > bestGlyph) {
                    best = c;
                    bestGlyph = g;
                }
            }
        }
        if (best == null || bestDist == Integer.MAX_VALUE) {
            return null;
        }
        int maxLen = Math.max(input.length(), best.length());
        if (bestDist <= 1 && input.length() <= 4) {
            return best;
        }
        if (maxLen > 0 && (double) bestDist / maxLen <= 0.33) {
            return best;
        }
        return null;
    }

    /**
     * 形近/音近决胜分（仅用于编辑距离平局时）：同字符 +1；
     * 命中"错字→正字"映射（如 夏→厦）额外 +2，分数高者胜出。
     */
    static int glyphOverlap(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int score = 0;
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                score++;
            } else if (TYPO_FIX.getOrDefault(a.charAt(i), (char) 0) == b.charAt(i)) {
                score += 2;
            }
        }
        return score;
    }

    /** 两个字符串的编辑距离（滚动数组版，O(n*m)） */
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private List<String> parseKnownCities(String config) {
        // 默认白名单始终生效，配置项 known-cities 仅作为追加扩展（不覆盖）
        List<String> list = new ArrayList<>(KNOWN_CITIES_FALLBACK);
        if (config != null && !config.isBlank()) {
            for (String s : config.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && !list.contains(t)) {
                    list.add(t);
                }
            }
        }
        return list;
    }
}
