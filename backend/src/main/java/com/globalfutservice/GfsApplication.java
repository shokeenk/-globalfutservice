package com.globalfutservice;

import com.globalfutservice.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Global FUT Services — storefront and fulfilment API.
 *
 * <p>A modular monolith. Each package under this one is a bounded context
 * ({@code catalog}, {@code pricing}, {@code orders}, {@code payments},
 * {@code credentials}, {@code loyalty}, {@code affiliate}, {@code identity},
 * {@code notify}) and they talk through service interfaces rather than reaching into
 * each other's entities. That gives the seams of a service architecture with none of
 * its operational cost, and leaves {@code credentials} in particular ready to be lifted
 * out behind its own boundary if it ever needs to be.
 *
 * <p>The {@code domain} package underneath is deliberately framework-free: no Spring, no
 * JPA, no Jackson. All the arithmetic that decides what a customer is charged lives
 * there and can be verified with nothing but a JDK — see {@code ./verify-domain.sh}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
@EnableAsync
public class GfsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GfsApplication.class, args);
    }
}
