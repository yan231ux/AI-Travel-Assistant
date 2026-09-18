package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.ContentModerationTask;
import com.yuntu.tripplanner.model.PostComment;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.repository.PostCommentRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论服务（阶段二 §9.6）：列表 / 发布 / 删除。
 *
 * <p>只在 PUBLISHED 帖子上可评论（评论者未必是作者）；删除采用三方权限（阶段三评论治理）：
 * <b>评论作者本人</b>、<b>帖子作者（楼主治理自己帖子下的评论）</b>、<b>管理员</b>，
 * 其他人一律 403。删除=软删 status=DELETED，同时递减帖子 comment_count，并写审计留痕
 * （谁以什么身份删了谁的评论 —— 楼主删他人评论属敏感操作，必须可追溯）。
 * 父评论被删后子回复保留，父内容在列表中以「评论已删除」占位，不做级联删除。
 *
 * <p>列表项直接下发 {@code can_delete}（按当前访问者身份算好的删除权限），
 * 前端不再自己拼权限规则，避免「后端能删、前端不显示按钮」这类口子不一致。
 */
@Slf4j
@Service
public class PostCommentService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PostCommentRepository commentRepository;
    private final TravelPostRepository postRepository;
    private final CommunityUserService communityUserService;
    /** 全链路审计（阶段四任务 10）：评论删除留痕（楼主删他人评论必须可追溯） */
    private final AuditService auditService;
    /**
     * AI 内容审核（阶段三：评论发表后异步初筛）。可选注入（不走构造器）——
     * ContentModerationService 决策回调依赖本服务，构造环会让 Spring 启动失败。
     */
    @Autowired(required = false)
    private ContentModerationService moderationService;

    public PostCommentService(PostCommentRepository commentRepository,
                              TravelPostRepository postRepository,
                              CommunityUserService communityUserService,
                              AuditService auditService) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /** 评论返回项 */
    @Data
    public static class CommentItem {
        @JsonProperty("id")
        private Long id;
        @JsonProperty("post_id")
        private Long postId;
        @JsonProperty("parent_id")
        private Long parentId;
        @JsonProperty("content")
        private String content;
        @JsonProperty("deleted")
        private Boolean deleted;
        @JsonProperty("author")
        private PostAuthorLite author;
        @JsonProperty("created_at")
        private String createdAt;
        /** 是否本人发表（前端用于「我的评论」标识） */
        @JsonProperty("mine")
        private Boolean mine;
        /** 当前访问者是否有权删除（评论作者 / 楼主 / 管理员；已删评论恒 false） */
        @JsonProperty("can_delete")
        private Boolean canDelete;
    }

    @Data
    public static class PostAuthorLite {
        @JsonProperty("id")
        private String id;
        @JsonProperty("nickname")
        private String nickname;
    }

    /** 评论分页结果 */
    @Data
    public static class CommentPage {
        @JsonProperty("items")
        private List<CommentItem> items;
        @JsonProperty("total")
        private long total;
        @JsonProperty("page")
        private int page;
    }

    public CommentPage listComments(String viewerId, Long postId, int page, int pageSize) {
        TravelPost post = requirePublicPost(postId);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<PostComment> w = new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId)
                .orderByAsc(PostComment::getCreatedAt);
        long total = commentRepository.selectCount(w);
        List<PostComment> rows = commentRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        // 访问者身份只解析一次：是否为楼主 / 管理员 —— 列表项 can_delete 的判定依据
        boolean viewerIsPostAuthor = viewerId != null && !viewerId.isBlank()
                && viewerId.equals(post.getUserId());
        boolean viewerIsAdmin = viewerId != null && !viewerId.isBlank()
                && communityUserService.isAdmin(viewerId);
        List<CommentItem> items = new ArrayList<>();
        if (!rows.isEmpty()) {
            Map<String, String> nicknames = communityUserService.nicknamesOf(
                    rows.stream().map(PostComment::getUserId).collect(Collectors.toSet()));
            for (PostComment c : rows) {
                boolean deleted = PostComment.STATUS_DELETED.equals(c.getStatus());
                CommentItem it = new CommentItem();
                it.setId(c.getId());
                it.setPostId(c.getPostId());
                it.setParentId(c.getParentId());
                it.setContent(deleted ? "评论已删除" : c.getContent());
                it.setDeleted(deleted);
                PostAuthorLite a = new PostAuthorLite();
                a.setId(c.getUserId());
                a.setNickname(deleted ? "" : nicknames.getOrDefault(c.getUserId(), c.getUserId()));
                it.setAuthor(a);
                it.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().format(TS));
                boolean mine = viewerId != null && c.getUserId().equals(viewerId);
                it.setMine(mine);
                // 已删评论不可再删；其余按「评论作者 / 楼主 / 管理员」三方权限下发
                it.setCanDelete(!deleted && (mine || viewerIsPostAuthor || viewerIsAdmin));
                items.add(it);
            }
        }
        CommentPage result = new CommentPage();
        result.setItems(items);
        result.setTotal(total);
        result.setPage(pageNo);
        return result;
    }

    /** 发表评论（PUBLISHED 帖子 + 非空内容 + 父评论校验） */
    @Transactional
    public CommentItem add(String userId, Long postId, String content, Long parentId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少用户标识");
        }
        // 用户治理（§7）：暂停账号 / 暂停评论用户一律拒绝
        communityUserService.requireCanComment(userId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        String text = content.trim();
        if (text.length() > 1000) {
            throw new IllegalArgumentException("评论不能超过 1000 字");
        }
        TravelPost post = requirePublicPost(postId);
        if (parentId != null) {
            PostComment parent = commentRepository.selectById(parentId);
            if (parent == null || !parent.getPostId().equals(postId)
                    || PostComment.STATUS_DELETED.equals(parent.getStatus())) {
                throw new IllegalArgumentException("回复的评论不存在");
            }
        }
        PostComment c = new PostComment();
        c.setPostId(postId);
        c.setUserId(userId);
        c.setParentId(parentId);
        c.setContent(text);
        c.setStatus(PostComment.STATUS_PUBLISHED);
        c.setLikeCount(0);
        commentRepository.insert(c);
        postRepository.update(null, new UpdateWrapper<TravelPost>()
                .eq("id", postId)
                .setSql("comment_count = IFNULL(comment_count, 0) + 1"));
        CommentItem it = new CommentItem();
        it.setId(c.getId());
        it.setPostId(c.getPostId());
        it.setParentId(c.getParentId());
        it.setContent(c.getContent());
        it.setDeleted(false);
        it.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().format(TS));
        it.setMine(true);
        it.setCanDelete(true); // 自己刚发的评论，恒可删
        PostAuthorLite a = new PostAuthorLite();
        a.setId(userId);
        a.setNickname(communityUserService.nicknameOf(userId));
        it.setAuthor(a);
        // AI 审核初筛（阶段三）：评论即时可见，AI 异步复核风险，命中后由管理员在审核队列处置。
        // 异步 + 快照载荷，失败不影响评论发表。
        if (moderationService != null) {
            moderationService.submitAsync(ContentModerationTask.TARGET_COMMENT,
                    String.valueOf(c.getId()), null, null, null, text, userId);
        }
        return it;
    }

    /**
     * 删除评论：三方权限 = 评论作者本人 / 帖子作者（楼主治理自己帖子下的评论）/ 管理员。
     * 软删 + 递减帖子计数 + 审计留痕（楼主删他人评论属敏感操作，必须可追溯到"以什么身份删的"）。
     */
    @Transactional
    public void delete(String operatorId, Long commentId) {
        PostComment c = commentId == null ? null : commentRepository.selectById(commentId);
        if (c == null || PostComment.STATUS_DELETED.equals(c.getStatus())) {
            throw new PostNotFoundException("评论不存在");
        }
        boolean commentAuthor = c.getUserId().equals(operatorId);
        // 帖子查不到（历史数据被物理删除）时不放大权限：仅按「评论作者 / 管理员」判定
        TravelPost post = c.getPostId() == null ? null : postRepository.selectById(c.getPostId());
        boolean postAuthor = post != null && operatorId != null && operatorId.equals(post.getUserId());
        boolean admin = communityUserService.isAdmin(operatorId);
        if (!(commentAuthor || postAuthor || admin)) {
            throw new ForbiddenException("只有评论作者、帖子作者或管理员可以删除该评论");
        }
        c.setStatus(PostComment.STATUS_DELETED);
        commentRepository.updateById(c);
        postRepository.update(null, new UpdateWrapper<TravelPost>()
                .eq("id", c.getPostId())
                .setSql("comment_count = GREATEST(IFNULL(comment_count, 0) - 1, 0)"));
        String asRole = commentAuthor ? "comment_author" : postAuthor ? "post_author" : "admin";
        auditService.record(operatorId, AuditLog.CAT_SOCIAL, "comment_deleted", "comment",
                String.valueOf(commentId),
                AuditService.detailOf(
                        "post_id", c.getPostId(),
                        "comment_author", c.getUserId(),
                        "post_author", post == null ? null : post.getUserId(),
                        "as", asRole));
        log.info("评论删除: commentId={} by={} as={}", commentId, operatorId, asRole);
    }

    private TravelPost requirePublicPost(Long postId) {
        TravelPost post = postId == null ? null : postRepository.selectById(postId);
        if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            throw new PostNotFoundException("帖子不存在");
        }
        if (!post.isPubliclyVisible()) {
            throw new ForbiddenException("帖子尚未公开，暂不能评论");
        }
        return post;
    }
}
