Disruptor 的 WaitStrategy 是一个非常重要的组件，它决定了消费者在没有可消费的数据时，如何等待生产者发布新数据。

在交易所、支付系统、订单系统、资产系统等高并发场景中，选择合适的 WaitStrategy，会直接影响系统的延迟、CPU 使用率和吞吐量。

结合你正在研究的 Disruptor 源码，我从策略种类、实现原理、适用场景、源码分析和交易系统选型几个方面进行说明。

一、WaitStrategy 是什么？

Disruptor 的基本处理流程如下：

```
生产者 Producer
|
| 1. 申请 Sequence
v
RingBuffer
|
| 2. 写入事件
v
publish(sequence)
|
| 3. 发布事件
v
消费者 Consumer
|
| 4. 等待可消费的 Sequence
v
WaitStrategy
|
| 5. 获取可消费事件
v
执行业务逻辑
```

假设生产者发布事件的速度比较慢：
```
Consumer:
|
| 检查是否有新事件
| 没有
| 等待
| 检查是否有新事件
| 没有
| 等待
| 检查是否有新事件
| 有新事件
v
处理事件
```

消费者在等待时，可以选择不同的行为：

* 一直循环检查，尽量减少唤醒延迟。
* 循环检查一段时间，然后让出 CPU。
* 通过操作系统线程调度机制等待。
* 使用锁和条件变量阻塞线程，等待生产者通知。

这些行为就是不同 WaitStrategy 的核心区别。

需要注意：WaitStrategy 主要控制消费者等待新事件的行为，并不直接控制生产者在 RingBuffer 已满时的等待行为。

你上一段研究的 SingleProducerSequencer.next() 中：
```
while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
{
LockSupport.parkNanos(1L);
}
```
这是生产者等待消费者释放 RingBuffer 空间的逻辑，并不是消费者的 WaitStrategy。

⸻

二、Disruptor 常见的 WaitStrategy

Disruptor 不同版本提供的策略可能有所不同。常见的策略包括：
```
策略	核心机制	CPU 使用率	延迟表现	典型场景
BusySpinWaitStrategy	持续自旋	极高	通常最低	对延迟极度敏感的交易系统
YieldingWaitStrategy	自旋，之后 Thread.yield()	高	很低	高吞吐、低延迟系统
SleepingWaitStrategy	自旋、让出 CPU、短暂休眠	较低	较高	延迟要求适中、希望降低 CPU 消耗
BlockingWaitStrategy	锁与条件变量阻塞	较低	相对较高	通用业务系统
LiteBlockingWaitStrategy	轻量化的阻塞等待实现	较低	取决于唤醒与调度	希望采用轻量阻塞机制的系统
LiteTimeoutBlockingWaitStrategy	带超时的轻量阻塞等待	较低	取决于唤醒与调度	需要等待超时控制的场景
TimeoutBlockingWaitStrategy	阻塞等待，支持超时	较低	取决于唤醒与调度	需要检测等待超时的系统
PhasedBackoffWaitStrategy	分阶段自旋、让出 CPU、切换到备用策略	可调节	可调节	希望平衡延迟与 CPU 消耗的系统
```
这里有几个需要特别说明的地方：

1. 这些策略的具体实现和构造参数依赖 Disruptor 版本。
2. LiteBlockingWaitStrategy 等轻量策略不应简单理解为一定比普通阻塞策略更快，实际表现取决于负载、线程调度和实现细节。
3. CPU 使用率和延迟都是相对表现，不存在脱离硬件、线程绑定和负载情况的绝对排名。

下面重点分析其中最常用的策略。

⸻

三、BusySpinWaitStrategy：忙等待策略

1. 核心思想

消费者不断检查有没有新事件。

没有事件时，不睡眠、不阻塞，也不主动让出 CPU。

简化理解：
```
while (true) {
long availableSequence = cursor.get();
if (availableSequence > currentSequence) {
break;
}
}
```
这只是示意代码，真实实现还需要考虑消费者 Sequence、依赖关系、内存可见性等因素。

2. 工作流程
```
消费者线程
|
v
检查是否有新事件
|
+---- 有 ----> 处理事件
|
+---- 无
|
v
继续自旋
|
v
再次检查
```
线程一直处于运行状态，避免了阻塞和重新调度带来的额外开销。

3. 优点

优点一：唤醒延迟低。

消费者始终处于运行状态，不需要等待操作系统重新调度线程。

