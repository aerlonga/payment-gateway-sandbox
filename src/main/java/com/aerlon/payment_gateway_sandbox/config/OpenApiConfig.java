package com.aerlon.payment_gateway_sandbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentGatewayOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Gateway Sandbox API")
                .version("v1")
                .description("API de pagamentos (sandbox): criacao idempotente, consulta, cancelamento, estorno e historico de eventos."));
    }
}
