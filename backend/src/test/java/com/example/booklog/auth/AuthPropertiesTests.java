package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

// JWT の秘密の値は 32 バイト以上（HS256）。短ければ起動しない
class AuthPropertiesTests {

	@Test
	void 三十二バイトなら使える() {
		assertThatNoException().isThrownBy(() -> new AuthProperties("a".repeat(32), false));
	}

	@Test
	void 三十一バイトやnullなら起動しない() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AuthProperties("a".repeat(31), false))
			.withMessageContaining("JWT_SECRET");
		assertThatIllegalArgumentException().isThrownBy(() -> new AuthProperties(null, false));
	}

	@Test
	void 文字数ではなくUTF8のバイト数で数える() {
		// 「あ」は UTF-8 で 3 バイト。11 文字で 33 バイト
		assertThatNoException().isThrownBy(() -> new AuthProperties("あ".repeat(11), false));
		assertThatIllegalArgumentException().isThrownBy(() -> new AuthProperties("あ".repeat(10), false));
	}

}
