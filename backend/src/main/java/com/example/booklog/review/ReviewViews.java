package com.example.booklog.review;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.booklog.comment.CommentCounts;
import com.example.booklog.helpful.Helpfuls;
import com.example.booklog.storage.PhotoStorage;

// レビューを ReviewView にする。写真・参考になった・コメント数はレビューごとに SQL を出さず、まとめて 1 回で取る
// （FN-09 3 章、TC-09-6）。写真の URL は署名付き URL（15 分）にする（FN-04 5 章の 4）
@Component
class ReviewViews {

	private final ReviewPhotoRepository photos;

	private final PhotoStorage storage;

	private final Helpfuls helpfuls;

	private final CommentCounts commentCounts;

	ReviewViews(ReviewPhotoRepository photos, PhotoStorage storage, Helpfuls helpfuls, CommentCounts commentCounts) {
		this.photos = photos;
		this.storage = storage;
		this.helpfuls = helpfuls;
		this.commentCounts = commentCounts;
	}

	ReviewView of(Review review, long viewerId) {
		return of(List.of(review), viewerId).getFirst();
	}

	List<ReviewView> of(List<Review> reviews, long viewerId) {
		if (reviews.isEmpty()) {
			return List.of();
		}
		List<Long> ids = reviews.stream().map(Review::getId).toList();
		Map<Long, List<ReviewView.Photo>> photosByReview = this.photos
			.findByReviewIdInOrderByReviewIdAscSortOrderAsc(ids)
			.stream()
			.collect(Collectors.groupingBy(ReviewPhoto::getReviewId,
					Collectors.mapping(this::photo, Collectors.toList())));
		Map<Long, Long> helpfulCounts = this.helpfuls.counts(ids);
		Set<Long> markedByViewer = this.helpfuls.markedBy(viewerId, ids);
		Map<Long, Long> commentCounts = this.commentCounts.of(ids);
		return reviews.stream()
			.map((review) -> ReviewView.of(review, viewerId, photosByReview.getOrDefault(review.getId(), List.of()),
					helpfulCounts.getOrDefault(review.getId(), 0L), markedByViewer.contains(review.getId()),
					commentCounts.getOrDefault(review.getId(), 0L)))
			.toList();
	}

	// URL の取り直し（POST /api/photos/urls）。削除された写真の ID は結果に入れない（FN-04 6 章）
	List<ReviewView.Photo> photos(Collection<Long> photoIds) {
		return this.photos.findAllById(photoIds).stream().map(this::photo).toList();
	}

	private ReviewView.Photo photo(ReviewPhoto photo) {
		PhotoStorage.SignedUrl url = this.storage.signedUrl(photo.getStorageKey());
		return new ReviewView.Photo(photo.getId(), url.url(), url.expiresAt());
	}

}
