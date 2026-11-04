package com.example.booklog.comment;

import java.time.Instant;

import com.example.booklog.user.Author;

// コメントを返すときの形（機能定義書 FN-06 6 章）。author はレビューの author と同じ形（機能定義書 3.5）
public record CommentView(long id, Author author, String body, boolean mine, Instant createdAt) {

	static CommentView of(Comment comment, long viewerId) {
		return new CommentView(comment.getId(), Author.of(comment.getAuthor()), comment.getBody(),
				comment.isWrittenBy(viewerId), comment.getCreatedAt());
	}

}
