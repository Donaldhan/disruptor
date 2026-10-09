Disruptor 源码解析：SingleProducerSequencer

SingleProducerSequencer 是 Disruptor 中负责单生产者场景下的序列号分配、RingBuffer 空间管理和生产者背压控制的核心组件。

结合你前面研究的 next() 方法和 WaitStrategy，这次我们从整个类的设计出发，分析它的核心字段、主要方法、运行流程，以及它与消费者、RingBuffer 和多生产者模式之间的关系。

重点掌握以下内容：

1. SingleProducerSequencer 在 Disruptor 中的定位。
2. 核心字段 nextValue、cachedValue、cursor 和 gatingSequences。
3. next()、tryNext()、publish() 的实现原理。
4. 如何避免覆盖消费者尚未处理的数据。
5. 为什么单生产者模式不需要 CAS。
6. 与 MultiProducerSequencer 的区别。
7. 在交易所高并发系统中的应用。

⸻

一、SingleProducerSequencer 在 Disruptor 中的定位

首先看 Disruptor 的整体架构。

简化架构如下：

                   Producer
                      |
                      v
              SingleProducerSequencer
                      |
              1. 分配 Sequence
              2. 检查 RingBuffer 空间
                      |
                      v
                  RingBuffer
                      |
              3. 写入 Event
                      |
                      v
                   publish()
                      |
                      v
                SequenceBarrier
                      |
                      v
                WaitStrategy
                      |
                      v
                EventProcessor
                      |
                      v
                  Consumer

各组件的职责：

```
组件	主要职责
RingBuffer	保存预分配的事件对象
SingleProducerSequencer	分配序列号，防止生产者覆盖未消费的数据
Sequence	表示生产者或消费者的逻辑进度
SequenceBarrier	协调消费者等待数据及其依赖关系
WaitStrategy	决定消费者如何等待新事件
EventProcessor	获取并处理事件
Consumer	执行业务逻辑
```
其中，SingleProducerSequencer 不负责实际的业务事件处理，也不负责消费者的等待策略。

它主要负责协调：

生产者已经申请到哪里，以及消费者是否已经处理到足以允许 RingBuffer 复用的位置。

⸻

二、为什么需要 SingleProducerSequencer？

假设 RingBuffer 容量为 8。
```
RingBuffer 容量：8
物理槽位：
Index  0   1   2   3   4   5   6   7
┌───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┘
```
逻辑 Sequence 会不断递增：
```
Sequence	物理槽位
0	0
1	1
2	2
3	3
4	4
5	5
6	6
7	7
8	0
9	1
10	2
```
物理槽位通过逻辑序列号循环映射：

index = sequence & indexMask;

当 RingBuffer 容量为 8 时：

indexMask = 8 - 1; // 7

因此：
```
8 & 7  = 0;
9 & 7  = 1;
10 & 7 = 2;
```
这里要求 RingBuffer 容量是 2 的幂。

问题：生产者可以一直写入吗？

假设：

生产者已经写入到 Sequence 7。
消费者只处理到 Sequence 3。

如果生产者继续申请 Sequence 8：

Sequence 8 -> Index 0

就会覆盖 Sequence 0 对应的物理槽位。

如果消费者仍然需要读取 Sequence 0，那么数据就可能被覆盖。

因此，生产者必须确保：

只有当消费者已经处理完某个槽位之前的旧事件时，才允许复用该槽位。

这就是 SingleProducerSequencer 的核心职责。

⸻

三、核心字段解析

不同 Disruptor 版本的具体实现可能略有区别。下面以常见的 LMAX Disruptor 3.x/4.x 单生产者实现思路为基础进行说明。

典型字段如下：
```
public final class SingleProducerSequencer
extends SingleProducerSequencerFields
{
private long nextValue = Sequence.INITIAL_VALUE;
private long cachedValue = Sequence.INITIAL_VALUE;
// 其他方法省略
}
```

部分字段也可能定义在父类中。

1. nextValue：生产者本地分配进度
```
private long nextValue = Sequence.INITIAL_VALUE;
```
表示生产者已经分配到的最后一个逻辑序列号。

例如：

nextValue = 10;

意味着生产者已经分配到 Sequence 10。

下一次执行：

next(1);

会尝试申请 Sequence 11。

