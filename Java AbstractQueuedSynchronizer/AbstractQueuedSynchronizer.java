import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;

/**
 * 这个类是一个框架，他依赖一个先进先出队的等待队列，用来实现阻塞索和同步器（例如：Semaphore）。
 * 此类被设计为对依赖单个原子int型值来表示状态的同步器是非常有用的。
 * 子类必须更改protected的方法，并定义state在获取和释放此对象方面的含义。此类中的其他方法执行所有排队和阻塞机制。
 * 子类可以维护其他字段，但仅跟踪使用 getState、setState 和 compareAndSetState 方法操作的原子更新的 int
 * 值以进行同步
 * 
 * 
 * 子类中应该这样使用它：例如Semaphore中 abstract static class Sync extends
 * AbstractQueuedSynchronizer然后static final class NonfairSync extends Sync
 * 在子类中定义非公共内部类，实现同步属性。子类可以适当的调用acquireInterruptibly之类的方法
 * 
 * 这个类支持默认独占和共享的一种或两种方式。独占模式获取锁时其他线程不会成功，多个线程在共享模式下获取锁时可能成功
 * 
 * 
 * 此类为内部队列提供检查、检测和监视方法，以及用于condition对象的类似方法。
 * 这些可以根据需要导出到类中，使用 AbstractQueuedSynchronizer 用于它们的同步机制。
 */

/*
 * 使用get、set、compareAndset State方法来更改同步状态
 * tryAcquire
 * tryRelease
 * tryAcquireShared
 * tryReleaseShared
 * isHeldExclusively
 * 以上的每个方法都会抛出一个 UnsupportedOperationException异常。
 * 实现这些方法必须是内部安全的、并且应该简短不应该阻塞。
 * 只有这些方法时使用此类中方法的合法手段，其他的都被定义为final的了。
 * 新来的线程可能会直接在排队或阻塞的线程之前获得了锁，从而破坏了公平性。因此你可以定义tryAcquire的行为来实现公平锁和非公平锁
 * 
 */

// 最终 你还可以使用Queue类和LockSupport来定义你自己的AQS

