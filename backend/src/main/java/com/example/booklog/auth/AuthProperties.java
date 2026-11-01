package com.example.booklog.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties の app.auth.*
// jwtSecret：JWT（HS256）の署名に使う秘密の値。HS256 では 32 バイト以上が必要なので、短ければ起動しない
// secureCookie：Cookie に Secure を付けるか（公開先の HTTPS では true）
@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, boolean secureCookie) {

	private static final int MIN_SECRET_BYTES = 32;

	public AuthProperties {
		if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException("app.auth.jwt-secret（環境変数 JWT_SECRET）は 32 バイト以上にしてください");
		}
	}

}
