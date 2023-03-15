
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * 异步调用执行器。这个类提供了一个基于Future类的实现，并且提供了开始和取消的方法
 * 还提供了查询计算是否完成，获取执行结果的方法；结果仅仅可以在完成执行后获取。
 * get方法会阻塞执行。只要计算完成了，就不能重启或者取消（除非使用runAndReset方法
 * <p>
 * FutureTask被用于封装一个Runnable或者是Callable，FutureTask可以被Executor执行
 * 当然你也可以自定义FutureTask的子类。
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * 这个版本已经发生改变，目前的设计是通过CAS控制一个状态来跟踪完成情况，以及通过
       一个简单的Treiber栈来容纳等待的线程
     */

    /**
     * state一开始是NEW->0. The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel. During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * 可能的状态转换
     * 
     * 正常执行完成：NEW -> COMPLETING -> NORMAL 
     * 异常执行完成：NEW -> COMPLETING -> EXCEPTIONAL
     * 执行被取消：NEW -> CANCELLED
     * 执行被中断：NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW = 0; //开始时的状态
    private static final int COMPLETING = 1; //完成的状态
    private static final int NORMAL = 2; //正常状态
    private static final int EXCEPTIONAL = 3; //异常状态
    private static final int CANCELLED = 4; //取消状态
    private static final int INTERRUPTING = 5; //处于正在中断的状态
    private static final int INTERRUPTED = 6; //已经被中断的状态

    //要被执行的逻辑，当被执行完后，会变为null
    private Callable<V> callable;
    //存储将要返回的结果或者是存储可以从get方法抛出的异常；它不是volatile的，读写受state状态的保护
    private Object outcome;
    //执行callable的线程
    private volatile Thread runner;
    //等待线程的栈
    private volatile WaitNode waiters;

    //这个方法会返回结果或者生成异常；参数 s是完成的标志
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        //如果是正常状态，代表执行正常，返回执行结果
        if (s == NORMAL)
            return (V) x;
        //如果线程被中断或者是执行被取消，会抛出异常
        if (s >= CANCELLED)
            throw new CancellationException();
        //不然就是执行期间出现错误
        throw new ExecutionException((Throwable) x);
    }

    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW; // ensure visibility of callable
    }

    /**
     * 给定的result可以作为执行完成后的一种标志，在执行完成后可以得到这个结果
     * 如果不想要这个结果可以设置为null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW; // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    //只要不是new就都是执行完毕的标志
    public boolean isDone() {
        return state != NEW;
    }

    //是否要将中断置位就看给定的参数，取消成功返回true，取消失败返回false
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW && STATE.compareAndSet(this, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        //如果state是NEW的状态，并且将STATE置位为INTERRUPTING或CANCELLED状态成功
        try {
            //如果要求中断线程，就应该将线程中断置位
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { //中断置位成功，state变为已经中断状态
                    STATE.setRelease(this, INTERRUPTED);
                }
            }
        } finally {
            //执行cancel方法时可能产生异常，因此无论如何只要执行了cancel，就应该尝试唤醒还在等待的线程
            finishCompletion();
        }
        return true;
    }

    //以下是阻塞等待结果的方法
    public V get() throws InterruptedException, ExecutionException {
        //如果state是执行完成（未设置最终状态）或者是NEW状态，就应该阻塞线程，等待结果
        int s = state;
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }

    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        //如果未执行完毕或者是等待超时，会产生Timeout异常
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * 这个方法在任务转换状态时调用isDone方法（无论任务正常或者取消状态）；
     * 实现者应该在完成执行任务时执行回调，或者bookkeeping。你可以在这个
     * 方法的实现内部查询状态，决定任务是否被取消了
     */
    protected void done() {}

    /**
     * 任务执行完成会将状态置位，结果置位，最后将状态置位为正常结束
     * 只有run方法执行成功，才会在其内部被调用
     */
    protected void set(V v) {
        if (STATE.compareAndSet(this, NEW, COMPLETING)) {
            outcome = v;
            STATE.setRelease(this, NORMAL); // final state
            finishCompletion();
        }
    }

    /**
     * 如果这个future没有被设置或被取消，就记录任务执行期间遇到的可抛出的异常，并给定可抛出的具体异常信息。
     * 这个方法会在run内部被调用
     */
    protected void setException(Throwable t) {
        //记录异常完成状态
        if (STATE.compareAndSet(this, NEW, COMPLETING)) {
            outcome = t;
            STATE.setRelease(this, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    public void run() {
        //不允许重新运行，或者运行期间也不允许再次运行

        //谁调用run方法谁就是这个Future的异步执行的线程
        if (state != NEW ||
                !RUNNER.compareAndSet(this, null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    //正常结束执行
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    //设置异常结束
                    setException(ex);
                }
                //正常结束执行就要设置结果并且将运行状态改为COMPLETION. NORMAL
                if (ran)
                    set(result);
            }
        } finally {
            // 在状态确定之前必须保证runner为空，避免并发调用run()
            runner = null;
            // 为了防止漏掉中断状态，必须重新读取状态，
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * 执行任务但不设置结果，然后将future设置为初始状态，重新执行的任务如果
     * 遇到异常或者中断，则整个过程也会失败，本质上是为多次执行任务而设计的
     *
     * ture if successfully run and reset
     */
    protected boolean runAndReset() {
        //如果任务处于执行完毕或其他非NEW状态，则不能被重新执行；如果当前任务已经有线程在执行，
        //则也不能执行重新run的操作
        if (state != NEW ||
                !RUNNER.compareAndSet(this, null, Thread.currentThread()))
            return false;
        //记录是否重新启动成功
        boolean ran = false;
        //当前任务的状态
        int s = state;
        try {
            Callable<V> c = callable;
            //任务没有被置为空并且任务必须是NEW的状态才可以执行
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // 在状态被设置之前runner必须是非空，防止并发的调用run方法
            runner = null;
            // 如果任务由于中断原因执行失败则必须处理中断，将任务的中断置位
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        //只有任务执行成功，并且任务的状态为NEW，方法才算调用成功
        return ran && s == NEW;
    }

    //确保任何来自可能cancel(true)的中断仅在run或runAndReset时传递给任务
    private void handlePossibleCancellationInterrupt(int s) {
        // 中断之前，调用interrupt的线程会暂停执行，在这里自旋等待一下
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // 我们想清除我们可能从cancel(true)中收到的任何中断。
        // 然而，允许使用中断作为一个独立的机制，让任务与它的调用者进行通信，
        // 并且没有办法只清除取消中断。
    }

    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    //移除并唤醒所有等待获取结果的线程最后调用done()方法
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null;) {
            //将waiters字段置为空，这里为了避免多线程的干扰采用cas
            if (WAITERS.weakCompareAndSet(this, q, null)) {
                for (;;) {
                    //如果节点中的thread还存活，则将其unpark
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    //清理q节点并将q置为下一个节点
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null; // help gc
    }

    /**
     * 等待完成或者从中断或取消时终止运行
     * 返回的是完成的状态或者超时的状态
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        // 以下代码非常的微妙，可以到达这种效果:
        // - 为每个调用的非常精确阻塞nanoTime
        // - if nanos <= 0L, 立即返回
        // - if nanos == Long.MIN_VALUE, 不会下溢
        // - if nanos == Long.MAX_VALUE, 并且nanoTime是非单调的，
        // 并且我们遭受了一个虚假的唤醒，我们将做的最糟糕的事情就是停顿一段时间。
        long startTime = 0L; // Special value 0L means not yet parked
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            int s = state;
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
            } else if (s == COMPLETING)
                // We may have already promised (via isDone) that we are done
                // so never return empty-handed or throw InterruptedException
                Thread.yield();
            else if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            } else if (q == null) {
                if (timed && nanos <= 0L)
                    return s;
                q = new WaitNode();
            } else if (!queued)
                queued = WAITERS.weakCompareAndSet(this, q.next = waiters, q);
            else if (timed) {
                final long parkNanos;
                if (startTime == 0L) { // first time
                    startTime = System.nanoTime();
                    if (startTime == 0L)
                        startTime = 1L;
                    parkNanos = nanos;
                } else {
                    long elapsed = System.nanoTime() - startTime;
                    if (elapsed >= nanos) {
                        removeWaiter(q);
                        return state;
                    }
                    parkNanos = nanos - elapsed;
                }
                // nanoTime may be slow; recheck before parking
                if (state < COMPLETING)
                    LockSupport.parkNanos(this, parkNanos);
            } else
                LockSupport.park(this);
        }
    }

    /**
     * 尝试在waiternode中去掉超时或被中断的线程减少垃圾的积累。
     * 内部节点在没有cas的情况下简单的解链。
     * 为了避免已经移除的节点对解链过程造成影响，当发生竞争时，
     * 就重新遍历节点。
     * 当node太多时，这个过程是很耗时的，当然这种情况不多见
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry: for (;;) { // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    } else if (!WAITERS.compareAndSet(this, q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    public String toString() {}

    // VarHandle mechanics
    private static final VarHandle STATE;
    private static final VarHandle RUNNER;
    private static final VarHandle WAITERS;
}
