package com.globalfutservice.config;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client's decisions, as application.yml ships them when Render sets none of the
 * variables: all three backup codes required; automatic sending to FUT Transfer off until it
 * is switched on, and then for every paid coin order with no amount limit; FUT Transfer's own
 * progress page not linked until its address is set. Read from the real file, so changing a
 * default there fails here.
 */
class ShippedDecisionsTest {

    private static Binder shipped() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        // The file alone: whatever this machine's environment holds is not what Render starts with.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        // The default document; the prod profile's does not touch these.
        env.getPropertySources().addLast(documents.get(0));
        return Binder.get(env);
    }

    @Test
    @DisplayName("all three backup codes are required")
    void backupCodes() throws Exception {
        assertThat(shipped().bind("gfs.fulfilment.backup-codes-required", Integer.class).get()).isEqualTo(3);
    }

    @Test
    @DisplayName("automatic sending: off until GFS_FUTTRANSFER_AUTO_DISPATCH=true, then every paid coin order, no amount limit")
    void autoDispatch() throws Exception {
        AppProperties.FutTransferAutoDispatch auto = shipped()
                .bind("gfs.fut-transfer.auto-dispatch", AppProperties.FutTransferAutoDispatch.class).get();
        assertThat(auto.enabled()).isFalse();
        assertThat(auto.maxK()).isNull();
        assertThat(auto.allows(100_000)).isTrue();
    }

    @Test
    @DisplayName("FUT Transfer's own progress page: not linked until its address is set")
    void progressPage() throws Exception {
        assertThat(shipped().bind("gfs.fut-transfer.progress-page-url", String.class).orElse("")).isEmpty();
    }
}
