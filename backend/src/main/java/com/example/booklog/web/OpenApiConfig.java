package com.example.booklog.web;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.booklog.common.ErrorCode;
import com.example.booklog.common.ErrorResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

// API の仕様書（OpenAPI。基本設計書 2.1）の全体に関わる設定。仕様書は springdoc-openapi がコントローラと record から作り、
// OpenApiExportTests が docs/api/openapi.yaml に書き出す。画面の型（frontend/src/api-schema.d.ts）もそこから作る
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static final String ACCESS_TOKEN = "accessToken";

	private static final String ERROR_SCHEMA = "ErrorResponse";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
			.info(new Info().title("BookLog API")
				.version("1")
				.description("読書記録アプリ「BookLog」の API。機能ごとの決まりは機能定義書（docs/functions）を見る。\n\n" + errorCodeTable()))
			// 画面と同じオリジンから呼ぶ（開発中は Vite が /api を 8080 に送る）
			.servers(List.of(new Server().url("/")))
			.components(new Components().addSecuritySchemes(ACCESS_TOKEN,
					new SecurityScheme().type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.COOKIE)
						.name("access_token")
						.description("ログイン（POST /api/auth/login）で受け取る JWT の Cookie（機能定義書 3.2）。"
								+ "GET 以外では、Cookie の XSRF-TOKEN の値を X-XSRF-TOKEN ヘッダーで送り返す（CSRF 対策）")))
			// ログインがいらない API（機能定義書 3.2）は、AuthController で個別に外す
			.addSecurityItem(new SecurityRequirement().addList(ACCESS_TOKEN));
	}

	// どの API も、エラーのときは ErrorResponse（機能定義書 3.3）を返す。
	// API ごとに返す code は機能定義書 9 章に書いてあるので、ここでは 4XX・5XX としてまとめて付ける
	@Bean
	OpenApiCustomizer errorResponses() {
		return (openApi) -> {
			// コントローラが返さない形なので、springdoc は作らない。OpenAPI 3.1 の形で、ほかの record と同じように作る
			ModelConverters converters = new ModelConverters(true);
			converters.addConverter(new RecordNullabilityConverter());
			converters.readAll(ErrorResponse.class)
				.forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
			Content content = new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
					new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA)));
			openApi.getPaths()
				.values()
				.forEach((path) -> path.readOperations()
					.forEach((operation) -> operation.getResponses()
						.addApiResponse("4XX",
								new ApiResponse().description("入力の誤り・ログインが必要・見つからないなど（code は API の説明のエラーの一覧）")
									.content(content))
						.addApiResponse("5XX", new ApiResponse().description("思いがけない失敗").content(content))));
		};
	}

	// 「code・HTTP ステータス・message」の表。ErrorCode から作るので、code を足すと仕様書にも出る
	private static String errorCodeTable() {
		return Arrays.stream(ErrorCode.values())
			.map((code) -> "| " + code.name() + " | " + code.status().value() + " | " + code.message() + " |")
			.collect(Collectors.joining("\n",
					"## エラーの一覧\n\nエラーのときは `{\"code\", \"message\", \"field\"}` を返す。"
							+ "message は画面にそのまま出せる文、field があればその入力欄の下に出す。\n\n"
							+ "| code | HTTP ステータス | message |\n| --- | --- | --- |\n",
					""));
	}

	@Bean
	RecordNullabilityConverter recordNullability() {
		return new RecordNullabilityConverter();
	}

	// record の項目が、必ず返るか・null になるかを仕様書に書く（画面の型で null を正しく扱えるように）。
	// - @Nullable のない項目は必ず値が入る → 必須
	// - @Nullable の項目は null のこともある → 必須で、型に null を足す
	// - @JsonInclude(NON_NULL) の項目は、null なら項目ごと返さない → 必須にしない
	// - @Schema を付けた項目（リクエストの項目）は、その注釈のとおりにする
	// 仕様書では record の名前が型の名前になる。別の record が同じ名前（EditRequest など）だと片方が消えるので、
	// conflicts() に残して OpenApiExportTests で止める（ここで例外にしても、springdoc は握りつぶして壊れた仕様書を返すため）
	static class RecordNullabilityConverter implements ModelConverter {

		private final Map<String, Class<?>> records = new ConcurrentHashMap<>();

		private final Set<String> conflicts = ConcurrentHashMap.newKeySet();

		@Override
		@SuppressWarnings({ "rawtypes", "unchecked" })
		public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context,
				Iterator<ModelConverter> chain) {
			if (!chain.hasNext()) {
				return null;
			}
			Schema resolved = chain.next().resolve(type, context, chain);
			Class<?> javaClass = Json.mapper().constructType(type.getType()).getRawClass();
			if (resolved == null || !javaClass.isRecord()) {
				return resolved;
			}
			Schema model = (resolved.get$ref() != null)
					? context.getDefinedModels().get(resolved.get$ref().replace("#/components/schemas/", ""))
					: resolved;
			if (model == null || model.getProperties() == null) {
				return resolved;
			}
			Class<?> sameName = this.records.putIfAbsent(model.getName(), javaClass);
			if (sameName != null && sameName != javaClass) {
				this.conflicts.add(model.getName() + "（" + sameName.getName() + " と " + javaClass.getName() + "）");
			}
			for (RecordComponent component : javaClass.getRecordComponents()) {
				Schema property = (Schema) model.getProperties().get(component.getName());
				if (property == null || hasSchemaAnnotation(component) || isOmittedWhenNull(component)) {
					continue;
				}
				if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
					property.addType("null");
				}
				if (model.getRequired() == null || !model.getRequired().contains(component.getName())) {
					model.addRequiredItem(component.getName());
				}
			}
			return resolved;
		}

		Set<String> conflicts() {
			return this.conflicts;
		}

		private static boolean hasSchemaAnnotation(RecordComponent component) {
			return component.getAccessor().isAnnotationPresent(io.swagger.v3.oas.annotations.media.Schema.class);
		}

		private static boolean isOmittedWhenNull(RecordComponent component) {
			JsonInclude include = component.getAccessor().getAnnotation(JsonInclude.class);
			return include != null && include.value() == JsonInclude.Include.NON_NULL;
		}

	}

}
