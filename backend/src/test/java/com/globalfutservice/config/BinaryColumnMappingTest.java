package com.globalfutservice.config;

import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Lob} on a {@code byte[]} is a production outage on PostgreSQL.
 *
 * <p>It reads like a size hint and is not one: it selects the large-object type, so
 * Hibernate maps the field to an {@code oid} and expects an {@code oid} column. Every
 * binary column in this schema is {@code BYTEA}. With {@code ddl-auto: validate} the
 * mismatch is caught at startup, which sounds safe and is not -- the container fails its
 * health check, the platform keeps the previous build running, and the deployment looks
 * like it simply never arrived. Nothing in the application logs an error a caller can
 * see, and every endpoint keeps answering from the old code.
 *
 * <p>That is exactly how it happened: the payment-proof entity shipped with {@code @Lob},
 * the API silently kept serving the build before it, and the only visible symptom was one
 * new endpoint returning 401 as though it had never been deployed.
 *
 * <p>There is no {@code @SpringBootTest} here to catch it -- there is no test database --
 * so this walks the entity classes directly. It is a cheap guard against an expensive and
 * very quiet failure.
 */
class BinaryColumnMappingTest {

    @Test
    @DisplayName("no entity annotates a byte[] with @Lob")
    void noLobOnByteArrays() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            for (Field field : entity.getDeclaredFields()) {
                if (field.getType() == byte[].class && field.isAnnotationPresent(Lob.class)) {
                    offenders.add(entity.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(offenders)
                .as("@Lob on a byte[] maps to oid, but every binary column here is BYTEA — "
                        + "the application will refuse to start. Drop the annotation; plain "
                        + "byte[] is the mapping CredentialVaultEntity already uses.")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan actually finds entities, so an empty pass means something")
    void scanFindsEntities() {
        // Without this, a package rename would turn the test above into a permanent pass
        // over an empty list -- a guard that cannot fail is not a guard.
        assertThat(entityClasses()).hasSizeGreaterThan(5);
    }

    private static List<Class<?>> entityClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<BeanDefinition> found = scanner.findCandidateComponents("com.globalfutservice");

        List<Class<?>> classes = new ArrayList<>(found.size());
        for (BeanDefinition definition : found) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException impossible) {
                throw new AssertionError("Scanner found a class it cannot load", impossible);
            }
        }
        return classes;
    }
}
