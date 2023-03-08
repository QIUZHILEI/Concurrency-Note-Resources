# Fork/Join Pool Framework

## 预备知识

### 工作窃取机制

​	为了完成一个较大型的任务，我们利用分治算法的思想将其拆分为多个相同规模的子任务，然后将这些子任务分配给一些线程执行。执行过程中，如果线程A先执行完毕所有分配的任务，然而线程B还没有执行完，这时线程A将会在线程B的未完成的任务中取走任务并执行，这样既充分的发挥了计算性能，又减少了任务的执行时间。

​	在进行任务窃取的时候，一般采用的数据结构是双端队列，被窃取任务的线程只能在队头获取任务，窃取任务的线程只能在队尾窃取任务，这样做有效的避免了竞争。

​	work-stealing的优点：

- 充分利用计算机并行计算能力
- 有效的减少了线程的竞争（虽然还存在竞争，但是比较少）

## 摘要

​	这篇文章讲述了一个支持并行编程风格的框架。这个框架基于分治思想，总分总，围绕任务队列和工作线程的高效构建和管理的设计。并测试了性能，表明了可能的改进。

## 1. 简介

​	Fork/Join并行模式是最简单和最有效能获取高性能的一种设计。它的设计类似分治算法。Fork操作开始于一个新的并行的子任务，join会等到fork的所有子任务完成（确实就是分治思想：将任务多次划分，直到任务的规模足够小，将各个子任务处理完，然后逐渐合并得到最终结果）。

## 2. 设计

### 2.1 Work-stealing



## 3.  实现

### 3. 1 双端队列

### 3.2 窃取和闲置



## 4. 性能

### 4.1 速度

### 4.2 GC

### 4.3 内存位置和带宽

### 4.4 任务同步

### 4.5 任务本地

### 4.6 其他框架比较