注意：

分配到某个 Sequence，不代表该事件已经写入，更不代表已经发布。

生产者通常需要依次执行：
```
next()
|
v
获取槽位
|
v
写入事件
|
v
publish()
```

2. cachedValue：缓存的消费者最小进度

private long cachedValue = Sequence.INITIAL_VALUE;

该字段用于缓存之前获取到的最小消费者 Sequence。

例如，存在三个 gating sequences：
```
Consumer A = 100
Consumer B = 95
Consumer C = 98
```

真正决定生产者能否继续复用 RingBuffer 空间的是：
```
min(100, 95, 98) = 95
```

如果生产者已经缓存：

cachedValue = 95;

后续申请时，就可以先利用这个缓存判断是否需要重新扫描所有消费者。

这样能够减少不必要的读取操作。

需要注意：

cachedValue 是生产者用于优化空间检查的缓存，不是消费者实时进度的权威记录。

3. cursor：对外可观察的生产者进度

cursor 通常定义在父类 AbstractSequencer 中。
```
protected final Sequence cursor;
```

它表示对外可观察的生产者进度，并参与事件发布协议。

在单生产者实现中，需要区分：
```
字段	含义
nextValue	生产者内部的序列号分配进度
cursor	对外可观察的生产者进度
cachedValue	缓存的消费者最小进度
```

它们承担不同职责，不能混为一谈。

尤其要注意：nextValue 已经递增，并不意味着对应事件已经发布。

消费者能否读取事件，还取决于发布协议和消费者依赖关系。

4. gatingSequences：消费者进度集合

该字段一般定义在 AbstractSequencer 中。
```
protected volatile Sequence[] gatingSequences;
```
它保存用于限制生产者复用 RingBuffer 空间的消费者序列。

假设：
```
Consumer A = 100
Consumer B = 95
Consumer C = 98
```
那么：

minimumSequence = 95

生产者必须根据这个最小进度判断 RingBuffer 是否有足够的可用空间。

为什么不只关注一个消费者？

因为 Disruptor 支持多个消费者，也支持消费者之间存在依赖关系。

例如：

              Producer
                  |
                  v
              RingBuffer
                  |
          +-------+-------+
          |               |
          v               v
     Consumer A       Consumer B
          |               |
          +-------+-------+
                  |
                  v
          后续依赖处理阶段

哪些消费者需要被纳入 gating sequences，取决于整个消费拓扑和相应的配置。

它们并不必然等于系统中所有消费者的简单集合。

⸻

四、核心方法一：next(int n)

这是 SingleProducerSequencer 最核心的方法之一。
```
@Override
public long next(final int n)
{
assert sameThread() :
"Accessed by two threads - use ProducerType.MULTI!";
if (n < 1 || n > bufferSize)
{
throw new IllegalArgumentException(
"n must be > 0 and < bufferSize");
}
long nextValue = this.nextValue;
long nextSequence = nextValue + n;
long wrapPoint = nextSequence - bufferSize;
long cachedGatingSequence = this.cachedValue;
if (wrapPoint > cachedGatingSequence ||
cachedGatingSequence > nextValue)
{
cursor.setVolatile(nextValue);
long minSequence;
while (wrapPoint >
(minSequence =
Util.getMinimumSequence(
gatingSequences, nextValue)))
{
LockSupport.parkNanos(1L);
}
this.cachedValue = minSequence;
}
this.nextValue = nextSequence;
return nextSequence;
}
```
这里直接使用你之前分析过的代码。

我们将它拆成 5 个步骤。

第一步：校验生产者线程

assert sameThread();

单生产者模式假设只有一个线程负责分配序列号。

因此，可以使用普通字段：
```
nextValue
cachedValue
```
而不必在每次申请序列号时使用 CAS 竞争。

如果多个线程并发调用同一个单生产者 Sequencer，就违反了它的使用约束。

第二步：计算本次申请的序列号
```
long nextValue = this.nextValue;
long nextSequence = nextValue + n;
```
假设：

this.nextValue = 10;
n = 3;

那么：

nextSequence = 13;

本次申请区间为：

11、12、13

方法返回：

return 13;

也就是说，返回本次申请的最后一个 Sequence。

第三步：计算 RingBuffer 覆盖边界

long wrapPoint = nextSequence - bufferSize;

