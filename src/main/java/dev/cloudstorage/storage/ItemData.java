package dev.cloudstorage.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable, amount-normalized server item bytes. No Bukkit dependency in the ledger. */
public record ItemData(String key, String material, byte[] bytes) {
    public ItemData {
        bytes = bytes.clone();
        if (material == null || material.isBlank() || bytes.length == 0 || bytes.length > 1_048_576)
            throw new IllegalArgumentException("Invalid item data");
        if (!hash(bytes).equals(key)) throw new IllegalArgumentException("Invalid item digest");
    }

    public static ItemData of(String material, byte[] bytes) {
        return new ItemData(hash(bytes), material, bytes);
    }

    @Override public byte[] bytes() { return bytes.clone(); }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
