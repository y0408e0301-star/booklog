package com.example.booklog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling：期限切れのリフレッシュトークン（RefreshTokenCleanup）と、古い書籍検索の結果・呼び出し回数
// （BookSearchCleanup）を 1 日 1 回消し、S3 から消せなかった写真（StorageDeleter）を 1 日 1 回消し直す
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
