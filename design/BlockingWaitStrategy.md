Disruptor 源码解析：BlockingWaitStrategy

BlockingWaitStrategy 是 Disruptor 中一种基于 Lock + Condition 实现的阻塞式等待策略。

它的核心思想是：

当消费者需要的数据尚未发布时，让消费者线程进入阻塞状态，而不是持续占用 CPU 自旋；当生产者发布数据后，再通过条件变量唤醒等待中的消费者。

结合你前面学习的 SingleProducerSequencer 和 ProcessingSequenceBarrier，本次重点分析：

1. BlockingWaitStrategy 的源码结构。
2. waitFor() 的执行流程。
3. signalAllWhenBlocking() 为什么需要加锁。
4. cursorSequence 和 dependentSequence 的区别。
5. 为什么使用两层循环，以及如何避免丢失唤醒。
6. 与其他 WaitStrategy 的性能对比。

下面以 Disruptor 3.x 中的经典实现为基础进行分析。不同版本的具体实现可能略有差异。

⸻

一、BlockingWaitStrategy 在 Disruptor 中的位置

先回顾一下整体调用链。
```
生产者线程
|
v
RingBuffer.publish(sequence)
|
v
SingleProducerSequencer.publish()
|
v
更新生产者发布游标
|
v
signalAllWhenBlocking()
|
| 唤醒等待线程
v
消费者线程
|
v
BatchEventProcessor.run()
|
v
ProcessingSequenceBarrier.waitFor(sequence)
|
v
BlockingWaitStrategy.waitFor()
|
v
检查目标序列是否可用
|
v
返回可用序列
|
v
EventHandler.onEvent()
```
这里需要注意一个细节：

生产者发布数据后，通常由 Sequencer 调用等待策略的 signalAllWhenBlocking()，而不是直接操作消费者线程。

因此，BlockingWaitStrategy 不需要知道具体有哪些消费者，也不需要维护消费者线程列表。

它只负责两件事：

* 消费者等待数据时，判断是否需要阻塞。
* 有新数据发布时，唤醒可能正在等待的消费者。

⸻

二、BlockingWaitStrategy 源码结构

Disruptor 3.x 的经典实现如下：
```
public final class BlockingWaitStrategy implements WaitStrategy
{
private final Lock lock = new ReentrantLock();
private final Condition processorNotifyCondition =
lock.newCondition();
@Override
public long waitFor(
final long sequence,
final Sequence cursorSequence,
final Sequence dependentSequence,
final SequenceBarrier barrier)
throws AlertException, InterruptedException
{
long availableSequence;
if (cursorSequence.get() < sequence)
{
lock.lock();
try
{
while (cursorSequence.get() < sequence)
{
barrier.checkAlert();
processorNotifyCondition.await();
}
}
finally
{
lock.unlock();
}
}
while ((availableSequence = dependentSequence.get()) < sequence)
{
barrier.checkAlert();
Thread.yield();
}
return availableSequence;
}
@Override
public void signalAllWhenBlocking()
{
lock.lock();
try
{
processorNotifyCondition.signalAll();
}
finally
{
lock.unlock();
}
}
}
```
这是一个经典版本的实现示例，保留了核心等待逻辑。

先观察它的结构：
```
if (cursorSequence.get() < sequence)
{
// 第一阶段：等待生产者发布数据
}
while ((availableSequence = dependentSequence.get()) < sequence)
{
// 第二阶段：等待依赖消费者推进
Thread.yield();
}
```

这是理解 BlockingWaitStrategy 最重要的地方：它并不是从头到尾都使用阻塞等待。

它分成两个阶段：

阶段	检查对象	等待方式
第一阶段	生产者发布游标 cursorSequence	Condition.await() 阻塞等待
第二阶段	依赖序列 dependentSequence	Thread.yield() 让出执行机会并反复检查

为什么这样设计？下面结合源码逐步分析。

⸻

三、第一阶段：为什么使用 Condition.await()？

核心源码：
```
if (cursorSequence.get() < sequence)
{
lock.lock();
try
{
while (cursorSequence.get() < sequence)
{
barrier.checkAlert();
processorNotifyCondition.await();
}
}
finally
{
lock.unlock();
}
}
```
假设消费者准备处理序列号 100：

sequence = 100;

但生产者当前只发布到了：

cursorSequence = 95

显然：