优点二：适合极低延迟场景。

当事件到达的间隔很短时，忙等待可能比阻塞等待更有优势。

4. 缺点

CPU 消耗很高。

例如：

机器：8 核 CPU
消费者线程：8 个
全部使用 BusySpinWaitStrategy

如果这 8 个线程都持续自旋，就可能占满大部分 CPU 执行资源。

而且，自旋还会影响同一机器上的其他线程，例如：

* 生产者线程。
* GC 相关线程。
* 网络 I/O 线程。
* 其他消费者线程。

因此，忙等待不一定总是最快。

如果生产者和消费者争抢同一批 CPU 资源，消费者自旋反而可能延缓生产者执行。

5. 适用场景

适合：

* 对微秒级甚至更低的尾延迟有严格要求的系统。
* 专用物理机或经过 CPU 隔离的环境。
* 事件持续密集到达的高负载系统。
* 对 CPU 成本不敏感的关键交易链路。

例如，交易所内部某些订单处理环节，如果系统已经经过充分的性能测试，可以考虑忙等待。

但不建议仅仅因为交易系统对延迟敏感，就默认使用该策略。

前提是：必须评估 CPU 资源、线程部署方式以及生产者能否及时获得执行机会。

⸻

四、YieldingWaitStrategy：让出 CPU 的等待策略

1. 核心思想

先自旋检查一段时间。

如果一直没有新事件，就调用：

Thread.yield();

让出当前线程的执行机会，允许调度器优先考虑其他可运行线程。

注意，Thread.yield() 只是向调度器表达让出 CPU 的意愿，不保证一定切换线程，也不保证其他线程一定获得 CPU。

2. 工作原理

以常见实现的思路为例：
```
int counter = SPIN_TRIES;
while (true) {
long availableSequence = checkAvailableSequence();
if (availableSequence > currentSequence) {
return availableSequence;
}
if (counter > 0) {
counter--;
} else {
Thread.yield();
}
}
```

这里同样是简化示意，不是某个版本的完整源码。

执行过程：
```
消费者开始等待
|
v
自旋检查
|
v
是否有新事件？
/       \
是        否
|         |
v         v
处理事件   自旋次数耗尽？
/    \
否     是
|      |
v      v
继续自旋 Thread.yield()
|
v
再次检查
```
3. 和 BusySpin 的区别
```
对比项	BusySpin	Yielding
等待时是否持续检查	是	是
是否调用 Thread.yield()	否	是
CPU 消耗	通常更高	通常较高
是否可能让其他线程获得执行机会	不主动让出	主动提示让出
延迟	通常极低	通常很低
适用环境	专用 CPU 资源	希望兼顾低延迟和一定调度弹性
```
4. 适用场景

例如：

* 高频事件处理。
* 生产者和消费者持续处理事件。
* 对延迟有较高要求，但又不希望消费者始终完全忙等。

不过，如果系统事件非常稀疏，消费者大量时间都在等待，那么 Yielding 依然会消耗不少 CPU。

⸻

五、SleepingWaitStrategy：休眠等待策略

1. 核心思想

SleepingWaitStrategy 通常采用分阶段等待：

1. 先自旋检查。
2. 自旋次数耗尽后，调用 Thread.yield()。
3. 仍然没有事件时，调用 LockSupport.parkNanos() 进行短暂休眠。

典型实现思路如下：
```
int counter = SPIN_TRIES;
while (true) {
long availableSequence = checkAvailableSequence();
if (availableSequence > currentSequence) {
return availableSequence;
}
if (counter > 100) {
counter--;
} else if (counter > 0) {
counter--;
Thread.yield();
} else {
LockSupport.parkNanos(1L);
}
}
```

以上仅用于说明原理，实际自旋次数和分支条件应以所使用版本的源码为准。

2. 为什么采用分阶段等待？

因为不同阶段的等待成本不同。

第一阶段：自旋。

如果事件马上到达，就不必发生线程调度。

第二阶段：让出 CPU。

如果短时间内没有事件，就降低持续自旋带来的资源消耗。

第三阶段：短暂休眠。

如果等待持续较长时间，就减少 CPU 空转。

整体流程：
```
开始等待
|
v
自旋检查
|
+---- 有事件 ----> 返回可消费序列
|
v
自旋次数耗尽
|
v
Thread.yield()
|
+---- 有事件 ----> 返回可消费序列
|
v
LockSupport.parkNanos()
|
v
再次检查
```

