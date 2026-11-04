package com.example.booklog.comment;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// コメント（機能定義書 FN-06）
@Service
class CommentService {

	static final int PAGE_SIZE = 50;

	// 本文の文字数の上限（C-2）。comments.body の varchar(500) と同じ
	static final int BODY_MAX = 500;

	private static final Pattern LINE_BREAKS = Pattern.compile("\r\n?");

	private final CommentRepository comments;

	private final UserRepository users;

	private final Clock clock;

	CommentService(CommentRepository comments, UserRepository users, Clock clock) {
		this.comments = comments;
		this.users = users;
		this.clock = clock;
	}

	// 51 件取って、51 件目があれば続きがある（nextCursor は 50 件目の ID）。
	// ID は新しいコメントほど大きいので、途中でコメントが増えても重複・抜けは出ない（FN-06 5 章の 1）
	@Transactional(readOnly = true)
	CommentPage list(long viewerId, long reviewId, @Nullable String cursor) {
		long after = parseCursor(cursor);
		requireReview(reviewId);
		List<Comment> found = this.comments.findAfter(reviewId, after, Limit.of(PAGE_SIZE + 1));
		List<Comment> page = found.subList(0, Math.min(found.size(), PAGE_SIZE));
		String next = (found.size() > PAGE_SIZE) ? String.valueOf(page.getLast().getId()) : null;
		return new CommentPage(page.stream().map((comment) -> CommentView.of(comment, viewerId)).toList(), next);
	}

	// 本文は改行を LF にそろえてトリムした後で 1〜500 文字（コードポイント）（C-2、FN-06 4 章）
	@Transactional
	CommentView create(long userId, long reviewId, @Nullable String rawBody) {
		String body = (rawBody == null) ? "" : LINE_BREAKS.matcher(rawBody).replaceAll("\n").strip();
		if (body.isEmpty()) {
			throw new ApiException(ErrorCode.COMMENT_REQUIRED, "body");
		}
		if (body.codePointCount(0, body.length()) > BODY_MAX) {
			// 画面では 500 文字を超えると送れない
			throw new ApiException(ErrorCode.BAD_REQUEST, "body");
		}
		requireReview(reviewId);
		User author = this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		try {
			return CommentView.of(this.comments.saveAndFlush(new Comment(reviewId, author, body, this.clock.instant())),
					userId);
		}
		catch (DataIntegrityViolationException ex) {
			// 確かめた後でレビューが削除された（外部キーの制約に引っかかった）
			throw new ApiException(ErrorCode.REVIEW_NOT_FOUND);
		}
	}

	// 消せるのはコメントを書いた人だけ。レビューを書いた人も、ほかの人のコメントは消せない（C-5、FN-06 5 章の 3）
	@Transactional
	void delete(long userId, long commentId) {
		Comment comment = this.comments.findWithAuthorById(commentId)
			.orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
		if (!comment.isWrittenBy(userId)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		this.comments.delete(comment);
	}

	private void requireReview(long reviewId) {
		if (!this.comments.reviewExists(reviewId)) {
			throw new ApiException(ErrorCode.REVIEW_NOT_FOUND);
		}
	}

	// 最初は cursor なし（ID 0 より後ろ）。画面はカーソルの中身を読まない（機能定義書 3.4）
	private static long parseCursor(@Nullable String cursor) {
		if (cursor == null) {
			return 0;
		}
		try {
			return Long.parseLong(cursor);
		}
		catch (NumberFormatException ex) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "cursor");
		}
	}

	// 一覧の形（機能定義書 3.4）。nextCursor が null なら最後まで読んだ
	record CommentPage(List<CommentView> items, @Nullable String nextCursor) {

		CommentPage {
			items = List.copyOf(items);
		}

	}

}
