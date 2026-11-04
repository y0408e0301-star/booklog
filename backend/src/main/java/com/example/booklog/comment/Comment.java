package com.example.booklog.comment;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.example.booklog.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

// コメント（データ設計書 4.6）。レビューを削除すると、外部キーの ON DELETE CASCADE で行も消える。編集はできない
@Entity
@Table(name = "comments")
public class Comment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@Column(nullable = false, updatable = false)
	private long reviewId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User author;

	@Column(nullable = false, updatable = false)
	private String body;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Comment() {
		this(0, new User("", "", "", Instant.EPOCH), "", Instant.EPOCH);
	}

	public Comment(long reviewId, User author, String body, Instant now) {
		this.reviewId = reviewId;
		this.author = author;
		this.body = body;
		this.createdAt = now;
	}

	public boolean isWrittenBy(long userId) {
		return this.author.getId() == userId;
	}

	public long getId() {
		if (this.id == null) {
			throw new IllegalStateException("まだ保存していないコメントです");
		}
		return this.id;
	}

	public long getReviewId() {
		return this.reviewId;
	}

	public User getAuthor() {
		return this.author;
	}

	public String getBody() {
		return this.body;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
