package com.example.booklog.follow;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.common.TimeCursor;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;
import com.example.booklog.user.UserSummary;

// フォロー（機能定義書 FN-07）。PUT・DELETE は何回送っても同じ結果になる（5 章の 2）
@Service
class FollowService {

	static final int PAGE_SIZE = 20;

	// 最初は cursor なし（どの行よりも後ろ）
	private static final TimeCursor FIRST = new TimeCursor(Instant.parse("9999-12-31T00:00:00Z"), Long.MAX_VALUE);

	private final FollowRepository follows;

	private final UserRepository users;

	FollowService(FollowRepository follows, UserRepository users) {
		this.follows = follows;
		this.users = users;
	}

	// 自分はフォローできない（F-3）。followerCount はほかの人の操作も含めた最新の数
	@Transactional
	FollowResult follow(long userId, String username) {
		User target = find(username);
		if (target.getId() == userId) {
			throw new ApiException(ErrorCode.CANNOT_FOLLOW_SELF);
		}
		try {
			this.follows.insertIfAbsent(userId, target.getId());
		}
		catch (DataIntegrityViolationException ex) {
			// 確かめた後で相手が退会した（外部キーの制約に引っかかった）
			throw new ApiException(ErrorCode.USER_NOT_FOUND);
		}
		return new FollowResult(true, this.follows.countByFolloweeId(target.getId()));
	}

	@Transactional
	FollowResult unfollow(long userId, String username) {
		User target = find(username);
		this.follows.deleteByFollowerIdAndFolloweeId(userId, target.getId());
		return new FollowResult(false, this.follows.countByFolloweeId(target.getId()));
	}

	// その人がフォローしている人（F-5）
	@Transactional(readOnly = true)
	UserPage following(long viewerId, String username, @Nullable String cursor) {
		TimeCursor after = parse(cursor);
		User user = find(username);
		return page(viewerId,
				this.follows.findFollowing(user.getId(), after.at(), after.id(), Limit.of(PAGE_SIZE + 1)));
	}

	// その人をフォローしている人（F-5）
	@Transactional(readOnly = true)
	UserPage followers(long viewerId, String username, @Nullable String cursor) {
		TimeCursor after = parse(cursor);
		User user = find(username);
		return page(viewerId,
				this.follows.findFollowers(user.getId(), after.at(), after.id(), Limit.of(PAGE_SIZE + 1)));
	}

	// 21 件取って、21 件目があれば続きがある（nextCursor は 20 件目の日時とユーザーの ID）。
	// 見ている人がフォローしているかは、20 件まとめて 1 回で調べる
	private UserPage page(long viewerId, List<FollowRepository.Row> found) {
		List<FollowRepository.Row> rows = found.subList(0, Math.min(found.size(), PAGE_SIZE));
		String next = (found.size() > PAGE_SIZE)
				? new TimeCursor(rows.getLast().followedAt(), rows.getLast().user().getId()).format() : null;
		List<Long> ids = rows.stream().map((row) -> row.user().getId()).toList();
		Set<Long> followed = ids.isEmpty() ? Set.of() : new HashSet<>(this.follows.findFolloweeIds(viewerId, ids));
		List<UserSummary> items = rows.stream()
			.map((row) -> UserSummary.of(row.user(), viewerId, followed.contains(row.user().getId())))
			.toList();
		return new UserPage(items, next);
	}

	private User find(String username) {
		return this.users.findByUsername(username).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
	}

	private static TimeCursor parse(@Nullable String cursor) {
		TimeCursor after = TimeCursor.parse(cursor);
		return (after == null) ? FIRST : after;
	}

	// フォロー・解除の結果（FN-07 6 章）
	record FollowResult(boolean following, long followerCount) {
	}

	// 一覧の形（機能定義書 3.4）。nextCursor が null なら最後まで読んだ
	record UserPage(List<UserSummary> items, @Nullable String nextCursor) {

		UserPage {
			items = List.copyOf(items);
		}

	}

}
