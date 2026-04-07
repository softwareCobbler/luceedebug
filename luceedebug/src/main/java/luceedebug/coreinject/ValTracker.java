package luceedebug.coreinject;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Track the variable path for each registered object ID.
     * Used by setVariable to build the full path like "local.foo.bar".
     * Path is the dot-separated path from the scope root (e.g., "local", "local.myStruct", "local.myStruct.nested").
     */
    private final Map<Long, String> pathById = new ConcurrentHashMap<>();

    /**
     * Track the frame ID for each registered object ID.
     * Used by setVariable to get the correct PageContext.
     */
    private final Map<Long, Long> frameIdById = new ConcurrentHashMap<>();

    private static class WeakTaggedObject {
        // Start at 1, not 0 - DAP uses variablesReference=0 to mean "no children"
        private static final AtomicLong nextId = new AtomicLong(1);
        public final long id;
        public final WeakReference<Object> wrapped;
        public WeakTaggedObject(Object obj) {
            this.id = nextId.getAndIncrement();
            this.wrapped = new WeakReference<>(Objects.requireNonNull(obj));
        }

        public Optional<TaggedObject> maybeToStrong() {
            var obj = wrapped.get();
            if (obj == null) {
                return Optional.empty();
            }
            return Optional.of(new TaggedObject(this.id, obj));
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
            pathById.remove(id);
            frameIdById.remove(id);

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
                Optional<TaggedObject> maybeStrong = weakTaggedObj.maybeToStrong();
                if (maybeStrong.isPresent()) {
                    return maybeStrong.get();
                }
            }
        }

        final WeakTaggedObject fresh = new WeakTaggedObject(obj);

        registerCleaner(obj, fresh.id);
        
        wrapperByObj.put(obj, fresh);
        wrapperByID.put(fresh.id, fresh);

        // __debug_updatedTracker("add", fresh.id);

        // expected to always succeed here
        return fresh.maybeToStrong().get();
    }

    private void registerCleaner(Object obj, long id) {
        cleaner.register(obj, new CleanerRunner(id));
    }

    public Optional<TaggedObject> maybeGetFromId(long id) {
        final WeakTaggedObject weakTaggedObj = wrapperByID.get(id);
        if (weakTaggedObj == null) {
            return Optional.empty();
        }

        return weakTaggedObj.maybeToStrong();
    }

    /**
     * Register or update the variable path for an object ID.
     * Called when registering scopes (path = scope name) or when expanding children (path = parent.childKey).
     * @param id The variablesReference ID
     * @param path The dot-separated path from scope root (e.g., "local", "local.foo", "local.foo.bar")
     */
    public void setPath(long id, String path) {
        if (path != null) {
            pathById.put(id, path);
        }
    }

    /**
     * Get the variable path for an object ID.
     * @param id The variablesReference ID
     * @return The path, or null if not tracked
     */
    public String getPath(long id) {
        return pathById.get(id);
    }

    /**
     * Register an object and set its path in one call.
     * @param obj The object to register
     * @param path The variable path for this object
     * @return TaggedObject with the ID
     */
    public TaggedObject registerObjectWithPath(Object obj, String path) {
        TaggedObject tagged = idempotentRegisterObject(obj);
        if (path != null) {
            pathById.put(tagged.id, path);
        }
        return tagged;
    }

    /**
     * Register an object and set its path and frameId in one call.
     * @param obj The object to register
     * @param path The variable path for this object
     * @param frameId The frame ID for this object (for setVariable support)
     * @return TaggedObject with the ID
     */
    public TaggedObject registerObjectWithPathAndFrameId(Object obj, String path, Long frameId) {
        TaggedObject tagged = idempotentRegisterObject(obj);
        if (path != null) {
            pathById.put(tagged.id, path);
        }
        if (frameId != null) {
            frameIdById.put(tagged.id, frameId);
        }
        return tagged;
    }

    /**
     * Set the frame ID for an object ID.
     * Used by setVariable to get the correct PageContext.
     * @param id The variablesReference ID
     * @param frameId The frame ID
     */
    public void setFrameId(long id, long frameId) {
        frameIdById.put(id, frameId);
    }

    /**
     * Get the frame ID for an object ID.
     * @param id The variablesReference ID
     * @return The frame ID, or null if not tracked
     */
    public Long getFrameId(long id) {
        return frameIdById.get(id);
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
