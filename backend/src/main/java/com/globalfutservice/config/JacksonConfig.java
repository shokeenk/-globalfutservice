package com.globalfutservice.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                // Dates as ISO-8601 strings, not epoch arrays. The frontend parses these
                // directly and a numeric timestamp array is a support ticket waiting to
                // happen.
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Reject unknown properties on the way in. Silently ignoring a field the
                // client thought it was sending is how a "discount" parameter appears to
                // work for a year before anyone notices it never applied.
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
