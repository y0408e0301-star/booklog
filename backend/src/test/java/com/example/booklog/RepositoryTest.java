package com.example.booklog;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

// Repository のテスト。JPA と Flyway だけを動かし（Controller・Service・S3 は動かさない）、
// テスト用の PostgreSQL（DatabaseTestConfiguration）にマイグレーションを流してから確かめる。
// テストごとにトランザクションを巻き戻すので、テストどうしでデータは残らない
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@Import(DatabaseTestConfiguration.class)
public @interface RepositoryTest {

}
