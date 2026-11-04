package com.example.booklog.review;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// レビューの写真（データ設計書 4.4）。レビューを削除すると、外部キーの ON DELETE CASCADE で行も消える
@Entity
@Table(name = "review_photos")
public class ReviewPhoto {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@Column(nullable = false, updatable = false)
	private long reviewId;

	@Column(nullable = false, updatable = false)
	private String storageKey;

	@Column(nullable = false, updatable = false)
	private String contentType;

	@Column(nullable = false, updatable = false)
	private int byteSize;

	// 0〜3。編集では、残した写真を前の順のまま前に、足した写真を選んだ順に後ろに並べ直す（FN-04 5 章の 5）
	@Column(nullable = false)
	private short sortOrder;

	protected ReviewPhoto() {
		this(0, "", "", 0, 0);
	}

	ReviewPhoto(long reviewId, String storageKey, String contentType, int byteSize, int sortOrder) {
		this.reviewId = reviewId;
		this.storageKey = storageKey;
		this.contentType = contentType;
		this.byteSize = byteSize;
		this.sortOrder = (short) sortOrder;
	}

	void moveTo(int sortOrder) {
		this.sortOrder = (short) sortOrder;
	}

	public long getId() {
		if (this.id == null) {
			throw new IllegalStateException("まだ保存していない写真です");
		}
		return this.id;
	}

	public long getReviewId() {
		return this.reviewId;
	}

	public String getStorageKey() {
		return this.storageKey;
	}

	public String getContentType() {
		return this.contentType;
	}

	public int getByteSize() {
		return this.byteSize;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

}
