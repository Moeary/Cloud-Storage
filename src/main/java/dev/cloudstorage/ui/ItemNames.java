package dev.cloudstorage.ui;

import dev.cloudstorage.storage.ItemData;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

public final class ItemNames {
    private final Properties chinese=new Properties();
    public ItemNames() {
        try (InputStream stream=Objects.requireNonNull(ItemNames.class.getResourceAsStream("/names/zh_cn.properties"))) {
            chinese.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot load item names",e); }
    }
    public String chinese(String material) { return chinese.getProperty(material,material.toLowerCase(Locale.ROOT)); }
    private String alias(String material) { return normalize(chinese(material)).replace("石头","石"); }
    public boolean exactChinese(String material,String search) {
        String query=normalize(search);
        return normalize(chinese(material)).equals(query) || alias(material).equals(query);
    }
    public boolean matches(ItemData item,String search) {
        String query=normalize(search).replace("minecraft:","");
        return query.isEmpty() || normalize(item.material()).contains(query)
                || normalize(chinese(item.material())).contains(query) || alias(item.material()).contains(query) || item.key().startsWith(query);
    }
    public static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('_',' ').strip();
    }
}
