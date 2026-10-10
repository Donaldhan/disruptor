Disruptor 源码解析：ProcessingSequenceBarrier

ProcessingSequenceBarrier 是 LMAX Disruptor 中非常重要的一个组件，主要负责协调消费者读取 RingBuffer 中的数据，判断依赖的序列是否已经发布，并在数据尚未就绪时配合等待策略进行等待。

结合你刚才学习的 SingleProducerSequencer，可以把这两个类联系起来理解：

* SingleProducerSequencer：生产者申请序列号、发布数据。
* ProcessingSequenceBarrier：消费者检查目标序列号是否已经可用，以及依赖的消费者是否处理到了指定位置。
* BatchEventProcessor：使用 ProcessingSequenceBarrier 获取可处理的事件，再调用业务处理器处理事件。

它们共同构成了 Disruptor 生产者与消费者之间的协调机制。

下面按照源码结构 → 核心方法 → 执行流程 → 并发原理 → 面试重点的顺序进行解析。

⸻

一、ProcessingSequenceBarrier 在 Disruptor 中的位置

先看整体结构。

假设一个生产者向 RingBuffer 写入事件，两个消费者按照依赖关系处理：

                     Producer
                         |
                         v
                 SingleProducerSequencer
                         |
                         v
                    RingBuffer
                         |
                         v
                ProcessingSequenceBarrier
                         |
                         v
                 BatchEventProcessor
                         |
                         v
                  EventHandler

在这个流程中：

1. 生产者向 RingBuffer 写入事件。
2. 生产者调用 publish() 发布对应的序列号。
3. 消费者通过 ProcessingSequenceBarrier.waitFor() 等待目标序列号可用。
4. Barrier 检查生产者已经发布到哪个位置，以及消费者依赖的序列号是否满足要求。
5. 返回当前可以处理的最高连续可用序列号。
6. BatchEventProcessor 遍历这些事件并交给业务处理器。

注意：Barrier 不负责真正执行业务处理，也不负责直接读取或修改事件内容。 它主要负责序列协调和等待。

⸻

二、ProcessingSequenceBarrier 源码结构

下面以 Disruptor 3.x 中常见的实现为基础进行分析。不同版本的字段、构造方法和异常处理可能存在差异。

核心源码结构如下：
```
final class ProcessingSequenceBarrier implements SequenceBarrier {
private final WaitStrategy waitStrategy;
private final SequenceBarrier dependentSequence;
private final Sequence cursorSequence;
private volatile boolean alerted = false;
ProcessingSequenceBarrier(
Sequencer sequencer,
WaitStrategy waitStrategy,
Sequence cursorSequence,
Sequence[] dependentSequences) {
this.waitStrategy = waitStrategy;
this.cursorSequence = cursorSequence;
if (dependentSequences.length == 0) {
dependentSequence = null;
} else {
dependentSequence = new FixedSequenceGroup(dependentSequences);
}
}
@Override
public long waitFor(long sequence)
throws AlertException, InterruptedException, TimeoutException {
checkAlert();
long availableSequence = waitStrategy.waitFor(
sequence,
cursorSequence,
dependentSequence,
this);
if (availableSequence < sequence) {
return availableSequence;
}
return availableSequence;
}
@Override
public long getCursor() {
return dependentSequence == null
? cursorSequence.get()
: dependentSequence.get();
}
@Override
public boolean isAlerted() {
return alerted;
}
@Override
public void alert() {
alerted = true;
waitStrategy.signalAllWhenBlocking();
}
@Override
public void clearAlert() {
alerted = false;
}
@Override
public void checkAlert() throws AlertException {
if (alerted) {
throw AlertException.INSTANCE;
}
}
}
```
说明：以上是用于讲解核心逻辑的简化代码，并非所有 Disruptor 版本的逐字源码。 特别是 waitFor() 中的可用序列检查，在不同版本中可能会有不同的实现细节。

下面逐个分析。

⸻

三、核心字段解析

1. waitStrategy
```
private final WaitStrategy waitStrategy;
```
表示等待策略。

当消费者想要处理序列号 100，但生产者尚未发布到 100 时，消费者应该怎么办？

常见的选择有：

