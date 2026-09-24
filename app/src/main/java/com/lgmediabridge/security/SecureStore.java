package com.lgmediabridge.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.lgmediabridge.core.LogBus;

import java.nio.charset.Charset;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES/GCM encryption backed by a non-exportable Android Keystore key.
 *
 * Used for the webOS pairing keys: those tokens allow full remote control of the
 * TV, so they are stored encrypted at rest and excluded from backup
 * (see res/xml/data_extraction_rules.xml) instead of living in plain
 * SharedPreferences.
 */
public final class SecureStore {

    private static final String TAG = "SecureStore";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "mediabridge_device_secrets";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final Context context;
    private SecretKey key;

    public SecureStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean isAvailable() {
        return key() != null;
    }

    /** Returns a Base64 envelope, or null when encryption is unavailable. */
    public synchronized String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        SecretKey secret = key();
        if (secret == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secret);
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plainText.getBytes(Charset.forName("UTF-8")));
            byte[] envelope = new byte[1 + iv.length + cipherText.length];
            envelope[0] = (byte) iv.length;
            System.arraycopy(iv, 0, envelope, 1, iv.length);
            System.arraycopy(cipherText, 0, envelope, 1 + iv.length, cipherText.length);
            return Base64.encodeToString(envelope, Base64.NO_WRAP);
        } catch (Exception e) {
            LogBus.get().e(TAG, "encrypt failed", e);
            return null;
        }
    }

    public synchronized String decrypt(String envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return null;
        }
        SecretKey secret = key();
        if (secret == null) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(envelope, Base64.NO_WRAP);
            int ivLength = raw[0] & 0xFF;
            if (ivLength <= 0 || ivLength >= raw.length) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secret,
                    new GCMParameterSpec(GCM_TAG_BITS, raw, 1, ivLength));
            byte[] plain = cipher.doFinal(raw, 1 + ivLength, raw.length - 1 - ivLength);
            return new String(plain, Charset.forName("UTF-8"));
        } catch (Exception e) {
            // A key that was cleared (app data reset, keystore reset) simply means
            // "pair again" - it is not an error worth surfacing as a failure.
            LogBus.get().w(TAG, "stored secret could not be decrypted; re-pair required");
            return null;
        }
    }

    private SecretKey key() {
        if (key != null) {
            return key;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) {
                java.security.Key existing = keyStore.getKey(ALIAS, null);
                if (existing instanceof SecretKey) {
                    key = (SecretKey) existing;
                    return key;
                }
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            key = generator.generateKey();
            return key;
        } catch (Exception e) {
            LogBus.get().e(TAG, "keystore unavailable", e);
            return null;
        }
    }
}
