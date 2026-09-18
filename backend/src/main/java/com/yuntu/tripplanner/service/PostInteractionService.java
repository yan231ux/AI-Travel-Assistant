package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.BehaviorRequest;
import com.yuntu.tripplanner.model.PostInteraction;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.repository.PostInteractionRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帖子互动服务（阶段二 §9.5：点赞/收藏/不喜欢，幂等；阶段三起互动同时进入画像闭环）。
 *
 * <p>幂等保证：(user_id, post_id, action_type) 唯一键 —— 重复点赞不会重复插行、不会重复计数；
 * 并发重复时唯一键冲突被捕获，视为"已生效"处理，不抛 500。取消 = 删行并递减计数器
 * （计数器用 GREATEST 防负）。计数列只作展示；真实互动关系以 post_interaction 行为准。
 *
 * <p>阶段三（内容个性化闭环）：首次生效的 点赞/收藏/不喜欢 会同步写一条
 * item_type=POST 的 user_behavior（LIKE +0.05 / SAVE +0.15 / DISLIKE -0.30，与景点共用同一
 * 增量规则），服务端据此把帖子的画像标签（风格/节奏/城市）写进用户画像 —— 收藏喜欢的帖子
 * 会提升同类标签，点"不感兴趣"会降低同类内容（帖子与景点互相影响）；取消操作不反噬权重。
 * 行为写入失败只记日志，不影响互动本身（互动为强一致，画像为尽力而为）。
 */
@Slf4j
@Service
public class PostInteractionService {

    private final PostInteractionRepository interactionRepository;
    private final TravelPostRepository postRepository;
    private final UserProfileService userProfileService;

    public PostInteractionService(PostInteractionRepository interactionRepository,
                                  TravelPostRepository postRepository,
                                  UserProfileService userProfileService) {
        this.interactionRepository = interactionRepository;
        this.postRepository = postRepository;
        this.userProfileService = userProfileService;
    }

    /** 互动结果快照（Controller 直接组装响应用） */
    public record InteractionState(boolean liked, boolean favorited, boolean disliked,
                                   int likeCount, int favoriteCount) {
    }

    /**
     * 设置/取消互动（active=true 点赞收藏等；false 取消）。
     *
     * @return 操作后的状态与计数
     */
    @Transactional
    public InteractionState interact(String userId, Long postId, String action, boolean active) {
        if (userId == null || userId.isBlank() || postId == null) {
            throw new IllegalArgumentException("缺少用户或帖子标识");
        }
        TravelPost post = requirePublished(postId);
        String act = normalizeAction(action);
        // 自己的帖子不能「不感兴趣」：这是对自己内容的表态，语义上不合理（与举报自己/评论自己类似）。
        // 前端隐藏按钮只负责体验，服务端这里做最终校验 —— 不依赖前端。
        if (PostInteraction.ACTION_DISLIKE.equals(act)
                && post.getUserId() != null && post.getUserId().equals(userId)) {
            throw new ForbiddenException("不能对自己的帖子表示不感兴趣");
        }
        boolean nowActive;
        if (active) {
            nowActive = add(userId, post, act);
        } else {
            boolean removed = remove(userId, postId, act);
            nowActive = !removed;
            // 取消收藏 → 画像回退（与帖子收藏 +0.15 对称的撤销；见 revokePersonalizationBehavior）
            if (removed && PostInteraction.ACTION_FAVORITE.equals(act)) {
                revokePersonalizationBehavior(userId, post);
            }
        }
        boolean like = act.equals(PostInteraction.ACTION_LIKE) ? nowActive
                : interactionRepository.selectCount(new LambdaQueryWrapper<PostInteraction>()
                        .eq(PostInteraction::getUserId, userId)
                        .eq(PostInteraction::getPostId, postId)
                        .eq(PostInteraction::getActionType, PostInteraction.ACTION_LIKE)) > 0;
        boolean fav = act.equals(PostInteraction.ACTION_FAVORITE) ? nowActive
                : interactionRepository.selectCount(new LambdaQueryWrapper<PostInteraction>()
                        .eq(PostInteraction::getUserId, userId)
                        .eq(PostInteraction::getPostId, postId)
                        .eq(PostInteraction::getActionType, PostInteraction.ACTION_FAVORITE)) > 0;
        boolean dis = act.equals(PostInteraction.ACTION_DISLIKE) ? nowActive
                : interactionRepository.selectCount(new LambdaQueryWrapper<PostInteraction>()
                        .eq(PostInteraction::getUserId, userId)
                        .eq(PostInteraction::getPostId, postId)
                        .eq(PostInteraction::getActionType, PostInteraction.ACTION_DISLIKE)) > 0;
        TravelPost fresh = postRepository.selectById(postId);
        return new InteractionState(like, fav, dis,
                fresh.getLikeCount() == null ? 0 : fresh.getLikeCount(),
                fresh.getFavoriteCount() == null ? 0 : fresh.getFavoriteCount());
    }

