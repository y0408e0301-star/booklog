package com.example.booklog.follow;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

// フォロー（データ設計書 4.7）。タイムラインの「フォロー中」（FN-09 T-1）とフォロー（FN-07）で使う。
// 追加は FollowRepository.insertIfAbsent で行う
@Entity
@Table(name = "follows")
@IdClass(Follow.Key.class)
public class Follow {

	@Id
	@Column(nullable = false, updatable = false)
	private long followerId;

	@Id
	@Column(nullable = false, updatable = false)
	private long followeeId;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Follow() {
		this(0, 0, Instant.EPOCH);
	}

	public Follow(long followerId, long followeeId, Instant now) {
		this.followerId = followerId;
		this.followeeId = followeeId;
		this.createdAt = now;
	}

	public long getFollowerId() {
		return this.followerId;
	}

	public long getFolloweeId() {
		return this.followeeId;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	// 主キー（follower_id, followee_id）
	public record Key(long followerId, long followeeId) implements Serializable {

		public Key() {
			this(0, 0);
		}

	}

}