cursorSequence.get() < sequence

结果为 true。

此时消费者还不能处理序列号 100，需要等待。

1. 为什么不一直循环检查？

一种简单的实现方式是：
```
while (cursorSequence.get() < sequence)
{
// 不断检查
}
```
这属于忙等待，也就是 Busy Spin。

假设生产者需要 10ms 才能发布下一批数据，消费者就可能在这 10ms 内持续占用一个 CPU 核心。

如果有 20 个消费者都在等待数据，就可能浪费大量 CPU 资源。

BlockingWaitStrategy 采用另一种方式：

processorNotifyCondition.await();

消费者进入等待状态，暂时不再持续执行检查循环。

当生产者发布数据并发出通知后，消费者才有机会恢复执行。

两种方式的区别
```
Busy Spin：
while (cursor < target) {
// 持续检查
}
Blocking：
while (cursor < target) {
condition.await();
}
```

Busy Spin 的优势是避免了线程阻塞与唤醒的部分开销，因此可能获得更低的唤醒延迟。

Blocking 的优势是消费者等待时 CPU 消耗通常更低。

⸻

2. 为什么要先 lock.lock()？
```
lock.lock();
try
{
while (cursorSequence.get() < sequence)
{
barrier.checkAlert();
processorNotifyCondition.await();
}
}
finally
{
lock.unlock();
}
```
因为 Condition 必须关联一个 Lock，并且调用 await() 时必须持有该锁。

await() 会完成两个关键动作：

1. 释放当前持有的锁。
2. 让当前线程进入等待状态。

当线程被唤醒后，它会重新竞争并获取这把锁，随后从 await() 返回。

可以将其理解为：
```
消费者线程
|
v
lock.lock()
|
v
检查 cursorSequence
|
| 数据尚未就绪
v
condition.await()
|
+---- 释放锁并等待
|
| 生产者发出通知
v
重新竞争并获取锁
|
v
await() 返回
|
v
重新检查 cursorSequence
```
注意：被唤醒不等于数据已经就绪。

线程可能因为其他通知、虚假唤醒或竞争而恢复执行，因此必须重新检查条件。

⸻

四、为什么必须使用 while，而不是 if？

这是 Java 并发面试中非常经典的问题。

源码：
```
while (cursorSequence.get() < sequence)
{
barrier.checkAlert();
processorNotifyCondition.await();
}
```
假设错误地写成：
```
if (cursorSequence.get() < sequence)
{
barrier.checkAlert();
processorNotifyCondition.await();
}
```
会有什么问题？

场景一：虚假唤醒

Java 的 Condition.await() 允许出现虚假唤醒。

也就是说，即使没有收到预期的通知，线程也可能从等待中返回。

如果使用 if：
```
cursor = 95
target = 100
进入 await()
|
v
发生虚假唤醒
|
v
await() 返回
|
v
直接继续执行
```
此时生产者仍然只发布到了 95。

消费者却可能错误地认为等待已经结束。

如果使用 while：
```
cursor = 95
target = 100
进入 await()
|
v
发生虚假唤醒
|
v
await() 返回
|
v
重新检查 cursor
|
v
95 < 100
|
v
再次 await()
```
这样才能保证线程只有在条件满足后才离开等待循环。

场景二：多个消费者被同时唤醒

经典实现使用：

processorNotifyCondition.signalAll();

这会唤醒所有在该 Condition 上等待的线程。

例如：
```
Consumer A：等待 sequence = 100
Consumer B：等待 sequence = 200
Consumer C：等待 sequence = 300
```
生产者发布到了 100，然后执行：

processorNotifyCondition.signalAll();

三个消费者都可能被唤醒。

但是：

* A 的等待条件已经满足。
* B 的等待条件还没有满足。
* C 的等待条件还没有满足。

因此，B 和 C 必须重新检查条件，并继续等待。

这也是使用 while 的重要原因。

结论：使用 while，而不是 if，是为了保证每次被唤醒后都重新验证等待条件。

⸻

五、第二阶段：为什么还有 Thread.yield()？

继续看源码：
```
while ((availableSequence = dependentSequence.get()) < sequence)
{
barrier.checkAlert();
Thread.yield();
}
```

这一段很容易被误解。

前面不是已经使用了阻塞等待吗？

为什么后面又使用：

Thread.yield();

原因在于：生产者已经发布数据，不代表消费者依赖的前置消费者已经处理完成。