3. 优缺点

优点：

* 比持续忙等待更节省 CPU。
* 不需要像传统阻塞方式那样完全依赖条件变量唤醒。
* 适合事件到达频率不稳定的系统。

缺点：

* 休眠和重新调度可能增加响应延迟。
* parkNanos() 的实际等待时间不一定等于指定的纳秒数。
* 事件到达后，消费者可能需要等到线程恢复执行才能处理。

4. 适用场景

例如：

* 后台异步任务。
* 非关键实时链路。
* 低频事件处理。
* 希望在延迟和 CPU 使用率之间取得平衡的系统。

如果交易所某个历史数据异步消费链路对微秒级延迟没有严格要求，可以考虑该策略。

⸻

六、BlockingWaitStrategy：阻塞等待策略

这是理解 Disruptor 等待机制时最值得掌握的一种策略。

1. 核心思想

消费者发现没有新事件时，不再持续自旋，而是通过锁和条件变量等待生产者通知。

典型流程：
```

消费者线程
|
v
检查是否有新事件
|
+---- 有 ----> 返回并处理
|
+---- 无
|
v
获取锁
|
v
再次检查
|
v
条件不满足
|
v
await() 等待
|
| 生产者发布事件
v
signalAll()
|
v
重新检查条件
|
v
处理事件
```

注意：这里是概念流程。具体实现可能使用 LockSupport、锁、条件变量以及其他协作机制，应该以实际版本源码为准。

2. 为什么阻塞前要再次检查？

假设消费者执行：
```
if (!hasAvailableEvent()) {
lock.lock();
condition.await();
}
```

可能发生这样的竞争：
```
T1：消费者检查，没有新事件
T2：生产者发布事件，并发出通知
T3：消费者开始等待
```
如果通知发生在消费者真正进入等待状态之前，而且没有正确协调状态，就可能出现丢失通知的问题。

正确的阻塞实现需要通过锁、条件检查和唤醒机制，避免这种竞态。

因此，阻塞等待通常需要遵循以下原则：

* 在适当的同步机制保护下检查条件。
* 条件不满足时进入等待。
* 被唤醒后重新检查条件。
* 不把收到通知直接等同于一定有可消费事件。

3. 优点

* 等待期间 CPU 使用率低。
* 适合长时间没有新事件的场景。
* 不需要消费者线程持续自旋。

4. 缺点

* 线程进入阻塞和恢复运行会带来额外开销。
* 事件到达后的响应延迟可能高于自旋策略。
* 线程调度会影响尾延迟。

5. 适用场景

例如：

* 普通业务事件处理。
* 异步任务执行。
* 数据同步。
* 非极低延迟要求的后台消费。
* 希望节省 CPU 资源的服务。

如果你的资产系统、历史数据处理系统存在较长的空闲时间，而且延迟目标相对宽松，BlockingWaitStrategy 通常值得优先测试。

⸻

七、TimeoutBlockingWaitStrategy：带超时的阻塞等待

这种策略可以理解为在阻塞等待的基础上，增加等待超时控制。

例如：
```
消费者开始等待
|
v
没有可消费事件
|
v
进入阻塞等待
|
+---- 生产者发布事件 ----> 被唤醒
|
+---- 等待超时 ----------> 返回或触发超时处理
```
具体超时后的行为取决于策略实现及调用方如何处理超时异常。

需要注意：

等待超时不等于业务处理超时，也不等于事件丢失。

它通常表示消费者等待可消费数据的时间达到指定阈值。

适用场景

* 需要检测长期没有事件到达的消费者。
* 希望在等待时间过长时进行监控或恢复处理。
* 某些需要超时返回的异步处理场景。

但如果业务本身要求持续等待事件，超时后仍然需要明确处理逻辑，不能简单地认为发生超时就可以跳过数据。

⸻

八、PhasedBackoffWaitStrategy：分阶段退避策略

这是比较灵活的一种策略。

它将等待过程划分为多个阶段：

1. 在指定时间内自旋。
2. 超过时间后尝试让出 CPU。
3. 如果等待继续，则切换到备用等待策略。

常见构造方式的概念形式如下：
```
WaitStrategy waitStrategy =
PhasedBackoffWaitStrategy.withLock(
1,
1,
TimeUnit.MILLISECONDS
);
```

上面只是示意，具体重载方法和参数含义要结合所用 Disruptor 版本确认。

