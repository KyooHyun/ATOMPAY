package com.atompay.cardpaycore.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "AtomPay API",
        version = "1.0",
        description = "카드 결제 처리 코어 — 동시성 · 멱등성 · 상태 머신"
))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "POST /api/v1/auth/login 으로 발급받은 JWT 토큰을 입력하세요."
)
public class OpenApiConfig {
}
