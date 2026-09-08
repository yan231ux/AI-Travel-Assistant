package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.PostComment;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.repository.PostCommentRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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
 * <p>只在 PUBLISHED 帖子上可评论（评论者未必是作者）；作者可删自己的评论、管理员可删违规评论
 * （删除=软删 status=DELETED，同时递减帖子 comment_count）。父评论被删后子回复保留，
 * 父内容在列表中以「评论已删除」占位，不做级联删除。
 */
@Slf4j
@Service
public class PostCommentService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PostCommentRepository commentRepository;
    private final TravelPostRepository postRepository;
    private final CommunityUserService communityUserService;

    public PostCommentService(PostCommentRepository commentRepository,
                              TravelPostRepository postRepository,
                              CommunityUserService communityUserService) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.communityUserService = communityUserService;
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
        @JsonProperty("mine")
        private Boolean mine;
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
                it.setMine(viewerId != null && c.getUserId().equals(viewerId));
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
        PostAuthorLite a = new PostAuthorLite();
        a.setId(userId);
        a.setNickname(communityUserService.nicknameOf(userId));
        it.setAuthor(a);
        return it;
    }

    /** 删除评论：作者本人或管理员（软删 + 递减帖子计数） */
    @Transactional
    public void delete(String operatorId, Long commentId) {
        PostComment c = commentId == null ? null : commentRepository.selectById(commentId);
        if (c == null || PostComment.STATUS_DELETED.equals(c.getStatus())) {
            throw new PostNotFoundException("评论不存在");
        }
        boolean owner = c.getUserId().equals(operatorId);
        boolean canDelete = owner || communityUserService.isAdmin(operatorId);
        if (!canDelete) {
            throw new ForbiddenException("只能删除自己的评论");
        }
        c.setStatus(PostComment.STATUS_DELETED);
        commentRepository.updateById(c);
        postRepository.update(null, new UpdateWrapper<TravelPost>()
                .eq("id", c.getPostId())
                .setSql("comment_count = GREATEST(IFNULL(comment_count, 0) - 1, 0)"));
        log.info("评论删除: commentId={} by={}", commentId, operatorId);
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
