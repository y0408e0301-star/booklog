package com.example.booklog.storage;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties の app.storage.*（写真の置き場所。インフラ設計書 6 章）
// bucket・region：S3 のバケットとリージョン
// endpoint：S3 以外（テストの MinIO）につなぐときだけ指定する。空なら AWS の S3
// accessKey・secretKey：空なら AWS の標準の探し方（開発中は ~/.aws/credentials、公開先は ECS のタスクロール）で鍵を探す
@ConfigurationProperties("app.storage")
public record StorageProperties(String bucket, String region, @Nullable String endpoint, @Nullable String accessKey,
		@Nullable String secretKey) {

	public StorageProperties {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("app.storage.bucket（環境変数 S3_BUCKET）を指定してください");
		}
	}

	boolean hasEndpoint() {
		return this.endpoint != null && !this.endpoint.isBlank();
	}

	boolean hasStaticKeys() {
		return this.accessKey != null && !this.accessKey.isBlank() && this.secretKey != null
				&& !this.secretKey.isBlank();
	}

}
