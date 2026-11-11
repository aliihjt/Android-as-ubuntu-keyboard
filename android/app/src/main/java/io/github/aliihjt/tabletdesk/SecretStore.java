package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small secrets (the laptop's unlock PIN) kept encrypted with a key that never
 * leaves the tablet's Android Keystore, so they are not readable from a backup
 * or a copy of the app's files.
 */
final class SecretStore {
    private static final String KEY = "fedorapad-secrets";
    private static final String PREFIX = "secret_";

    private SecretStore() {
    }

    static void put(Context c, String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
            prefs(c).edit().putString(PREFIX + name, packed).apply();
        } catch (Exception e) {  // no Keystore: don't keep the secret at all
            remove(c, name);
        }
    }

    /** The secret, or "" when none is saved or it can't be read any more. */
    static String get(Context c, String name) {
        String packed = prefs(c).getString(PREFIX + name, "");
        int cut = packed.indexOf(':');
        if (cut < 0) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(packed.substring(0, cut), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(packed.substring(cut + 1), Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            remove(c, name);
            return "";
        }
    }

    static void remove(Context c, String name) {
        prefs(c).edit().remove(PREFIX + name).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
