package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.common.SpotVisibility;
import com.yuntu.tripplanner.model.Spot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 景点治理可见性判定单测（B组 §5 景点数据运营中心）。
 * 口径：正常运营 = ONLINE（null 视为 ONLINE 兼容存量）且非合并别名 且 flag 为空/OUTDATED。
 */
class SpotVisibilityTest {

    private Spot spot() {
        Spot s = new Spot();
        s.setSpotId("spot_上海_x");
        s.setName("外滩");
        s.setStatus(Spot.STATUS_ONLINE);
        return s;
    }

    @Test
    void defaultOnline_noFlag_noMerged_isActive() {
        assertTrue(SpotVisibility.isActive(spot()));
    }

    @Test
    void nullStatus_treatedAsOnline() {
        Spot s = spot();
        s.setStatus(null); // 存量代码构造/DB 默认 ONLINE 场景兼容
        assertTrue(SpotVisibility.isActive(s));
    }

    @Test
    void offline_notActive() {
        Spot s = spot();
        s.setStatus(Spot.STATUS_OFFLINE);
        assertFalse(SpotVisibility.isActive(s));
    }

    @Test
    void mergedAlias_notActive() {
        Spot s = spot();
        s.setMergedInto("spot_上海_y");
        assertFalse(SpotVisibility.isActive(s));
    }

    @Test
    void blockingFlags_notActive() {
        for (String flag : new String[]{Spot.FLAG_NON_SPOT, Spot.FLAG_CLOSED, Spot.FLAG_ERROR_POI}) {
            Spot s = spot();
            s.setFlag(flag);
            assertFalse(SpotVisibility.isActive(s), flag + " 不应处于运营状态");
        }
    }

    @Test
    void outdatedFlag_stillActive() {
        Spot s = spot();
        s.setFlag(Spot.FLAG_OUTDATED); // 过时仅提示待核验，不阻断展示
        assertTrue(SpotVisibility.isActive(s));
    }

    @Test
    void nullSpot_notActive() {
        assertFalse(SpotVisibility.isActive(null));
    }
}
