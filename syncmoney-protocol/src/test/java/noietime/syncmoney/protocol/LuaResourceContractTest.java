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

    private static final Map<String, String> REVIEWED_SOURCE_SHA256 = Map.of(
            "atomic_add_balance.lua", "AF5A90006BC6C171A065375C8F63C3C8A46F415814A13975BA5661D960EE85B6",
            "atomic_audit.lua", "8B2F3BA884E22F1DDA45892D23EBD85B375C1A8E92B4C005F9143BB549AAFE2C",
            "atomic_bank_deposit.lua", "94305A7E3282505361179704FC8A9BB861F87C2D3D95EB3F9616D07BF28F4CC7",
            "atomic_bank_transfer.lua", "0C9F52074E652E3A753FDB0B30F08F093FC5532D5519BB96EDE6848816907F3E",
            "atomic_bank_withdraw.lua", "564EEEB99322FF4C2E55E630D759AB039CB7AFA236A7722AA04E56F588FC2139",
            "atomic_plugin_transfer.lua", "5F15559225B4FED2274A05F62BA5D2FB0ADE003AB51E47CD0280CBF91F870ECD",
            "atomic_set_balance.lua", "D010EA5EC441A10761042C1BA45B16ED66D83A51B662564E270FCD6C14DFFEFC",
            "atomic_transfer.lua", "F700C5EE9A5ABD361603E3A4FD0AA3EC78AFF007165D1A63D74A493888146E7E"
    );

    @Test
    void extractedLuaResourcesMatchTheReviewedOperationReceiptSourceBytes() throws IOException, NoSuchAlgorithmException {
        for (Map.Entry<String, String> expected : REVIEWED_SOURCE_SHA256.entrySet()) {
            try (InputStream input = getClass().getResourceAsStream("/lua/" + expected.getKey())) {
                assertNotNull(input, "Missing canonical Lua resource " + expected.getKey());
                String actual = HexFormat.of().withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                assertEquals(expected.getValue(), actual, expected.getKey());
            }
        }
    }
}