1. 生产者游标与依赖序列有什么区别？

假设存在如下依赖关系：
```
Producer
|
v
Consumer A
|
v
Consumer B
```
Consumer B 必须等待 Consumer A 处理完成后，才能处理对应序列。

现在假设：

生产者发布进度：1000
Consumer A 进度：900
Consumer B 进度：899

Consumer B 希望处理：

sequence = 950

此时：

cursorSequence = 1000
dependentSequence = 900

第一阶段的条件：

cursorSequence.get() < sequence

结果为：

1000 < 950  // false

因此，Consumer B 不需要等待生产者发布数据。

但是，第二阶段的条件：

dependentSequence.get() < sequence

结果为：

900 < 950  // true

说明 Consumer A 还没有处理到序列号 950。

Consumer B 必须等待。

2. 为什么不继续使用 Condition.await()？

经典 BlockingWaitStrategy 的第二阶段采用：

Thread.yield();

而不是再次调用：

processorNotifyCondition.await();

主要原因是这套通知机制通常由生产者发布数据时触发，而不是每个依赖消费者更新自己的 Sequence 时都触发。

如果消费者在等待依赖序列时直接进入 Condition 阻塞，而前置消费者推进 Sequence 时又没有发出对应通知，那么它可能无法及时恢复执行。

使用 Thread.yield() 可以反复检查依赖序列，同时尝试让其他线程获得执行机会。

不过，Thread.yield() 并不保证操作系统一定会切换到其他线程，也不保证降低 CPU 使用率。

它只是向调度器表达一个让出当前执行机会的提示。

因此，第二阶段虽然不是纯粹的忙自旋，但仍可能持续消耗 CPU。

⸻

六、signalAllWhenBlocking()：生产者如何唤醒消费者？

核心源码：
```
@Override
public void signalAllWhenBlocking()
{
lock.lock();
try
{
processorNotifyCondition.signalAll();
}
finally
{
lock.unlock();
}
}
```
这个方法与前面的 await() 形成配合。

当生产者发布事件后，Sequencer 通常会调用：

waitStrategy.signalAllWhenBlocking();

随后执行：

processorNotifyCondition.signalAll();

通知所有正在等待该 Condition 的线程。

1. 为什么 signalAll() 也需要加锁？

Java 的 Condition 要求调用 signal() 或 signalAll() 的线程持有关联的锁。

因此，需要：

lock.lock();

然后：

processorNotifyCondition.signalAll();

最后释放锁：

lock.unlock();

如果调用时没有持有锁，会抛出 IllegalMonitorStateException。

2. 为什么使用 signalAll，而不是 signal？

processorNotifyCondition.signalAll();

会通知所有等待该 Condition 的线程。

而：

processorNotifyCondition.signal();

只会选择一个等待线程进行唤醒。

考虑以下场景：
```
共享 RingBuffer
Consumer A 等待序列 100
Consumer B 等待序列 200
Consumer C 等待序列 300
```
当生产者发布数据时，具体哪些消费者能够继续执行，取决于各自的目标序列以及依赖关系。

使用 signalAll() 可以避免只唤醒一个不满足条件的消费者，而其他满足条件的消费者却继续阻塞的情况。

代价是：被唤醒的线程需要重新竞争锁，并重新检查条件。

这可能产生一定的唤醒开销和锁竞争。

⸻

七、重点：为什么这里通常不会丢失唤醒？

这是理解 BlockingWaitStrategy 最有价值的部分之一。

考虑下面的时序：
```
消费者线程                         生产者线程
检查 cursor < target
发现条件不满足
准备 await()
发布事件
signalAll()
进入等待
```
如果消费者检查条件之后、真正进入等待之前，生产者就完成了通知，会不会发生：

生产者已经通知完毕，但消费者随后才进入等待，导致消费者一直睡眠？

如果设计不正确，确实可能发生这种丢失唤醒问题。

但是，经典实现通过同一把 lock 协调检查条件与通知操作，避免了这个关键竞态。

我们看两种情况。

情况一：消费者先拿到锁
```
消费者                           生产者
lock.lock()
检查 cursor < target
进入 await()
|
| await 原子地释放锁并等待
v
等待状态
lock.lock()
发布通知
signalAll()
unlock()
消费者被唤醒
```
这里的关键是：

