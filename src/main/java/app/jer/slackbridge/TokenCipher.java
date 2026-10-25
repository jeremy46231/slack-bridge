package app.jer.slackbridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts Slack user tokens before they're written to links.yml, so a copy of the plugin's data
 * folder alone can't post as anyone. The key comes from settings.slack-link.token-key or the
 * SLACK_TOKEN_KEY environment variable.
 */
public class TokenCipher {

  private static final String PREFIX = "v1:";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public TokenCipher(String secret) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(secret.getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(digest, "AES");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      this.random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      byte[] out = Arrays.copyOf(iv, IV_BYTES + ciphertext.length);
      System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** @return the token, or null if it wasn't encrypted with this key */
  public String decrypt(String stored) {
    if (stored == null || !stored.startsWith(PREFIX)) {
      return null;
    }
    try {
      byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, this.key,
          new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
      byte[] plaintext = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return null;
    }
  }
}
