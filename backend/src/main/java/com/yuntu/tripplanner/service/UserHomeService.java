package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.UserHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户旅行主页服务（阶段四任务 3）。
 *
 * <p>组装对外可见的公开数据：昵称/注册时间 + 粉丝/关注数 + 已发布帖子
 * （PUBLISHED 公开流语义）。目标用户不存在抛 403（语义同关注校验，便于控制器统一映射）。
 */
@Slf4j
@Service
public class UserHomeService {

    private final CommunityUserService communityUserService;
    private final FollowService followService;
    private final PostService postService;

    public UserHomeService(CommunityUserService communityUserService,
                           FollowService followService,
                           PostService postService) {
        this.communityUserService = communityUserService;
        this.followService = followService;
        this.postService = postService;
    }

    /**
     * 用户旅行主页。
     *
     * @param viewerId 当前查看者（可为 null → following 恒 false）
     * @param targetId 主页主人
     */
    public UserHome home(String viewerId, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("缺少用户标识");
        }
        if (!communityUserService.exists(targetId)) {
            throw new ForbiddenException("用户不存在");
        }
        UserHome home = new UserHome();
        home.setUserId(targetId);
        home.setNickname(communityUserService.nicknameOf(targetId));
        home.setCreatedAt(communityUserService.createdAtOf(targetId));
        home.setFollowerCount(followService.followerCount(targetId));
        home.setFollowingCount(followService.followingCount(targetId));
        boolean mine = viewerId != null && viewerId.equals(targetId);
        home.setMine(mine);
        home.setFollowing(!mine && followService.isFollowing(viewerId, targetId));

        try {
            PostPage posts = postService.userPublishedPosts(viewerId, targetId, 1, 12);
            home.setPostCount(posts.getTotal());
            home.setPosts(posts.getItems() == null ? java.util.List.of() : posts.getItems());
        } catch (Exception e) {
            log.warn("用户主页帖子加载失败（降级空列表）: target={} - {}", targetId, e.getMessage());
            home.setPostCount(0);
            home.setPosts(java.util.List.of());
        }
        return home;
    }
}
