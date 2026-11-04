package com.example.booklog.comment;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

// レビューのカードのコメント数（RV-6）。レビューの一覧を ReviewView にするときに使う
@Component
public class CommentCounts {

	private final CommentRepository comments;

	CommentCounts(CommentRepository comments) {
		this.comments = comments;
	}

	// 0 件のレビューは結果に入らない
	public Map<Long, Long> of(Collection<Long> reviewIds) {
		return this.comments.countByReviewIds(reviewIds)
			.stream()
			.collect(Collectors.toMap(Count::reviewId, Count::count));
	}

	// レビューごとのコメント数（CommentRepository の集計の結果）
	public record Count(long reviewId, long count) {
	}

}
