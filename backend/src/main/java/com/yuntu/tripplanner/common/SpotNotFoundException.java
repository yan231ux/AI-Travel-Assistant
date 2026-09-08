package com.yuntu.tripplanner.common;

/**
 * 景点不存在（收藏/取消收藏/详情等操作目标 spot_id 查无记录时抛出）。
 *
 * <p>作用：把"资源不存在"从通用异常中区分出来，由 Controller 映射为 404，
 * 避免「任意构造 spot_id 也能收藏成功」产生无效收藏记录（Review P1-2）。
 */
public class SpotNotFoundException extends RuntimeException {

    public SpotNotFoundException(String spotId) {
        super("景点不存在: " + spotId);
    }
}