Condition.await() 会原子地释放关联锁并进入等待流程。

生产者必须获取同一把锁才能调用 signalAll()。

因此，生产者不会在消费者仍然持有锁、尚未进入 await() 的关键窗口内完成通知。

情况二：生产者先拿到锁
```
生产者                           消费者
lock.lock()
发布事件
更新发布游标
signalAll()
unlock()
lock.lock()
检查 cursor < target
条件已经满足
不调用 await()
```
即使通知发生在消费者开始等待之前，消费者也会重新检查条件，发现数据已经就绪，因此不会进入等待状态。

这就是条件变量编程的核心原则：

使用同一把锁保护等待条件检查和通知过程，并在 while 循环中检查条件，避免丢失唤醒和虚假唤醒导致的错误。

注意：这里的安全性还依赖于生产者正确的发布顺序、游标更新方式以及 Sequencer 与等待策略的配合，而不仅仅来自 signalAll() 本身。

⸻

八、为什么先检查 cursorSequence，再检查 dependentSequence？

这是这个实现中很重要的一个性能设计。

源码逻辑：
```

if (cursorSequence.get() < sequence)
{
// 阻塞等待生产者发布
}
while ((availableSequence = dependentSequence.get()) < sequence)
{
// 等待依赖消费者推进
}
```

我们可以把它理解为两道检查。

第一关：生产者有没有发布？

cursorSequence.get() >= sequence

如果不满足，就没有必要继续等待依赖消费者，因为对应的事件可能还没有发布。

所以先阻塞等待生产者。

第二关：前置消费者有没有处理完成？

dependentSequence.get() >= sequence

只有当前消费者存在依赖关系时，这个检查才具有实际意义。

如果前置消费者尚未完成对应序列，就需要继续等待。

完整流程如下：
```
消费者请求 sequence
|
v
生产者游标 >= sequence ?
|
+--+--+
|     |
否     是
|     |
v     v
Condition  检查依赖序列
await()         |
|           v
|      依赖序列 >= sequence ?
|           |
|        +--+--+
|        |     |
|       否     是
|        |     |
|        v     v
|     yield   返回可用序列
|
+--重新检查
```
这样可以避免消费者在生产者尚未发布数据时持续占用 CPU 检查依赖序列。

⸻

九、没有依赖消费者时会发生什么？

这里有一个值得注意的实现细节。

ProcessingSequenceBarrier 在没有依赖消费者时，通常会把 dependentSequence 设为 null。

因此，创建 Barrier 时可能会使用生产者游标作为实际等待策略的依赖序列参数：

dependentSequence == null
? cursorSequence
: dependentSequence

这是为了确保等待策略始终能够检查一个有效的序列对象。

当没有依赖消费者时：

cursorSequence = 100
目标 sequence = 100

第一阶段结束后，第二阶段检查的仍然是生产者游标。

由于目标序列已经可用，循环不会执行。

而在存在依赖消费者时，第二阶段检查的则是依赖序列组的进度。

所以，BlockingWaitStrategy 的第二阶段并不是一定会产生额外等待；是否进入循环取决于依赖序列的实际进度。

⸻

十、BlockingWaitStrategy 的性能特点

我们可以从三个方面分析。

1. CPU 消耗

在生产者暂时没有数据时，消费者通过：

processorNotifyCondition.await();

进入阻塞状态。

因此，相比持续自旋的策略，它通常能显著降低等待期间的 CPU 消耗。

但在等待依赖消费者推进时，它使用：

Thread.yield();

仍然可能消耗 CPU。

2. 延迟

阻塞等待通常涉及：

* 线程进入等待状态。
* 生产者发出通知。
* 操作系统调度消费者恢复运行。
* 消费者重新检查条件。

因此，与持续自旋相比，它通常会引入更高的唤醒延迟。

但实际延迟取决于操作系统调度、线程竞争、事件到达频率以及硬件环境，不能仅凭策略名称判断具体数值。

3. 锁竞争

由于消费者需要：

lock.lock();

而生产者通知时也需要：

lock.lock();

因此，在高频发布场景下，锁竞争和线程唤醒可能成为额外开销。

另外，signalAll() 可能同时唤醒多个消费者，产生额外的调度和锁竞争成本。

