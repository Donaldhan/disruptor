Disruptor 源码解析：MultiProducerSequencer

MultiProducerSequencer 是 Disruptor 在多生产者并发场景下负责序列号分配、并发发布协调和 RingBuffer 空间管理的核心组件。

上一节我们分析了 SingleProducerSequencer。两者最大的区别在于：

* SingleProducerSequencer：只有一个生产者线程分配序列号，可以直接更新普通字段。
* MultiProducerSequencer：多个生产者线程可能同时申请序列号，需要通过 CAS 等机制协调并发操作。

但多生产者实现真正值得研究的，不仅是 CAS，还有一个关键问题：

多个生产者可能先后申请到序列号，却以不同顺序完成事件写入和发布。Disruptor 如何判断哪些事件已经真正可以被消费者读取？

这涉及 MultiProducerSequencer 的三个核心机制：

1. CAS 分配序列号。
2. availableBuffer 标记每个槽位的发布状态。
3. SequenceBarrier 和消费者进度协调读取过程。

下面结合源码、时序图和交易所业务场景展开分析。

⸻

一、MultiProducerSequencer 在 Disruptor 中的位置

先看整体架构。

          Producer A          Producer B          Producer C
              |                   |                   |
              +-------------------+-------------------+
                                  |
                                  v
                        MultiProducerSequencer
                                  |
                      +-----------+-----------+
                      |                       |
                      v                       v
                CAS 分配 Sequence       检查 RingBuffer 容量
                      |                       |
                      +-----------+-----------+
                                  |
                                  v
                              RingBuffer
                                  |
                      +-----------+-----------+
                      |           |           |
                      v           v           v
                  Sequence 10 Sequence 11 Sequence 12
                      |           |           |
                      v           v           v
                   写入事件     写入事件     写入事件
                      |           |           |
                      v           v           v
                   publish     publish     publish
                      |           |           |
                      +-----------+-----------+
                                  |
                                  v
                        SequenceBarrier
                                  |
                                  v
                             Consumer

这里有一个重要区别：

序列号分配顺序，不等于事件完成写入的顺序，也不一定等于事件发布完成的顺序。

例如：
```
Producer A 申请 Sequence 10
Producer B 申请 Sequence 11
Producer B 先写完并发布 Sequence 11
Producer A 还在写 Sequence 10
```
此时，Sequence 11 对应的槽位可能已经完成写入，但 Sequence 10 还没有完成。

消费者不能仅凭 Sequence 11 已经发布，就认定 Sequence 10 和 11 都可以安全读取。

MultiProducerSequencer 必须能够区分每个序列号对应的槽位是否已经发布。

这就是它与单生产者实现最值得深入研究的地方。

⸻

二、核心字段解析

以常见的 Disruptor 3.x/4.x 实现为基础，MultiProducerSequencer 的核心字段包括：
```
public final class MultiProducerSequencer extends AbstractSequencer
{
private static final int BUFFER_PAD = 32;
private static final long INDEX_MASK;
private static final long AVAILABLE_ARRAY_OFFSET;
private final long[] availableBuffer;
private final int indexMask;
private final int indexShift;
}
```
不同版本的具体字段、数组类型和内存访问实现可能略有差异。

重点关注以下几个概念。
```
字段	作用
cursor	记录生产者已经分配到的最高序列号
gatingSequences	消费者进度集合，用于限制 RingBuffer 空间复用
availableBuffer	标记各个物理槽位对应的逻辑序列号是否已经发布
indexMask	将逻辑序列号映射到 RingBuffer 槽位
indexShift	将逻辑序列号映射到发布状态数组中的代数标记
```
其中，最值得关注的是：
```
private final long[] availableBuffer;
```
为什么多生产者模式需要这个数组，而单生产者模式通常不需要采用同样的机制？

因为多个生产者可以同时写入不同的槽位，而且完成写入的顺序可能与序列号分配顺序不同。

因此，必须能够独立记录每个槽位的发布状态。

⸻

三、核心机制一：CAS 分配序列号

先看多生产者模式中最重要的方法之一：next(int n)。