假设：

bufferSize = 8;
nextSequence = 13;

那么：

wrapPoint = 13 - 8 = 5;

这表示生产者必须确保 Sequence 5 对应的旧槽位已经可以安全复用。

第四步：检查消费者进度
```
if (wrapPoint > cachedGatingSequence ||
cachedGatingSequence > nextValue)
```
如果缓存不足以证明 RingBuffer 有足够空间，就重新检查消费者的最小进度：
```
minSequence =
Util.getMinimumSequence(gatingSequences, nextValue);
```
如果：
```
wrapPoint > minSequence
```
则说明消费者尚未达到安全边界。

此时：
```
LockSupport.parkNanos(1L);
```
生产者短暂等待，并重新检查消费者进度。

直到：

minSequence >= wrapPoint

才允许继续。

第五步：更新生产者进度
```
this.nextValue = nextSequence;
return nextSequence;
```
这时，本次序列号申请成功。

但要再次强调：

next() 只负责申请 Sequence，不负责完成事件发布。

⸻

五、核心方法二：tryNext(int n)

除了阻塞等待的 next()，Sequencer 通常还提供非阻塞尝试申请的方法：

public long tryNext(int n) throws InsufficientCapacityException

具体声明以使用的版本为准。

它的核心思想是：

如果当前 RingBuffer 空间足够，就立即申请；如果空间不足，则返回容量不足的异常，而不是持续等待消费者。

简化逻辑：
```
public long tryNext(int n)
throws InsufficientCapacityException
{
if (n < 1 || n > bufferSize)
{
throw new IllegalArgumentException();
}
long nextSequence = nextValue + n;
long wrapPoint = nextSequence - bufferSize;
long cachedGatingSequence = cachedValue;
if (wrapPoint > cachedGatingSequence)
{
long minSequence =
Util.getMinimumSequence(
gatingSequences, nextValue);
if (wrapPoint > minSequence)
{
throw InsufficientCapacityException.INSTANCE;
}
cachedValue = minSequence;
}
nextValue = nextSequence;
return nextSequence;
}
```
以上是简化示意，不是所有版本的完整源码。

与 next() 的区别

对比项	next(n)	tryNext(n)
申请成功	返回最后一个 Sequence	返回最后一个 Sequence
空间不足	等待消费者推进	抛出容量不足异常
是否可能等待	是	不会因为等待消费者而持续阻塞
适用场景	可以等待的生产者	希望快速失败或由上层控制重试的场景

需要注意，非阻塞申请并不代表整个生产者流程绝对不会阻塞。

例如，申请成功之后的业务逻辑、锁竞争、网络调用仍可能产生等待。

此外，调用方必须正确处理容量不足的情况，不能简单地忽略异常。

⸻

六、核心方法三：publish(long sequence)

申请序列号后，生产者还需要发布事件。

典型使用方式：
```
long sequence = ringBuffer.next();
try
{
Event event = ringBuffer.get(sequence);
event.setOrderId("12345");
event.setAmount(100);
}
finally
{
ringBuffer.publish(sequence);
}
```
这里涉及两个完全不同的阶段：
```
阶段一：分配序列号
next()
|
v
获得 Sequence
阶段二：写入并发布事件
ringBuffer.get(sequence)
|
v
写入业务数据
|
v
publish(sequence)
|
v
消费者能够观察到已发布事件
```

在单生产者模式下，发布操作最终会更新对应的发布进度。

具体实现通常由 SingleProducerSequencer 或其父类负责。

例如，常见实现思路为：
```
@Override
public void publish(long sequence)
{
cursor.set(sequence);
}
```

实际源码还可能涉及 setVolatile()、setRelease() 或其他内存语义实现，具体取决于 Disruptor 版本。

为什么 publish 很重要？

假设：

long sequence = sequencer.next();

此时：

nextValue = 10

生产者获得了 Sequence 10。

但是，如果事件尚未写入，或者还没有发布，那么消费者不能仅仅因为生产者申请过 Sequence 10，就认为它已经可以被安全读取。

发布操作建立了生产者与消费者之间的数据可见性协议。

因此，生产者通常应保证：

1. 先获取 Sequence。
2. 再写入事件。
3. 最后发布 Sequence。