* 自旋等待。
* 使用 Thread.yield() 让出 CPU 执行机会。
* 通过 LockSupport.parkNanos() 短暂休眠。
* 使用锁和条件变量阻塞等待。

这些行为由 WaitStrategy 决定。

常见策略包括：
```
等待策略	工作方式	优点	缺点
BusySpinWaitStrategy	持续自旋	延迟低	消耗 CPU
YieldingWaitStrategy	自旋并适当 yield	延迟与 CPU 消耗折中	仍可能消耗较多 CPU
SleepingWaitStrategy	自旋、yield、短暂休眠	CPU 消耗较低	唤醒延迟较高
BlockingWaitStrategy	锁和条件变量等待	CPU 消耗较低	涉及线程阻塞和唤醒开销
```
例如：
```
WaitStrategy waitStrategy = new BlockingWaitStrategy();
```
意味着消费者在没有可处理数据时，可以通过阻塞等待，而不是无限制地占用 CPU 自旋。

需要注意，等待策略主要控制消费者如何等待，不会改变事件本身的业务处理逻辑。

⸻

2. cursorSequence

private final Sequence cursorSequence;

这个字段通常指向生产者的发布游标。

例如：

生产者已经发布：
0 1 2 3 4 5 6 7 8 9
cursorSequence = 9

消费者调用：

barrier.waitFor(10);

此时，生产者游标只有 9，说明生产者还没有发布到目标序列 10。

消费者需要继续等待，或者由等待策略返回当前可用的进度。

这里有一个关键细节：

cursorSequence 表示生产者发布进度的参考值，并不总是意味着所有低于该值的事件都已经可以安全处理。

对于多生产者场景，生产者可能已经申请了较高的序列号，但前面的某些序列还没有发布。

因此，不能简单地把“最大已申请序列号”当作“最大连续已发布序列号”。

在单生产者场景中，SingleProducerSequencer 对发布游标的更新通常发生在 publish() 之后。

这也解释了你刚才看到的源码注释：

cursor value is updated after the call to publish(long) is made.

⸻

3. dependentSequence

private final SequenceBarrier dependentSequence;

这是理解 ProcessingSequenceBarrier 的重点。

它表示当前消费者所依赖的其他消费者的处理进度。

例如，假设有三个消费者：
```
RingBuffer
|
v
Consumer A
|
v
Consumer B
|
v
Consumer C
```
这里：

* Consumer A 负责解析交易事件。
* Consumer B 负责更新资产数据。
* Consumer C 负责生成下游统计结果。

如果 Consumer C 必须等待 Consumer B 完成，那么 C 的可处理进度不仅取决于生产者是否发布了事件，还取决于 B 的处理进度。

假设：

生产者发布进度：1000
Consumer A 进度：950
Consumer B 进度：900

如果 Consumer C 依赖 B，那么 C 就不能仅仅因为生产者已经发布到 1000，便认为序列 901 已经满足依赖条件。

它需要等待 B 的进度推进。

对于多个依赖消费者，通常需要取它们进度的最小值。

例如：

Consumer A = 950
Consumer B = 900
Consumer C = 920
最慢依赖者 = 900

那么依赖组的进度就是：

dependentSequence = min(950, 900, 920)
= 900

这能保证消费者不会越过最慢的依赖者。

需要区分两件事：

* 生产者是否发布了某个序列。
* 当前消费者依赖的其他消费者是否已经处理完该序列。

这两个条件不是一回事。

⸻

四、构造方法解析

核心逻辑如下：
```
ProcessingSequenceBarrier(
Sequencer sequencer,
WaitStrategy waitStrategy,
Sequence cursorSequence,
Sequence[] dependentSequences) {
this.waitStrategy = waitStrategy;
this.cursorSequence = cursorSequence;
if (dependentSequences.length == 0) {
dependentSequence = null;
} else {
dependentSequence = new FixedSequenceGroup(dependentSequences);
}
}
```
构造方法接收四个参数。
```
参数	作用
sequencer	序列器，负责生产者序列号分配与发布
waitStrategy	消费者等待策略
cursorSequence	生产者发布游标
dependentSequences	当前消费者依赖的其他消费者序列
```
这里有两个重点。