// AbstractQueuedLongSynchronizer 维护一个long型的原子变量，这个类维护的是int
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer {

    // 以下三个属性表示节点（线程）状态，他们可以作为参数或返回值

    // 线程处于等待状态（由于调用了某个同步器的lock或acquire方法为获取锁转而等待）
    static final int WAITING = 1; // 等待标志是 1
    // 线程被取消了（线程异常终止或）
    static final int CANCELLED = 0x80000000; // 取消标志是这个
    // 线程处于条件等待状态
    static final int COND = 2; // 条件等待是2
    // 同步状态位（比如一个资源的互斥访问，获取锁时state-1 ，释放时state+1）
    private volatile int state;
    // 等待队列头，懒初始化
    private transient volatile Node head;
    // 等待队尾，在初始化后，仅仅使用 casTail 进行更改操作
    private transient volatile Node tail;

    // 等待队列的节点对象
    abstract static class Node {
        volatile Node prev; // 前驱
        volatile Node next; // 后继
        Thread waiter; // 处于等待的线程（代表本节点）
        volatile int status; // 当前节点所代表的线程的状态

        // --------------------为清理队列节点使用
        // 设置前驱节点
        final boolean casPrev(Node c, Node v) { // for cleanQueue
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }

        // 设置后续节点
        final boolean casNext(Node c, Node v) { // for cleanQueue
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }
        // -----------------------

        // 将本节点上的线程的status取消设置，singal方法中使用
        final int getAndUnsetStatus(int v) { // for signalling
            // 获取值，并将现在的status设置为status按位与~v
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }

        // 设置本节点的前驱
        final void setPrevRelaxed(Node p) { // for off-queue assignment
            U.putReference(this, PREV, p);
        }

        // 设置status值
        final void setStatusRelaxed(int s) { // for off-queue assignment
            U.putInt(this, STATUS, s);
        }

        // 清除status 标志位为0 (在线程被unpark之后设置为)
        final void clearStatus() { // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }

    }

    // 独占模式节点
    static final class ExclusiveNode extends Node {
    }

    // 共享模式节点
    static final class SharedNode extends Node {
    }

    /*
     * ConditionNode可以被子类作为Condition的实现使用，用isHeldExclusively方法来判断，同步器是否被当前线程独占
     * 使用getState获取的值调用release方法来完全释放此对象，并根据保存的state值进行acquire操作，最终将此对象恢复到其先前获取的状态
     * 如果没有AbstractQueuedSynchronizer的方法创建此condition则不要使用它。
     * 
     * Condition Lock，必须是独占锁才可以使用，因此在进行await时是已经获取了锁，所以没有其他线程会对该条件队列并发的非安全的访问，
     * 所以其内部的队列修改都是非原子的
     */
    static final class ConditionNode extends Node
            implements ForkJoinPool.ManagedBlocker {
        ConditionNode nextWaiter; // link to next waiting node

        /**
         * Allows Conditions to be used in ForkJoinPools without
         * risking fixed pool exhaustion. This is usable only for
         * untimed Condition waits, not timed versions.
         * 
         * 处于运行或者是waiting状态的节点或者是中断置位的可以释放con lock
         */
        public final boolean isReleasable() {
            return status <= 1 || Thread.currentThread().isInterrupted();
        }

        public final boolean block() {
            while (!isReleasable())
                LockSupport.park();
            return true;
        }
    }

    // 有头尾部的单链表
    public class ConditionObject implements Condition, java.io.Serializable {

        // 队头队尾
        private transient ConditionNode firstWaiter;
        private transient ConditionNode lastWaiter;

        public ConditionObject() {
        }

        // 唤醒方法
        // singal和singnalAll的核心方法，他会唤醒一个或多个waiter（all参数代表唤醒一个还是多个），将其放入同步队列
        private void doSignal(ConditionNode first, boolean all) {
            while (first != null) {
                ConditionNode next = first.nextWaiter;
                if ((firstWaiter = next) == null) {
                    lastWaiter = null;
                }
                if ((first.getAndUnsetStatus(COND) & COND) != 0) {
                    enqueue(first);
                    if (!all) {
                        break;
                    }
                }
                first = next;
            }
        }

        public final void signal() {
            ConditionNode first = firstWaiter;
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            if (first != null)
                doSignal(first, false);
        }

        public final void signalAll() {
            ConditionNode first = firstWaiter;
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            if (first != null)
                doSignal(first, true);
        }

        // wait方法

        // 将node加入condition队列，并释放锁，返回状态位供后续重新acquire使用
        private int enableWait(ConditionNode node) {
            if (isHeldExclusively()) {
                node.waiter = Thread.currentThread();
                node.setStatusRelaxed(COND | WAITING);
                ConditionNode last = lastWaiter;
                if (last == null) {
                    firstWaiter = node;
                } else {
                    last.nextWaiter = node;
                }
                lastWaiter = node;
                // 释放锁，并且如果释放失败就会将节点状态置位为CANCELLED
                int saveState = getState();
                if (release(saveState)) {
                    return saveState;
                }
            }
            node.status = CANCELLED;
            throw new IllegalMonitorStateException();
        }

        // 如果node已经被放入condition queue，且node可以再次从sync queue 进行acquire则返回true
        private boolean canReacquire(ConditionNode node) {
            // 检查链接，而不是status，避免入队race
            // 只有在node不为null，并且node没有前驱（是第一个节点才有权利acquire）并且已经进入sync队列，才是可acquire的
            return node != null && node.prev != null && isEnqueued(node);
        }

        // 清理condition队列，去掉给定的node和非waiting位的node
        private void unlinkCancelledWaiters(ConditionNode node) {
        }

        /*
         * 不可中断的await方法
         * 1. 保存有getState方法获取的状态位
         * 2. 将保存的状态位为参数调用release方法，如果失败抛出IllegalMonitorStateException
         * 3. 阻塞直到singnal
         * 4. 重新acquire通过指定版本的acquire方法，参数是保存的状态位
         */
        public final void awaitUninterruptibly() {
        }

        // 可中断的await方法
        /*
         * 1.如果当前线程被中断了，就抛出异常
         * 2.释放锁，获取保存的状态
         * 3.阻塞，直到signal信号或者被中断
         * 4.被signal之后，就重新去抢锁
         * 5.如果
         */
        public final void await() throws InterruptedException {
            // 新建一个cond node
            ConditionNode node = new ConditionNode();
            // 释放锁
            int saveState = enableWait(node);
            LockSupport.setCurrentBlocker(this);
            boolean interrupted = false, cancelled = false, rejected = false;
            // 当不满足重新acquire的条件时
            while (!canReacquire(node)) {
                // 首先检查线程当前中断是否置位
                if (interrupted |= Thread.interrupted()) {
                    // 不是运行状态
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0) {
                        break;
                    }
                } else if ((node.status & COND) != 0) {
                    try {
                        if (rejected) {
                            node.block();
                        } else {
                            ForkJoinPool.managedBlock(node);
                        }
                    } catch (RejectedExecutionException ex) {
                        rejected = true;
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                }
            }

            // 退出了循环，就是unpark完成，或者是中断置位了
            LockSupport.setCurrentBlocker(null);
            node.clearStatus();
            // 为了确保线程处理中断置位后的事情，还是要抢锁
            acquire(node, savedState, false, false, false, 0L);
            if (interrupted) {
                if (cancelled) {
                    unlinkCancelledWaiters(node);
                }
                Thread.currentThread().interrupt();
            }

        }

        // 可中断且有时间限制
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
        }

        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {

        }

        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
        }

        // 调试方法
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                if ((w.status & COND) != 0)
                    return true;
            }
            return false;
        }

        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                if ((w.status & COND) != 0)
                    ++n;
            }
            return n;
        }

        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<>();
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                if ((w.status & COND) != 0) {
                    Thread t = w.waiter;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    // 提供的三个获取或更改同步状态的方法 在执行获取和释放锁时候需要用到
    /*
     * protected final int getState() {}
     * protected final void setState(int newState) {}
     * protected final boolean compareAndSetState(int expect, int update) {}
     */

    // 以下是对队列的操作
    // cas操作进行实例进队
    private boolean casTail(Node c, Node v) {
        return U.compareAndSet(this, TAIL, c, v);
    }

    // 头节点懒初始化，head和tail同时指向同一个null节点，只需cas一次，因为在tail为空时，其他出队操作也不会成功，都会来初始化头部，而仅有一个线程可以将队头初始化成功
    private void tryInitializeHead() {
        Node h = new ExclusiveNode();
        if (U.compareAndSet(this, HEAD, null, h))
            tail = h;
    }

    // 入队操作 这个操作目前只用于ConditionNodes，其他的情况则于acquire类似的方法交替使用
    final void enqueue(Node node) {
        // 从队尾入队
        if (node != null) {
            for (;;) {
                Node t = tail;
                node.setPrevRelaxed(t);
                if (t == null) {
                    tryInitializeHead();
                } else if (casTail(t, node)) {
                    // 如果入队成功,连接好队列
                    t.next = node;
                    // 这时如果t作为node的前驱cacelled被置位，那么node节点应该得到执行
                    if (t.status < 0) {
                        LockSupport.unpark(node.waiter);
                    }
                    break;
                }
            }
        }
    }

    // 查找某个节点是否在队列中（从后向前找） 非cas的
    final boolean isEnqueued(Node node) {
        for (Node t = tail; t != null; t = t.prev)
            if (t == node)
                return true;
        return false;
    }

    // -------------以下为主要的acquire和signal方法
    // 唤醒某个节点的后继节点，使其避免park竞争。这个操作可能会由于一个或多个线程的取消而无法被唤醒，但cancelAcquire保证了其有效性
    // Signal只负责唤醒节点，不负责维护队列
    private static void signalNext(Node h) {
        Node s;
        // 当前节点不为null，并且当前节点有后继，并且后继状态不是可运行态
        if (h != null && (s = h.next) != null && s.status != 0) {
            // 清除等待状态
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    private static void signalNextIfShared(Node h) {
        Node s;
        if (h != null && (s = h.next) != null &&
                (s instanceof SharedNode) && s.status != 0) {
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    // !! acquire的主方法:
    /*
     * node:如果是Condition条件队列里传递过来的node，则他可能有前驱节点并且不为null
     * arg:参数
     * shared:是否是共享模式
     * interruptible:是否可中断
     * timed:是否是超时等待
     * time:如果是超时等待，等待多久
     * 
     * 核心是：
     * 检查node是否为第一个：
     * 是：初始化头部
     * 不是：确保有效前驱
     * 如果node是第一个或者还没有进队，则尝试tryAcquire
     * 或者如果node还没有被创建，则创建它
     * 或者如果node还没有进队，则尝试一次进队
     * 或者如果从park中被唤醒，重新尝试
     * 或者如果WAITING状态没有被设置则设置
     * 不然就阻塞、然后清除status(0)然后检查cancellation位
     */

    final int acquire(Node node, int arg, boolean shared,
            boolean interruptible, boolean timed, long time) {
        Thread current = Thread.currentThread();
        byte spins = 0, postSpins = 0; // 当第一个线程unpark时尝试
        boolean interrupted = false, first = false;
        Node pred = null; // node进队后的前驱节点（主要用于标记Condition队列里传递过来的节点）

        for (;;) {
            /*
             * 首先确保队列中的线程都是waiting/cond，如果存在CANCELLED状态的需要执行清理操作，
             * 因此，如果节点不是第一个，且有前驱，并且前驱不是head时，就要检测前驱节点是否是有
             * 效的状态，如果前驱节点是无效状态，则执行队列清理工作
             */
            if (!first && (pred = (node == null) ? null : node.prev) != null && !(first = (head == pred))) {
                if (pred.status < 0) {
                    cleanQueue();
                } else if (pred.prev == null) {
                    Thread.onSpinWait(); // 确保序列化
                    continue;
                }
            }
            /*
             * 如果节点是第一个节点或者他没有前驱，那么说明他是可以竞争锁的节点，因此应该执行一次acqiure操作
             */
            if (first || pred == null) {
                boolean acquired;
                // 抢锁过程可能会触发异常
                try {
                    if (shared) {
                        acquired = (tryAcquireShared(arg) >= 0);
                    } else {
                        acquired = tryAcquire(arg);
                    }
                } catch (Throwable ex) {
                    // 如果抢锁过程出现异常要确保该节点是无效节点
                    cacelRequire(node, interrupted, false);
                    throw ex;
                }
                // 如果抢得了锁，本节点就应该清空节点的状态,并且是第一个节点(说明之前已经在队列里了)
                if (acquired) {
                    if (first) {
                        // 当前节点就作为头
                        node.prev = null;
                        // 这里更改head不需要是原子操作，因为这个节点是第一个，说明head有效，并且只有他能够操作head
                        head = node;
                        pred.next = null;
                        node.waiter = null;
                        // 同一时间只有一个线程能获取锁，如果锁是共享锁，那么就应该让后续节点也可以继续抢锁
                        if (shared) {
                            signalNextIfShared(node);
                        }
                        // 如果当前线程抢锁超时，应该被中断抢锁
                        if (interrupted) {
                            current.interrupt();
                        }
                    }
                    return 1;
                }
            }
            // 抢锁失败，如果node还没有被创建（这是新的竞争锁的线程）
            if (node == null) {
                if (shared) {
                    node = new SharedNode();
                } else {
                    node = new ExclusiveNode();
                }
            }
            // 如果node已经被创建，但是node没有有效的前驱（前面已经抢过锁,到这里说明抢锁失败），则应该入队
            else if (pred == null) {
                node.waiter = current;
                Node t = tail;
                // 先让node链接tail，之后确保tail不变，才能将node设置为tail
                node.setPrevRelaxed(t);
                // 如果这时tail是null，说明队列为初始状态，应该初始化
                if (t == null) {
                    tryInitializeHead();
                } 
                //如果入队未成功应该断开与队列的链接
                else if (!casTail(t, node)) {
                    node.setPrevRelaxed(null);
                }
                //如果入队成功则将队列拼接完整
                 else {
                    t.next = node;
                }
            }

            // --------------在这之后，有可能在队头的节点线程unpark之后再次抢锁失败

            /*
             * 共享状体起作用：
             * 在非公平的抢锁情况下，如果被首个节点代表的线程A被unpark之后，线程只是进入了RUNNABLE状态，
             * 线程可能没有被系统调度进入执行状态（从RUNNABLE到RUNNING是需要一点时间的），此时如果有一
             * 个线程B来抢锁，这个线程A抢锁的成功几率是很小的，虽然共享状态是允许再次被抢锁，但是抢锁的
             * 一瞬间是互斥的，如果这个线程A抢共享锁失败，不应该很快的陷入阻塞状态，为了避免这种情况，
             * 这里的设计就是让线程A先自旋，然后重试抢锁，而且万一自旋后也抢锁失败，并再次陷入阻塞，
             * 那么线程A会被B调用signalNextIfShared释放，可以再次抢锁(如果B先调用unpark(A)，A后
             * 调用park，A是不会被阻塞的，这就是LockSupport的优点)，但是每次阻塞都会使线程A被要求自
             * 旋的次数增加，这样线程A抢锁的次数就会增多，那么它抢锁成功的概率就会随阻塞次数而大大增加(有点像响应比调度)
             */ 
            else if (first && spins != 0) {
                --spins;
                Thread.onSpinWait();
            }
            /*
             * 到这一步已经入队，但是可能这时锁被释放，如果没有这个分支判断，直接进入阻塞状态，可能错过了能够直接抢锁的机会，
             * 而这一步就会使线程重新试一遍抢锁，可以避免错过成功抢锁的机会。
             */
            else if (node.status == 0) {
                node.status = WAITING;
            }
            // 最终抢锁失败并且状态位已设置，应该阻塞
            else {
                // 类似响应比，如果重复阻塞次数越多，抢锁的次数（spins）就会越多，获得锁的几率就越大
                long nanos;
                spins = postSpins = (byte) ((postSpins << 1) | 1);

                // 非超时的park
                if (!timed) {
                    LockSupport.park(this);
                }
                // 超时的park
                else if ((nanos = time - System.nanoTime()) > 0L) {
                    LockSupport.park(this, nanos);
                } 
                //如果等待超时则应该是获取锁失败跳出循环
                else {
                    break;
                }
                node.clearStatus();
                // 判断线程是否已经中断，如果是则interrupted变量变为true，并且如果是可中断的就会退出循环，并cancelAcquire
                if ((interrupted |= Thread.interrupted()) && interruptible) {
                    break;
                }
            }
        }
        //可以因为线程被中断或者park超时导致获取锁失败，这时要对node进行清理工作
        return cancelAcquire(node, interrupted,interruptible);
    }

    // 可能从尾部开始，重复遍历，去除已经被cancel的节点，直到没有cancel的节点。unpark可能已经被重新连接的节点的标记，成为下一个回合的合格的能够竞争锁的人
    /*
     * 思想（线程不安全情况下）：(注意：断开链接之前要时刻注意如果队列已经变化，应该回到队尾重新开始，也因此清理完一个节点就要重新从队尾开始)
     * (1)清理双向链表，涉及断开链接，因此要知道当前节点的前驱和后继，(q,p,s)
     * (2)从表尾开始向前清理，q=tail，首先如果q为null，直接返回，如果此时q被其他线程操作断掉链接，这时候也不能继续执行应该返回
     */

    // !TODO()
    private void cleanQueue() {
        for (;;) {
            for (Node q = tail, s = null, p, n;;) {
                if (q == null || (p = q.prev) == null) {
                    return;
                }
            }
        }
    }

    // 取消一个正在acquire的尝试，node:如果在排队前取消可能为空，如果interrupted位真，则interruptible为真，应该报告中断并使中断置位
    private int cancelAcquire(Node node, boolean interrupted,
            boolean interruptible) {
        // 清空node的所有状态
        if (node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            // 如果node在队列中，那必须清理队列
            if (node.prev != null) {
                cleanQueue();
            }
        }
        // 如果线程被中断了，如果是可中断的，说明线程已经被中断就返回CANCELLED状态，如果不是可中断，说明其他原因造成中断，因此需要将中断置位
        if (interrupted) {
            if (interruptible) {
                return CANCELLED;
            } else {
                Thread.currentThread().interrupt();
            }
        }
        return 0;
    }

    // 下面开始是为使用者暴露的方法

    /*
     * 这个方法在独占模式下尝试获取锁。这个方法应该查询state位是否允许获取（在独占模式下），如果允许则获取。
     * 这个方法经常被执行acquire的线程所调用。如果该方法报告失败，则调用者的线程如果还没有入队，则入队等候，直到其他线程的释放信号将其唤醒。
     * 这个可以用来实现Lock.tryLock()
     * 
     * params：arg参数会被传递给acquire方法，或者是进入Condition等待时保存，这个值是自定义的，是用户赋予其意义的
     * returns: 函数在获取成功时应该返回true，一旦返回true，就代表获取成功（获取锁）
     * throws:
     * IllegalMonitorStateException->如果acquire将同步器state处于非法状态，则该异常必须以一致的方式抛出。
     * UnsupportedOperationException - 如果不支持独占模式
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /*
     * 试图设置状态以反映独占模式下的释放。这个方法总是被执行释放的线程调用。
     * 
     * params：释放参数。这个值总是传递给释放方法的值，或者进入条件等待时的当前状态值。否则，这个值是不被解释的，可以代表任何你喜欢的东西。
     * returns: true 如果这个对象现在处于完全释放的状态，那么任何等待的线程都可以尝试获取；否则就是false。
     * throws:
     * IllegalMonitorStateException->如果release将同步器state处于非法状态，则该异常必须以一致的方式抛出。
     * UnsupportedOperationException - 如果不支持独占模式
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    // 返回的是还可以获取的资源数——>正值，代表还可以获取的资源数，0，代表当前没有线程被阻塞在acquire方法，也没有可用资源数；负值，代表有当前有多少个线程被阻塞在acquire方法
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    // 如果这个释放操作可能允许一个等待的获取成功，则为true，否则为false
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    // 这个方法如果不使用Condition则无需定义，如果同步器（锁）被当前线程持有，则true；这个方法只在AbstractQueuedSynchronizer.ConditionObject方法中被内部调用
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    // 这个方法在独占模式下使用，忽视中断。至少一次tryAcquire，直到成功返回，不成功就将线程入队，可能会重复阻塞、非阻塞
    public final void acquire(int arg) {
        if (!tryAcquire(arg)) {
            acquire(null, arg, false, false, false, 0L);
        }
    }

    // 类似上面的方法，只是会等到获取成功，或发生中断（会抛出异常）
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted() || (!tryAcquire(arg) && acquire(null, arg, false, true, false, 0L) < 0)) {
            throw new InterruptedException();
        }
    }

    // 尝试以独占模式获取，如果超过了给定时间则失败。也是至少调用一次tryAcquire，成功则返回，不成功则加入阻塞队列，线程可能被反复阻塞、解阻塞，直到成功或超时失败、或中断异常
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // 如果中断没有被置位
        if (!Thread.interrupted()) {
            if (tryAcquire(arg))
                return true;
            if (nanosTimeout <= 0)
                return false;
            int stat = acquire(null, arg, false, false, true, System.nanoTime() + nanosTimeout);
            if (stat > 0)
                return true;
            if (stat == 0)
                return false;
        }
        throw new InterruptedException();

    }

    // 在独占模式下释放，如果tryRelease返回true，则通过解除一个或多个线程的阻塞来实现。用来实现Lock.unlock
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            signalNext(head);
            return true;
        }
        return false;
    }

    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            acquire(null, arg, true, false, false, 0L);

    }

    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted() ||
                (tryAcquireShared(arg) < 0 &&
                        acquire(null, arg, true, true, false, 0L) < 0))
            throw new InterruptedException();
    }

    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (!Thread.interrupted()) {
            if (tryAcquireShared(arg) >= 0)
                return true;
            if (nanosTimeout <= 0L)
                return false;
            int stat = acquire(null, arg, true, true, true,
                    System.nanoTime() + nanosTimeout);
            if (stat > 0)
                return true;
            if (stat == 0)
                return false;
        }
        throw new InterruptedException();
    }

    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            signalNext(head);
            return true;
        }
        return false;
    }

    // -------------以下是监测队列的方法

    // 是否有等待获取锁的线程，由于线程可能在任何时刻超时或中断，因此即使返回真值，也不能保证一定就有线程等待获取锁
    public final boolean hasQueuedThreads() {
        for (Node p = tail, h = head; p != h && p != null; p = p.prev)
            if (p.status >= 0)
                return true;
        return false;
    }

    // 查询是否有线程竞争过同步器
    public final boolean hasContended() {
        return head != null;
    }

    // 返回等待时间最长的线程（队头）!TODO()
    public final Thread getFirstQueuedThread() {
        Thread first = null, w;
        Node h, s;
        if ((h = head) != null && ((s = h.next) == null ||
                (first = s.waiter) == null ||
                s.prev == null)) {
            // traverse from tail on stale reads
            for (Node p = tail, q; p != null && (q = p.prev) != null; p = q)
                if ((w = p.waiter) != null)
                    first = w;
        }
        return first;
    }

    // 这个方法返回给定的线程是否在等待队列
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.waiter == thread)
                return true;
        return false;
    }

    //
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null && (s = h.next) != null &&
                !(s instanceof SharedNode) && s.waiter != null;
    }

    public final boolean hasQueuedPredecessors() {
        Thread first = null;
        Node h, s;
        if ((h = head) != null && ((s = h.next) == null ||
                (first = s.waiter) == null ||
                s.prev == null))
            first = getFirstQueuedThread(); // retry via getFirstQueuedThread
        return first != null && first != Thread.currentThread();
    }

    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.waiter != null)
                ++n;
        }
        return n;
    }

    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.waiter;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!(p instanceof SharedNode)) {
                Thread t = p.waiter;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p instanceof SharedNode) {
                Thread t = p.waiter;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    public String toString() {
        return super.toString()
                + "[State = " + getState() + ", "
                + (hasQueuedThreads() ? "non" : "") + "empty queue]";
    }

    // 以下方法是使用Condition方法时特有的
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

}