// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.cache;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.jcs3.JCS;
import org.apache.commons.jcs3.access.CacheAccess;
import org.apache.commons.jcs3.auxiliary.AuxiliaryCache;
import org.apache.commons.jcs3.auxiliary.AuxiliaryCacheFactory;
import org.apache.commons.jcs3.auxiliary.disk.behavior.IDiskCacheAttributes;
import org.apache.commons.jcs3.auxiliary.disk.block.BlockDiskCacheAttributes;
import org.apache.commons.jcs3.auxiliary.disk.block.BlockDiskCacheFactory;
import org.apache.commons.jcs3.auxiliary.disk.indexed.IndexedDiskCacheAttributes;
import org.apache.commons.jcs3.auxiliary.disk.indexed.IndexedDiskCacheFactory;
import org.apache.commons.jcs3.engine.CompositeCacheAttributes;
import org.apache.commons.jcs3.engine.behavior.ICompositeCacheAttributes.DiskUsagePattern;
import org.apache.commons.jcs3.engine.control.CompositeCache;
import org.apache.commons.jcs3.utils.serialization.StandardSerializer;
import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Wrapper class for JCS Cache. Sets some sane environment and returns instances of cache objects.
 * Static configuration for now assumes some small LRU cache in memory and larger LRU cache on disk
 *
 * @author Wiktor Niesiobędzki
 * @since 8168
 */
public final class JCSCacheManager {
    private static final long MAX_OBJECT_TTL = -1;
    private static final String PREFERENCE_PREFIX = "jcs.cache";

    /**
     * Property that determines the disk cache implementation
     */
    public static final BooleanProperty USE_BLOCK_CACHE = new BooleanProperty(PREFERENCE_PREFIX + ".use_block_cache", true);

    private static final AuxiliaryCacheFactory DISK_CACHE_FACTORY = getDiskCacheFactory();
    private static FileLock cacheDirLock;

    /**
     * default objects to be held in memory by JCS caches (per region)
     */
    public static final IntegerProperty DEFAULT_MAX_OBJECTS_IN_MEMORY = new IntegerProperty(PREFERENCE_PREFIX + ".max_objects_in_memory", 1000);

    private static final Logger jcsLog;

