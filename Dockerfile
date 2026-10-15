# syntax=docker/dockerfile:1
# 台股投資管理系統（stock-ledger）
# 兩階段建置：第一階段用 JDK 編譯出 jar，第二階段只放 JRE + jar，映像檔比較小也比較安全。

# ---------- 第一階段：編譯 ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 先只複製 Maven 設定並下載相依套件；pom.xml 沒變時這一層會被快取，重建快很多
COPY .mvn .mvn
COPY mvnw pom.xml ./
# 在 Windows 編輯過的 mvnw 可能是 CRLF 換行，Linux 會執行失敗，先轉成 LF
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# 單元測試在開發時另外跑（./mvnw test），打包映像檔時略過以加快速度
RUN ./mvnw -B -q package -DskipTests

# ---------- 第二階段：執行 ----------
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Taipei

# 不用 root 執行，降低被入侵時的影響範圍
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Taipei", "-jar", "/app/app.jar"]
