package noietime.syncmoney.web.server;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebAdminConfigCorsTest {

    @Test
    void readsAnOriginListAndTrimsEntries() throws Exception {
        WebAdminConfig config = load("""
                web-admin:
                  security:
                    cors-allowed-origins:
                      - https://admin.example
                      - ' https://ops.example '
                """);

        assertEquals(List.of("https://admin.example", "https://ops.example"),
                config.getCorsAllowedOrigins());
    }

    @Test
    void splitsScalarOriginsOnCommas() throws Exception {
        WebAdminConfig config = load("""
                web-admin:
                  security:
                    cors-allowed-origins: 'https://admin.example, https://ops.example'
                """);

        assertEquals(List.of("https://admin.example", "https://ops.example"),
                config.getCorsAllowedOrigins());
    }

    @Test
    void missingOrEmptyOriginsDenyCrossOriginAccess() throws Exception {
        assertEquals(List.of(), load("web-admin: {}\n").getCorsAllowedOrigins());
        assertEquals(List.of(), load("""
                web-admin:
                  security:
                    cors-allowed-origins: []
                """).getCorsAllowedOrigins());
    }

    @Test
    void wildcardMustBeExplicitlyConfigured() throws Exception {
        WebAdminConfig config = load("""
                web-admin:
                  security:
                    cors-allowed-origins: '*'
                """);

        assertEquals(List.of("*"), config.getCorsAllowedOrigins());
    }

    private static WebAdminConfig load(String yaml) throws Exception {
        YamlConfiguration fileConfig = new YamlConfiguration();
        fileConfig.loadFromString(yaml);
        WebAdminConfig config = new WebAdminConfig();
        config.load(fileConfig);
        return config;
    }
}
