package luceedebug.coreinject;

import java.lang.ref.WeakReference;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;

import java.util.HashMap;
import java.util.HashSet;

/**
 * weak auto cleaning handleonly multimap
 * This is generic but we always use TWrapped as Object so it probably doesn't need to be
 */
public class RefTracker<TWrapped> {
    private Cleaner cleaner;
    private Mapping mapping = new Mapping();
    private HashSet<CleanerRunner> reachableCleaners = new HashSet<>(); // necessary? does the cleaner hold refs to these for us?

    @SuppressWarnings("unused")
    private ValTracker valTracker;

    public RefTracker(ValTracker valTracker, Cleaner cleaner) {
        this.valTracker = valTracker;
        this.cleaner = cleaner;
    }

    public static class Wrapper_t<TWrapped> {
        private static long nextId = 0;
        public final long id;
        // strong, but we maintain only a weak reference to Wrapper_t
        // `wrapped` may contain a strong reference to `Wrapper_t`
        // if O is only reachable through WeakReferences, O is unreachable, right?
        // once weakrefs to O are cleared, O is "phantom reachable" ?
        public final TWrapped wrapped;
        public Wrapper_t(TWrapped wrapped) {
            this.id = nextId++;
            this.wrapped = wrapped;
        }
    }

    private class Mapping {
        final private HashMap<Long, WeakReference<Wrapper_t<TWrapped>>> vById = new HashMap<>();

        public void dropById(long id) {
            vById.remove(id);
        }

        public Wrapper_t<TWrapped> makeRef(TWrapped wrapped) {
            final Wrapper_t<TWrapped> wrapper = new Wrapper_t<>(wrapped);
            
            new CleanerRunner(wrapper);
            vById.put(wrapper.id, new WeakReference<>(wrapper));
            return wrapper;
        }

        /**
         * If the id doesn't map to a WeakRef<Thread>, return null
         * If the id maps to a WeakRef<Thread>, but it has been collected, return null
         * Otherwise, return a strong reference to the underlying WeakRef
         */
        public Wrapper_t<TWrapped> getFromId(long id) {
            final WeakReference<Wrapper_t<TWrapped>> maybeExists = vById.get(id);
            if (maybeExists == null || maybeExists.get() == null) {
                return null;
            }
            return maybeExists.get();
        }
    }

    private class CleanerRunner implements Runnable {
        private final long id;
        
        @SuppressWarnings("unused") // want a strong ref to it ... right? we don't ever read it though
        private final Cleanable cleanable;

        public CleanerRunner(Wrapper_t<TWrapped> obj) {
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
            //System.out.println("-------- //////// someWeakishMap2 ran a cleaner which is promising");
        }
    }

    /**
     * returns T | null
     */
    public Wrapper_t<TWrapped> maybeGetFromId(long id) {
        return mapping.getFromId(id);
    }

    /**
     * This should always succeed, and return a valid id
     */
    public Wrapper_t<TWrapped> makeRef(TWrapped wrapped) {
        return mapping.makeRef(wrapped);
    }
}
