package com.example.booklog.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// application.properties の app.storage.*（インフラ設計書 6 章）
class StoragePropertiesTests {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void バケットがなければ起動しない(String bucket) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new StorageProperties(bucket, "ap-northeast-1", "", "", ""))
			.withMessageContaining("S3_BUCKET");
	}

	@Test
	void エンドポイントが空ならAWSのS3() {
		assertThat(new StorageProperties("b", "r", null, null, null).hasEndpoint()).isFalse();
		assertThat(new StorageProperties("b", "r", " ", null, null).hasEndpoint()).isFalse();
		assertThat(new StorageProperties("b", "r", "http://localhost:9000", null, null).hasEndpoint()).isTrue();
	}

	@Test
	void 鍵は2つともあるときだけ使う() {
		assertThat(new StorageProperties("b", "r", null, "key", "secret").hasStaticKeys()).isTrue();
		assertThat(new StorageProperties("b", "r", null, "key", "").hasStaticKeys()).isFalse();
		assertThat(new StorageProperties("b", "r", null, "", "secret").hasStaticKeys()).isFalse();
		assertThat(new StorageProperties("b", "r", null, null, null).hasStaticKeys()).isFalse();
	}

}
