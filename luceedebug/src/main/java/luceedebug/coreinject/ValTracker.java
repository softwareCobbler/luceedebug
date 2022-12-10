package luceedebug.coreinject;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;

import java.util.HashMap;
import java.util.HashSet;

import com.google.common.collect.MapMaker;
import java.util.concurrent.ConcurrentMap;

/**
 * weak auto cleaning multimap
 */
public class ValTracker {
    private Cleaner cleaner;
    private Mapping mapping = new Mapping();
    private HashSet<CleanerRunner> reachableCleaners = new HashSet<>(); // necessary? does the cleaner hold refs to these for us?

    public ValTracker(Cleaner cleaner) {
        this.cleaner = cleaner;
    }

    //
    // holding a strong ref to a Wrapper_t keeps the target object alive
    //
    public static class Wrapper_t {
        private static long nextId = 0;
        public final long id;
        // strong, but we maintain only a weak reference to Wrapper_t
        // `wrapped` may contain a strong reference to `Wrapper_t`
        // if O is only reachable through WeakReferences, O is unreachable, right?
        // once weakrefs to O are cleared, O is "phantom reachable" ?
        public final Object wrapped;
        public Wrapper_t(Object wrapped) {
            this.id = nextId++;
            this.wrapped = wrapped;
        }
    }

    private class Mapping {
        // todo: we could probably replace a lot of the valtracker/reftracker with these concurrent maps with weak keys?
        final private ConcurrentMap<Object, WeakReference<Wrapper_t>> vByObj = new MapMaker()
            .concurrencyLevel(/* default as per docs */ 4)
            .weakKeys()
            .makeMap();
        final private HashMap<Long, WeakReference<Wrapper_t>> vById = new HashMap<>();

        public void dropById(long id) {
            WeakReference<Wrapper_t> weakWrapped = vById.get(id);
            if (weakWrapped != null) {
                Wrapper_t wrapper = weakWrapped.get();
                if (wrapper != null) {
                    vByObj.remove(wrapper.wrapped);
                }
            }
            vById.remove(id);
        }

        public Wrapper_t idempotentRegisterObject(Object wrapped) {
            WeakReference<Wrapper_t> weakWrapped = vByObj.get(wrapped);
            if (weakWrapped != null) {
                Wrapper_t wrapper = weakWrapped.get();
                if (wrapper != null) {
                    return wrapper;
                }
            }

            final Wrapper_t wrapper = new Wrapper_t(wrapped);
            
            new CleanerRunner(wrapper);

            vByObj.put(wrapped, new WeakReference<>(wrapper));
            vById.put(wrapper.id, new WeakReference<>(wrapper));

            return wrapper;
        }

        /**
         * If the id doesn't map to a WeakRef<Thread>, return null
         * If the id maps to a WeakRef<Thread>, but it has been collected, return null
         * Otherwise, return a strong reference to the underlying WeakRef
         */
        public Wrapper_t getFromId(long id) {
            final WeakReference<Wrapper_t> weakWrapped = vById.get(id);
            if (weakWrapped == null) {
                return null;
            }
            // possibly null, that is expected
            return weakWrapped.get();
        }
    }

    private class CleanerRunner implements Runnable {
        private final long id;
        
        @SuppressWarnings("unused") // want a strong ref to it ... right? we don't ever read it though
        private final Cleanable cleanable;

        public CleanerRunner(Wrapper_t obj) {
            this.id = obj.id;
            this.cleanable = cleaner.register(obj, this);
            reachableCleaners.add(this); // side-effecting constructor
        }

        public void run() {
            // remove the mapping from (id -> Object)
            // the other mapping, WeakMap<TBase, Object> should have been cleared as per the behavior of WeakMap
            mapping.dropById(id);
            // remove ourself from cleaners
            reachableCleaners.remove(this);
            //System.out.println("-------- //////// someWeakishMap3 ran a cleaner which is promising");
        }
    }

    /**
     * returns T | null
     */
    public Wrapper_t maybeGetFromId(long id) {
        return mapping.getFromId(id);
    }

    /**
     * This should always succeed, and return a valid id
     */
    public Wrapper_t idempotentRegisterObject(Object wrapped) {
        return mapping.idempotentRegisterObject(wrapped);
    }
}