1. 为什么 dependentSequences 为空时设为 null？
```
if (dependentSequences.length == 0) {
dependentSequence = null;
}
```
如果消费者没有依赖其他消费者，那么它只需要关注生产者的发布进度。

例如：
```
Producer
|
v
Consumer A

Consumer A 没有前置消费者，因此没有必要维护一个依赖序列组。
```
在这种情况下，Barrier 可以直接使用生产者游标作为等待策略的重要参考。

2. 为什么需要 FixedSequenceGroup？

dependentSequence = new FixedSequenceGroup(dependentSequences);

当消费者依赖多个消费者时，FixedSequenceGroup 将多个 Sequence 组合起来，向外提供统一的序列视图。

例如：
```
Consumer A ─┐
Consumer B ─┼──> FixedSequenceGroup
Consumer C ─┘
```
该分组的进度通常取所有依赖序列中的最小值。

这样，等待策略就不需要逐个理解消费者的拓扑关系，而是可以通过统一接口读取依赖进度。

⸻

五、核心方法：waitFor()

这是整个类最重要的方法。

在常见的 Disruptor 3.x 实现中，核心逻辑类似于：
```
@Override
public long waitFor(long sequence)
throws AlertException, InterruptedException, TimeoutException {
checkAlert();
long availableSequence = waitStrategy.waitFor(
sequence,
cursorSequence,
dependentSequence,
this);
if (availableSequence < sequence) {
return availableSequence;
}
return sequencer.getHighestPublishedSequence(
sequence,
availableSequence);
}
```
这里特别需要注意：生产者游标达到目标序列，并不一定意味着目标序列及其之前的所有事件都已经发布。 因此，某些版本还会调用 getHighestPublishedSequence() 检查连续可用的发布范围。

我们逐步分析。

1. 第一步：检查是否收到停止通知

checkAlert();

这个方法用于检查 Barrier 是否被设置为 alert 状态。

如果已经收到 alert，就会抛出 AlertException。

典型场景是消费者关闭：
```
应用程序准备关闭
|
v
barrier.alert()
|
v
唤醒等待中的消费者
|
v
checkAlert()
|
v
抛出 AlertException
|
v
消费者退出处理循环
```
这样可以避免消费者一直等待新事件，导致线程无法正常退出。

2. 第二步：等待目标序列可用
```
long availableSequence = waitStrategy.waitFor(
sequence,
cursorSequence,
dependentSequence,
this);
```
假设消费者希望读取：

sequence = 100;

等待策略会综合判断：

* 生产者发布进度是否达到 100。
* 当前消费者依赖的消费者是否处理到 100。
* 如果没有达到，应该继续自旋、让出 CPU，还是阻塞等待。

这里有一个重要的设计：Barrier 把等待行为交给 WaitStrategy，而不是自己实现所有等待算法。

因此，同一个 Barrier 可以配合不同的等待策略使用。

3. 第三步：判断目标序列是否已经可用
```
if (availableSequence < sequence) {
return availableSequence;
}
```
假设：

目标序列：100
当前可用序列：95

那么：

95 < 100

说明消费者当前还无法处理目标序列 100，于是返回 95。

调用者可以据此判断本轮是否有新事件可处理。

如果等待策略返回 100 或更高的值，说明它认为目标序列已经满足相应的等待条件。

4. 第四步：确认最高连续已发布序列
```
return sequencer.getHighestPublishedSequence(
sequence,
availableSequence);
```
这一步在一些常见的 Disruptor 3.x 实现中非常关键。

例如，多生产者可能出现如下情况：
```
序列号：      100 101 102 103 104 105
发布状态：     ✓   ✓   ✗   ✓   ✓   ✓
```
虽然序列 105 已经发布，但 102 仍然没有发布。

如果消费者一次申请读取 100 到 105，就不能直接处理整个区间。

最高连续可用序列只能是：

101

因此，Barrier 需要通过 Sequencer 检查从起始序列开始的连续发布范围。

注意：这一机制在多生产者场景中尤其重要。具体检查方式取决于 Sequencer 的实现。

⸻

六、getCursor()：为什么不是简单返回生产者游标？

典型实现：
```
@Override
public long getCursor() {
return dependentSequence == null
? cursorSequence.get()
: dependentSequence.get();
}
```
它的语义可以理解为：