下面是典型实现的核心逻辑，省略了部分异常处理和容量检查细节：
```
@Override
public long next(int n)
{
if (n < 1)
{
throw new IllegalArgumentException("n must be > 0");
}
long current;
long next;
do
{
current = cursor.get();
next = current + n;
long wrapPoint = next - bufferSize;
long cachedGatingSequence = gatingSequenceCache.get();
if (wrapPoint > cachedGatingSequence ||
cachedGatingSequence > current)
{
long gatingSequence =
Util.getMinimumSequence(
gatingSequences, current);
if (wrapPoint > gatingSequence)
{
LockSupport.parkNanos(1L);
continue;
}
gatingSequenceCache.set(gatingSequence);
}
}
while (!cursor.compareAndSet(current, next));
return next;
}
```
注意：以上是用于理解机制的简化代码，不是可以直接替换实际源码的完整实现。实际版本的字段访问、缓存方式和循环结构可能不同。

1. 为什么需要 CAS？

假设当前：

cursor = 100;

两个生产者同时执行：
```
long current = cursor.get();
long next = current + 1;
```
可能出现：
```
Producer A 读取 cursor = 100
Producer B 读取 cursor = 100
Producer A 计算 next = 101
Producer B 计算 next = 101
```
如果直接更新：
```
cursor.set(101);
```
两个生产者就可能都认为自己申请到了 Sequence 101。

这显然不正确。

因此，多生产者模式需要通过 CAS 完成原子更新：
```
cursor.compareAndSet(current, next);
```
它的语义是：

只有当 cursor 仍然等于 current 时，才将其更新为 next。

如果两个生产者同时尝试更新：
```
初始 cursor = 100
Producer A:
CAS(100, 101) -> 成功
Producer B:
CAS(100, 101) -> 失败
```
B 需要重新读取 cursor，再次尝试申请。

此时：

cursor = 101

B 可以重新申请 Sequence 102。

最终：
```
Producer A -> Sequence 101
Producer B -> Sequence 102
```
这样就避免了序列号冲突。

2. 为什么采用 CAS 循环？

因为 CAS 只保证单次更新的原子性。

在竞争激烈的情况下，CAS 可能失败，因此通常需要循环重试：
```
do
{
current = cursor.get();
next = current + 1;
}
while (!cursor.compareAndSet(current, next));
```
这里体现的是典型的乐观并发控制：

1. 先读取当前状态。
2. 基于当前状态计算新值。
3. 尝试原子更新。
4. 如果状态已变化，重新计算。

3. 批量申请如何处理？

例如：
```
long sequence = sequencer.next(3);
```
假设当前 cursor 为 100，那么本次申请的序列号为：

101、102、103

CAS 更新的目标值是：

cursor: 100 -> 103

方法返回：

103

返回值表示本次申请的最后一个序列号。

这与 SingleProducerSequencer.next(n) 的接口语义相同。

⸻

四、核心机制二：为什么 CAS 成功不代表事件已经发布？

这是理解 MultiProducerSequencer 的关键。

假设：

Producer A 申请 Sequence 101
Producer B 申请 Sequence 102

此时：

cursor = 102

但注意，cursor 的推进只是序列号分配成功，并不意味着 Sequence 101 和 102 都已经完成事件写入。

接下来可能发生：
```
时间线：
Producer A                  Producer B
|                           |
v                           v
申请 Sequence 101          申请 Sequence 102
|                           |
v                           v
开始写入事件                开始写入事件
|                           |
|                           v
|                       写入完成
|                           |
|                           v
|                       发布 Sequence 102
|
| 仍在执行较慢的业务逻辑
|
v
写入完成
|
v
发布 Sequence 101
```
在这段时间内：

Sequence 101：尚未发布
Sequence 102：已经发布

如果消费者只判断：

cursor.get() >= 102

就会误以为 Sequence 101 和 102 都可以读取。

但 Sequence 101 对应的事件可能还没有写完。

这会造成数据读取时机错误，严重时还可能读取到未初始化或不完整的事件内容。

因此：

多生产者模式不能单纯依靠 cursor 判断每个事件是否已经发布。

还需要一套独立的发布状态记录机制。

⸻

五、核心机制三：availableBuffer

availableBuffer 就是用来解决上述问题的。

private final long[] availableBuffer;

它记录每个物理槽位对应的逻辑序列号是否已经发布。