总结
```
维度	BlockingWaitStrategy
空闲时 CPU 消耗	通常较低
事件唤醒延迟	通常高于纯自旋
是否使用锁	是
是否使用 Condition	是
是否可能发生线程调度开销	是
是否适合极低延迟场景	需要结合实际测试
是否适合 CPU 资源敏感的场景	通常适合
```
⸻

十一、与其他 WaitStrategy 的对比

结合你正在研究的 SingleProducerSequencer、MultiProducerSequencer 和 ProcessingSequenceBarrier，建议重点理解以下几种等待策略。
```
策略	主要等待方式	CPU 消耗	延迟特点	典型场景
BlockingWaitStrategy	Lock + Condition，依赖等待时可能 yield	较低到中等	通常较高	普通异步业务、资源敏感型服务
BusySpinWaitStrategy	持续自旋	很高	通常较低	专用 CPU 核心、极低延迟场景
YieldingWaitStrategy	自旋并周期性 yield	较高	通常较低	高吞吐、低延迟且允许一定 CPU 消耗
SleepingWaitStrategy	自旋、yield、短暂休眠	通常较低	通常较高	对 CPU 消耗较敏感的场景
```
对于交易所订单、撮合周边数据处理等场景，不应该直接认定某种策略一定最好。

例如：

* 如果消费者线程可以独占 CPU 核心，BusySpin 可能值得测试。
* 如果服务需要控制 CPU 消耗，Blocking 可能更合适。
* 如果需要在延迟和资源消耗之间折中，可以测试 Yielding 或 Sleeping。

最终应该结合真实负载测试吞吐量、P99/P999 延迟和 CPU 使用率，而不是只比较单次 waitFor() 的执行时间。

⸻

十二、面试高频问题

问题 1：BlockingWaitStrategy 为什么使用 Condition？

参考回答：

当消费者需要的序列尚未发布时，通过 Condition.await() 让线程阻塞，避免持续自旋消耗 CPU。生产者发布数据后，通过 signalAllWhenBlocking() 通知等待线程恢复执行。

问题 2：为什么使用 while 而不是 if？

参考回答：

因为 Condition.await() 可能出现虚假唤醒，而且 signalAll() 会唤醒多个等待线程。线程恢复执行后必须重新检查等待条件，只有条件满足才能退出循环。

问题 3：为什么 signalAll() 也需要获取锁？

参考回答：

Java 的 Condition 要求调用 signal() 或 signalAll() 时持有其关联锁。因此，生产者需要获取同一把 ReentrantLock，执行通知，然后释放锁。

问题 4：为什么第二阶段使用 Thread.yield()？

参考回答：

生产者发布数据后，依赖消费者可能还没有处理到目标序列。经典实现使用 Thread.yield() 反复检查依赖序列，避免依赖消费者推进时没有对应通知而导致消费者一直阻塞。不过，yield 不能保证线程切换，也不能保证低 CPU 消耗。

问题 5：BlockingWaitStrategy 为什么不适合所有低延迟场景？

参考回答：

它使用锁、条件变量和线程唤醒机制，在部分场景中会产生额外调度开销。因此，如果对极低延迟有严格要求，持续自旋可能更合适。但自旋会增加 CPU 消耗，最终需要通过实际基准测试选择策略。

⸻

十三、最后总结：抓住三个关键点

学习 BlockingWaitStrategy，最重要的是理解以下三件事。

第一，生产者没有发布数据时，消费者使用 Condition 阻塞等待。

while (cursorSequence.get() < sequence)
{
barrier.checkAlert();
processorNotifyCondition.await();
}

第二，生产者发布数据后，通过 signalAllWhenBlocking() 唤醒等待线程。

lock.lock();
try
{
processorNotifyCondition.signalAll();
}
finally
{
lock.unlock();
}

第三，生产者已经发布数据，不代表依赖消费者已经完成处理。

while ((availableSequence = dependentSequence.get()) < sequence)
{
barrier.checkAlert();
Thread.yield();
}

将它们联系起来，就能理解 BlockingWaitStrategy 的设计：

先通过阻塞等待避免在没有生产数据时持续消耗 CPU，再通过依赖序列检查确保消费者遵守事件处理顺序。

接下来建议继续分析 YieldingWaitStrategy 和 BusySpinWaitStrategy，尤其是它们的 waitFor() 实现。把三者放在一起比较，就能更清楚地理解 Disruptor 如何在吞吐量、延迟和 CPU 消耗之间进行权衡。