对于需要批量申请序列号的情况，还必须正确发布整个申请区间，避免留下未发布的序列号空洞。

⸻

七、核心方法四：hasAvailableCapacity(int requiredCapacity)

Sequencer 还需要支持检查 RingBuffer 当前是否有足够的容量。

典型接口：

public boolean hasAvailableCapacity(int requiredCapacity)

它的职责是：

检查当前是否存在足够的可用槽位，而不必真正申请这些槽位。

例如：
```
if (sequencer.hasAvailableCapacity(10))
{
// 当前容量检查通过
}
```
需要注意，这种容量检查本身不是对后续申请的预留。

在单生产者模式中，如果严格遵守单线程约束，其他生产者不会与当前线程竞争序列号；但消费者进度仍可能变化。

而在多生产者模式中，其他生产者可能在检查之后申请空间。

因此，不能把：

hasAvailableCapacity(n)

理解成：

我已经独占预留了 n 个槽位。

它只是一次容量检查。

如果需要真正申请序列号，应调用 next(n) 或 tryNext(n)。

⸻

八、核心方法五：remainingCapacity()

remainingCapacity() 用于估算当前 RingBuffer 剩余的可用容量。

典型实现的思路如下：
```
public long remainingCapacity()
{
long next = cursor.get();
long consumed = Util.getMinimumSequence(
gatingSequences, next);
return bufferSize - (next - consumed);
}
```

这里同样是简化示意，具体版本可能采用不同的序列字段或边界处理。

假设：

RingBuffer 容量 = 1024
生产者进度 = 1000
最慢消费者进度 = 900

那么：

已占用容量约为：
1000 - 900 = 100
剩余容量约为：
1024 - 100 = 924

实际可用容量的精确计算，需要结合具体实现中 cursor、nextValue、发布状态和消费者进度的语义理解。

特别是，生产者申请进度和已发布进度可能存在差异，因此不要把简化公式机械地套用到所有版本。

⸻

九、SingleProducerSequencer 如何避免数据覆盖？

这是整个类最重要的设计问题。

假设：

RingBuffer 容量：8
生产者进度：10
最慢消费者进度：4

生产者准备申请一个新的 Sequence：

nextSequence = 11

那么：

wrapPoint = 11 - 8 = 3

由于：

wrapPoint = 3
consumerSequence = 4

满足：

consumerSequence >= wrapPoint

所以空间足够。

如果生产者准备申请到 Sequence 13：

wrapPoint = 13 - 8 = 5

而：

consumerSequence = 4

那么：

5 > 4

空间不足，生产者必须等待。

可以归纳为：

wrapPoint = nextSequence - bufferSize;
if (wrapPoint > minGatingSequence)
{
// 消费者进度不足，需要等待或拒绝申请
}

这个判断就是单生产者模式下防止覆盖未消费数据的核心。

⸻

十、为什么 SingleProducerSequencer 不需要 CAS？

这是面试中经常被问到的问题。

多生产者环境下：
```
Producer A ----+
|
Producer B ----+----> RingBuffer
|
Producer C ----+
```
多个生产者可能同时申请序列号。

例如：
```
long current = nextValue;
long next = current + 1;
```
如果没有同步：
```
Producer A 读取到 10
Producer B 读取到 10
Producer A 申请 11
Producer B 申请 11
```
就会发生序列号冲突。

因此，多生产者模式必须协调并发申请，通常会使用 CAS 或其他原子操作。

而单生产者模式：
```
唯一 Producer
|
v
SingleProducerSequencer
|
v
RingBuffer
```
只允许一个生产者线程负责分配序列号。

因此：

this.nextValue = nextSequence;

可以直接更新普通字段。

这减少了：

* CAS 重试。
* 原子变量竞争。
* 多生产者序列号协调开销。

但需要注意：

单生产者模式只意味着生产者序列号分配由单线程完成，不意味着整个 Disruptor 系统只有一个线程。

消费者依然可以有多个线程。

⸻

十一、SingleProducerSequencer 与 MultiProducerSequencer 的区别
```
对比维度	SingleProducerSequencer	MultiProducerSequencer
生产者数量	一个生产者线程	多个生产者线程
序列号分配	普通字段更新	需要并发协调
是否需要 CAS	分配路径通常不需要	通常需要
竞争开销	较低	相对较高
RingBuffer 空间检查	检查 gating sequences	同样需要检查容量和消费进度
事件发布	更新单生产者发布进度	需要处理并发发布状态
典型场景	单线程事件入口	多线程并发事件入口
```
不过，不能简单认为：

