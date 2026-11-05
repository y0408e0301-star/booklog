package com.example.booklog.profile;

import java.time.Clock;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.follow.FollowRepository;
import com.example.booklog.review.ReviewRepository;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.user.Bios;
import com.example.booklog.user.DisplayNames;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// プロフィールの表示と、表示名・自己紹介の変更（機能定義書 FN-11）。件数は開いたときに数える（5 章の 1）
@Service
class ProfileService {

	private final UserRepository users;

	private final ReviewRepository reviews;

	private final FollowRepository follows;

	private final ReadingStatuses statuses;

	private final Clock clock;

	ProfileService(UserRepository users, ReviewRepository reviews, FollowRepository follows, ReadingStatuses statuses,
			Clock clock) {
		this.users = users;
		this.reviews = reviews;
		this.follows = follows;
		this.statuses = statuses;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	ProfileView get(long viewerId, String username) {
		User user = this.users.findByUsername(username).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
		return view(user, viewerId);
	}

	// 変えられるのはログインした人のプロフィールだけ（8 章）
	@Transactional
	ProfileView edit(long userId, @Nullable String rawDisplayName, @Nullable String rawBio) {
		String displayName = DisplayNames.valid(rawDisplayName);
		String bio = Bios.valid(rawBio);
		// JWT は正しいがユーザーがいない（退会した）ときは、ログインしていないものとして扱う
		User user = this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		user.editProfile(displayName, bio, this.clock.instant());
		return view(user, userId);
	}

	private ProfileView view(User user, long viewerId) {
		long id = user.getId();
		return new ProfileView(user.getUsername(), user.getDisplayName(), user.getBio(),
				this.reviews.countByAuthorId(id), this.follows.countByFollowerId(id),
				this.follows.countByFolloweeId(id), this.statuses.counts(id),
				id != viewerId && this.follows.existsByFollowerIdAndFolloweeId(viewerId, id), id == viewerId);
	}

}
