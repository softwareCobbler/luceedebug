package luceedebug.coreinject;

import java.util.concurrent.LinkedBlockingDeque;

public class AsyncWorker {
    final private LinkedBlockingDeque<Runnable> queue_ = new LinkedBlockingDeque<>();
    final private CancellationTag cancellationTag = new CancellationTag();
    
    private boolean pendingCancellation = false;
    private boolean started = false;
    static private class CancellationTag implements Runnable {
        public void run() {}
    }

    public AsyncWorker() {}

    public void start() {
        if (started) {
            return;
        }

        started = true;

        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        var v = queue_.takeLast();
                        if (v == cancellationTag) {
                            return;
                        }
                        v.run();
                    }
                    catch (Throwable e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }
            }
        }).start();
    }

    public void queueWork(Runnable f) {
        queue_.addFirst(f);
    }

    public void cancel() {
        if (pendingCancellation) {
            return;
        }
        queue_.addFirst(cancellationTag);
    }
}
