package luceedebug.coreinject;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ValTracker {
    private final Cleaner cleaner;

    /**
     * Really we want a ConcurrentWeakHashMap - we could use Guava mapMaker with weakKeys.
     * Instead we opt to use a sync'd map, because we expect that the number of threads
     * touching the map can be more than 1, but will typically be exactly 1 (the DAP session issuing 'show variables' requests)
     */
    private final Map<Object, WeakTaggedObject> wrapperByObj = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Long, WeakTaggedObject> wrapperByID = new ConcurrentHashMap<>();

    private static class WeakTaggedObject {
        private static final AtomicLong nextId = new AtomicLong();
        public final long id;
        public final WeakReference<Object> wrapped;
        public WeakTaggedObject(Object obj) {
            this.id = nextId.getAndIncrement();
            this.wrapped = new WeakReference<>(Objects.requireNonNull(obj));
        }

        public TaggedObject toStrong() {
            var obj = wrapped.get();
            if (obj == null) {
                return null;
            }
            return new TaggedObject(this.id, obj);
        }
    }

    public static class TaggedObject {
        public final long id;
        
        /**
         * nonNull
         */
        public final Object obj;

        private TaggedObject(long id, Object obj) {
            this.id = id;
            this.obj = Objects.requireNonNull(obj);
        }
    }

    private class CleanerRunner implements Runnable {
        private final long id;
        
        CleanerRunner(long id) {
            this.id = id;
        }

        @Override
        public void run() {
            // Remove the mapping from (id -> Object)
            // The other mapping, Map</*weak key*/Object, TaggedObject> should have been cleared as per the behavior of the weak-key'd map
            // It would be nice to assert that wrapperByObj().size() == wrapperByID.size() after we're done here, but the entries for wrapperByObj
            // are cleaned non-deterministically (in the google guava case, the java sync'd WeakHashMap seems much more deterministic but maybe
            // not guaranteed to be so), so there's no guarantee that the sizes sync up.
            
            wrapperByID.remove(id);

            // __debug_updatedTracker("remove", id);
        }
    }

    public ValTracker(Cleaner cleaner) {
        this.cleaner = cleaner;
    }

    /**
     * This should always succeed, and return an existing or freshly generated TaggedObject.
     * @return TaggedObject
     */
    public TaggedObject idempotentRegisterObject(Object obj) {
        Objects.requireNonNull(obj);

        {
            final WeakTaggedObject weakTaggedObj = wrapperByObj.get(obj);
            if (weakTaggedObj != null) {
                TaggedObject strong = weakTaggedObj.toStrong();
                if (strong != null) {
                    return strong;
                }
            }
        }

        final WeakTaggedObject fresh = new WeakTaggedObject(obj);

        registerCleaner(obj, fresh.id);
        
        wrapperByObj.put(obj, fresh);
        wrapperByID.put(fresh.id, fresh);

        // __debug_updatedTracker("add", fresh.id);

        return fresh.toStrong();
    }

    private void registerCleaner(Object obj, long id) {
        cleaner.register(obj, new CleanerRunner(id));
    }

    /**
     * @return TaggedObject?
     */
    public TaggedObject maybeGetFromId(long id) {
        final WeakTaggedObject weakTaggedObj = wrapperByID.get(id);
        if (weakTaggedObj == null) {
            return null;
        }

        return weakTaggedObj.toStrong();
    }

    /**
     * debug/sanity check that tracked values are being cleaned up in both maps in response to gc events
     */
    @SuppressWarnings("unused")
    private void __debug_updatedTracker(String what, long id) {
        synchronized (wrapperByObj) {
            System.out.println(what + " id=" + id + " wrapperByObjSize=" + wrapperByObj.entrySet().size() + ", wrapperByIDSize=" + wrapperByID.entrySet().size());
            for (var e : wrapperByObj.entrySet()) {
                // size might be reported as N but if all keys have been GC'd then we won't iterate at all
                System.out.println(" entry (K null)=" + (e.getKey() == null ? "y" : "n") + " (V.id)=" + e.getValue().id + " (v.obj null)=" + (e.getValue().wrapped.get() == null ? "y" : "n"));
            }
        }
    }
}
