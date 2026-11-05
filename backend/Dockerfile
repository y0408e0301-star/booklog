# 公開先（ECS Fargate）で動かす API のイメージ（インフラ設計書 3.2）。
# jar は手元で ./mvnw package -DskipTests で作ってから入れる（画面は S3 から配るので with-frontend は付けない）。
# RUN を使わないので、x86 のパソコンでも docker buildx build --platform linux/arm64 でそのまま作れる
FROM amazoncorretto:21-al2023-headless@sha256:212faf5f8f93d8f4854a3d89528834d11ad2d685de80721d4d24ac2fdd3e379e

WORKDIR /app
COPY --chown=65532:65532 target/backend-0.0.1-SNAPSHOT.jar app.jar

# root では動かさない（ユーザーを作るには RUN が要るので、番号だけで指定する）
USER 65532:65532

# ヒープの上限はタスクのメモリ（1GB）の 75%
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