可以将它理解为：
```
RingBuffer 数据区：
Index       0       1       2       3       4
+-------+-------+-------+-------+-------+
Event     |  E0   |  E1   |  E2   |  E3   |  E4   |
+-------+-------+-------+-------+-------+
发布状态区：
Index       0       1       2       3       4
+-------+-------+-------+-------+-------+
State     |  -1   |   0   |  -1   |   0   |  -1   |
+-------+-------+-------+-------+-------+
```
上面只是示意。实际数组中的状态值采用序列号代数标记，并不是简单地用 0 和 -1 永久表示已发布和未发布。

核心思路是：

每个物理槽位记录自己最近一次被哪个逻辑序列号发布。

这样就能判断：

* 某个槽位是否已经发布。
* 发布状态对应的是当前这一轮，还是之前的循环轮次。

⸻

六、为什么不能只使用 boolean？

假设 RingBuffer 容量为 8。

物理槽位映射：

index = sequence & 7;

那么：
```
Sequence 0 -> Index 0
Sequence 8 -> Index 0
Sequence 16 -> Index 0
Sequence 24 -> Index 0
```
同一个物理槽位会不断被复用。

如果只记录：

availableBuffer[index] = true;

就会产生问题。

例如：
```
Sequence 0 已经发布
|
v
Index 0 标记为 true
|
v
RingBuffer 循环
|
v
生产者申请 Sequence 8
|
v
Sequence 8 尚未写完
```
此时，如果状态仍然是 true，消费者可能错误地认为 Sequence 8 已经发布。

因此，必须同时识别：

1. 物理槽位。
2. 当前逻辑序列号所属的循环轮次。

Disruptor 使用代数标记解决这个问题。

⸻

七、availableBuffer 的核心算法

在典型实现中，发布状态相关的两个关键方法是：
```
private int calculateIndex(long sequence)
{
return (int) sequence & indexMask;
}
private int calculateAvailabilityFlag(long sequence)
{
return (int) (sequence >>> indexShift);
}
```
具体实现可能因版本而不同，但核心思想相同。

1. calculateIndex

假设：

bufferSize = 8;
indexMask = 7;

则：

calculateIndex(10);

计算结果：

10 & 7 = 2;

所以 Sequence 10 对应物理槽位 2。

2. calculateAvailabilityFlag

这个方法计算逻辑序列号对应的循环轮次标记。

对于容量为 8 的 RingBuffer：
```
Sequence 0～7   -> 第 0 轮
Sequence 8～15  -> 第 1 轮
Sequence 16～23 -> 第 2 轮
```
因此，理想化地表示：
```
Sequence 2  -> Index 2，轮次 0
Sequence 10 -> Index 2，轮次 1
Sequence 18 -> Index 2，轮次 2
```
虽然它们映射到同一个物理槽位，但对应不同的逻辑轮次。

在实际代码中：

sequence >>> indexShift

通过位移运算计算轮次。

因为 RingBuffer 容量为 2 的幂：

bufferSize = 2^k
indexShift = k

所以：

sequence >>> indexShift

相当于：

sequence / bufferSize

在常见容量约束下，这个移位计算更适合高频路径。

⸻

八、发布事件时如何更新 availableBuffer？

在典型的多生产者实现中，发布方法的核心逻辑类似：
```
@Override
public void publish(long sequence)
{
setAvailable(sequence);
}
```
批量发布：
```
@Override
public void publish(long lo, long hi)
{
for (long sequence = lo; sequence <= hi; sequence++)
{
setAvailable(sequence);
}
}
```
而 setAvailable() 的核心逻辑类似：
```
private void setAvailable(long sequence)
{
int index = calculateIndex(sequence);
int flag = calculateAvailabilityFlag(sequence);
long offset = calculateAvailabilityOffset(index);
UNSAFE.putOrderedLong(
availableBuffer,
offset,
flag);
}
```
这里的具体内存访问 API 会随版本变化，可能使用 VarHandle 或其他封装。

为了便于理解，可以把它看成：

availableBuffer[index] = flag;

但实际实现需要使用满足发布协议要求的内存语义，不能简单替换成任意普通数组写入。

举例

假设：

RingBuffer 容量 = 8
Producer A 获得 Sequence 9
Producer B 获得 Sequence 10

它们对应：

Sequence 9  -> Index 1，轮次 1
Sequence 10 -> Index 2，轮次 1

