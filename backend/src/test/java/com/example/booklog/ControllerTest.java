package com.example.booklog;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import com.example.booklog.auth.SecurityTestConfiguration;

// Controller のテスト。value の Controller と、エラーの形（ApiExceptionHandler）・ログインの確かめ方（SecurityConfig）だけを動かす。
// Service は @MockitoBean で偽物にするので、データベースと S3 は使わない（Docker も要らない）
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@Import({ SecurityTestConfiguration.class, MutableClock.Config.class })
public @interface ControllerTest {

	@AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
	Class<?>[] value() default {};

}
