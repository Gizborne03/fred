package freenet.client.async;

import java.util.ArrayList;
import java.util.List;

import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/** Runs PersistentJob's and periodically, or on demand, suspends all jobs and calls 
 * innerCheckpoint(). */
public abstract class PersistentJobRunnerImpl implements PersistentJobRunner {
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        Logger.registerClass(PersistentJobRunnerImpl.class);
    }
    
    final Executor executor;
    final Ticker ticker;
    /** The number of jobs actually running. */
    private int runningJobs;
    /** If true, we must suspend and write to disk. */
    private boolean mustCheckpoint;
    /** Jobs queued to run after the write finishes. */
    private final List<QueuedJob> queuedJobs;
    private ClientContext context;
    private long lastCheckpointed;
    static final int WRITE_AT_PRIORITY = NativeThread.HIGH_PRIORITY-1;
    final long checkpointInterval;
    /** Not to be used by child classes. */
    private Object sync = new Object();
    protected Object serializeCheckpoints = new Object();
    private boolean willCheck = false;
    /** Have we started the loading process? If so, we should accept jobs. */
    private boolean loading = false;
    /** Have we completed the loading process? If so, we should checkpoint. */
    private boolean started = false;
    /** True if checkpoint is in progress */
    private boolean writing = false;
    /** True if we should reject all new jobs */
    private boolean killed = false;

    public PersistentJobRunnerImpl(Executor executor, Ticker ticker, long interval) {
        this.executor = executor;
        this.ticker = ticker;
        queuedJobs = new ArrayList<QueuedJob>();
        lastCheckpointed = System.currentTimeMillis();
        this.checkpointInterval = interval;
    }
    
    public void start(ClientContext context) {
        synchronized(sync) {
            this.context = context;
        }
    }

    @Override
    public void queue(PersistentJob job, int threadPriority) throws PersistenceDisabledException {
        synchronized(sync) {
            if(!loading) throw new PersistenceDisabledException();
            if(killed) throw new PersistenceDisabledException();
            if(context == null) throw new IllegalStateException();
            if(mustCheckpoint) {
                if(logDEBUG) Logger.debug(this, "Queueing job "+job);
                queuedJobs.add(new QueuedJob(job, threadPriority));
            } else {
                if(logDEBUG) Logger.debug(this, "Running job "+job);
                runningJobs++;
                executor.execute(new JobRunnable(job, threadPriority, context));
            }
        }
    }
    
    @Override
    public void queueInternal(PersistentJob job, int threadPriority) throws PersistenceDisabledException {
        synchronized(sync) {
            if(!loading) throw new PersistenceDisabledException();
            if(killed) throw new PersistenceDisabledException();
            if(context == null) throw new IllegalStateException();
            if(writing) {
                Logger.error(this, "Internal job must not be queued during writing! They should have finished before we start writing and cannot be started \"externally\"!", new Exception("error"));
                queuedJobs.add(new QueuedJob(job, threadPriority));
            } else {
                if(mustCheckpoint) {
                    if(logMINOR) Logger.minor(this, "Delaying checkpoint...");
                }
                runningJobs++;
                if(logDEBUG) Logger.debug(this, "Running job "+job);
                executor.execute(new JobRunnable(job, threadPriority, context));
            }
        }
    }
    
    @Override
    public void queueInternal(PersistentJob job) {
        try {
            queueInternal(job, NativeThread.NORM_PRIORITY);
        } catch (PersistenceDisabledException e) {
            // Maybe this could happen ... panic button maybe?
            Logger.error(this, "Dropping internal job because persistence has been turned off!: "+e, e);
        }
    }
    
    @Override
    public void queueNormalOrDrop(PersistentJob job) {
        try {
            queue(job, NativeThread.NORM_PRIORITY);
        } catch (PersistenceDisabledException e) {
            return;
        }
    }
    
    private class JobRunnable implements Runnable {
        
        private final int threadPriority;
        private final PersistentJob job;
        private final ClientContext context;

        public JobRunnable(PersistentJob job, int threadPriority, ClientContext context) {
            this.job = job;
            this.threadPriority = threadPriority;
            this.context = context;
        }

        @Override
        public void run() {
            boolean ret = false;
            try {
                if(logDEBUG) Logger.debug(this, "Starting "+job);
                ret = job.run(context);
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t+" running job "+job, t);
            } finally {
                if(logDEBUG) Logger.debug(this, "Completed "+job+" with mustCheckpoint="+mustCheckpoint+" started="+started+" runningJobs="+runningJobs);
                handleCompletion(ret, threadPriority);
            }
        }
        
    }
    
    public void handleCompletion(boolean ret, int threadPriority) {
        synchronized(sync) {
            if(ret) {
                mustCheckpoint = true;
                if(logMINOR) Logger.minor(this, "Writing because asked to");
            }
            runningJobs--;
            if(!started) {
                if(logMINOR) Logger.minor(this, "Not started yet");
                return;
            }
            if(!mustCheckpoint) {
                if(System.currentTimeMillis() - lastCheckpointed > checkpointInterval) {
                    mustCheckpoint = true;
                    if(logMINOR) Logger.minor(this, "Writing at interval");
                }
            }
            if(!mustCheckpoint) {
                delayedCheckpoint();
                return;
            }
            if(runningJobs != 0) {
                if(logDEBUG) Logger.debug(this, "Not writing yet");
                return;
            }
            if(killed) {
                sync.notifyAll();
                return;
            } else {
                writing = true;
                if(threadPriority < WRITE_AT_PRIORITY) {
                    checkpointOffThread();
                    return;
                }
            }
        }
        checkpoint(false);
    }

    private class QueuedJob {
        public QueuedJob(PersistentJob job, int threadPriority) {
            this.job = job;
            this.threadPriority = threadPriority;
        }
        final PersistentJob job;
        final int threadPriority;
    }

    private void checkpoint(boolean shutdown) {
        if(logMINOR) Logger.minor(this, "Writing checkpoint...");
        synchronized(sync) {
            if(!started) return;
        }
        synchronized(serializeCheckpoints) {
            try {
                innerCheckpoint(shutdown);
            } catch (Throwable t) {
                Logger.error(this, "Unable to save: "+t, t);
            }
        }
        synchronized(sync) {
            mustCheckpoint = false;
            writing = false;
            QueuedJob[] jobs = queuedJobs.toArray(new QueuedJob[queuedJobs.size()]);
            if(logDEBUG) Logger.debug(this, "Starting "+jobs.length+" queued jobs");
            for(QueuedJob job : jobs) {
                runningJobs++;
                executor.execute(new JobRunnable(job.job, job.threadPriority, context));
            }
            updateLastCheckpointed();
            queuedJobs.clear();
            sync.notifyAll();
        }
        if(logMINOR) Logger.minor(this, "Completed writing checkpoint");
    }
    
    public void delayedCheckpoint() {
        synchronized(sync) {
            if(killed) return;
            if(willCheck) return;
            ticker.queueTimedJob(new PrioRunnable() {
                
                @Override
                public void run() {
                    synchronized(sync) {
                        willCheck = false;
                        if(!(mustCheckpoint || 
                                System.currentTimeMillis() - lastCheckpointed > checkpointInterval))
                            return;
                        if(killed) return;
                        if(runningJobs != 0) return;
                        writing = true;
                    }
                    checkpoint(false);
                }
                
                @Override
                public int getPriority() {
                    return WRITE_AT_PRIORITY;
                }
                
            }, checkpointInterval);
            willCheck = true;
        }
    }

    public void checkpointOffThread() {
        executor.execute(new PrioRunnable() {

            @Override
            public void run() {
                synchronized(sync) {
                    if(killed) return;
                }
                checkpoint(false);
            }

            @Override
            public int getPriority() {
                return WRITE_AT_PRIORITY;
            }
            
        });
    }

    public void setCheckpointASAP() {
        synchronized(sync) {
            mustCheckpoint = true;
        }
    }
    
    protected void updateLastCheckpointed() {
        lastCheckpointed = System.currentTimeMillis();
    }

    protected abstract void innerCheckpoint(boolean shutdown);
    
    protected void onLoading() {
        synchronized(sync) {
            loading = true;
        }
    }
    
    protected void onStarted() {
        synchronized(sync) {
            loading = true;
            started = true;
            updateLastCheckpointed();
            if(!mustCheckpoint) return;
            writing = true;
        }
        checkpointOffThread();
    }
    
    public void shutdown() {
        synchronized(sync) {
            killed = true;
        }
    }

    /** Typically called after shutdown() to wait for current jobs to complete. */
    public void waitForIdleAndCheckpoint() {
        synchronized(sync) {
            while(runningJobs > 0 || writing) {
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
        checkpoint(true);
    }
    
    public boolean isKilledOrNotLoaded() {
        synchronized(sync) {
            return killed || !started;
        }
    }
    
    public boolean hasStarted() {
        synchronized(sync) {
            return started;
        }
    }
    
    protected ClientContext getClientContext() {
        return context;
    }
    
    public CheckpointLock lock() {
        synchronized(sync) {
            while(writing || mustCheckpoint) {
                try {
                    sync.wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
            runningJobs++;
        }
        return new CheckpointLock() {

            @Override
            public void unlock(boolean forceWrite, int threadPriority) {
                handleCompletion(forceWrite, threadPriority);
            }
            
        };
    }


}