package com.example.booklog.common;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// API のエラーをすべて機能定義書 3.3 の形（ErrorResponse）で返す。
// Spring MVC の標準のエラー（JSON が読めない・URL がないなど）も、ResponseEntityExceptionHandler が受けたものを同じ形にする
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
		return ResponseEntity.status(ex.code().status()).body(ErrorResponse.of(ex.code(), ex.field()));
	}

	// 思いがけない失敗（データベースの障害など）。中身は利用者に見せず、ログに残す
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("API の処理に失敗しました", ex);
		return ResponseEntity.internalServerError().body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, null));
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		return super.handleExceptionInternal(ex, toErrorResponse(statusCode), headers, statusCode, request);
	}

	private static ErrorResponse toErrorResponse(HttpStatusCode statusCode) {
		if (statusCode.value() == 400) {
			return ErrorResponse.of(ErrorCode.BAD_REQUEST, null);
		}
		if (statusCode.value() == 404) {
			return ErrorResponse.of(ErrorCode.NOT_FOUND, null);
		}
		// 送られたファイルが multipart の上限（1 枚 5MB・1 回 21MB）を超えた（FN-04 4 章）
		if (statusCode.value() == 413) {
			return ErrorResponse.of(ErrorCode.PHOTO_TOO_LARGE, "photos");
		}
		if (statusCode.is5xxServerError()) {
			return ErrorResponse.of(ErrorCode.INTERNAL_ERROR, null);
		}
		// 405（メソッドが違う）・415（JSON でない）など。画面からは起きないので、code に HTTP ステータスの名前を入れる
		return new ErrorResponse(HttpStatus.valueOf(statusCode.value()).name(), ErrorCode.BAD_REQUEST.message(), null);
	}

}