    /** 当前用户对帖子的互动状态（详情页/列表填充用，不经此服务的写路径也可用） */
    public InteractionState stateOf(String userId, Long postId) {
        if (userId == null || userId.isBlank() || postId == null) {
            return new InteractionState(false, false, false, 0, 0);
        }
        boolean like = interactionRepository.selectCount(new LambdaQueryWrapper<PostInteraction>()
                .eq(PostInteraction::getUserId, userId)
                .eq(PostInteraction::getPostId, postId)
                .eq(PostInteraction::getActionType, PostInteraction.ACTION_LIKE)) > 0;
        boolean fav = interactionRepository.selectCount(new LambdaQueryWrapper<PostInteraction>()
                .eq(PostInteraction::getUserId, userId)
                .eq(PostInteraction::getPostId, postId)
                .eq(PostInteraction::getActionType, PostInteraction.ACTION_FAVORITE)) > 0;
        boolean dis = interactionRepository.selectCount(new LambdaQueryWrapper<PostInteraction>()
                .eq(PostInteraction::getUserId, userId)
                .eq(PostInteraction::getPostId, postId)
                .eq(PostInteraction::getActionType, PostInteraction.ACTION_DISLIKE)) > 0;
        TravelPost p = postRepository.selectById(postId);
        int likeCount = p == null || p.getLikeCount() == null ? 0 : p.getLikeCount();
        int favCount = p == null || p.getFavoriteCount() == null ? 0 : p.getFavoriteCount();
        return new InteractionState(like, fav, dis, likeCount, favCount);
    }

    /** 新增互动行；已存在/并发冲突 → 幂等返回当前已生效。
     *  首次生效时顺带把互动写进画像行为（阶段三闭环；审查报告 P1-4：与互动同事务，
     *  画像更新失败整体回滚，杜绝"已收藏但系统没记住"的不一致） */
    private boolean add(String userId, TravelPost post, String action) {
        PostInteraction pi = new PostInteraction();
        pi.setUserId(userId);
        pi.setPostId(post.getId());
        pi.setActionType(action);
        try {
            interactionRepository.insert(pi);
        } catch (DuplicateKeyException e) {
            log.debug("互动已存在（幂等）：user={} post={} action={}", userId, post.getId(), action);
            return true;
        }
        bumpCounter(post.getId(), action, 1);
        recordPersonalizationBehavior(userId, post, action);
        return true;
    }