    static {
        // raising logging level gives ~500x performance gain
        // http://westsworld.dk/blog/2008/01/jcs-and-performance/
        jcsLog = Logger.getLogger("org.apache.commons.jcs3");
        try {
            jcsLog.setLevel(Level.INFO);
            jcsLog.setUseParentHandlers(false);
            // we need a separate handler from Main's, as we downgrade LEVEL.INFO to DEBUG level
            Arrays.stream(jcsLog.getHandlers()).forEach(jcsLog::removeHandler);
            jcsLog.addHandler(new Handler() {
                final SimpleFormatter formatter = new SimpleFormatter();

                @Override
                public void publish(LogRecord record) {
                    String msg = formatter.formatMessage(record);
                    if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                        Logging.error(msg);
                    } else if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        Logging.warn(msg);
                        // downgrade INFO level to debug, as JCS is too verbose at INFO level
                    } else if (record.getLevel().intValue() >= Level.INFO.intValue()) {
                        Logging.debug(msg);
                    } else {
                        Logging.trace(msg);
                    }
                }

                @Override
                public void flush() {
                    // nothing to be done on flush
                }

                @Override
                public void close() {
                    // nothing to be done on close
                }
            });
        } catch (Exception e) {
            Logging.log(Logging.LEVEL_ERROR, "Unable to configure JCS logs", e);
        }
    }

    private JCSCacheManager() {
        // Hide implicit public constructor for utility classes
    }

    static {
        File cacheDir = new File(Config.getDirs().getCacheDirectory(true), "jcs");

        try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Logging.warn("Cache directory " + cacheDir.toString() + " does not exists and could not create it");
            } else {
                File cacheDirLockPath = new File(cacheDir, ".lock");
                try {
                    if (!cacheDirLockPath.exists() && !cacheDirLockPath.createNewFile()) {
                        Logging.warn("Cannot create cache dir lock file");
                    }
                    cacheDirLock = FileChannel.open(cacheDirLockPath.toPath(), StandardOpenOption.WRITE).tryLock();

                    if (cacheDirLock == null)
                        Logging.warn("Cannot lock cache directory. Will not use disk cache");
                } catch (IOException e) {
                    Logging.log(Logging.LEVEL_WARN, "Cannot create cache dir \"" + cacheDirLockPath + "\" lock file:", e);
                    Logging.warn("Will not use disk cache");
                }
            }
        } catch (Exception e) {
            Logging.log(Logging.LEVEL_WARN, "Unable to configure disk cache. Will not use it", e);
        }

        // this could be moved to external file
        Properties props = new Properties();
        // these are default common to all cache regions
        // use of auxiliary cache and sizing of the caches is done with giving proper getCache(...) params
        // CHECKSTYLE.OFF: SingleSpaceSeparator
        props.setProperty("jcs.default.cacheattributes",                      CompositeCacheAttributes.class.getCanonicalName());
        props.setProperty("jcs.default.cacheattributes.MaxObjects",           DEFAULT_MAX_OBJECTS_IN_MEMORY.get().toString());
        props.setProperty("jcs.default.cacheattributes.UseMemoryShrinker",    "true");
        props.setProperty("jcs.default.cacheattributes.DiskUsagePatternName", "UPDATE"); // store elements on disk on put
        props.setProperty("jcs.default.elementattributes",                    CacheEntryAttributes.class.getCanonicalName());
        props.setProperty("jcs.default.elementattributes.IsEternal",          "false");
        props.setProperty("jcs.default.elementattributes.MaxLife",            Long.toString(MAX_OBJECT_TTL));
        props.setProperty("jcs.default.elementattributes.IdleTime",           Long.toString(MAX_OBJECT_TTL));
        props.setProperty("jcs.default.elementattributes.IsSpool",            "true");
        // CHECKSTYLE.ON: SingleSpaceSeparator
        try {
            JCS.setConfigProperties(props);
        } catch (Exception e) {
            Logging.log(Logging.LEVEL_WARN, "Unable to initialize JCS", e);
        }
    }

    private static AuxiliaryCacheFactory getDiskCacheFactory() {
        try {
            return useBlockCache() ? new BlockDiskCacheFactory() : new IndexedDiskCacheFactory();
        } catch (SecurityException | LinkageError e) {
            Logging.error(e);
            return null;
        }
    }

    private static boolean useBlockCache() {
        return Boolean.TRUE.equals(USE_BLOCK_CACHE.get());
    }

    /**
     * Returns configured cache object for named cache region
     * @param <K> key type
     * @param <V> value type
     * @param cacheName region name
     * @return cache access object
     */
    public static <K, V> CacheAccess<K, V> getCache(String cacheName) {
        return getCache(cacheName, DEFAULT_MAX_OBJECTS_IN_MEMORY.get().intValue(), 0, null);
    }

    /**
     * Returns configured cache object with defined limits of memory cache and disk cache
     * @param <K> key type
     * @param <V> value type
     * @param cacheName         region name
     * @param maxMemoryObjects  number of objects to keep in memory
     * @param maxDiskObjects    maximum size of the objects stored on disk in kB
     * @param cachePath         path to disk cache. if null, no disk cache will be created
     * @return cache access object
     */
    @SuppressWarnings("unchecked")
    public static <K, V> CacheAccess<K, V> getCache(String cacheName, int maxMemoryObjects, int maxDiskObjects, String cachePath) {
        CacheAccess<K, V> cacheAccess = getCacheAccess(cacheName, getCacheAttributes(maxMemoryObjects));

        if (cachePath != null && cacheDirLock != null && cacheAccess != null && DISK_CACHE_FACTORY != null) {
            CompositeCache<K, V> cc = cacheAccess.getCacheControl();
            try {
                IDiskCacheAttributes diskAttributes = getDiskCacheAttributes(maxDiskObjects, cachePath, cacheName);
                if (cc.getAuxCaches().length == 0) {
                    cc.setAuxCaches(new AuxiliaryCache[]{DISK_CACHE_FACTORY.createCache(
                            diskAttributes, null, null, new StandardSerializer())});
                }
            } catch (Exception e) { // NOPMD
                // in case any error in setting auxiliary cache, do not use disk cache at all - only memory
                cc.setAuxCaches(new AuxiliaryCache[0]);
                Logging.debug(e);
            }
        }
        return cacheAccess;
    }

    private static <K, V> CacheAccess<K, V> getCacheAccess(String cacheName, CompositeCacheAttributes cacheAttributes) {
        try {
            return JCS.getInstance(cacheName, cacheAttributes);
        } catch (SecurityException | LinkageError e) {
            Logging.error(e);
            return null;
        }
    }

    /**
     * Close all files to ensure, that all indexes and data are properly written
     */
    public static void shutdown() {
        JCS.shutdown();
    }

    private static IDiskCacheAttributes getDiskCacheAttributes(int maxDiskObjects, String cachePath, String cacheName) {
        IDiskCacheAttributes ret;
        removeStaleFiles(cachePath + File.separator + cacheName, useBlockCache() ? "_INDEX_v2" : "_BLOCK_v2");
        String newCacheName = cacheName + (useBlockCache() ? "_BLOCK_v2" : "_INDEX_v2");

        if (useBlockCache()) {
            BlockDiskCacheAttributes blockAttr = new BlockDiskCacheAttributes();
            /*
             * BlockDiskCache never optimizes the file, so when file size is reduced, it will never be truncated to desired size.
             *
             * If for some mysterious reason, file size is greater than the value set in preferences, just use the whole file. If the user
             * wants to reduce the file size, (s)he may just go to preferences and there it should be handled (by removing old file)
             */
            File diskCacheFile = new File(cachePath + File.separator + newCacheName + ".data");
            if (diskCacheFile.exists()) {
                blockAttr.setMaxKeySize((int) Math.max(maxDiskObjects, diskCacheFile.length()/1024));
            } else {
                blockAttr.setMaxKeySize(maxDiskObjects);
            }
            blockAttr.setBlockSizeBytes(4096); // use 4k blocks
            ret = blockAttr;
        } else {
            IndexedDiskCacheAttributes indexAttr = new IndexedDiskCacheAttributes();
            indexAttr.setMaxKeySize(maxDiskObjects);
            ret = indexAttr;
        }
        ret.setDiskLimitType(IDiskCacheAttributes.DiskLimitType.SIZE);
        File path = new File(cachePath);
        if (!path.exists() && !path.mkdirs()) {
            Logging.warn("Failed to create cache path: {0}", cachePath);
        } else {
            ret.setDiskPath(cachePath);
        }
        ret.setCacheName(newCacheName);

        return ret;
    }

    private static void removeStaleFiles(String basePathPart, String suffix) {
        deleteCacheFiles(basePathPart + suffix);
    }

    private static void deleteCacheFiles(String basePathPart) {
        Utils.deleteFileIfExists(new File(basePathPart + ".key"));
        Utils.deleteFileIfExists(new File(basePathPart + ".data"));
    }

    private static CompositeCacheAttributes getCacheAttributes(int maxMemoryElements) {
        CompositeCacheAttributes ret = new CompositeCacheAttributes();
        ret.setMaxObjects(maxMemoryElements);
        ret.setDiskUsagePattern(DiskUsagePattern.UPDATE);
        return ret;
    }
}