* 没有依赖消费者时，返回生产者游标。
* 存在依赖消费者时，返回依赖序列组的进度。

举个例子：

生产者发布进度：1000
Consumer A 进度：950
Consumer B 进度：900

如果当前消费者依赖 A 和 B：

barrier.getCursor();

返回值通常是：

900

因为 B 是最慢的依赖消费者。

这样可以让调用者了解当前依赖链上的处理进度。

不过需要注意：getCursor() 返回的是一个瞬时进度快照，不能保证调用之后序列状态不会变化。

⸻

七、alert()、clearAlert() 和 checkAlert()

这三个方法主要用于消费者的生命周期管理。

1. alert()
```
@Override
public void alert() {
alerted = true;
waitStrategy.signalAllWhenBlocking();
}
```

它做两件事：

1. 设置 alerted = true。
2. 通知等待策略唤醒可能正在阻塞的消费者。

为什么需要唤醒？

假设消费者正在使用 BlockingWaitStrategy：
```
Consumer Thread
|
v
Condition.await()
|
v
线程阻塞
```
此时，如果只设置：

alerted = true;

消费者可能仍然一直阻塞，无法及时检查这个标志。

因此还需要调用：
```
waitStrategy.signalAllWhenBlocking();
```
让阻塞中的消费者有机会恢复执行。

2. clearAlert()
```
@Override
public void clearAlert() {
alerted = false;
}
```
清除 alert 状态。

当消费者需要重新启动处理循环时，可以清除之前的通知状态。

3. checkAlert()
```
@Override
public void checkAlert() throws AlertException {
if (alerted) {
throw AlertException.INSTANCE;
}
}
```

如果已经设置 alert，则抛出 AlertException。

这里使用：

AlertException.INSTANCE

表示复用异常实例，避免在高频控制流程中不断创建新的异常对象。

不过，异常的具体实现及其栈信息处理方式需要结合对应版本的 AlertException 源码确认。

⸻

八、结合 BatchEventProcessor 理解完整执行流程

仅仅理解 ProcessingSequenceBarrier 还不够，需要把它放到实际消费者的调用链中。

消费者通常通过 BatchEventProcessor 运行事件处理循环。

其核心逻辑可以概括为：
```
while (true) {
long nextSequence = sequence + 1;
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
while (nextSequence <= availableSequence) {
T event = dataProvider.get(nextSequence);
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence);
nextSequence++;
}
sequence = availableSequence;
}
```
以上是便于理解的简化伪代码，不是所有版本的逐字源码。

我们通过一个具体例子理解。

假设 RingBuffer 中有以下事件：

序列号：0 1 2 3 4 5 6 7 8 9

消费者当前处理到：

sequence = 4

因此，下一个目标序列是：

nextSequence = 5

消费者调用：

sequenceBarrier.waitFor(5);

假设 Barrier 返回：

availableSequence = 8

这意味着本轮可以处理序列 5 到 8。

于是消费者执行：
```
处理事件 5
处理事件 6
处理事件 7
处理事件 8
```
处理完之后，将自己的进度更新到：

sequence = 8

下一轮再等待序列 9。

为什么要批量处理？

如果每处理一个事件都重新调用等待逻辑，就会增加额外的方法调用和序列检查开销。

一次获取多个连续可用事件，再批量处理，有利于提高吞吐量。

当然，实际效果取决于事件到达速率、等待策略和业务处理耗时。

⸻

九、放到交易所高并发场景中理解

结合交易所的交易、资产和行情数据处理，可以这样理解 Disruptor 的角色。

例如：
```
Kafka Consumer
|
v
RingBuffer
|
v
Disruptor
|
+-------------------+
|                   |
v                   v
行情处理消费者        资产处理消费者
|                   |
v                   v
行情缓存更新          资产状态更新

这里需要强调：这是一个可能的架构示例，不代表所有交易所都采用 Disruptor，也不意味着不同消费者之间一定存在依赖关系。

假设资产处理消费者必须等待某个前置消费者完成：

交易事件
|
v
前置事件处理
|
v
资产状态更新

ProcessingSequenceBarrier 就可以协调资产消费者的读取进度，确保它不会越过依赖消费者尚未完成的序列。

不过，如果行情和资产消费者彼此独立，它们可以使用各自的消费序列，不必互相等待。

这正是 Disruptor 的一个重要特点：消费者依赖关系可以由事件处理拓扑决定，而不需要把所有消费者串行化。
```
⸻

