package luceedebug.coreinject;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.MapMaker;

public class ValTracker {
    private final Cleaner cleaner;

    private final Map<Object, TaggedObject> wrapperByObj = new MapMaker()
        .weakKeys()
        .concurrencyLevel(/* default as per docs */ 4)
        .makeMap();

    private final Map<Long, TaggedObject> wrapperByID = new ConcurrentHashMap<>();

    static class TaggedObject {
        private static final AtomicLong nextId = new AtomicLong();
        public final long id;
        public final WeakReference<Object> wrapped;
        public TaggedObject(Object obj) {
            this.id = nextId.getAndIncrement();
            this.wrapped = new WeakReference<>(obj);
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
            dropById(id);
            // System.out.println("Cleaned object id=" + id);
        }

        private void dropById(long id) {
            TaggedObject wrapper = wrapperByID.get(id);
            if (wrapper != null) {
                Object obj = wrapper.wrapped.get();
                if (obj != null) {
                    wrapperByObj.remove(obj);
                }
            }
            wrapperByID.remove(id);
        }
    }

    public ValTracker(Cleaner cleaner) {
        this.cleaner = cleaner;
    }

    /**
     * This should always succeed, and return a valid id
     */
    public TaggedObject idempotentRegisterObject(Object obj) {
        {
            final TaggedObject wrapper = wrapperByObj.get(obj);
            if (wrapper != null) {
                if (wrapper.wrapped.get() != null) {
                    return wrapper;
                }
            }
        }

        final TaggedObject freshWrapper = new TaggedObject(obj);

        registerCleaner(obj, freshWrapper.id);
        
        wrapperByObj.put(obj, freshWrapper);
        wrapperByID.put(freshWrapper.id, freshWrapper);

        return freshWrapper;
    }

    private void registerCleaner(Object obj, long id) {
        cleaner.register(obj, new CleanerRunner(id));
    }

    /**
     * returns T | null
     * @return 
     */
    public TaggedObject maybeGetFromId(long id) {
        final TaggedObject wrapper = wrapperByID.get(id);
        if (wrapper == null) {
            return null;
        }

        return wrapper;
    }
}
