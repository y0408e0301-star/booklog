package com.example.booklog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

// テスト用の PostgreSQL を Docker で起動し、接続先を自動で差し替える（開発用の DB は使わない）。
// Repository のテスト（@DataJpaTest）はこれだけを使い、S3（MinIO）は起動しない
@TestConfiguration(proxyBeanMethods = false)
public class DatabaseTestConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// 開発用・本番と同じ版（compose.yaml）
		return new PostgreSQLContainer("postgres:17");
	}

}