    /**
     * 帖子互动 → 画像行为（阶段三任务 1/2/6：帖子行为进入统一行为模型并更新画像）。
     * 仅首次生效（add 成功路径）调用；取消收藏走 {@link #revokePersonalizationBehavior}
     * （回退幅度小于增加），点赞/不感兴趣取消不回退。
     *
     * <p>审查报告 P1-4 强一致：不吞异常。interact() 全程在同一事务内，画像/行为写入失败
     * 时异常直接上抛 → 互动行与计数一并回滚（对齐 SpotService.favorite 的"收藏与画像同事务"），
     * 避免"接口返回已收藏、画像其实没变"的用户可见不一致。
     */
    private void recordPersonalizationBehavior(String userId, TravelPost post, String action) {
        String behaviorAction;
        switch (action) {
            case PostInteraction.ACTION_FAVORITE:
                behaviorAction = UserBehavior.ACTION_SAVE; // 收藏 = 强正反馈 +0.15
                break;
            case PostInteraction.ACTION_LIKE:
                behaviorAction = UserBehavior.ACTION_LIKE; // 点赞 = 弱正反馈 +0.05
                break;
            case PostInteraction.ACTION_DISLIKE:
                behaviorAction = UserBehavior.ACTION_DISLIKE; // 不感兴趣 = 负反馈 -0.30（降同类）
                break;
            default:
                return;
        }
        BehaviorRequest req = new BehaviorRequest();
        req.setItemType(UserBehavior.ITEM_TYPE_POST);
        req.setItemId(String.valueOf(post.getId()));
        req.setItemName(post.getTitle());
        req.setActionType(behaviorAction);
        userProfileService.recordBehavior(userId, req);
    }

    /**
     * 取消收藏 → 画像回退（与「帖子收藏 +0.15」对称的撤销，UNSAVE -0.10，回退幅度小于增加）。
     * 只在确实删掉互动行时调用，幂等空删不扣分。
     *
     * <p>边界：点赞（+0.05）与取消点赞<b>不</b>回退 —— 点赞是弱信号且取消点赞是高频轻操作，
     * 回退收益低却会反复扰动画像；只有「收藏」这类强正反馈（0.15）才值得支持撤销。
     */
    private void revokePersonalizationBehavior(String userId, TravelPost post) {
        BehaviorRequest req = new BehaviorRequest();
        req.setItemType(UserBehavior.ITEM_TYPE_POST);
        req.setItemId(String.valueOf(post.getId()));
        req.setItemName(post.getTitle());
        req.setActionType(UserBehavior.ACTION_UNSAVE);
        userProfileService.recordBehavior(userId, req);
    }

    /** 删除互动行；返回是否真的移除了（不存在则取消操作幂等） */
    private boolean remove(String userId, Long postId, String action) {
        int deleted = interactionRepository.delete(new LambdaQueryWrapper<PostInteraction>()
                .eq(PostInteraction::getUserId, userId)
                .eq(PostInteraction::getPostId, postId)
                .eq(PostInteraction::getActionType, action));
        if (deleted > 0) {
            bumpCounter(postId, action, -1);
            return true;
        }
        return false;
    }

    private void bumpCounter(Long postId, String action, int delta) {
        String column;
        if (PostInteraction.ACTION_LIKE.equals(action)) {
            column = "like_count";
        } else if (PostInteraction.ACTION_FAVORITE.equals(action)) {
            column = "favorite_count";
        } else {
            return; // DISLIKE 无计数列（仅关系留痕）
        }
        postRepository.update(null, new UpdateWrapper<TravelPost>()
                .eq("id", postId)
                .setSql(delta > 0
                        ? column + " = IFNULL(" + column + ", 0) + 1"
                        : column + " = GREATEST(IFNULL(" + column + ", 0) - 1, 0)"));
    }

    private TravelPost requirePublished(Long postId) {
        TravelPost post = postId == null ? null : postRepository.selectById(postId);
        if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            throw new PostNotFoundException("帖子不存在");
        }
        if (!post.isPubliclyVisible()) {
            throw new ForbiddenException("只有已发布的帖子可以互动");
        }
        return post;
    }

    private String normalizeAction(String action) {
        if (action == null) {
            throw new IllegalArgumentException("缺少互动类型");
        }
        switch (action.trim().toUpperCase()) {
            case PostInteraction.ACTION_LIKE:
                return PostInteraction.ACTION_LIKE;
            case PostInteraction.ACTION_FAVORITE:
                return PostInteraction.ACTION_FAVORITE;
            case PostInteraction.ACTION_DISLIKE:
                return PostInteraction.ACTION_DISLIKE;
            default:
                throw new IllegalArgumentException("不支持的互动类型: " + action);
        }
    }
}
