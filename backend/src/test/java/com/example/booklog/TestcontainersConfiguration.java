package com.example.booklog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

// API を通したテスト（@SpringBootTest）で使う外のサービスを Docker で起動し、接続先を自動で差し替える。
// 今は PostgreSQL だけ（DatabaseTestConfiguration）。写真（1-6）で S3 の代わりの MinIO を足す。
// 開発用の DB（compose.yaml）は使わない（Docker は動かしておく）。
// テストクラスどうしで Spring のコンテキストを使い回すので、コンテナは 1 回だけ起動する
@TestConfiguration(proxyBeanMethods = false)
@Import(DatabaseTestConfiguration.class)
public class TestcontainersConfiguration {

}
