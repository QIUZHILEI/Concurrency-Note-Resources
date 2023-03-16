package java.util.concurrent;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 线程池解决了两个问题：
 * · 执行大量的异步任务时的性能改进，避免的过多线程调度的额外开销
 * · 提供了绑定和管理资源的方法
 * 
 * 为了使线程池具有强大的适用性，这里提供了多个可调节的参数。为了方便使用
 * 通常可以使用Executors工具类的方法：
 * · newCacheThreadPool 无界的线程池，自带线程回收
 * · newFixedThreadPool 固定大小的线程池
 * · newSingleThreadExecutor 单个后台线程
 * 这三个方法足以应付多数使用场景，当然你可以选择自定义线程池的参数，参数
 * 设置参考如下：
 * 1.core和max池大小：
 * 线程池会根据指定的corePoolSize和maximumPoolSize设置的范围自动的调整
 * 线程池中线程数。
 * 当在方法execute中提交新任务时，如果此时池内的线程数未达到corePoolSize，
 * 即使有其他空闲状态的线程，也会新创建一个线程来执行任务；如果此时池内线程数已经达到corePoolSize,
 * 并且只有当任务队列满了的时候，才会新创建一个线程执行任务。
 * 可以使用setCorePoolSize和SetMaximumPoolSize动态的修改池的大小
 * 
 * 2.按需构造：
 * 线程池中在一开始时没有线程，只有当新任务到底时才创建线程。为了满足某种需求，例如：用一个非空的
 * 任务队列构造一个线程池时，可能希望线程池初始化时就创建好线程。你可以使用prestartCoreThread和
 * prestartAllCoreThreads方法来预先启动核心线程
 * 
 * 3.创建新线程
 * 新线程的创建是由ThreadFactory完成，如果没有另外的指定，则使用E​​xecutors.defaultThreadFactory(),
 * 它创建的线程都属于同一个线程组，并且具有同样的优先级。
 * 当然你可以提供不同的ThreadFactory，就可以自定义线程的名称、所属线程组、优先级、守护线程等状态。
 * 如果自定义的ThreadFactory的newThread()返回null，程序将继续执行，但是不会创建线程执行任务。
 * newThread()的线程必须是可变的，也就是池对它有修改的权限
 * 
 * 4.存活时间
 * keepAliveTime是为了约束超过corePoolSize那一部分的线程，也就是说，如果池中的线程数如果目前超过corePoolSize
 * 那么，当没有可执行的任务时候，这一部分的线程或在过了keepAliveTime之后被销毁。这样做是为了减少资源消耗，
 * 可以使用setKeepAliveTime(long,TimeUnit)方法修改这个参数
 * allowCoreThreadTimeOut(boolean)这个方法可以将keepAliveTime策略用于核心线程
 * 
 * 5.队列
 * 任何的BlockingQueue(阻塞队列)都可以用于传输和保存提交的任务，注意：
 * · 如果当前线程数小于corePoolSize，则线程池更倾向于创建新线程来执行任务
 * · 如果当前线程数多于corePoolSize，则线程池更倾向于将任务加入队列
 * · 如果队列满了，则会创建一个新的线程来执行任务。
 * · 当队列满了，线程数为maximumPoolSize，此时无法接受新任务，会触发拒绝策略
 * 排队的一般策略有以下三种：
 * 1. 直接交付：这需要使maximumPoolSize是无限大，因为如果新任务到来时，池中线程数已经达到了corePoolSize，并且没有空闲
 * 的核心线程来执行它，新任务会尝试入队，但是入队会失败，这将导致新创建一个线程去运行它。这是默认的一个很好的选择
 * 2.
 * 无限队列（链队列）：LinkedBlockingQueue，这种方式将会导致maximumPoolSize参数无效，线程池中最大线程数为corePoolSize
 * 当没有线程可执行新任务时将会将新任务放入队列中。这种方式适用于各任务彼此之间不会相互影响。但是如果新任务爆发式的到来，
 * 这会导致任务的响应时间过长。（因为队列是无界的，因此不会触发拒绝策略）
 * 3. 有限队列（数组队列）：ArrayBlockingQueue，这种方式有助于防止资源耗尽，但这有可能降低吞吐量。
 * 大队列小池：可以有效降低CPU利用率、OS资源上下文切换。但会导致人为的吞吐量降低。如果任务是频繁的引起阻塞的（例如IO），
 * 这会导致系统花费更多的时间在线程调度上。
 * 小队列大池：可以有效发挥CPU利用率，但是队列较小，如果新任务过多，会导致频繁的拒绝策略的执行，也会降低吞吐量。
 * 6.拒绝任务
 * 当线程池已被终止或队列已满并且线程池内线程达到最大界限，这时调用execute方法新任务将不能被执行，
 * 这时会调用RejectedExecutionHandler 的
 * RejectedExecutionHandler.rejectedExecution(Runnable, ThreadPoolExecutor) 方法
 * 提供拒绝策略有四种：
 * 1. AbortPolicy：抛出运行时异常RejectedExecutionException
 * 2. CallerRunsPolicy：交给调用execute方法的线程执行任务。这种简单的反馈控制机制，可以减慢提交新任务的速度
 * 3. DiscardPolicy：直接丢弃任务，这种情况使用较少
 * 4. DiscardOldestPolicy: 如果线程池没有被关闭，则将队列头部任务丢弃，将现在的新任务加入并执行
 * 需要注意的是，使用不同的类型的队列应该使用不同的拒绝策略
 * 
 * 7.钩子方法
 * 这个类提供了可以重写的两个方法：beforeExecute(Thread,Runnable)和afterExecute(Runnable,Throwable)
 * 这两个方法会在每个任务之前和之后执行，这些可以用于操纵执行的环境。
 * 另外可以重写terminated方法以执行线程池完全终止后需要完成的的任何特殊处理。
 * 注意如果这几个回调方法中出现了异常，那么内部的任务可能会依次终止、突然终止或者可能被取代
 * 
 * 8.线程维护
 * 可以使用getQueue方法获取阻塞队列，但不要获取这个队列做其他事情，当遇到大量的任务被取消时，可以使用remove和purge方法协助存储回收
 * ·
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * ctl:主要的池控制状态，它是一个int型的原子值，表示线程的有效runState,
     * 表示是否运行、关闭等。workerCount被限制在2^29-1（约为5亿）个线程，
     * 如果在将来这将成为一个问题，就将其改为AtomicLong并调整下面的shift/mask。
     * 
     * WorkerCount是被允许启动和不允许启动的工作者的数量。这个值可能会暂时与实际活跃
     * 的线程数不同。
     * runState提供了主要的生命周期控制，采取的以下几种值：
     * 1.RUNNING:接受新任务并且处理入队任务
     * 2.SHUTDOWN:不接受新任务，但处理入队的任务
     * 3.STOP:不接受新任务，不处理入队任务
     * 4.TIDYING:所有的任务已经终止，workerCount为0，过渡到TIDYING状态的线程执行
     * terminated方法
     * 5.TERMINATED:terminated方法已经执行完成
     * 为了便于比较，这些数字的顺序很重要。runState会随着时间的推移而单调递增：
     * 在调用shutdown方法后：RUNNING -> SHUTDOWN
     * 如果调用的是shutdownNow方法，则状态为：(RUNNING or SHUTDOWN) -> STOP
     * 当池和队列都空，状态：SHUTDOWN -> TIDYING
     * 当池为空，状态：STOP -> TIDYING
     * 当terminated方法被调用完成后，状态：TIDYING -> TERMINATED
     * When the terminated() hook method has completed
     *
     * 等待在awaitTermination方法的线程将会在线程池状态变为TERMINATED后返回
     *
     * 检测从SHUTDOWN状态到TIDYING状态的转换不是那么的直接，因为在SHUTDOWN后
     * 队列可能由非空变为空，但我们只有看到队列为空并且workerCount变为0时才可以终止
     * （这时需要重新检查）
     */

    // 表示有效的runState
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3; // = 29
    private static final int COUNT_MASK = (1 << COUNT_BITS) - 1; // = 536870911

    // runState被存储在高位
    private static final int RUNNING = -1 << COUNT_BITS; // = -536870912
    private static final int SHUTDOWN = 0 << COUNT_BITS; // = 0
    private static final int STOP = 1 << COUNT_BITS; // = 536870912
    private static final int TIDYING = 2 << COUNT_BITS; // = 1073741824
    private static final int TERMINATED = 3 << COUNT_BITS; // = 1610612736

    // Packing and unpacking ctl
    private static int runStateOf(int c) {
        return c & ~COUNT_MASK;
    }

    private static int workerCountOf(int c) {
        return c & COUNT_MASK;
    }

    private static int ctlOf(int rs, int wc) {
        return rs | wc;
    }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    // 状态<SHUTDOWN 就是在运行
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    // worker数量+1
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    // worker数量-1
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    // 这个方法仅在一个线程突然终止执行时，其他的decrements是在getTask中进行
    private void decrementWorkerCount() {
        ctl.addAndGet(-1);
    }

    // -------------------------------------------------------------
    /**
     * 用来保存任务并移交给工作线程的队列。这个队列不要求poll方法返回null就
     * 必然意味着队列为空，只依靠isEmpty方法来判断队列是否为空（例如：我们想
     * 从SHUTDOWN过渡到TIDYING时必须这样做）。这种做法适用了特殊队列，比如
     * 允许返回poll方法返回null的DelayQueue
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * 对worker集合和相关属性的获取持有锁。虽然我们可以使用无锁并发Set，但事实证明
     * 使用锁Set是比较好的。其中的原因是：这可以序列化interruptldleWorkers，从而
     * 避免不必要的interrupt风暴，特别是在shutdown期间。使用并发集可能引起并发的
     * 中断那些尚未中断的线程。这个锁也简化了一些统计方法的设计。
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    // 存储线程池中工作的线程，可以在持有mainLock时获取这个数据结构
    private final HashSet<Worker> workers = new HashSet<>();

    // 提供awaitTermination的阻塞操作
    private final Condition termination = mainLock.newCondition();

    // 跟踪池内达到的最大线程数
    private int largestPoolSize;

    // 一个已完成任务的计数，它仅仅在工作线程终止的时候更新
    private long completedTaskCount;

    // threadFactory只需要是volatile的，因为只需要在外部更新它，而内部仅仅使用
    /**
     * 调用者要做好处理addWorkder异常/错误的准备，因为可能由于OS对最大线程数的限制。
     * 尽管这不被视为一个错误，但是这种情况会导致新任务被拒绝或者队列中的任务卡住。
     * 有可能在创建新线程时，会造成栈溢出，内存溢出错误，但是要保证池的稳定性，遇到
     * 这样的错误，可以通过清理池，来获取更多的内存，这样创建新线程就不会内存溢出了。
     */
    private volatile ThreadFactory threadFactory;

    // 拒绝策略，默认是拒绝执行
    private volatile RejectedExecutionHandler handler;
    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    // 单位：纳秒，线程空闲时最长存活时间
    private volatile long keepAliveTime;

    // 默认是false，如果设置为true，则keepAliveTime对核心线程也奏效
    private volatile boolean allowCoreThreadTimeOut;

    // 核心池大小，实际上worker数量被存储在COUNT_BITS中，实际有效值为corePoolSize & COUNT_MASK
    private volatile int corePoolSize;

    // 最大池大小。实际上worker数量被存储在COUNT_BITS中，实际有效值为maximumPoolSize & COUNT_MASK
    private volatile int maximumPoolSize;

    /**
     * Worker类主要维护运行任务的线程的中断控制状态，以及其他次要的信息。
     * 这个类扩展了AQS，以简化对每个任务执行的锁的获取和释放
     * 为了防止线程开始执行任务之前
     */
    private final class Worker
            extends AbstractQueuedSynchronizer
            implements Runnable {
        // 执行任务的线程，如果ThreadFactory创建线程失败，这个域为null
        @SuppressWarnings("serial")
        final Thread thread;
        // 初始化的任务，可能为null
        @SuppressWarnings("serial") // Not statically typed as Serializable
        Runnable firstTask;
        // 每个线程完成的任务计数
        volatile long completedTasks;

        // TODO: switch to AbstractQueuedLongSynchronizer and move
        // completedTasks into the lock word.

        // 初始化任务和线程
        Worker(Runnable firstTask) {
            setState(-1); // 初始state设置为-1，为了抑制中断，直到runWorker
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        /** 将主运行循环委托给外部的runWorker方法. */
        public void run() {
            runWorker(this);
        }

        // 锁方法 0代表未锁，1代表锁定状态
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock() {
            acquire(1);
        }

        public boolean tryLock() {
            return tryAcquire(1);
        }

        public void unlock() {
            release(1);
        }

        // 用isHeldExeclusively代表锁定状态
        public boolean isLocked() {
            return isHeldExclusively();
        }

        // 中断线程的执行
        void interruptIfStarted() {
            Thread t;
            // running wait cond状态都可中断
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * 设置控制state的方法
     */

    /**
     * 将state设置为指定的值，如果已经是指定的值则不再管他
     * targetState应该是SHUTDOWN或者STOP而不应该是TERMINATED或TIDYING
     * （这两种状态应该使用tryTerminated()方法）
     */
    private void advanceRunState(int targetState) {
        // assert targetState == SHUTDOWN || targetState == STOP;
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) ||
                    ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * 如果当前状态是SHUTDOWN并且池中线程数为0并且队列为空或者状态为STOP并且池中无线程
     * 则过渡到TERMINATED状态。
     * 如果终止条件符合但，workerCount不为0，则中断一个空闲的Worker以确保关闭信号的传播。
     * 这个方法必须在任何可能使TERMINATED状态成为可能的行为后调用——在shutdown期间减少
     * worker数量或从队列中移除任务。
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) ||
                    runStateAtLeast(c, TIDYING) ||
                    (runStateLessThan(c, STOP) && !workQueue.isEmpty()))
                return;
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /**
     * Interrupts all threads, even if active. Ignores SecurityExceptions
     * (in which case some threads may remain uninterrupted).
     */
    private void interruptWorkers() {
        // assert mainLock.isHeldByCurrentThread();
        for (Worker w : workers)
            w.interruptIfStarted();
    }

    /**
     * 中断空闲的线程，以便于他们能够检查终止或配置变化
     * 参数onlyOne如果为true，则最多中断一个线程。
     * 只有当TERMINATED状态被置位，并且仍然有worker(线程)存活就会从
     * tryTerminate调用这个方法。在这种情况下，最多只有一个等待的工作
     * 器被中断，以传播关闭信号，以防所有线程当前都在等待
     * 中断任何一个任意的线程都能确保自关闭开始以来新到达的工作者也将最
     * 终退出。为了保证最终的终止，只中断一个空闲的工作者就足够了，
     * 但是shutdown()会中断所有的空闲工作者，这样多余的工作者就会及时退出，
     * 而不是等待一个落伍的任务完成。
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    // 执行拒绝策略
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    // 被用于善后清理工作，这个方法在ScheduledThreadPoolExecutor中使用来清理延迟的任务
    void onShutdown() {
    }

    /**
     * 使用drainTo方法将任务队列排入一个新的列表。如果队列是一个延迟队列
     * 或其他类型的队列，poll或drainTo方法可能无法删除一些元素，那么它就
     * 会一个一个的删除他们
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /**
     * 根据当前池的状态和给定的(core/max size)，检查是否可以添加一个新的线程。如果可以将相应
     * 的调整workerCount，如果能添加一个新的Thread（Worker），就将firstTask最为其第一个任务运行。
     * 如果线程池被关闭或者有资格停止，这个方法会返回false；如果工厂创建Thread失败，可能是因为
     * OutOfMemery或者创建时异常，这个方法也会返回false。
     * 总之，如果创建工作者失败，就会干净的回滚
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry: for (int c = ctl.get();;) {
            // Check if queue empty only if necessary.
            if (runStateAtLeast(c, SHUTDOWN)
                    && (runStateAtLeast(c, STOP)
                            || firstTask != null
                            || workQueue.isEmpty()))
                return false;

            for (;;) {
                if (workerCountOf(c) >= ((core ? corePoolSize : maximumPoolSize) & COUNT_MASK))
                    return false;
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get(); // Re-read ctl
                if (runStateAtLeast(c, SHUTDOWN))
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            w = new Worker(firstTask);
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    int c = ctl.get();

                    if (isRunning(c) ||
                            (runStateLessThan(c, STOP) && firstTask == null)) {
                        if (t.getState() != Thread.State.NEW)
                            throw new IllegalThreadStateException();
                        workers.add(w);
                        workerAdded = true;
                        int s = workers.size();
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (!workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * 回滚worker线程的创建
     * - 如果worker存在队列中，就删除
     * - 递减workerCount
     * - 重新检查terminated，防止这个worker耽误了线程池的终止
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 为死掉的worker执行清理的和薄记，这个方法仅在工作的线程中调用。
     * 如果任务不是意外终止，就假定workerCount已经被调整，以考虑退出。
     * 这个方法会将工作者在池中移除，如果运行的工作者少于corePoolSize
     * 或队列非空但没有worker，这时考虑终止线程池的运行或替换掉worker。
     *
     * @param completedAbruptly 由于用户定义的任务有问题导致线程突然的终止
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += w.completedTasks;
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        tryTerminate();

        int c = ctl.get();
        if (runStateLessThan(c, STOP)) {
            if (!completedAbruptly) {
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && !workQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            addWorker(null, false);
        }
    }

    /**
     * 工作的线程通过这个方法获取队列中的任务，获取的过程是阻塞的
     * 如果出现以下几种情况代表获取任务失败，返回null：
     * 1. 有超过maximumPoolSize的线程
     * 2. 线程池被停止了
     * 3. 线程池被关闭，队列为空
     * 4. 这个线程在等待时超过了keepAliveTime(allowCoreThreadTimeOut||workerCount>corePoolSize)
     * (如果队列非空，并且这个线程不是池中的最后一个线程)
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();

            // Check if queue empty only if necessary.
            if (runStateAtLeast(c, SHUTDOWN)
                    && (runStateAtLeast(c, STOP) || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            int wc = workerCountOf(c);

            // Are workers subject to culling?
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            if ((wc > maximumPoolSize || (timed && timedOut))
                    && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                Runnable r = timed ? workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) : workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * 这个方法是主要的worker线程循环运行的逻辑，只要线程池不关闭，worker线程会
     * 不断的从队列中取出任务并执行，同时也伴随一些其他问题：
     *
     * 1. 我们可能会一开始就初始化一个任务，这种情况下，不应该通过getTask获取任务
     * 否则会因为池子状态或配置参数的改变而退出。
     * 如果遇到其他意外原因而退出，会调用processWorkerExit(worker,true)处理
     *
     * 2. 在运行任何任务之前，都需要获取锁，防止任务执行时其他池中断。然后我们确保
     * 除非线程池停止，否则就不会将这个线程中断置位
     *
     * 3. 每个任务执行前都会调用beforeExecute，如果这个方法调用出了问题，我们
     * 会跳出循环并processWorkerExit(w, completedAbruptly); 使线程终止运行
     *
     * 4. 假设beforeExecute正常执行成功，我们运行任务，收集任何抛出的异常，并将其
     * 发送给afterExecute，我们分别处理任何的RuntimeException、Error和Throwable
     * 因为我们不能在task的run中重新抛出任何异常，所以我们在离开时将它们打包进Error中
     * (送给UncaughtExecptionHandler)。任何异常的抛出都将导致线程死亡
     *
     * 5. afterExecute也可能会遇到一些异常，这些异常同样会导致线程终止执行，
     * 这些异常信息也是很有用的。
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        w.unlock(); // allow interrupts
        boolean completedAbruptly = true;
        try {
            while (task != null || (task = getTask()) != null) {
                w.lock();
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted. This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                if ((runStateAtLeast(ctl.get(), STOP) ||
                        (Thread.interrupted() &&
                                runStateAtLeast(ctl.get(), STOP)))
                        &&
                        !wt.isInterrupted())
                    wt.interrupt();
                try {
                    beforeExecute(wt, task);
                    try {
                        task.run();
                        afterExecute(task, null);
                    } catch (Throwable ex) {
                        afterExecute(task, ex);
                        throw ex;
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            processWorkerExit(w, completedAbruptly);
        }
    }

    // 构造方法
    public ThreadPoolExecutor(int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                Executors.defaultThreadFactory(), defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                threadFactory, defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                Executors.defaultThreadFactory(), handler);
    }

    public ThreadPoolExecutor(int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            RejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
                maximumPoolSize <= 0 ||
                maximumPoolSize < corePoolSize ||
                keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * 在将来执行这个任务，这个任务可能被一个新线程或原有的池中的线程执行
     * 如果提交的任务不能被执行，可能由于线程池关闭了或者队列容量满了，这时
     * 应该执行拒绝策略
     */
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        /*
         * 三步：
         *
         * 1. 如果目前池中的线程数少于corePoolSize，就新启动一个线程将这个任务
         * 作为新线程的(worker)的第一个任务。对addWorker的调用会原子化的检查
         * runState和workerCount以防止在不应该增加线程时候增加。
         *
         * 2. 如果一个任务可以成功的入队，我们会二次检查，我们是否应该添加一个线程
         * (因为在上一次检查时存在一个已经死掉的线程)或者进入这个方法之前线程池就已
         * 关闭了。因此我们重新检查state，并且，如果已经停止了我们就有必要回滚，或者
         * 如果没有一个新的线程我们就新建一个
         *
         * 3. 如果任务入队失败，我们会尝试添加一个新线程，如果这也失败了，我们就知道
         * 这时线程池已经关闭或者是队列饱和了，我们应该拒绝这个任务。
         */
        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true))
                return;
            c = ctl.get();
        }
        if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();
            if (!isRunning(recheck) && remove(command))
                reject(command);
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        } else if (!addWorker(command, false))
            reject(command);
    }

    /**
     * 执行先前提交的任务，但不接受新任务，并且如果线程池已经关闭，这个方法的
     * 调用将没有任何效果。调用这个方法的线程不会等待所有任务执行的结束
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown(); // ScheduledThreadPoolExecutor的关闭钩子
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    /**
     * 该方法会尝试中断正在执行的任务，并且取消队列中等待的任务，返回为被执行的
     * 任务list(原来的队列中的元素移动到这里)。调用这个方法的线程不会等待所有任
     * 务执行的结束。
     * 这个方法会尝试中断正在执行的任务，但也只是通过interrupt，如果任务不响应
     * interrupt，那么这个线程将不会被中断
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return runStateAtLeast(ctl.get(), SHUTDOWN);
    }

    //ScheduledThreadPoolExecutor 使用
    boolean isStopped() {
        return runStateAtLeast(ctl.get(), STOP);
    }

    /**
     * 如果线程池正在被关闭或shutdownNow但还未终止返回true。
     * 这个方法对调试可能很管用，在shutdown后的足够长的时间内报告ture，
     * 表明可能有线程中的任务忽略了中断，导致线程池没有被正确的终止
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return runStateAtLeast(c, SHUTDOWN) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    //见AbstractExecutorService
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            while (runStateLessThan(ctl.get(), TERMINATED)) {
                if (nanos <= 0L)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    // 以下方法可以调整和获取线程池的一些重要配置信息----------------------------------

    // 配置ThreadFactory
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    // 配置拒绝策略
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * 设置corePoolSize，如果比原来的小，则需要终止一些多余的并且空闲的线程
     * 如果比原来的大，并且如果需要，就启动新的线程运行任务
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    //预先启动一个空闲的线程来等待任务
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
                addWorker(null, true);
    }

    //和prestartCoreThread方法一样，但是即使是corePoolSize为0，也会启动一个线程
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    // 预先启动所有的核心线程
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            // 如果允许core线程也响应keepAliveTime的配置，则应该终止当前活跃到生命周期末尾的线程
            if (value)
                interruptIdleWorkers();
        }
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        // 如果设置的maximumPoolSize比原来小，应该尝试终止多余当前maximumPoolSize的活跃线程
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        // 如果设置的keepAliveTime比原本的小，那么既有可能目前已经有线程到达的生命周期的末尾，因此应该终止一些线程的执行
        if (delta < 0)
            interruptIdleWorkers();
    }

    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    // 获取这个队列最好不要修改它，可以作为调试和监视的用途
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    // 下面的remove和purge方法可以协助存储回收---------------------------

    /**
     * 移除内部队列中的任务（如果它存在的话），从而使他在尚未启动情况下不被运行。
     * 这个方法作为取消任务的一部分是很有用的，但是他可能无法取消已经转换为其他形式
     * 存在的任务，例如使用submit提交后，任务被Future保存，作为Future的状态存在，
     * 这种情况可以使用purge删除那些已经被取消执行的Futures
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // 队列中删除了一个任务，可能会导致无任务可以执行，应该尝试终止线程池
        return removed;
    }

    /**
     * 试图从队列中移除已经被取消的Future任务，这个方法作为协助存储回收的方法，没有其他功能的影响。
     * 由于Future任务被取消无法得到执行，就会造成堆积，因此调用这个方法可以立即回收并尝试删除它们，
     * 如果有其他线程干扰的情况下，可能会造成删除失败
     * TODO：ConcurrentModificationExecption？
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>) r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>) r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // 如果删除队列中的任务，可能会造成队列空，这时应该尝试终止线程池的执行
    }

    // 以下是一些统计方法----------------------------------------------------------

    // 返回当前池中的线程数
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                    : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    // 返回正在执行任务的线程数
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    // 返回曾经达到的最大池size（线程数）
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    // 返回曾经被执行过的任务的总数（包含失败的任务数）
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    // 返回一个已经完成的任务的总数（近似）
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    // ---------------------------------------------

    // 打印线程池的相关状态
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String runState = isRunning(c) ? "Running" : runStateAtLeast(c, TERMINATED) ? "Terminated" : "Shutting down";
        return super.toString() +
                "[" + runState +
                ", pool size = " + nworkers +
                ", active threads = " + nactive +
                ", queued tasks = " + workQueue.size() +
                ", completed tasks = " + ncompleted +
                "]";
    }

    // Hooks 钩子方法 三个

    /**
     * r代表任务，t代表执行这个任务的线程，这个方法会在execute之前执行，可以用于初始化ThreadLocal和日志信息
     * 为了正确的嵌套调用，子类应该在方法内调用父类的这个方法
     */
    protected void beforeExecute(Thread t, Runnable r) {
    }

    /**
     * 方法会在execute执行给定的任务之后执行，r代表完成的任务，如果正常完成则t为null
     * 否则，t代表r运行时产生的异常。
     * 为了正确的嵌套调用，子类应该在方法内调用父类的这个方法
     * 
     * FutureTask会捕获并跟踪runnable运行时异常，如果想使用产生的异常，可以通过FT获取
     *
     * <pre> {@code
     * class ExtendedExecutor extends ThreadPoolExecutor {
     *     // ...
     *     protected void afterExecute(Runnable r, Throwable t) {
     *         super.afterExecute(r, t);
     *         if (t == null
     *                 && r instanceof Future<?>
     *                 && ((Future<?>) r).isDone()) {
     *             try {
     *                 Object result = ((Future<?>) r).get();
     *             } catch (CancellationException ce) {
     *                 t = ce;
     *             } catch (ExecutionException ee) {
     *                 t = ee.getCause();
     *             } catch (InterruptedException ie) {
     *                 // ignore/reset
     *                 Thread.currentThread().interrupt();
     *             }
     *         }
     *         if (t != null)
     *             System.out.println(t);
     *     }
     * }
     * }</pre>
     */
    protected void afterExecute(Runnable r, Throwable t) {
    }

    // 当线程池终止执行后执行，为了正确的嵌套多个重载，子类应该在这个方法内调用super.terminated()
    protected void terminated() {
    }

    // 交给调用execute的线程执行
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        public CallerRunsPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    // 拒绝执行并抛出拒绝执行异常，这个策略是ScheduledThreadPoolExecutor，ThreadPoolExecutor的默认策略
    public static class AbortPolicy implements RejectedExecutionHandler {
        public AbortPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    " rejected from " +
                    e.toString());
        }
    }

    // 直接丢弃策略（什么也不做）
    public static class DiscardPolicy implements RejectedExecutionHandler {
        public DiscardPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    // 删除队列第一个，尝试重新execute任务
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        public DiscardOldestPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
