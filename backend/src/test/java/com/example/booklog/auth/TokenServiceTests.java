package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.example.booklog.TestEntities;

// アクセストークン（JWT）とリフレッシュトークンを作る（機能定義書 3.2）。
// JWT の作成・確認は本物の部品（JwtConfig）を使い、保存先（RefreshTokenRepository）だけを偽物にする
@ExtendWith(MockitoExtension.class)
class TokenServiceTests {

	private static final AuthProperties PROPERTIES = new AuthProperties("test-only-jwt-secret-0123456789abcdef", false);

	@Mock
	RefreshTokenRepository refreshTokens;

	private final JwtConfig jwtConfig = new JwtConfig();

	private TokenService tokens;

	@BeforeEach
	void setUp() {
		this.tokens = new TokenService(this.jwtConfig.jwtEncoder(PROPERTIES), this.refreshTokens,
				TestEntities.fixedClock());
	}

	@Test
	void アクセストークンにはユーザーのIDとユーザーIDと15分の期限を入れる() {
		TokenService.IssuedTokens issued = this.tokens.issue(TestEntities.user(7, "taro_y"));

		Jwt jwt = decoderAt(TestEntities.NOW).decode(issued.accessToken());
		assertThat(jwt.getSubject()).isEqualTo("7");
		assertThat(jwt.getClaimAsString("username")).isEqualTo("taro_y");
		assertThat(jwt.getIssuedAt()).isEqualTo(TestEntities.NOW);
		assertThat(jwt.getExpiresAt()).isEqualTo(TestEntities.NOW.plus(TokenService.ACCESS_TOKEN_LIFETIME));
		// 表示名やパスワードのハッシュは入れない（FN-01 5 章 No.5）
		assertThat(jwt.getClaims()).containsOnlyKeys("sub", "username", "iat", "exp");
	}

	@Test
	void アクセストークンは15分を過ぎると使えない() {
		String token = this.tokens.issue(TestEntities.user(7, "taro_y")).accessToken();
		Instant expires = TestEntities.NOW.plus(TokenService.ACCESS_TOKEN_LIFETIME);
		assertThat(decoderAt(expires.minusSeconds(1)).decode(token).getSubject()).isEqualTo("7");
		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoderAt(expires).decode(token));
	}

	@Test
	void ほかの秘密の値で署名したトークンは使えない() {
		String token = this.tokens.issue(TestEntities.user(7, "taro_y")).accessToken();
		JwtDecoder other = this.jwtConfig.jwtDecoder(new AuthProperties("another-jwt-secret-0123456789abcdefgh", false),
				clockAt(TestEntities.NOW));
		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> other.decode(token));
	}

	@Test
	void リフレッシュトークンはハッシュだけを14日の期限で保存する() {
		TokenService.IssuedTokens issued = this.tokens.issue(TestEntities.user(7, "taro_y"));

		ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(this.refreshTokens).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(7);
		assertThat(saved.getValue().isExpired(TestEntities.NOW.plus(TokenService.REFRESH_TOKEN_LIFETIME).minusNanos(1)))
			.isFalse();
		assertThat(saved.getValue().isExpired(TestEntities.NOW.plus(TokenService.REFRESH_TOKEN_LIFETIME))).isTrue();
		assertThat(saved.getValue()).extracting("tokenHash").isEqualTo(TokenService.hash(issued.refreshToken()));
	}

	@Test
	void リフレッシュトークンは32バイトの乱数をBase64URLにしたもので毎回違う() {
		String first = this.tokens.issue(TestEntities.user(7, "taro_y")).refreshToken();
		String second = this.tokens.issue(TestEntities.user(7, "taro_y")).refreshToken();
		// 32 バイト → パディングなしの Base64 で 43 文字
		assertThat(first).matches("[A-Za-z0-9_-]{43}");
		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void ハッシュはSHA256の16進数() {
		assertThat(TokenService.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	private JwtDecoder decoderAt(Instant now) {
		return this.jwtConfig.jwtDecoder(PROPERTIES, clockAt(now));
	}

	private static Clock clockAt(Instant now) {
		return Clock.fixed(now, ZoneOffset.UTC);
	}

}
