# 线程池

## Executors概述

### 标准简述	

`Executor`接口是一个简单的标准化接口，用于用户自定义类线程子系统，包括线程池、异步I/O和轻量级任务框架。

​	`ExecutorService`是一个比较完善的异步任务执行框架。`ExecutorService`管理任务排队和调度，允许控制关闭。

​	`ScheduledExecutorService`添加了对延迟和定期任务执行的支持。

​	对于提交的任务类型为`Runnable`和`Callable`，`Future`可以得到异步执行的结果，`RunnableFuture`带有回调函数，可以在异步任务执行完毕后，执行指定的回调函数。

```java
public interface Executor {

    /**
     * 执行在将来给定的command任务，command任务的执行可能在一个新的线程中，或者是池化的线程中，亦或者是在调用者线程中
     * 如果当前任务不能被执行，就抛出RejectedExecutionException
     * 如果给定的任务是null，就抛出NullPointerException
     */
    void execute(Runnable command);
}
```

### 实现类

​	`ThreadPoolExecutor `和`ScheduledThreadPoolExecutor`类提供可调参灵活的线程池。`Executors`是一个Utils工具集，他提供`Future`的具体实现类`FutureTask`以及帮助协调异步任务组处理的`ExecutorCompletionService`

​	`ForkJoinPool`用于CPU密集型任务

### 重要接口

- Future接口

```java
//Future代表异步执行的结果
public interface Future<V> {
    /*
     尝试取消任务的执行：
     	· 如果任务已经完成或者取消（已经cancel或出现其他情况而取      * 消了）则不会有任何影响
     	· 如果任务还没开始运行就被取消，那么这个任务不会被执行
        · 如果任务正在执行，cacel方法会尝试是否停止执行任务（mayInterruptIfRunning参数决定）
     返回值不一定代表任务的执行是否被取消了，想要判断是否被取消，可以使用isCancelled方法判断
     	· 参数是ture，如果线程应该被中断，则方法返回true，否则线程应被允许完成执行
     	· 如果任务不能被取消，特别是任务已经完成了，方法会返回false
     	· 如果两个或更多线程导致一个任务被取消，那么至少一个返回ture
     */
    boolean cancel(boolean mayInterruptIfRunning);

    //在任务正常完成之前被cancel则返回ture
    boolean isCancelled();

    //无论任务是正常、异常、取消的结束了，都会返回true
    boolean isDone();

    /**
     * 等待任务的完成，并获取结果
     * @throws CancellationException 任务被取消
     * @throws ExecutionException 任务执行异常
     * @throws InterruptedException 任务waiting期间被中断
     */
    V get() throws InterruptedException, ExecutionException;

    /**
     * 等待指定的时间，如果完成了就返回结果，没有完成会抛出异常
     * @throws CancellationException 任务被取消
     * @throws ExecutionException 任务执行异常
     * @throws InterruptedException 任务waiting期间被中断
     * @throws TimeoutException 因任务未执行完毕而等待结果超时
     */
    V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;
}
```

- RunnableFuture接口

```
//run方法会管理任务的执行过程（设置状态，报告异常，执行任务）
public interface RunnableFuture<V> extends Runnable, Future<V> {
    //将Future设置为其执行的结果，除非它被取消
    void run();
}
```

- ExecutorService接口

```java
/*
	ExecutorService接口有可以产生Future的方法，和跟踪一个或多个异步任务的进度
	ExecutorService可以被shutdown，这将会导致它拒绝执行新到来的任务。它提供了两	种关闭的方式：
		· shutdown：这方法可以停止接收任务，并在执行完现在已经提交的所有任务后，停			止执行
		· shutdownNow：这个方法会停止执行已接收但等待执行的任务，并且会尝试终止目			 前正在执行的任务。
	在executor没有正在执行的或等待执行的任务，会回收资源关闭executor
*/
public interface ExecutorService extends Executor {

    /**
     * 启动有序关闭，停止接受新任务，执行所有已经提交的任务。如果所有任务已经完成，		则，该方法是非阻塞的。
     */
    void shutdown();

    /**
     * 尝试停止所有当前正在执行的任务，终止等待执行的任务。返回等待执行的任务（非阻		塞的）
     * 虽然可以尝试停止当前正在执行的任务，其方式也是用Thread的interrupt方法，对		   于不响应中断的线程，可能不会停止
     */
    List<Runnable> shutdownNow();

    //当前executor的是否已经shutdown
    boolean isShutdown();

    //当前executor是否已经终止运行
    boolean isTerminated();

    // 阻塞直到在shutdown请求后的所有任务被执行完或者当前线程被中断（这两个不论先	   后）
    boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException;

	//提交一个有返回值的任务，立即得到一个Future
    <T> Future<T> submit(Callable<T> task);
    //提交一个有返回值的任务，返回值就是result，立即得到Future
    <T> Future<T> submit(Runnable task, T result);
    //提交一个没有返回值的任务，立即得到Future
    Future<?> submit(Runnable task);

    /*
     提交一系列的任务，返回这些任务的future，当所有任务完成时，所有的future的isDone方法会返回ture
     切记，任务的完成可能是以正常的或异常的形式完成。如果这个集合被修改了，那么这个方法的结果将是未定义的			
     * @throws InterruptedException如果在等待结果时被中断那么还没有被执行的任务将会被取消执行
     * @throws NullPointerException 如果集合中的任何Callable为空
     * @throws RejectedExecutionException 如果集合中的任何任务不能被调度执行
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException;

    /**
     * 与上一个方法类似，但是在给定的内如果有未完成的任务，那就取消那些任务的执行
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                  long timeout, TimeUnit unit)
        throws InterruptedException;

    /**
     * 返回其中一个正常执行结束的任务的结果
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if tasks or any element task
     *         subject to execution is {@code null}
     * @throws IllegalArgumentException if tasks is empty
     * @throws ExecutionException 如果没有任何一个任务成功的执行
     * @throws RejectedExecutionException if tasks cannot be scheduled
     *         for execution
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException;

    /** 和上一个方法类似
     * @throws TimeoutException 如果在给定的时间之内，没有任何任务完成
     * @throws ExecutionException 如果没有任务成功的完成执行
     * @throws RejectedExecutionException 如果任务不能被调度执行
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                    long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;
}
```



## AbstractExecutorService 线程池框架的基础

​	参见AbstractExecutorService.java

## ThreadPoolExecutors核心

​	参见ThreadPoolExecutors.java

### 核心方法

- tryTerminated
- interruptIdleWorkers
- addWorker
- getTask
- runWorker
- execute(submit方法也会调用这个方法)
