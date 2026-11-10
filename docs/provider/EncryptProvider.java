import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypt a provider JSON file with a passphrase entered interactively. */
public class EncryptProvider {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java EncryptProvider.java input.json output.8dp");
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Run from an interactive terminal");
        byte[] plain = Files.readAllBytes(Path.of(args[0]));
        if (plain.length == 0 || plain.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Invalid input size");
        char[] passphrase = console.readPassword("File passphrase: ");
        if (passphrase == null || passphrase.length == 0) throw new IllegalArgumentException("Empty passphrase");
        char[] confirm = console.readPassword("Confirm passphrase: ");
        if (!Arrays.equals(passphrase, confirm)) {
            Arrays.fill(passphrase, '\0');
            if (confirm != null) Arrays.fill(confirm, '\0');
            throw new IllegalArgumentException("Passwords do not match");
        }
        Arrays.fill(confirm, '\0');
        byte[] header = new byte[36];
        byte[] magic = {0x38, 0x44, 0x50, 0x52, 0x4f, 0x56, 0x01, 0x00};
        System.arraycopy(magic, 0, header, 0, magic.length);
        byte[] random = new byte[28];
        new SecureRandom().nextBytes(random);
        System.arraycopy(random, 0, header, 8, random.length);
        byte[] salt = Arrays.copyOfRange(header, 8, 24);
        byte[] nonce = Arrays.copyOfRange(header, 24, 36);
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, 210000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); Arrays.fill(passphrase, '\0'); }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(plain);
            byte[] output = Arrays.copyOf(header, header.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, output, header.length, ciphertext.length);
            Files.write(Path.of(args[1]), output, StandardOpenOption.CREATE_NEW);
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(plain, (byte) 0); }
        System.out.println("Encrypted provider file created.");
    }
}