典型思路：
```
消费者等待
|
v
短时间自旋
|
+---- 有事件 ----> 处理
|
v
继续等待
|
v
进入退避阶段
|
v
使用备用策略
|
v
有事件后恢复处理
```
为什么需要这种策略？

假设你希望同时满足：

* 事件很快到达时，尽可能降低延迟。
* 长时间没有事件时，不希望持续占用 CPU。

单纯使用 BusySpin 不能很好地满足第二个要求。

单纯使用 Blocking 又可能增加事件到达后的响应延迟。

PhasedBackoff 的价值就是让等待策略根据等待持续时间发生变化。

适用场景

* 生产负载存在明显波动。
* 既关注延迟，又关注 CPU 成本。
* 希望通过参数调整自旋和阻塞之间的平衡。

需要注意：它不是所有负载下都优于其他策略。自旋时长、备用策略和线程调度都会影响最终结果。

⸻

九、如何选择？结合交易所架构分析

结合你熟悉的交易所业务，可以将消费者划分为几类。

场景一：订单核心处理链路
```
交易请求
|
v
订单处理
|
v
订单事件
|
v
RingBuffer
|
v
订单消费者
```

特点：

* 对处理延迟敏感。
* 事件可能持续密集到达。
* 延迟抖动可能影响后续处理。

建议：

优先测试 BusySpinWaitStrategy 和 YieldingWaitStrategy。

但应同时考虑：

* 是否有专用 CPU 核。
* 生产者和消费者是否发生 CPU 竞争。
* GC 是否造成停顿。
* 线程数是否超过实际可用 CPU 资源。
* 实际负载下的 P99、P99.9 延迟。

不能仅凭策略名称就认定 BusySpin 一定更快。

场景二：资产事件处理

例如：
```
成交事件
|
v
Kafka
|
v
资产变更事件
|
v
RingBuffer
|
v
资产状态更新
```
资产系统通常同时关注：

* 延迟。
* 吞吐量。
* CPU 使用率。
* 事件完整性。
* 异常恢复和对账。

如果资产事件持续密集到达，可以测试 Yielding。

如果事件频率波动明显，且 CPU 资源有限，可以测试 Sleeping 或 Blocking。

但这里还有一个比 WaitStrategy 更重要的问题：

等待策略不会自动保证资产更新不丢失，也不会解决重复消费、乱序、幂等和账务一致性。

这些问题仍然需要通过事件序列、版本控制、幂等键、持久化记录、重试和对账机制解决。

场景三：历史数据处理

例如：
```
Kafka
|
v
历史成交数据消费者
|
v
RingBuffer
|
v
ES / HBase / MySQL
```
这种场景通常更关注：

* 吞吐量。
* CPU 成本。
* 批量写入效率。
* 对下游数据库的压力控制。

如果延迟不是特别敏感，可以优先测试 Blocking 或 Sleeping。

但需要注意，实际瓶颈可能是 ES、HBase、网络或者批量写入，而不是消费者等待策略。

场景四：支付系统事件处理

例如：
```

支付请求
|
v
支付状态变更
|
v
事件队列
|
v
支付消费者
|
v
账本更新 / 通知 / 对账
```

这类系统通常更加关注：

* 状态机正确性。
* 幂等。
* 数据持久化。
* 重试。
* 对账和异常恢复。

如果不是严格的极低延迟场景，可以优先评估 Blocking 或 Sleeping。

但不能为了降低延迟而牺牲支付状态的一致性，也不能将内存中的 RingBuffer 当作可靠消息存储的替代品。

⸻

十、几种策略的横向对比
```
维度	BusySpin	Yielding	Sleeping	Blocking	PhasedBackoff
等待方式	持续自旋	自旋 + yield	自旋 + yield + park	阻塞等待	分阶段退避
CPU 消耗	极高	高	较低	较低	取决于参数
响应延迟	通常最低	通常很低	通常较高	通常较高	可调节
长时间空闲	不适合	不理想	较适合	适合	较适合
对 CPU 资源的要求	高	较高	较低	较低	可调节
适用负载	持续高频	高频	中低频或波动负载	低频或一般业务	混合负载
```
这里的延迟比较是一般性趋势，不是严格排序。

例如，在 CPU 过载的环境中，BusySpin 可能因为抢占生产者资源而表现更差。

⸻

十一、Disruptor 中 WaitStrategy 的源码调用链

如果你正在阅读 Disruptor 源码，可以重点看这几个位置：

