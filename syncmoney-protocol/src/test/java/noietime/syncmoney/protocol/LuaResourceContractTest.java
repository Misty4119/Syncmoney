package noietime.syncmoney.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LuaResourceContractTest {

    private static final Map<String, String> BASELINE_SHA256 = Map.of(
            "atomic_add_balance.lua", "BC9B8AC2F5500E522B951995E9DF79FC9CAAA34AF534C2410B029880518810C9",
            "atomic_audit.lua", "1BEDB6DC9489768854656F4C5C89714C1001FC838EE3B41608E4582B3D647070",
            "atomic_bank_deposit.lua", "06C25533E6BDA116665A8845CAC0AE2D53458E60D3D3CDA99E4CCFC726F42C55",
            "atomic_bank_transfer.lua", "C731DECB67ADF3E00FAFD617EEBBB14DA48237B7FC65A6CE62DECFA788B6FABF",
            "atomic_bank_withdraw.lua", "F844D47C449083E357BBA34C9524EAB195CBD560D3069D4A5707C6AEB454F880",
            "atomic_plugin_transfer.lua", "2C41B42CBAE84E2E71EEEE74DC7C4A080C3032FEF5FCB6463894FB9DCA1F4639",
            "atomic_set_balance.lua", "33AEAE8200CEEB586367F927960968BDDA9DE08F8420CC7290B22431E62A706A",
            "atomic_transfer.lua", "22F16525EF1C7D275EBB66D237D0D247DC263280FFE520182E98E5930BF4B35C"
    );

    @Test
    void extractedLuaResourcesMatchThePostT06SourceBytes() throws IOException, NoSuchAlgorithmException {
        for (Map.Entry<String, String> expected : BASELINE_SHA256.entrySet()) {
            try (InputStream input = getClass().getResourceAsStream("/lua/" + expected.getKey())) {
                assertNotNull(input, "Missing canonical Lua resource " + expected.getKey());
                String actual = HexFormat.of().withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                assertEquals(expected.getValue(), actual, expected.getKey());
            }
        }
    }
}