当 B 先完成写入并发布 Sequence 10：

availableBuffer[2] = 1

但 A 尚未发布 Sequence 9：

availableBuffer[1] != 1

消费者检查时就能发现：

Sequence 9：未发布
Sequence 10：已发布

即使 cursor 已经推进到 10，也不会把 Sequence 9 误认为已发布。

⸻

九、消费者如何判断事件是否已经发布？

这部分通常由 Sequencer 的可用序列查询方法完成。

例如：
```
@Override
public long getHighestPublishedSequence(
long lowerBound,
long availableSequence)
{
for (long sequence = lowerBound;
sequence <= availableSequence;
sequence++)
{
if (!isAvailable(sequence))
{
return sequence - 1;
}
}
return availableSequence;
}
```
这是用于说明机制的简化实现。

实际代码还会通过 availableBuffer、索引和轮次标记检查每个序列号的发布状态。

示例

假设：

消费者希望从 Sequence 9 开始读取。
生产者分配进度：
cursor = 10
发布状态：
Sequence 9  -> 未发布
Sequence 10 -> 已发布

消费者检查：

Sequence 9：未发布

于是返回：

highestPublishedSequence = 8

消费者不能越过 Sequence 9 直接读取 Sequence 10。

等 Producer A 发布 Sequence 9 后：

Sequence 9  -> 已发布
Sequence 10 -> 已发布

消费者再次检查，就可以安全读取连续的 Sequence 9 和 10。

这里的关键是：

消费者获得的是从指定起点开始的连续可用序列范围，而不是简单地读取最大已发布序列号。

这也是为什么多生产者实现需要逐槽位维护发布状态。

⸻

十、next() 的完整执行流程

把 CAS、容量检查和发布状态结合起来，可以理解多生产者模式的整体流程。
```
Producer A / B / C
|
v
next(n)
|
v
读取 cursor 当前值
|
v
计算 nextSequence
|
v
计算 wrapPoint
|
v
检查 gatingSequences
|
+---- 空间不足 ----> 等待并重新检查
|
v
CAS 更新 cursor
|
+---- CAS 失败 ----> 重新读取并重试
|
v
返回申请的 Sequence
|
v
写入 RingBuffer 事件
|
v
publish(sequence)
|
v
更新 availableBuffer
|
v
消费者检查连续可用序列
|
v
处理已发布事件
```
注意，容量检查和 CAS 更新必须正确配合。

如果只检查容量，却没有在并发申请时协调序列号，多个生产者仍然可能超额申请 RingBuffer 空间。

而如果只通过 CAS 分配序列号，却没有检查 gating sequences，生产者又可能覆盖消费者尚未处理的数据。

因此，CAS 解决并发序列号分配问题，gating sequences 解决 RingBuffer 空间复用问题，availableBuffer 解决并发发布状态问题。

三者的职责不同。

⸻

十一、tryNext()：不等待空间的申请方式

与 next() 类似，多生产者 Sequencer 通常还提供：
```
public long tryNext(int n)
throws InsufficientCapacityException
```
它的主要语义是：

* 容量足够：尝试原子分配序列号。
* 容量不足：抛出 InsufficientCapacityException，让调用方决定如何处理。

在多生产者场景中，即使容量检查通过，CAS 也可能因为其他生产者同时申请而失败，因此通常仍需要重试序列号分配。

简化逻辑：
```
public long tryNext(int n)
throws InsufficientCapacityException
{
if (!hasAvailableCapacity(n))
{
throw InsufficientCapacityException.INSTANCE;
}
while (true)
{
long current = cursor.get();
long next = current + n;
if (cursor.compareAndSet(current, next))
{
return next;
}
}
}
```
这段代码仅用于说明接口意图，不是完整、可直接使用的真实实现。

实际实现必须保证容量检查与并发申请之间的安全性，不能仅依赖上面这种未经完整校验的简化逻辑。

适用场景包括：

* 不能长时间阻塞的调用线程。
* 希望快速失败并由上层重试的业务。
* 需要将容量不足转换成限流或降级信号的系统。

例如交易系统可以根据业务要求决定：
```
RingBuffer 容量不足
|
+---- 允许排队：上游等待
|
+---- 允许重试：返回重试信号
|
+---- 不允许等待：拒绝或降级
```
但涉及订单和资金业务时，必须确保拒绝、重试与业务幂等机制相匹配。

