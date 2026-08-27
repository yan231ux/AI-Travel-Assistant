package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.AmapGeocode;
import com.yuntu.tripplanner.model.CityValidationResult;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 城市名校验单测：假城市拦截、拼错纠错建议、别名映射、geocode 存在性校验。
 *
 * <p>规范化只来自<b>别名映射</b>；geocode 解析成功即 valid（POI/区县也算有效目的地），
 * 但不会基于 geocode 结果自动改名——避免把具体地点误改写成市级城市名。
 */
@ExtendWith(MockitoExtension.class)
class CityValidatorTest {

    @Mock
    private AmapClient amapClient;

    private CityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CityValidator(amapClient, "北京,大理,成都,三亚,厦门,西安");
    }

    private static AmapGeocode region(double lng, double lat, String province, String city, String level) {
        return new AmapGeocode(lng, lat, province, city, level, province + city);
    }

    @Test
    void fakeCityBlockedWithNoSuggestion() {
        when(amapClient.geocodeInfo(anyString())).thenReturn(null);

        CityValidationResult r = validator.validate("亚特兰蒂斯");

        assertFalse(r.valid(), "瞎填的假城市必须被拦截");
        assertNull(r.suggestion(), "距离过远不该瞎建议");
        assertTrue(r.message("亚特兰蒂斯").contains("请检查城市名"));
    }

    @Test
    void misspelledCitySuggestsCorrection() {
        // 白名单扩大后，"成堵"已由编辑距离直接纠错，无需调用高德
        CityValidationResult r = validator.validate("成堵");

        assertFalse(r.valid());
        assertEquals("成都", r.suggestion(), "成堵 → 应建议成都");
        assertTrue(r.message("成堵").contains("你是不是想找「成都」"));
    }

    @Test
    void validCityPasses() {
        // "成都"已在白名单，直接放行，不触发地理编码
        CityValidationResult r = validator.validate("成都");

        assertTrue(r.valid());
        assertNull(r.normalizedCity(), "本身就是标准名，无需规范化");
    }

    @Test
    void cityAliasNormalized() {
        // 别名走映射，不触发地理编码
        CityValidationResult r = validator.validate("蓉城");

        assertTrue(r.valid());
        assertEquals("成都", r.normalizedCity());
    }

    @Test
    void interestPointRejectedAsDestination() {
        // 兴趣点（如"武侯祠"）不是行政区划级 → 地理编码兜底拒绝，避免把任意含该串的小地点当目的地
        when(amapClient.geocodeInfo(anyString()))
                .thenReturn(new AmapGeocode(104.05, 30.57, "四川省", "成都市", "兴趣点", "四川省成都市武侯区武侯祠"));

        CityValidationResult r = validator.validate("武侯祠");

        assertFalse(r.valid(), "纯兴趣点不算城市级目的地");
    }

    @Test
    void naturalFeatureRejectedByLevelGate() {
        // "珠穆朗玛峰"虽能被高德解析且地址包含输入，但级别是自然地名（山峰）→ 兜底拒绝
        when(amapClient.geocodeInfo(anyString()))
                .thenReturn(new AmapGeocode(86.9, 27.9, "西藏自治区", "日喀则市", "山峰", "西藏自治区日喀则市定日县珠穆朗玛峰"));

        CityValidationResult r = validator.validate("珠穆朗玛峰");

        assertFalse(r.valid(), "自然地名不应作为旅游城市放行");
    }

    @Test
    void geocodeFallbackAcceptsAdministrativeLevel() {
        // 白名单未收录的真实城市，只要高德解析为行政区划级即兜底放行（兜底 A 的本职）
        CityValidator wide = new CityValidator(amapClient, "");
        when(amapClient.geocodeInfo(anyString()))
                .thenReturn(new AmapGeocode(122.5, 53.0, "黑龙江省", "大兴安岭地区", "市", "黑龙江省大兴安岭地区漠河市"));

        CityValidationResult r = wide.validate("漠河");
        assertTrue(r.valid(), "白名单外但高德解析为行政区划级的真实城市应被兜底放行");
    }

    @Test
    void userProvidedDestinations() {
        // 用完整默认白名单校验用户提供的批量数据；geocode 全程 mock 为 null，验证白名单+纠错主判定
        CityValidator wide = new CityValidator(amapClient, "");
        when(amapClient.geocodeInfo(anyString())).thenReturn(null);

        // 期望直接放行（含别名 汕頭、后缀剥离 湛江市）
        List<String> expectValid = List.of(
                "广州", "深圳", "佛山", "东莞", "惠州", "珠海", "中山", "汕头", "湛江", "梅州",
                "杭州", "苏州", "南京", "上海", "北京", "天津", "重庆", "成都", "西安", "武汉",
                "长沙", "郑州", "青岛", "厦门", "三亚", "湛江市", "汕頭");
        // 形近/音近 → 期望纠错建议（不直接放行）
        Map<String, String> expectTypo = Map.ofEntries(
                Map.entry("广洲", "广州"), Map.entry("深镇", "深圳"), Map.entry("佛上", "佛山"),
                Map.entry("东宛", "东莞"), Map.entry("惠洲", "惠州"), Map.entry("珠诲", "珠海"),
                Map.entry("中三", "中山"), Map.entry("梅洲", "梅州"), Map.entry("杭洲", "杭州"),
                Map.entry("苏洲", "苏州"), Map.entry("南惊", "南京"), Map.entry("上诲", "上海"),
                Map.entry("北惊", "北京"), Map.entry("天进", "天津"), Map.entry("重亲", "重庆"),
                Map.entry("成度", "成都"), Map.entry("西按", "西安"), Map.entry("武汗", "武汉"),
                Map.entry("长纱", "长沙"), Map.entry("洲州", "郑州"), Map.entry("青到", "青岛"),
                Map.entry("夏门", "厦门"), Map.entry("三丫", "三亚"));
        // 明显无效（假城市 / 虚拟 / 物品 / 乱码）
        List<String> expectInvalid = List.of(
                "火星", "奥特曼", "皮卡丘", "快乐星球", "西瓜", "苹果", "小猫小狗", "aaaaa", "测试 123",
                "阿巴阿巴", "银河系", "珠穆朗玛峰", "太平洋", "王者荣耀", "原神", "香蕉共和国", "魔法小镇",
                "虚空城", "abcde", "随便写的地方");

        StringBuilder report = new StringBuilder("=== CityValidator 用户数据校验结果 ===\n");
        int failures = 0;

        for (String d : expectValid) {
            CityValidationResult r = wide.validate(d);
            if (!r.valid()) {
                failures++;
                report.append("✗ 应放行却拦截: ").append(d).append("\n");
            }
        }
        for (Map.Entry<String, String> e : expectTypo.entrySet()) {
            CityValidationResult r = wide.validate(e.getKey());
            if (r.valid()) {
                failures++;
                report.append("✗ 错别字应被拦截却放行: ").append(e.getKey()).append("\n");
            } else if (!e.getValue().equals(r.suggestion())) {
                failures++;
                report.append("✗ 纠错建议不符: ").append(e.getKey())
                        .append(" → 期望[").append(e.getValue()).append("] 实际[")
                        .append(r.suggestion()).append("]\n");
            }
        }
        for (String d : expectInvalid) {
            CityValidationResult r = wide.validate(d);
            if (r.valid()) {
                failures++;
                report.append("✗ 应拦截却放行: ").append(d).append("\n");
            }
        }
        report.append("失败项: ").append(failures).append("\n");
        System.out.println(report);
        assertEquals(0, failures, report.toString());
    }

    @Test
    void districtResolvesAsValidDestination() {
        when(amapClient.geocodeInfo(anyString()))
                .thenReturn(new AmapGeocode(104.04, 30.64, "四川省", "成都市", "区县", "四川省成都市武侯区"));

        CityValidationResult r = validator.validate("武侯区");

        assertTrue(r.valid(), "区县 geocode 能解析，也算有效目的地");
        assertNull(r.normalizedCity(), "非别名不规范化");
    }

    @Test
    void fuzzyPoiMatchBlockedWhenAddressDoesNotContainInput() {
        // 高德对乱码做模糊子串匹配：实测「噜啦啦市」→ 福建某县一家"噜啦啦"店。
        // geocode 返回非空但 formatted_address 不完整包含输入「噜啦啦市」→ 必须拦截，否则假城市放行。
        when(amapClient.geocodeInfo(anyString()))
                .thenReturn(new AmapGeocode(117.34, 23.94, "福建省", "漳州市", "兴趣点", "福建省漳州市云霄县噜啦啦(侨兴路店)"));

        CityValidationResult r = validator.validate("噜啦啦市");

        assertFalse(r.valid(), "模糊匹配到同名小店不应算有效城市");
        assertNull(r.suggestion(), "与已知城市距离过远，不瞎建议");
        assertTrue(r.message("噜啦啦市").contains("请检查城市名"));
    }

    @Test
    void levenshteinBasics() {
        assertEquals(0, CityValidator.levenshtein("成都", "成都"));
        assertEquals(1, CityValidator.levenshtein("成堵", "成都"));
        assertEquals(3, CityValidator.levenshtein("abc", "def"));
    }
}