1. WaitStrategy

它定义等待消费者可用序列的基本接口。

典型接口形式：
```
long waitFor(
long sequence,
Sequence cursor,
Sequence dependentSequence,
SequenceBarrier barrier
) throws AlertException, InterruptedException, TimeoutException;
```
不同版本的接口可能存在差异。

其中：

* sequence：消费者期望读取的序列号。
* cursor：生产者发布进度相关的序列。
* dependentSequence：当前消费者实际依赖的序列进度。
* barrier：用于检查消费者是否收到停止、告警等信号。

注意，cursor 和 dependentSequence 并不总是相同的值。

对于存在消费者依赖关系的处理链路，消费者必须等待其依赖的前置消费者推进到相应位置。

2. SequenceBarrier

消费者通常通过 SequenceBarrier 等待可用数据。

概念流程如下：
```
EventProcessor
|
v
SequenceBarrier.waitFor(sequence)
|
v
WaitStrategy.waitFor(...)
|
v
检查可用序列
|
v
返回可消费的序列范围
```

3. BatchEventProcessor

BatchEventProcessor 会使用返回的可用序列，逐条或者批量处理事件。

简化来看：
```
long nextSequence = sequence + 1;
while (true) {
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
while (nextSequence <= availableSequence) {
Event event = dataProvider.get(nextSequence);
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);
nextSequence++;
}
}
```
以上是概念示意，省略了异常处理、告警检查、序列更新和批次边界处理等细节。

它说明了一个关键点：

WaitStrategy 负责等待数据可用，而 EventProcessor 负责根据可用序列处理事件。

⸻

十二、一个容易忽略的问题：WaitStrategy 不等于背压策略

这点在面试中非常值得说明。

假设：

生产者：每秒产生 100 万条事件
消费者：每秒只能处理 50 万条事件

那么：

生产者速度 > 消费者速度

如果 RingBuffer 容量有限，随着时间推移，生产者最终可能因为没有可用槽位而等待。

但是，改变消费者的 WaitStrategy 并不能自动解决这个吞吐量不匹配的问题。

例如：

new BusySpinWaitStrategy()

只能改变消费者在等待新事件时的行为，不能让消费者的业务处理能力从每秒 50 万条自动提升到 100 万条。

同样，换成 Blocking 也不会自动解决积压。

真正需要考虑的是：

* 增加消费者处理能力。
* 减少单条事件的处理成本。
* 合理批处理。
* 调整生产者和消费者的并行度。
* 设计 RingBuffer 容量。
* 控制上游流量。
* 明确积压、限流和故障恢复机制。

还要区分两种等待：

等待位置	等待原因	相关机制
消费者等待生产者	暂时没有新事件	WaitStrategy
生产者等待消费者	RingBuffer 空间不足	Sequencer 的 gating sequence 检查

这是你前面学习 SingleProducerSequencer.next() 时，应该与本节建立起来的联系。

⸻

十三、面试时如何回答？

如果面试官问：

Disruptor 有哪些 WaitStrategy？应该如何选择？

可以这样回答：

Disruptor 的 WaitStrategy 主要用于控制消费者等待生产者发布新事件时的行为。

常见策略包括 BusySpin、Yielding、Sleeping、Blocking、TimeoutBlocking 和 PhasedBackoff 等，不同版本提供的具体策略可能略有区别。

BusySpin 通过持续自旋降低等待延迟，但 CPU 消耗很高，适合拥有专用 CPU 资源的极低延迟场景。

Yielding 在自旋后调用 Thread.yield()，适合高吞吐、低延迟场景，但仍然会消耗较多 CPU。

Sleeping 在自旋、让出 CPU 后使用短暂休眠，能够降低 CPU 消耗，但可能增加事件到达后的响应延迟。

Blocking 通过阻塞等待减少空闲时的 CPU 消耗，适合一般业务和低频事件处理。

PhasedBackoff 则通过分阶段等待，在延迟和 CPU 消耗之间进行权衡。

实际选择时，我会根据事件到达频率、延迟目标、CPU 资源和负载特征进行压测，并比较吞吐量、P99/P99.9 延迟和 CPU 使用率，而不是只根据策略名称决定。

此外，需要区分消费者等待新事件与生产者等待 RingBuffer 空间这两种情况：前者由 WaitStrategy 影响，后者主要由 Sequencer 和消费者 gating sequence 的进度决定。