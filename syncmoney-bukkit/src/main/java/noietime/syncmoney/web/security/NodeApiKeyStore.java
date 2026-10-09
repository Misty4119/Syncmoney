package noietime.syncmoney.web.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Secure storage for node API keys using AES-256-GCM encryption.
 *
 * Uses PBKDF2 with SHA-256 to derive a 256-bit key from the master key.
 * Each encryption generates a random 12-byte IV for security.
 * Encrypted values are prefixed with "enc:" to identify them.
 *
 * Format: "enc:<Base64(iv):<Base64(ciphertext):<Base64(tag>>"
 */
public class NodeApiKeyStore {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final String ENCRYPTED_PREFIX = "enc:";

    /**
     * Encrypt plaintext using AES-256-GCM.
     *
     * @param plainText The plaintext to encrypt
     * @param masterKey The master key used to derive the encryption key
     * @return Encrypted string with prefix "enc:", or original string if encryption fails
     */
    public String encrypt(String plainText, String masterKey) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }

        if (plainText.startsWith(ENCRYPTED_PREFIX)) {
            return plainText;
        }

        try {
            SecretKey key = deriveKey(masterKey);

            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            String encoded = Base64.getEncoder().encodeToString(byteBuffer.array());
            return ENCRYPTED_PREFIX + encoded;

        } catch (Exception e) {
            return plainText;
        }
    }

    /**
     * Decrypt ciphertext using AES-256-GCM.
     *
     * @param cipherText The encrypted text (with "enc:" prefix)
     * @param masterKey The master key used to derive the decryption key
     * @return Decrypted plaintext, or original string if decryption fails
     */
    public String decrypt(String cipherText, String masterKey) {
        if (cipherText == null || cipherText.isBlank()) {
            return cipherText;
        }

        if (!cipherText.startsWith(ENCRYPTED_PREFIX)) {
            return cipherText;
        }

        try {
            String encoded = cipherText.substring(ENCRYPTED_PREFIX.length());
            byte[] decoded = Base64.getDecoder().decode(encoded);

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] encrypted = new byte[byteBuffer.remaining()];
            byteBuffer.get(encrypted);

            SecretKey key = deriveKey(masterKey);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return cipherText;
        }
    }

    /**
     * Derive a 256-bit AES key from the master key using PBKDF2.
     *
     * @param masterKey The master key
     * @return Derived SecretKey
     */
    private SecretKey deriveKey(String masterKey) throws Exception {
        byte[] salt = "SyncmoneyNodeKeyStore".getBytes(StandardCharsets.UTF_8);

        PBEKeySpec spec = new PBEKeySpec(
                masterKey.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_LENGTH
        );

        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();

        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Check if a string is encrypted.
     *
     * @param text The text to check
     * @return true if the text has the encrypted prefix
     */
    public boolean isEncrypted(String text) {
        return text != null && text.startsWith(ENCRYPTED_PREFIX);
    }

    /**
     * Remove encryption prefix from a string.
     *
     * @param text The encrypted text
     * @return The text without encryption prefix
     */
    public String removePrefix(String text) {
        if (text != null && text.startsWith(ENCRYPTED_PREFIX)) {
            return text.substring(ENCRYPTED_PREFIX.length());
        }
        return text;
    }

    /**
     * Add encryption prefix to a string.
     *
     * @param text The plaintext
     * @return The text with encryption prefix
     */
    public String addPrefix(String text) {
        if (text != null && !text.startsWith(ENCRYPTED_PREFIX)) {
            return ENCRYPTED_PREFIX + text;
        }
        return text;
    }
}