⸻

十二、SingleProducerSequencer 与 MultiProducerSequencer 对比
```
对比维度	SingleProducerSequencer	MultiProducerSequencer
生产者数量	单生产者线程	多生产者线程
序列号分配	普通字段更新	CAS 等并发协调
生产者竞争	基本不存在	可能发生
RingBuffer 空间检查	gating sequences	gating sequences
发布状态管理	可以利用单生产者的有序发布特性	需要独立跟踪各槽位发布状态
典型实现字段	nextValue、cachedValue	cursor、availableBuffer 等
主要开销	较低	存在 CAS 与发布状态检查开销
适用场景	单线程事件入口	多线程并发事件入口
```
这里需要避免两个常见误区。

误区一：多生产者模式一定更慢。

多生产者模式有额外的协调开销，但如果系统天然存在多个生产者线程，使用单生产者模式并不能消除并发，反而可能迫使你增加汇聚层和线程切换。

误区二：CAS 保证了事件发布顺序。

CAS 只保证序列号分配过程中的原子更新，并不保证各个生产者按照序列号顺序完成事件写入和发布。

真正判断事件是否已经发布，还需要依赖 availableBuffer 等机制。

⸻

十三、结合交易所订单和资产系统

假设你的系统有多个 Netty 线程接收交易请求：
```
Netty Thread 1 ----+
|
Netty Thread 2 ----+----> MultiProducerSequencer
|
Netty Thread 3 ----+
|
v
RingBuffer
|
v
订单处理消费者
|
v
Kafka / 资产系统
```
此时 MultiProducerSequencer 主要解决三件事。

1. 并发申请序列号

不同 Netty 线程可以同时提交事件。

通过 CAS，保证每次成功申请得到不同的序列号。

2. 防止覆盖未消费事件

当 RingBuffer 快满时，通过 gating sequences 检查最慢消费者的进度。

消费者进度不足时，生产者不能继续复用相关槽位。

3. 处理不同生产者的发布时序

假设：

Thread A -> Sequence 100
Thread B -> Sequence 101

B 先完成写入，A 后完成写入。

通过逐槽位发布状态，消费者能够识别 Sequence 100 尚未发布的情况，不会因为 Sequence 101 已经发布就越过前面的空缺。

但要特别注意：

Sequence 顺序不等于业务顺序。

即使 RingBuffer 按 Sequence 顺序交付事件，也不意味着不同线程提交事件的先后关系一定符合业务要求。

例如，同一账户的两个资金变更事件如果要求严格串行，就必须在业务层设计分区、单线程执行器、版本校验或其他顺序控制机制。

MultiProducerSequencer 本身不会自动建立账户级、订单级或交易对级的业务顺序。

⸻

十四、面试重点：三个机制一定要讲清楚

如果面试官问你：

MultiProducerSequencer 如何保证多生产者场景下的正确性？

建议从以下三个层面回答。

第一层：CAS 保证序列号分配不冲突

多个生产者可能同时读取相同的 cursor。

通过 CAS 原子更新，只有一个生产者能够成功占用对应的序列号区间；其他生产者需要重新读取并重试。

第二层：gating sequences 防止覆盖未消费数据

在申请序列号之前，Sequencer 会检查 RingBuffer 是否仍然有足够空间。

如果最慢消费者尚未达到安全边界，生产者就不能继续复用相应的物理槽位。

第三层：availableBuffer 记录独立发布状态

多个生产者的写入和发布可能乱序。

因此，Sequencer 为每个物理槽位记录相应的逻辑序列号标记。

消费者只有在所需的连续序列范围已经发布后，才能继续读取。

可以总结为：

CAS
|
+-- 解决：序列号分配冲突
gatingSequences
|
+-- 解决：RingBuffer 空间复用安全
availableBuffer
|
+-- 解决：并发发布状态判断

这三个机制分别处理不同的问题，不能相互替代。

最后再补充一点：如果你正在准备 Java 高并发或交易系统架构面试，建议进一步研究 MultiProducerSequencer 的 publish()、setAvailable()、isAvailable() 和 getHighestPublishedSequence() 这几个方法。它们能够帮助你把序列号分配、发布状态与消费者可见性完整地串联起来。