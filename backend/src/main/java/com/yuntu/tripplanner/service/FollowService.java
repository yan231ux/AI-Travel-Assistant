package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.PostAuthor;
import com.yuntu.tripplanner.model.UserFollow;
import com.yuntu.tripplanner.repository.UserFollowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 关注服务（阶段四任务 2，PRODUCT_EVOLUTION_PLAN §15 任务 2/3）。
 *
 * <p>关注方向：{@code userId} 关注 {@code followUserId}。
 * <ul>
 *   <li>幂等：重复关注靠 (user_id, follow_user_id) 唯一键拦截（DuplicateKey → 已关注视为成功）；
 *   <li>自关注禁止（400 Forbidden）；目标用户不存在禁止（存在性经 CommunityUserService 校验）；
 *   <li>取关 = 删除关系行（幂等，删不到也成功）；
 *   <li>只做社交关系存储，不驱动画像权重（与景点/帖子行为的画像闭环职责分离）；
 *   <li>关注/取关进全链路审计（阶段四任务 10：CAT_SOCIAL）。
 * </ul>
 */
@Slf4j
@Service
public class FollowService {

    private final UserFollowRepository followRepository;
    private final CommunityUserService communityUserService;
    private final AuditService auditService;

    public FollowService(UserFollowRepository followRepository,
                         CommunityUserService communityUserService,
                         AuditService auditService) {
        this.followRepository = followRepository;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /** 关注：userId 关注 targetId；重复关注幂等（返回是否新关注） */
    public boolean follow(String userId, String targetId) {
        validate(userId, targetId);
        UserFollow row = new UserFollow();
        row.setUserId(userId);
        row.setFollowUserId(targetId);
        try {
            followRepository.insert(row);
            log.info("用户关注: {} -> {}", userId, targetId);
            auditService.record(userId, AuditLog.CAT_SOCIAL, "user_followed",
                    "user", targetId, null);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("重复关注已忽略: {} -> {}", userId, targetId);
            return false;
        }
    }

    /** 取关：删除关系行；不存在也视为成功（幂等） */
    public void unfollow(String userId, String targetId) {
        validate(userId, targetId);
        followRepository.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowUserId, targetId));
        log.info("用户取关: {} -> {}", userId, targetId);
        auditService.record(userId, AuditLog.CAT_SOCIAL, "user_unfollowed",
                "user", targetId, null);
    }

    /** 是否已关注 */
    public boolean isFollowing(String userId, String targetId) {
        if (userId == null || targetId == null || userId.isBlank() || targetId.isBlank()) {
            return false;
        }
        Long count = followRepository.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowUserId, targetId));
        return count != null && count > 0;
    }

    /** 关注数（我关注了多少人） */
    public long followingCount(String userId) {
        return nvl(followRepository.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getUserId, userId)));
    }

    /** 粉丝数（多少人关注了我） */
    public long followerCount(String userId) {
        return nvl(followRepository.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowUserId, userId)));
    }

    /** 我关注的用户列表（按关注时间倒序；返回对外作者信息 id/nickname） */
    public List<PostAuthor> following(String userId, int limit) {
        return listAuthors(userId, null, limit);
    }

    /** 我的粉丝列表（按关注时间倒序） */
    public List<PostAuthor> followers(String userId, int limit) {
        return listAuthors(null, userId, limit);
    }

    /** 批量昵称解析后的关注/粉丝列表（避免 N+1） */
    private List<PostAuthor> listAuthors(String byUser, String targetUser, int limit) {
        LambdaQueryWrapper<UserFollow> w = new LambdaQueryWrapper<UserFollow>()
                .orderByDesc(UserFollow::getCreatedAt);
        if (byUser != null) {
            w.eq(UserFollow::getUserId, byUser);
        }
        if (targetUser != null) {
            w.eq(UserFollow::getFollowUserId, targetUser);
        }
        List<UserFollow> rows = followRepository.selectList(w.last(
                "LIMIT " + Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100))));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> userIds = byUser != null
                ? rows.stream().map(UserFollow::getFollowUserId).collect(Collectors.toList())
                : rows.stream().map(UserFollow::getUserId).collect(Collectors.toList());
        Map<String, String> nicknames = communityUserService.nicknamesOf(userIds);
        return userIds.stream().map(id -> {
            PostAuthor a = new PostAuthor();
            a.setId(id);
            a.setNickname(nicknames.getOrDefault(id, id));
            return a;
        }).collect(Collectors.toList());
    }

    private void validate(String userId, String targetId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少用户标识");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("缺少关注目标");
        }
        if (userId.equals(targetId)) {
            throw new ForbiddenException("不能关注自己");
        }
        if (!communityUserService.exists(targetId)) {
            throw new ForbiddenException("关注的用户不存在");
        }
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }
}
