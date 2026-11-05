package com.example.booklog.user;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.booklog.common.ErrorCode;

// 自己紹介の条件（FN-11 PF-7・4 章、TC-11-3）
class BiosTests {

	@Test
	void 改行をLFにそろえて前後の空白を取り除く() {
		assertThat(Bios.valid(" 一行目\r\n二行目\r三行目\n　")).isEqualTo("一行目\n二行目\n三行目");
	}

	@Test
	void 空でもよい() {
		assertThat(Bios.valid("")).isEmpty();
		assertThat(Bios.valid(" \n ")).isEmpty();
	}

	@Test
	void 百六十文字までで文字数はコードポイントで数える() {
		assertThat(Bios.valid("あ".repeat(160))).hasSize(160);
		assertThat(Bios.valid("😀".repeat(160))).hasSize(320);
		assertApiError(() -> Bios.valid("あ".repeat(161)), ErrorCode.BAD_REQUEST, "bio");
	}

	@Test
	void 項目がなければBAD_REQUEST() {
		assertApiError(() -> Bios.valid(null), ErrorCode.BAD_REQUEST, "bio");
	}

}