十、ProcessingSequenceBarrier 与 SingleProducerSequencer 的区别

你前面研究的 SingleProducerSequencer 与本次的 ProcessingSequenceBarrier，很容易混淆。

下面通过表格对比。
```
对比维度	SingleProducerSequencer	ProcessingSequenceBarrier
所属角色	生产者侧序列协调	消费者侧序列协调
核心任务	分配序列号、检查容量、发布数据	等待目标序列可用、协调依赖进度
是否分配序列号	是	否
是否发布事件	负责协调发布	否
是否等待	主要管理生产者申请序列时的容量等待	等待消费者所需的序列可用
是否维护消费者依赖	通过消费者 Sequence 判断可用容量	读取依赖 Sequence 判断可处理范围
是否调用 WaitStrategy	生产者容量等待有独立机制	是
典型调用者	RingBuffer / 生产者	BatchEventProcessor
```
可以把它们理解成两个方向的协调器：
```
生产者方向：
申请序列号
|
v
SingleProducerSequencer
|
v
写入 RingBuffer
|
v
publish()
消费者方向：
waitFor(sequence)
|
v
ProcessingSequenceBarrier
|
v
确认可用序列
|
v
BatchEventProcessor 处理事件

⸻
```
十一、面试中值得重点掌握的 5 个问题

问题 1：ProcessingSequenceBarrier 的作用是什么？

参考回答：

ProcessingSequenceBarrier 是 Disruptor 中用于协调消费者读取进度的组件。它通过生产者游标和依赖消费者的 Sequence，结合 WaitStrategy，判断目标序列是否可用，并返回当前可处理的序列范围。

问题 2：为什么需要 WaitStrategy？

参考回答：

不同业务场景对延迟和 CPU 消耗的要求不同。Disruptor 将等待机制抽象为 WaitStrategy，可以根据场景选择自旋、让出 CPU、休眠或阻塞等待，而不需要修改 Barrier 的核心协调逻辑。

问题 3：为什么需要 dependentSequence？

参考回答：

当消费者依赖其他消费者的处理结果时，仅检查生产者发布进度是不够的。dependentSequence 用于表示前置消费者的处理进度，避免当前消费者越过依赖链中尚未完成的事件。

问题 4：为什么生产者游标达到目标序列，还需要进一步检查？

参考回答：

在多生产者场景中，较大的序列号可能已经发布，但前面的某个序列仍然没有发布。因此，生产者游标不能直接等同于从指定起始序列开始的最高连续已发布序列。Barrier 在相应实现中会进一步调用 Sequencer 检查连续发布范围。

问题 5：alert() 为什么需要唤醒等待线程？

参考回答：

如果消费者正在阻塞等待，只设置 alert 标志并不能保证它立即恢复执行。alert() 还会调用等待策略的唤醒机制，让消费者能够恢复执行、检查 alert 状态，并退出处理循环。

⸻

十二、最后总结：建议掌握的源码调用链

如果你准备继续阅读 Disruptor 源码，建议重点跟踪以下调用链：
```
RingBuffer.next()
|
v
SingleProducerSequencer.next()
|
v
RingBuffer.get(sequence)
|
v
写入事件
|
v
RingBuffer.publish(sequence)
|
v
SingleProducerSequencer.publish(sequence)
|
v
更新发布游标
BatchEventProcessor.run()
|
v
ProcessingSequenceBarrier.waitFor(sequence)
|
v
WaitStrategy.waitFor(...)
|
v
检查依赖进度
|
v
Sequencer.getHighestPublishedSequence(...)
|
v
返回可用序列范围
|
v
BatchEventProcessor 处理事件
|
v
更新消费者 Sequence
```
需要注意，具体调用链会随着 Disruptor 版本以及等待策略实现而有所不同。

最重要的理解是：

SingleProducerSequencer 解决生产者如何安全发布事件的问题；ProcessingSequenceBarrier 解决消费者何时可以读取事件的问题；BatchEventProcessor 则负责真正消费事件。

三者配合，构成了 Disruptor 高性能事件处理机制的核心。