SingleProducerSequencer 一定比 MultiProducerSequencer 快。

只有在生产者确实能够限制为单线程时，才适合使用单生产者实现。

如果系统需要多个线程并发提交事件，就应该根据实际需求选择正确的生产者类型。

错误地使用单生产者模式可能导致序列号分配和事件写入发生竞争，带来数据错误。

⸻

十二、结合交易所系统理解

假设你要设计一个交易所内部的订单处理链路：
```
订单请求
|
v
订单接入
|
v
RingBuffer
|
v
订单处理线程
|
v
订单状态变更
|
v
Kafka
|
v
资产系统
```
如果订单接入层已经通过单线程完成事件归一化，并由唯一线程向 RingBuffer 发布事件，就可以考虑：

ProducerType.SINGLE

此时 SingleProducerSequencer 负责：

1. 为订单事件分配 Sequence。
2. 判断 RingBuffer 是否有可用槽位。
3. 在消费者处理较慢时限制生产者继续写入。
4. 在事件写入后，通过发布协议让消费者读取。

但是，如果多个网络 I/O 线程会直接向同一个 RingBuffer 发布订单事件，那么就不能仅仅因为希望获得更高性能，就强行采用单生产者模式。

可以考虑两种架构。

方案 A：多生产者直接提交
```
Netty Thread 1 ----+
|
Netty Thread 2 ----+----> MultiProducerSequencer
|
Netty Thread 3 ----+
```
优点是接入路径简单。

缺点是多个生产者需要协调序列号分配。

方案 B：多线程接入，单线程汇聚
```

Netty Thread 1 ----+
|
Netty Thread 2 ----+----> 队列 / 汇聚层
|           |
Netty Thread 3 ----+           v
单生产者线程
|
v
SingleProducerSequencer
|
v
RingBuffer
```

这种方案可以减少 RingBuffer 入口处的多生产者竞争，但汇聚层本身也可能引入排队和调度开销。

因此，最终还是要结合端到端延迟、吞吐量、线程模型和背压机制进行评估。

对于交易系统，还必须明确：

* 订单是否需要按账户、交易对或订单簿分区。
* 是否需要严格保证分区内的事件顺序。
* RingBuffer 满时应该阻塞、拒绝还是让上游限流。
* 事件发布失败时如何处理。
* 进程异常退出后如何恢复。
* 如何保证订单状态与资产账务的一致性。

SingleProducerSequencer 解决的是内存事件队列中的序列号分配和空间复用问题，而不是整个交易系统的持久化和业务一致性问题。

⸻

十三、最后总结：面试应该怎么讲？

如果面试官问：

请介绍一下 Disruptor 的 SingleProducerSequencer。

可以这样回答：

SingleProducerSequencer 是 Disruptor 单生产者模式下的核心序列号分配组件，负责为生产者分配 Sequence，并通过消费者的 gating sequences 控制 RingBuffer 空间的复用。

它主要维护三个重要状态：nextValue 表示生产者本地的序列号分配进度，cachedValue 缓存之前计算得到的最小消费者进度，cursor 则表示对外可观察的生产者进度并参与事件发布协议。

在调用 next(n) 时，首先计算本次申请的最后一个序列号 nextSequence，然后通过 wrapPoint = nextSequence - bufferSize 计算覆盖边界。

如果缓存的消费者进度不足以证明空间安全，就重新获取 gating sequences 中的最小值。如果最慢消费者尚未达到安全边界，生产者就等待；否则更新缓存并完成序列号分配。

单生产者模式不需要在序列号分配路径中通过 CAS 协调多个生产者，因此可以减少竞争开销。但它要求生产者遵守单线程约束。

此外，next() 只负责申请序列号，生产者仍然需要写入事件并调用 publish()。消费者只有在发布协议允许的情况下，才能读取已发布的数据。

核心思想就是：通过单线程序列号分配降低竞争，通过 gating sequences 保证 RingBuffer 空间安全复用，通过 publish 协议协调生产者和消费者之间的数据可见性。