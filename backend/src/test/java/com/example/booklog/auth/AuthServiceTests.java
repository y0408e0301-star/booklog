package com.example.booklog.auth;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// ユーザー登録・ログイン・取り直し・ログアウト（機能定義書 FN-01）の処理を、データベースを使わずに確かめる
@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

	private static final String DUMMY_HASH = "dummy-hash";

	private static final TokenService.IssuedTokens TOKENS = new TokenService.IssuedTokens("access", "refresh");

	@Mock
	UserRepository users;

	@Mock
	RefreshTokenRepository refreshTokens;

	@Mock
	TokenService tokens;

	@Mock
	PasswordEncoder passwordEncoder;

	private AuthService service;

	@BeforeEach
	void setUp() {
		when(this.passwordEncoder.encode("dummy-password-0")).thenReturn(DUMMY_HASH);
		this.service = new AuthService(this.users, this.refreshTokens, this.tokens, this.passwordEncoder,
				TestEntities.fixedClock());
	}

	@Nested
	class ユーザーIDが使えるか {

		@Test
		void 使われていなければtrue() {
			when(AuthServiceTests.this.users.existsByUsername("taro_y")).thenReturn(false);
			assertThat(AuthServiceTests.this.service.isUsernameAvailable(" taro_y ")).isTrue();
		}

		@Test
		void 使われていればfalse() {
			when(AuthServiceTests.this.users.existsByUsername("Taro_Y")).thenReturn(true);
			assertThat(AuthServiceTests.this.service.isUsernameAvailable("Taro_Y")).isFalse();
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "ab", "abcdefghijklmnop", "taro-y", "taro.y", "taro y", "たろう", "ｔａｒｏ" })
		void 条件に合わなければUSERNAME_INVALIDでデータベースは見ない(String username) {
			assertApiError(() -> AuthServiceTests.this.service.isUsernameAvailable(username),
					ErrorCode.USERNAME_INVALID, "username");
			verifyNoInteractions(AuthServiceTests.this.users);
		}

		@ParameterizedTest
		@ValueSource(strings = { "abc", "abcdefghijklmno", "A_1", "___" })
		void 三文字から十五文字の半角英数字とアンダースコアなら使える(String username) {
			assertThat(AuthServiceTests.this.service.isUsernameAvailable(username)).isTrue();
		}

	}

	@Nested
	class 登録 {

		// N-14・BR-1
		@Test
		void トリムした値とハッシュにしたパスワードで保存しトークンを渡す() {
			when(AuthServiceTests.this.passwordEncoder.encode("password1")).thenReturn("hashed");
			when(AuthServiceTests.this.users.saveAndFlush(any(User.class)))
				.thenAnswer((invocation) -> TestEntities.withId(invocation.getArgument(0), 1L));
			when(AuthServiceTests.this.tokens.issue(any(User.class))).thenReturn(TOKENS);

			AuthService.LoginResult result = AuthServiceTests.this.service.signup(" taro_y ", " たろう ", "password1");

			ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
			verify(AuthServiceTests.this.users).saveAndFlush(saved.capture());
			assertThat(saved.getValue().getUsername()).isEqualTo("taro_y");
			assertThat(saved.getValue().getDisplayName()).isEqualTo("たろう");
			assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
			assertThat(saved.getValue().getCreatedAt()).isEqualTo(TestEntities.NOW);
			assertThat(result.me()).isEqualTo(new AuthService.Me("taro_y", "たろう"));
			assertThat(result.tokens()).isEqualTo(TOKENS);
		}

		@ParameterizedTest
		@ValueSource(strings = { "abcdefg1", "12345678a", "pass word 1", "p@ss!#$%1", " abcdef1 " })
		void パスワードは英字と数字を含む8文字以上の半角文字でトリムしない(String password) {
			when(AuthServiceTests.this.passwordEncoder.encode(password)).thenReturn("hashed");
			when(AuthServiceTests.this.users.saveAndFlush(any(User.class)))
				.thenAnswer((invocation) -> TestEntities.withId(invocation.getArgument(0), 1L));

			AuthServiceTests.this.service.signup("taro_y", "たろう", password);

			verify(AuthServiceTests.this.passwordEncoder).encode(password);
		}

		@Test
		void パスワードは64文字まで() {
			String password = "a1".repeat(32);
			when(AuthServiceTests.this.passwordEncoder.encode(password)).thenReturn("hashed");
			when(AuthServiceTests.this.users.saveAndFlush(any(User.class)))
				.thenAnswer((invocation) -> TestEntities.withId(invocation.getArgument(0), 1L));
			AuthServiceTests.this.service.signup("taro_y", "たろう", password);
			verify(AuthServiceTests.this.users).saveAndFlush(any(User.class));
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "abcdef1", "abcdefghij", "1234567890", "pass\tword1", "ぱすわーど1234", "ｐａｓｓｗｏｒｄ１" })
		void 条件に合わないパスワードはPASSWORD_INVALID(String password) {
			assertApiError(() -> AuthServiceTests.this.service.signup("taro_y", "たろう", password),
					ErrorCode.PASSWORD_INVALID, "password");
			verify(AuthServiceTests.this.users, never()).saveAndFlush(any());
		}

		@Test
		void 六十五文字のパスワードはPASSWORD_INVALID() {
			assertApiError(() -> AuthServiceTests.this.service.signup("taro_y", "たろう", "a1".repeat(32) + "b"),
					ErrorCode.PASSWORD_INVALID, "password");
		}

		@Test
		void ユーザーIDから順に確かめる() {
			assertApiError(() -> AuthServiceTests.this.service.signup("x", "", "short"), ErrorCode.USERNAME_INVALID,
					"username");
			assertApiError(() -> AuthServiceTests.this.service.signup("taro_y", "", "short"),
					ErrorCode.DISPLAY_NAME_REQUIRED, "displayName");
		}

		@Test
		void 使われているユーザーIDはUSERNAME_TAKEN() {
			when(AuthServiceTests.this.users.existsByUsername("taro_y")).thenReturn(true);
			assertApiError(() -> AuthServiceTests.this.service.signup("taro_y", "たろう", "password1"),
					ErrorCode.USERNAME_TAKEN, "username");
			verify(AuthServiceTests.this.users, never()).saveAndFlush(any());
		}

		@Test
		void 同時に登録されて一意索引で失敗してもUSERNAME_TAKEN() {
			when(AuthServiceTests.this.passwordEncoder.encode("password1")).thenReturn("hashed");
			when(AuthServiceTests.this.users.saveAndFlush(any(User.class)))
				.thenThrow(new DataIntegrityViolationException("users_username_lower_key"));
			assertApiError(() -> AuthServiceTests.this.service.signup("taro_y", "たろう", "password1"),
					ErrorCode.USERNAME_TAKEN, "username");
			verifyNoInteractions(AuthServiceTests.this.tokens);
		}

	}

	@Nested
	class ログイン {

		private User user;

		@BeforeEach
		void setUp() {
			this.user = TestEntities.user(1, "taro_y");
		}

		@Test
		void パスワードが合えばトークンを渡す() {
			when(AuthServiceTests.this.users.findByUsernameForUpdate("taro_y")).thenReturn(Optional.of(this.user));
			when(AuthServiceTests.this.passwordEncoder.matches("password1", "hash-taro_y")).thenReturn(true);
			when(AuthServiceTests.this.tokens.issue(this.user)).thenReturn(TOKENS);

			AuthService.LoginResult result = AuthServiceTests.this.service.login(" taro_y ", "password1");

			assertThat(result.me().username()).isEqualTo("taro_y");
			assertThat(result.tokens()).isEqualTo(TOKENS);
		}

		@Test
		void 空ならデータベースを見ずにLOGIN_FAILED() {
			assertApiError(() -> AuthServiceTests.this.service.login(" ", "password1"), ErrorCode.LOGIN_FAILED);
			assertApiError(() -> AuthServiceTests.this.service.login(null, "password1"), ErrorCode.LOGIN_FAILED);
			assertApiError(() -> AuthServiceTests.this.service.login("taro_y", ""), ErrorCode.LOGIN_FAILED);
			assertApiError(() -> AuthServiceTests.this.service.login("taro_y", null), ErrorCode.LOGIN_FAILED);
			verifyNoInteractions(AuthServiceTests.this.users);
		}

		@Test
		void いないユーザーでもダミーのハッシュで確かめてからLOGIN_FAILED() {
			when(AuthServiceTests.this.users.findByUsernameForUpdate("nobody")).thenReturn(Optional.empty());
			assertApiError(() -> AuthServiceTests.this.service.login("nobody", "password1"), ErrorCode.LOGIN_FAILED);
			// いるときと同じくらい時間をかける（FN-01 3.2）
			verify(AuthServiceTests.this.passwordEncoder).matches("password1", DUMMY_HASH);
		}

		@Test
		void パスワードが違えば失敗を数えてLOGIN_FAILED() {
			when(AuthServiceTests.this.users.findByUsernameForUpdate("taro_y")).thenReturn(Optional.of(this.user));
			assertApiError(() -> AuthServiceTests.this.service.login("taro_y", "wrong1234"), ErrorCode.LOGIN_FAILED);
			assertThat(this.user.getFailedLoginCount()).isEqualTo(1);
			verifyNoInteractions(AuthServiceTests.this.tokens);
		}

		@Test
		void 五回目の失敗でLOGIN_LOCKED() {
			when(AuthServiceTests.this.users.findByUsernameForUpdate("taro_y")).thenReturn(Optional.of(this.user));
			for (int i = 0; i < 4; i++) {
				assertApiError(() -> AuthServiceTests.this.service.login("taro_y", "wrong1234"),
						ErrorCode.LOGIN_FAILED);
			}
			assertApiError(() -> AuthServiceTests.this.service.login("taro_y", "wrong1234"), ErrorCode.LOGIN_LOCKED);
			assertThat(this.user.getLockedUntil()).isEqualTo(TestEntities.NOW.plus(AuthService.LOCK_DURATION));
		}

		@Test
		void ロック中は正しいパスワードでもパスワードを確かめずLOGIN_LOCKED() {
			this.user.recordFailedLogin(TestEntities.NOW, AuthService.LOCK_DURATION);
			for (int i = 0; i < 4; i++) {
				this.user.recordFailedLogin(TestEntities.NOW, AuthService.LOCK_DURATION);
			}
			when(AuthServiceTests.this.users.findByUsernameForUpdate("taro_y")).thenReturn(Optional.of(this.user));

			assertApiError(() -> AuthServiceTests.this.service.login("taro_y", "password1"), ErrorCode.LOGIN_LOCKED);
			verify(AuthServiceTests.this.passwordEncoder, never()).matches(anyString(), anyString());
		}

		@Test
		void 成功すると失敗の回数を0に戻す() {
			this.user.recordFailedLogin(TestEntities.NOW, AuthService.LOCK_DURATION);
			when(AuthServiceTests.this.users.findByUsernameForUpdate("taro_y")).thenReturn(Optional.of(this.user));
			when(AuthServiceTests.this.passwordEncoder.matches("password1", "hash-taro_y")).thenReturn(true);

			AuthServiceTests.this.service.login("taro_y", "password1");

			assertThat(this.user.getFailedLoginCount()).isZero();
		}

		@Test
		void 七十二バイトを超えるパスワードは確かめる処理だけ行って違うとする() {
			when(AuthServiceTests.this.users.findByUsernameForUpdate("taro_y")).thenReturn(Optional.of(this.user));
			// BCrypt は 72 バイトを超えると例外にするため、そのまま渡さない
			assertApiError(() -> AuthServiceTests.this.service.login("taro_y", "a1".repeat(37)),
					ErrorCode.LOGIN_FAILED);
			verify(AuthServiceTests.this.passwordEncoder).matches("", "hash-taro_y");
		}

	}

	@Nested
	class 取り直し {

		private static final String TOKEN = "refresh-token-value";

		private RefreshToken current;

		@BeforeEach
		void setUp() {
			this.current = new RefreshToken(1, TokenService.hash(TOKEN),
					TestEntities.NOW.plus(TokenService.REFRESH_TOKEN_LIFETIME), TestEntities.NOW);
		}

		@Test
		void 今のトークンを無効にして新しいトークンを渡す() {
			User user = TestEntities.user(1, "taro_y");
			when(AuthServiceTests.this.refreshTokens.findByTokenHashForUpdate(TokenService.hash(TOKEN)))
				.thenReturn(Optional.of(this.current));
			when(AuthServiceTests.this.users.findById(1L)).thenReturn(Optional.of(user));
			when(AuthServiceTests.this.tokens.issue(user)).thenReturn(TOKENS);

			AuthService.LoginResult result = AuthServiceTests.this.service.refresh(TOKEN);

			assertThat(this.current.getRevokedAt()).isEqualTo(TestEntities.NOW);
			assertThat(result.tokens()).isEqualTo(TOKENS);
		}

		@ParameterizedTest
		@NullAndEmptySource
		void トークンがなければREFRESH_FAILED(String token) {
			assertApiError(() -> AuthServiceTests.this.service.refresh(token), ErrorCode.REFRESH_FAILED);
			verifyNoInteractions(AuthServiceTests.this.refreshTokens);
		}

		@Test
		void 知らないトークンはREFRESH_FAILED() {
			when(AuthServiceTests.this.refreshTokens.findByTokenHashForUpdate(anyString()))
				.thenReturn(Optional.empty());
			assertApiError(() -> AuthServiceTests.this.service.refresh("unknown"), ErrorCode.REFRESH_FAILED);
		}

		@Test
		void 無効にしたトークンが使われたらその人のトークンをすべて無効にする() {
			this.current.revoke(TestEntities.NOW.minusSeconds(60));
			when(AuthServiceTests.this.refreshTokens.findByTokenHashForUpdate(TokenService.hash(TOKEN)))
				.thenReturn(Optional.of(this.current));

			assertApiError(() -> AuthServiceTests.this.service.refresh(TOKEN), ErrorCode.REFRESH_FAILED);

			verify(AuthServiceTests.this.refreshTokens).revokeAllByUserId(1, TestEntities.NOW);
			verifyNoInteractions(AuthServiceTests.this.tokens);
		}

		@Test
		void 期限切れのトークンはREFRESH_FAILEDでほかのトークンは無効にしない() {
			RefreshToken expired = new RefreshToken(1, TokenService.hash(TOKEN), TestEntities.NOW,
					TestEntities.NOW.minus(TokenService.REFRESH_TOKEN_LIFETIME));
			when(AuthServiceTests.this.refreshTokens.findByTokenHashForUpdate(TokenService.hash(TOKEN)))
				.thenReturn(Optional.of(expired));

			assertApiError(() -> AuthServiceTests.this.service.refresh(TOKEN), ErrorCode.REFRESH_FAILED);

			verify(AuthServiceTests.this.refreshTokens, never()).revokeAllByUserId(anyLong(), any());
		}

		@Test
		void ユーザーが消されていればREFRESH_FAILED() {
			when(AuthServiceTests.this.refreshTokens.findByTokenHashForUpdate(TokenService.hash(TOKEN)))
				.thenReturn(Optional.of(this.current));
			when(AuthServiceTests.this.users.findById(1L)).thenReturn(Optional.empty());
			assertApiError(() -> AuthServiceTests.this.service.refresh(TOKEN), ErrorCode.REFRESH_FAILED);
		}

	}

	@Nested
	class ログアウト {

		@Test
		void 自分のリフレッシュトークンだけを無効にする() {
			RefreshToken token = new RefreshToken(1, TokenService.hash("mine"), TestEntities.NOW.plusSeconds(60),
					TestEntities.NOW);
			when(AuthServiceTests.this.refreshTokens.findByTokenHash(TokenService.hash("mine")))
				.thenReturn(Optional.of(token));

			AuthServiceTests.this.service.logout(1, "mine");

			assertThat(token.isRevoked()).isTrue();
		}

		@Test
		void ほかの人のトークンは無効にしない() {
			RefreshToken token = new RefreshToken(2, TokenService.hash("other"), TestEntities.NOW.plusSeconds(60),
					TestEntities.NOW);
			when(AuthServiceTests.this.refreshTokens.findByTokenHash(TokenService.hash("other")))
				.thenReturn(Optional.of(token));

			AuthServiceTests.this.service.logout(1, "other");

			assertThat(token.isRevoked()).isFalse();
		}

		@ParameterizedTest
		@NullAndEmptySource
		void トークンがなければ何もしない(String token) {
			AuthServiceTests.this.service.logout(1, token);
			verifyNoInteractions(AuthServiceTests.this.refreshTokens);
		}

	}

	@Nested
	class 自分の情報 {

		@Test
		void ユーザーIDと表示名を返す() {
			User user = TestEntities.user(1, "taro_y");
			when(AuthServiceTests.this.users.findById(1L)).thenReturn(Optional.of(user));
			assertThat(AuthServiceTests.this.service.me(1)).isEqualTo(new AuthService.Me("taro_y", "taro_yの表示名"));
		}

		@Test
		void ユーザーが消されていればUNAUTHENTICATED() {
			when(AuthServiceTests.this.users.findById(1L)).thenReturn(Optional.empty());
			assertApiError(() -> AuthServiceTests.this.service.me(1), ErrorCode.UNAUTHENTICATED);
		}

	}

}
