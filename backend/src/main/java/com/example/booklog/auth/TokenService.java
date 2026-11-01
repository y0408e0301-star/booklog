package com.example.booklog.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.booklog.user.User;

// アクセストークン（JWT）とリフレッシュトークンを作る（機能定義書 3.2）
@Service
class TokenService {

	static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);

	static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(14);

	private static final int REFRESH_TOKEN_BYTES = 32;

	private final JwtEncoder jwtEncoder;

	private final RefreshTokenRepository refreshTokens;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	TokenService(JwtEncoder jwtEncoder, RefreshTokenRepository refreshTokens, Clock clock) {
		this.jwtEncoder = jwtEncoder;
		this.refreshTokens = refreshTokens;
		this.clock = clock;
	}

	// 登録・ログイン・取り直しのときに、2 つのトークンを新しく作る。リフレッシュトークンはハッシュを保存する
	IssuedTokens issue(User user) {
		Instant now = this.clock.instant();
		String refreshToken = newRefreshToken();
		this.refreshTokens
			.save(new RefreshToken(user.getId(), hash(refreshToken), now.plus(REFRESH_TOKEN_LIFETIME), now));
		return new IssuedTokens(accessToken(user, now), refreshToken);
	}

	// JWT の中身は誰でも読めるため、ユーザーの ID とユーザー ID だけを入れる（FN-01 5 章 No.5）
	private String accessToken(User user, Instant now) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(String.valueOf(user.getId()))
			.claim("username", user.getUsername())
			.issuedAt(now)
			.expiresAt(now.plus(ACCESS_TOKEN_LIFETIME))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return this.jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	// 推測できない 32 バイトの乱数を、Cookie に入れられる文字（Base64URL）にしたもの
	private String newRefreshToken() {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// データベースにはトークンそのものではなく SHA-256 の 16 進数を保存する（データベースが漏れても使えないように）
	static String hash(String refreshToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 が使えません", ex);
		}
	}

	record IssuedTokens(String accessToken, String refreshToken) {
	}

}
