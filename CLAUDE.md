# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

CravBank is a Spring Boot web service that integrates with the [EnableBanking API](https://enablebanking.com/) to retrieve banking account information.

## Build & Run Commands
JAVA : C:\Users\celin\.jdks\temurin-25.0.2
```bash
# Run the application
./gradlew bootRun

# Build (produces JAR in build/libs/)
./gradlew clean build

# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.cravero.cravbank.CravbankApplicationTests"

# Build without running tests
./gradlew build -x test
```

## Stack

- **Java 25**, **Spring Boot 4.0.5**, **Gradle 9.4.1** (Kotlin DSL)
- **SpringDoc OpenAPI 3.0** — Swagger UI auto-generated at `http://localhost:8080/swagger-ui.html`
- **JUnit 5** for tests

## Architecture

The project is in early scaffold state. The intended architecture is:

- `com.cravero.cravbank` — root package, contains `CravbankApplication`
- Controllers (REST endpoints) expose banking data fetched from EnableBanking
- Services handle EnableBanking API communication (OAuth2 flow + account data retrieval)
- Configuration in `application.properties` (EnableBanking client credentials, redirect URIs, etc.)

EnableBanking uses an OAuth2 authorization code flow — the typical integration involves:
1. Redirecting the user to EnableBanking's authorization URL
2. Handling the callback with the auth code
3. Exchanging it for an access token
4. Using the token to call EnableBanking's REST API for accounts/transactions