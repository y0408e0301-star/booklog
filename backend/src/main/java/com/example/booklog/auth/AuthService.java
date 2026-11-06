package com.example.booklog.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.review.ReviewPhotoRepository;
import com.example.booklog.storage.StorageDeleter;
import com.example.booklog.user.DisplayNames;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// ユーザー登録・ログイン・JWT の取り直し・ログアウト・退会（機能定義書 FN-01）
@Service
class AuthService {

	static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	// トリムした後で 3〜15 文字の半角英数字と「_」（A-2）
	private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,15}");

	// 8〜64 文字の半角文字（空白と記号を含む）で、英字と数字を 1 文字以上ずつ含む（A-3）
	private static final Pattern PASSWORD_CHARS = Pattern.compile("[\\x20-\\x7E]{8,64}");

	private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

	private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");

	// BCrypt は先頭の 72 バイトしか使わず、Spring Security はそれより長いと例外にする
	private static final int BCRYPT_MAX_BYTES = 72;

	private final UserRepository users;

	private final RefreshTokenRepository refreshTokens;

	private final TokenService tokens;

	private final PasswordEncoder passwordEncoder;

	private final ReviewPhotoRepository photos;

	private final StorageDeleter storageDeleter;

	private final TransactionTemplate transaction;

	private final Clock clock;

	// ユーザーがいないときに、いるときと同じくらい時間をかけるために確かめるハッシュ（FN-01 3.2）
	private final String dummyPasswordHash;

	AuthService(UserRepository users, RefreshTokenRepository refreshTokens, TokenService tokens,
			PasswordEncoder passwordEncoder, ReviewPhotoRepository photos, StorageDeleter storageDeleter,
			TransactionTemplate transaction, Clock clock) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.photos = photos;
		this.storageDeleter = storageDeleter;
		this.transaction = transaction;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password-0");
	}

	@Transactional(readOnly = true)
	boolean isUsernameAvailable(@Nullable String rawUsername) {
		String username = validUsername(rawUsername);
		return !this.users.existsByUsername(username);
	}

	@Transactional
	LoginResult signup(@Nullable String rawUsername, @Nullable String rawDisplayName, @Nullable String password) {
		String username = validUsername(rawUsername);
		String displayName = DisplayNames.valid(rawDisplayName);
		validatePassword(password);
		if (this.users.existsByUsername(username)) {
			throw new ApiException(ErrorCode.USERNAME_TAKEN, "username");
		}
		User user;
		try {
			// 同時に同じユーザー ID で登録されたときは、lower(username) の一意索引で失敗する
			user = this.users.saveAndFlush(
					new User(username, displayName, this.passwordEncoder.encode(password), this.clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ApiException(ErrorCode.USERNAME_TAKEN, "username");
		}
		return new LoginResult(Me.of(user), this.tokens.issue(user));
	}

	// 失敗の回数を増やしたことは、401・423 を返すときもコミットする（noRollbackFor）
	@Transactional(noRollbackFor = ApiException.class)
	LoginResult login(@Nullable String rawUsername, @Nullable String password) {
		String username = (rawUsername == null) ? "" : rawUsername.strip();
		// 空かどうかだけを確かめる。条件（文字数など）は教えない（FN-01 4 章）
		if (username.isEmpty() || password == null || password.isEmpty()) {
			throw new ApiException(ErrorCode.LOGIN_FAILED);
		}
		Optional<User> found = this.users.findByUsernameForUpdate(username);
		if (found.isEmpty()) {
			passwordMatches(password, this.dummyPasswordHash);
			throw new ApiException(ErrorCode.LOGIN_FAILED);
		}
		User user = found.get();
		Instant now = this.clock.instant();
		if (user.isLocked(now)) {
			// ロック中はパスワードを確かめない（BR-8）
			throw new ApiException(ErrorCode.LOGIN_LOCKED);
		}
		if (!passwordMatches(password, user.getPasswordHash())) {
			user.recordFailedLogin(now, LOCK_DURATION);
			throw new ApiException(user.isLocked(now) ? ErrorCode.LOGIN_LOCKED : ErrorCode.LOGIN_FAILED);
		}
		user.recordSuccessfulLogin(now);
		return new LoginResult(Me.of(user), this.tokens.issue(user));
	}

	// リフレッシュトークンを使って、両方のトークンを新しくする（FN-01 3.3、BR-9）。
	// 無効にしたものが使われたときに、その人のトークンをすべて無効にしたことは、401 を返すときもコミットする
	@Transactional(noRollbackFor = ApiException.class)
	LoginResult refresh(@Nullable String refreshToken) {
		if (refreshToken == null || refreshToken.isEmpty()) {
			throw new ApiException(ErrorCode.REFRESH_FAILED);
		}
		Instant now = this.clock.instant();
		RefreshToken current = this.refreshTokens.findByTokenHashForUpdate(TokenService.hash(refreshToken))
			.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_FAILED));
		if (current.isRevoked()) {
			// 替わる前の古いトークンが使われた：盗まれたとみなして、その人のログインをすべて切る
			this.refreshTokens.revokeAllByUserId(current.getUserId(), now);
			throw new ApiException(ErrorCode.REFRESH_FAILED);
		}
		if (current.isExpired(now)) {
			throw new ApiException(ErrorCode.REFRESH_FAILED);
		}
		current.revoke(now);
		User user = this.users.findById(current.getUserId())
			.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_FAILED));
		return new LoginResult(Me.of(user), this.tokens.issue(user));
	}

	// ログアウトしたブラウザのリフレッシュトークンだけを無効にする（ほかのブラウザのログインは続く。FN-01 5 章の 10）
	@Transactional
	void logout(long userId, @Nullable String refreshToken) {
		if (refreshToken == null || refreshToken.isEmpty()) {
			return;
		}
		this.refreshTokens.findByTokenHash(TokenService.hash(refreshToken))
			.filter((token) -> token.getUserId() == userId)
			.ifPresent((token) -> token.revoke(this.clock.instant()));
	}

	@Transactional(readOnly = true)
	Me me(long userId) {
		// JWT は正しいがユーザーがいない（退会した）ときは、ログインしていないものとして扱う
		return this.users.findById(userId)
			.map((user) -> Me.of(user))
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
	}

	// 退会する（A-11・A-12）。パスワードの間違いはログインの失敗に数えない（5 章の 7）。
	// 写真の置き場所を読んでから 1 つのトランザクションで users の行を消し（外部キーで一緒に消える。5 章の 6）、
	// コミットの後で S3 から消す（BR-5・BR-14）。パスワードを確かめる間は、データベースの接続を持たない
	void withdraw(long userId, @Nullable String password) {
		User user = this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		if (password == null || !passwordMatches(password, user.getPasswordHash())) {
			throw new ApiException(ErrorCode.PASSWORD_WRONG, "password");
		}
		List<String> keys = this.transaction.execute((status) -> {
			List<String> found = this.photos.findStorageKeysByAuthorId(userId);
			this.users.delete(user);
			return found;
		});
		this.storageDeleter.deleteOrRecord(keys);
	}

	private boolean passwordMatches(String password, String hash) {
		if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			// 登録できない長さなので必ず違う。時間をそろえるため、確かめる処理だけは行う
			this.passwordEncoder.matches("", hash);
			return false;
		}
		return this.passwordEncoder.matches(password, hash);
	}

	private static String validUsername(@Nullable String rawUsername) {
		String username = (rawUsername == null) ? "" : rawUsername.strip();
		if (!USERNAME.matcher(username).matches()) {
			throw new ApiException(ErrorCode.USERNAME_INVALID, "username");
		}
		return username;
	}

	// パスワードはトリムしない（BR-1）
	private static void validatePassword(@Nullable String password) {
		if (password == null || !PASSWORD_CHARS.matcher(password).matches() || !HAS_LETTER.matcher(password).find()
				|| !HAS_DIGIT.matcher(password).find()) {
			throw new ApiException(ErrorCode.PASSWORD_INVALID, "password");
		}
	}

	// 自分の情報（FN-01 6 章）
	record Me(String username, String displayName) {

		static Me of(User user) {
			return new Me(user.getUsername(), user.getDisplayName());
		}

	}

	record LoginResult(Me me, TokenService.IssuedTokens tokens) {
	}

}
