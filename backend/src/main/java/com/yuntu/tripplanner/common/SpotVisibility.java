package com.yuntu.tripplanner.common;

import com.yuntu.tripplanner.model.Spot;

/**
 * 景点治理可见性判定（B组 设计方案 §5，单一口径供全链路复用）。
 *
 * <p>"正常运营中" = ONLINE 且非合并别名 且 未被治理标记为需下线对象
 * （NON_SPOT/CLOSED/ERROR_POI；OUTDATED 仅提示待核验，仍正常展示）。
 * 推荐流取数、用户详情、自动同步是否可写，全部走这一个判定，避免各消费点口径漂移。
 */
public final class SpotVisibility {

    private SpotVisibility() {
    }

    /** 该景点是否处于正常运营状态（可推荐/可展示/可被自动同步更新） */
    public static boolean isActive(Spot s) {
        if (s == null) {
            return false;
        }
        // status 为 null 视为 ONLINE（DB 层 NOT NULL DEFAULT 'ONLINE'；兼容存量代码构造的行）
        if (s.getStatus() != null && !Spot.STATUS_ONLINE.equals(s.getStatus())) {
            return false;
        }
        if (s.getMergedInto() != null && !s.getMergedInto().isBlank()) {
            return false;
        }
        String flag = s.getFlag();
        // OUTDATED 豁免：过时标记只提示"待重同步核验"，不阻断展示
        return flag == null || flag.isBlank() || Spot.FLAG_OUTDATED.equals(flag);
    }
}
