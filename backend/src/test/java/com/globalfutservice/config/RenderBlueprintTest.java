package com.globalfutservice.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment file never switches coin fulfilment on.
 *
 * <p>Sending orders to FUT Transfer spends real coins, and it is switched on by hand in the
 * Render dashboard after a supervised first order. A blueprint sync that turned it on would
 * skip that step, so the file is held to "false".
 */
class RenderBlueprintTest {

    @Test
    @DisplayName("render.yaml sets GFS_FUTTRANSFER_ENABLED to false, and only once")
    void fulfilmentOffInBlueprint() throws Exception {
        Path blueprint = Path.of("..", "render.yaml");
        assertThat(blueprint).exists();
        String yaml = Files.readString(blueprint);

        Matcher m = Pattern.compile("-\s*key:\s*GFS_FUTTRANSFER_ENABLED\s*\n\s*value:\s*\"?([^\"\s]+)\"?")
                .matcher(yaml.replace("\r\n", "\n"));
        assertThat(m.find()).as("GFS_FUTTRANSFER_ENABLED is declared in render.yaml").isTrue();
        assertThat(m.group(1)).isEqualTo("false");
        assertThat(m.find()).as("declared only once").isFalse();
    }
}
