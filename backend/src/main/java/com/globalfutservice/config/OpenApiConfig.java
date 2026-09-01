package com.globalfutservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gfsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Global FUT Services API")
                        .version("1.0.0")
                        .description("""
                                Storefront and fulfilment API for a trading-service business.

                                Two rules govern every endpoint here:

                                1. The client sends intent, never a price. Quotes are minted
                                   and signed server-side; a tampered or expired quote cannot
                                   become an order.
                                2. Payment state comes from the gateway webhook, never from a
                                   browser redirect. The redirect decides what the customer
                                   sees; the webhook decides what the ledger says.
                                """)
                        .contact(new Contact().name("Global FUT Services")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
