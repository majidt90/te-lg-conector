package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM stand-in for Android's LruCache, used only by tools/verify.py.
 *
 * Same public API as the platform class (get/put/evictAll/size) with simple
 * insertion-order eviction - enough for the classes under test to run on a JVM.
 * Not part of the APK.
 */
public class LruCache<K, V> {

    private final int maxSize;
    private final Map<K, V> map = new LinkedHashMap<>(16, 0.75f, true);

    public LruCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized V put(K key, V value) {
        V previous = map.put(key, value);
        trim();
        return previous;
    }

    public synchronized V remove(K key) {
        return map.remove(key);
    }

    public synchronized void evictAll() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized int maxSize() {
        return maxSize;
    }

    protected int sizeOf(K key, V value) {
        return 1;
    }

    private void trim() {
        while (map.size() > maxSize) {
            K oldest = map.keySet().iterator().next();
            map.remove(oldest);
        }
    }
}
