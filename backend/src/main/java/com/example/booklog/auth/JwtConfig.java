package com.example.booklog.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

// JWT（アクセストークン）の作成と確認（機能定義書 3.2）。署名は HS256 で、秘密の値は app.auth.jwt-secret
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfig {

	// 期限切れのときに OAuth2Error に入れる code。JsonAuthenticationEntryPoint が見て TOKEN_EXPIRED を返す
	static final String TOKEN_EXPIRED_ERROR = "token_expired";

	@Bean
	JwtEncoder jwtEncoder(AuthProperties properties) {
		return NimbusJwtEncoder.withSecretKey(secretKey(properties)).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(AuthProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		decoder.setJwtValidator(expiryValidator(clock));
		return decoder;
	}

	// 期限（exp）を確かめる。Spring Security の標準の確認（JwtTimestampValidator）は期限切れと
	// ほかの誤りを同じ invalid_token にするため、期限切れを見分けられるよう自分で確かめる。
	// 標準と違い、時計のずれの猶予（60 秒）は設けない（署名も期限も、このサーバーだけで作って確かめるため）
	static OAuth2TokenValidator<Jwt> expiryValidator(Clock clock) {
		return (jwt) -> {
			Instant expiresAt = jwt.getExpiresAt();
			if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
				return OAuth2TokenValidatorResult
					.failure(new OAuth2Error(TOKEN_EXPIRED_ERROR, "アクセストークンの期限が切れています", null));
			}
			return OAuth2TokenValidatorResult.success();
		};
	}

	private static SecretKey secretKey(AuthProperties properties) {
		return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

}
