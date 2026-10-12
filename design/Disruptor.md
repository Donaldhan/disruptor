Disruptor 深度解析：从整体架构到核心源码

结合前面已经分析的 SingleProducerSequencer、MultiProducerSequencer、ProcessingSequenceBarrier、BlockingWaitStrategy、BatchEventProcessor 和 EventHandlerGroup，这次我们从整体架构出发，把 Disruptor 的设计思想、核心源码、生产消费流程和性能优化机制串起来。

对于 Java 后端架构师，学习 Disruptor 不应该只停留在 API 层面，更重要的是理解：

1. 为什么 Disruptor 选择 RingBuffer，而不是传统的阻塞队列？
2. 生产者如何申请序列、写入事件和发布事件？
3. 消费者如何判断事件是否真正可用？
4. Sequence 如何协调生产者与多个消费者？
5. 多消费者之间如何实现并行、依赖和有序执行？
6. Disruptor 的性能优势来自哪里？又有哪些限制？
7. 如何将它应用到交易所订单、成交、资产更新等高性能场景？

⸻

一、Disruptor 是什么？

LMAX Disruptor 是一个用于高性能线程间消息传递的 Java 库。

它最初由 LMAX 团队开发，主要解决低延迟、高吞吐量场景下的线程间事件传递问题。

传统的生产消费模型通常是：
```
Producer
|
v
BlockingQueue
|
v
Consumer
```
而 Disruptor 采用预分配的环形缓冲区和序列号协调机制：
```
Producer
|
v
RingBuffer + Sequencer
|
v
SequenceBarrier
|
v
EventProcessor
|
v
EventHandler
```
它的核心目标是：

* 降低线程间消息传递的开销。
* 减少不必要的对象分配和 GC 压力。
* 支持高效的批量消费。
* 支持多生产者和多消费者。
* 支持消费者依赖关系编排。
* 在合适的硬件和工作负载下，实现较低的处理延迟。

需要注意，Disruptor 并不是一个通用的分布式消息队列，也不提供 Kafka 那样的持久化日志、跨进程传输和消息保留能力。

它主要解决的是 JVM 进程内部的高性能事件传递问题。

⸻

二、整体架构：先建立全局认知

先看 Disruptor 的核心组件。

从源码角度，可以将核心组件分成五个层次。
```
层次	核心组件	主要职责
事件存储层	RingBuffer	存储和复用事件对象
序列协调层	Sequencer	管理序列申请、事件发布和槽位复用
等待协调层	SequenceBarrier、WaitStrategy	等待事件可用，协调消费者依赖
消费执行层	BatchEventProcessor、WorkProcessor	驱动事件消费
消费拓扑层	Disruptor、EventHandlerGroup、ConsumerRepository	注册消费者，构建依赖关系
```
其中，RingBuffer、Sequencer 和 Sequence 是理解整个系统的基础。

可以把它们分别理解为：

* RingBuffer：事件放在哪里。
* Sequencer：谁可以使用哪个序列，以及事件什么时候发布。
* Sequence：各个参与者目前推进到哪里。

其他组件则围绕这三个基础概念构建生产消费流程。

⸻

三、RingBuffer：为什么采用环形数组？

3.1 RingBuffer 的基本结构

假设 RingBuffer 容量为 8。

          +-----+-----+-----+-----+
          |  0  |  1  |  2  |  3  |
          +-----+-----+-----+-----+
          |  7  |           |  4  |
          +-----+-----+-----+-----+
                |  6  |  5  |
                +-----+-----+

RingBuffer 的底层可以理解为一个固定容量的数组。

假设容量为 8：
```
Event[] entries = new Event[8];
```
为了将逻辑序列号映射到数组下标，可以使用：
```
int index = (int) (sequence & (bufferSize - 1));
```
当容量为 2 的幂时：

index = sequence \bmod bufferSize

可以用位运算实现取模，减少计算开销。

例如：
```
bufferSize = 8
sequence = 8
index = 8 & 7 = 0
sequence = 9
index = 9 & 7 = 1
sequence = 10
index = 10 & 7 = 2
```
逻辑序列号持续递增，而物理数组下标循环复用。

因此：
```
Sequence 0  -> Slot 0
Sequence 1  -> Slot 1
...
Sequence 7  -> Slot 7
Sequence 8  -> Slot 0
Sequence 9  -> Slot 1
```
注意：数组下标相同，不代表逻辑事件相同。

序列号用于区分不同轮次的事件，避免把槽位复用与逻辑进度混为一谈。

3.2 为什么需要预分配事件对象？

常见初始化方式：
```
RingBuffer<OrderEvent> ringBuffer =
RingBuffer.createMultiProducer(
OrderEvent::new,
1024
);
```
这里使用事件工厂预先创建 RingBuffer 中的事件对象。

正常运行时，生产者反复获取槽位，并修改其中的字段，而不是每次都创建新的事件对象。

例如：
```
long sequence = ringBuffer.next();
try {
OrderEvent event = ringBuffer.get(sequence);
event.setOrderId(orderId);
event.setPrice(price);
event.setQuantity(quantity);
} finally {
ringBuffer.publish(sequence);
}
```
这种设计能够减少事件对象及队列节点的反复分配。

但需要注意：

预分配不代表整个应用没有 GC。

如果事件处理器中大量创建集合、临时对象、字符串或包装类型，仍然可能产生显著的 GC 压力。

另外，预分配也不代表事件对象可以被生产者无限制地覆盖。槽位是否可以安全复用，还需要结合消费者进度判断。

⸻

四、Sequencer：Disruptor 的核心协调器

如果说 RingBuffer 负责存储，那么 Sequencer 就是整个生产消费协调机制的核心。

它主要解决四个问题：

1. 生产者如何申请序列号？
2. 生产者如何发布事件？
3. 消费者如何判断序列是否可用？
4. 生产者如何判断 RingBuffer 是否有可复用空间？

Disruptor 的核心 Sequencer 通常有两种实现：

* SingleProducerSequencer
* MultiProducerSequencer

它们使用相同的基本抽象，但在并发申请和发布管理方面存在重要差异。

4.1 单生产者模式

单生产者模式假设只有一个生产线程申请序列。

其基本过程是：
```
Producer
|
v
申请下一个序列
|
v
检查 RingBuffer 容量
|
v
获取事件槽位
|
v
写入事件
|
v
publish(sequence)
```
单生产者不需要解决多个生产线程同时争抢下一个序列的问题，因此可以采用更简单的协调逻辑。

例如：
```
long sequence = ringBuffer.next();
try {
OrderEvent event = ringBuffer.get(sequence);
event.setOrderId(orderId);
} finally {
ringBuffer.publish(sequence);
}
```
其中 next() 负责申请序列，publish() 负责声明事件已经发布。

这两个步骤必须区分。

申请到序列号，不代表事件已经准备好供消费者读取。

4.2 多生产者模式

如果多个生产线程同时写入 RingBuffer：
```
Producer A ──┐
Producer B ──┼──> RingBuffer
Producer C ──┘
```
那么多个线程可能同时申请序列。

典型的多生产者流程：
```
Producer A ──> claim sequence 101
Producer B ──> claim sequence 102
Producer C ──> claim sequence 103
```
生产者拿到序列号后，可以并发写入各自对应的槽位。

但这里会产生一个重要问题。

假设：
```
Producer A：拿到 101，写入很慢
Producer B：拿到 102，已经写入并发布
Producer C：拿到 103，已经写入并发布
```
此时：
```
101：尚未发布
102：已经发布
103：已经发布
```
消费者不能简单地认为，既然 103 已经发布，就可以忽略 101，直接处理 102、103。

如果业务要求按照序列顺序处理，就必须识别中间未发布的序列。

MultiProducerSequencer 使用额外的发布状态管理机制记录各序列是否可用。

消费者在推进消费进度时，必须遵守连续可用序列的约束。

例如：
```
Sequence: 101  102  103  104
Status:    ×    ✓    ✓    ✓
```
此时，虽然 102、103、104 已经发布，但从 101 开始的连续可用范围仍然为空。

当 101 发布之后，连续可用范围才可能扩展到 104。

这是多生产者模式中非常重要的设计。

4.3 为什么不能只看 Cursor？

Cursor 通常表示生产者已发布进度中的一个关键位置，但在多生产者模式下，不能把 Cursor 简单理解为从起始位置到该位置的所有事件都已经发布。

例如：
```
Cursor = 103
101：未发布
102：已发布
103：已发布
```
实际是否可以读取某个序列，需要通过对应的可用性检查机制判断。

这也是为什么多生产者模式需要 availableBuffer 等发布状态管理机制，而不是只依赖一个递增的游标。

⸻

五、Sequence：贯穿整个 Disruptor 的核心抽象

Sequence 用于记录一个参与者的逻辑进度。

它不仅用于生产者，还用于消费者及依赖关系管理。

例如：
```
Producer Cursor = 1000
Consumer A      = 950
Consumer B      = 900
Consumer C      = 980
```
这些数值分别表示不同参与者的进度，不能直接混为一谈。

5.1 Sequence 为什么使用填充字段？

在高性能并发场景中，多个线程频繁更新不同变量时，可能出现伪共享（False Sharing）。

假设：
```
Cache Line
+----------------------+----------------------+
| Producer Sequence    | Consumer Sequence    |
+----------------------+----------------------+
```
如果两个变量位于同一缓存行，并且由不同 CPU 核心频繁更新，缓存一致性协议可能导致缓存行反复在核心之间转移。

即使两个线程操作的是不同变量，也可能相互影响性能。

Disruptor 的 Sequence 实现使用填充设计来降低这类风险。

较新版本的具体实现可能有所不同，但其目标是减少频繁更新的序列变量之间的伪共享。

不过要注意：

* 缓存行大小与处理器架构有关。
* 填充并不保证所有硬件和布局下都能彻底避免伪共享。
* 性能收益需要通过目标环境的基准测试验证。

5.2 Sequence 如何影响 RingBuffer 容量？

假设：
```
RingBuffer capacity = 1024
Producer cursor = 2000
Slowest required consumer = 1000
```
消费者进度决定生产者可以安全推进到哪里。

在常见的有消费者门控约束的模型中，如果最慢的必要消费者仍停留在 1000，那么生产者不能无限制地覆盖其尚未处理的事件。

RingBuffer 是固定容量的循环结构，生产者必须确保重用槽位时，不会覆盖必要消费者仍然需要的数据。

因此：

消费者越慢，生产者可继续推进的空间就越有限。

这也是 Disruptor 在有界缓冲区中实现生产消费协调的关键机制。

⸻

六、SequenceBarrier：消费者如何等待事件？

前面我们已经分析过 ProcessingSequenceBarrier。

现在将它放回整体架构中理解。

消费者通常通过：
```
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
```
等待所需序列可用。

这一步不是简单地等待生产者 Cursor 大于目标序列。

它还需要考虑：

* 目标序列是否已经发布。
* 多生产者模式下是否存在发布缺口。
* 消费者是否依赖其他消费者。
* 使用了什么等待策略。

基本调用关系：
```
BatchEventProcessor
|
v
SequenceBarrier.waitFor()
|
v
ProcessingSequenceBarrier
|
+----> WaitStrategy
|
+----> Sequencer 可用性检查
|
v
返回可处理的序列范围
```
其中：

* WaitStrategy 决定如何等待。
* Sequencer 负责判断事件是否真正可用。
* SequenceBarrier 将等待逻辑与消费者依赖关系结合起来。

这也是 Disruptor 各组件之间的一个重要职责边界。

⸻

七、WaitStrategy：性能与资源之间的权衡

Disruptor 提供多种等待策略。

常见策略包括：

* BlockingWaitStrategy
* BusySpinWaitStrategy
* YieldingWaitStrategy
* SleepingWaitStrategy
* TimeoutBlockingWaitStrategy
* LiteBlockingWaitStrategy，具体是否提供取决于版本。

可以从三个维度比较：
```
策略	主要特点	典型考虑
Blocking	等待时阻塞线程	降低空闲时 CPU 消耗
BusySpin	持续自旋检查	降低阻塞和唤醒开销，但消耗 CPU
Yielding	自旋后让出执行机会	在 CPU 消耗与延迟之间折中
Sleeping	自旋或让出后进入短暂休眠	适用于不要求极低等待延迟的场景
TimeoutBlocking	带超时语义的阻塞等待	需要限制等待时间的场景
```
如何选择？

如果是普通业务系统：

优先考虑 BlockingWaitStrategy

如果是对延迟敏感、CPU 资源充足的处理链路：

可以评估 BusySpinWaitStrategy

如果希望在两者之间折中：

可以评估 YieldingWaitStrategy

但是，不能仅仅根据策略名称决定生产配置。

应当结合：

* 事件到达频率。
* 消费者空闲比例。
* CPU 核心数量。
* 线程绑定与调度情况。
* 延迟分布，而不仅仅是平均延迟。
* 整体系统的 CPU 预算。

对于交易所系统，低延迟很重要，但如果为每个消费者都配置持续自旋，可能会挤占其他关键业务线程的 CPU 时间。

⸻

八、BatchEventProcessor：真正执行消费的组件

前面我们已经详细分析过 BatchEventProcessor，这里从整体角度再串联一次。

它的主要任务是：

1. 读取自己的消费进度。
2. 计算下一个待处理序列。
3. 通过 SequenceBarrier 等待事件可用。
4. 获取事件。
5. 调用 EventHandler。
6. 更新消费进度。
7. 继续消费下一批事件。

典型的消费逻辑可以简化为：
```
long nextSequence = sequence.get() + 1;
while (running) {
long availableSequence =
sequenceBarrier.waitFor(nextSequence);
while (nextSequence <= availableSequence) {
T event = dataProvider.get(nextSequence);
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);
nextSequence++;
}
sequence.set(availableSequence);
}
```
这是一份概念性示意代码，并非固定版本的完整实现。

这里最关键的是：
```
sequenceBarrier.waitFor(nextSequence);
```
以及：
```
eventHandler.onEvent(...);
```
前者负责等待，后者负责业务处理。

批次处理完成后，再更新对外可见的消费进度。

为什么批量处理能够提升性能？

假设消费者一次可以处理 100 个事件。

如果每个事件都单独进行额外的批次级操作，就可能产生不必要的开销。

而批量消费可以：

* 减少部分重复等待。
* 降低消费进度更新的频率。
* 利用 endOfBatch 触发批量业务操作。

不过，实际收益还取决于事件处理逻辑。如果单个事件的业务处理本身耗时很长，RingBuffer 的优化可能无法成为主要性能瓶颈。

⸻

九、EventHandlerGroup：构建消费者依赖 DAG

这是你刚刚研究的内容。

EventHandlerGroup 主要用于构建消费者之间的依赖关系。

假设成交事件需要经过：

               TradeEvent
                    |
          +---------+---------+
          |         |         |
          v         v         v
         Order     Asset     Market
          |         |
          +----+----+
               |
               v
         Notification

订单、资产、行情处理可以独立执行。

通知处理只依赖订单和资产更新完成。

可以通过消费者组组合依赖序列，并通过 then() 声明下游处理器。

例如：
```
EventHandlerGroup<TradeEvent> orderAndAsset =
disruptor.handleEventsWith(
orderHandler,
assetHandler
);
disruptor.handleEventsWith(marketHandler);
orderAndAsset.then(notificationHandler);
```
这里的关键是：

消费者依赖不是通过业务线程之间互相调用实现的，而是通过 Sequence 和 SequenceBarrier 表达并协调。

因此，Disruptor 能够在保持必要顺序约束的同时，让没有依赖关系的消费者并行处理事件。

需要注意，这种并行性依赖于实际线程配置、消费拓扑及资源情况；API 上声明独立消费者，不等于整个系统的业务逻辑天然没有竞争条件。

⸻

十、EventHandlerGroup 与 WorkHandler 模式

Disruptor 不只有 EventHandler 这一种消费模式。

常见的两类模式是：

1. 多个独立的 EventHandler。
2. 使用 WorkerPool 或相关工作池 API，让多个工作处理器竞争处理事件。

二者的语义不同。

10.1 EventHandler：每个消费者都能看到每个事件

例如：

              RingBuffer
                   |
           +-------+-------+
           |       |       |
           v       v       v
           A       B       C

如果 A、B、C 是三个独立的 EventHandler，那么在正常运行的消费模型下，每个处理器都会处理相应序列中的事件。

适用于：

* 同一成交事件分别更新订单、资产和行情。
* 一个事件被多个独立业务模块订阅。
* 多个消费者之间存在明确的依赖关系。

10.2 WorkHandler：多个工作者分担事件

工作池的概念模型：

               RingBuffer
                    |
                    v
               Work Pool
              /    |    \
             v     v     v
            W1    W2    W3

在正常运行情况下，同一个工作池中的一个事件会分配给其中一个工作者处理，而不是要求所有工作者都处理同一事件。

适用于：

* 独立任务的并行处理。
* 任务之间不要求固定执行顺序。
* 多个工作线程共同承担处理负载。

但是，工作池并不自动解决业务幂等、外部副作用和失败恢复问题。

此外，不同 Disruptor 版本对工作池 API 的支持及推荐方式可能有所变化，具体应以使用版本为准。

⸻

十一、Disruptor 的完整生产消费流程

现在把核心组件串起来。

假设有一个多生产者、多个消费者的交易事件处理系统。
```
Producer A ──┐
|
Producer B ──┼──────┐
|      |
Producer C ──┘      |
v
MultiProducerSequencer
|
v
RingBuffer
|
v
Event Publish
|
v
SequenceBarrier
|
v
BatchEventProcessor
|
v
EventHandler
```
具体执行过程如下。

第一步：申请序列

生产者调用：

long sequence = ringBuffer.next();

Sequencer 分配可用的逻辑序列。

第二步：获取槽位并写入事件
```
OrderEvent event = ringBuffer.get(sequence);
event.setOrderId(orderId);
event.setPrice(price);
```
生产者将数据写入相应事件对象。

第三步：发布事件

ringBuffer.publish(sequence);

Sequencer 记录相应序列已经发布。

在多生产者模式下，发布状态管理尤为重要。

第四步：消费者等待事件

long availableSequence =
sequenceBarrier.waitFor(nextSequence);

消费者等待所需事件可用。

第五步：消费事件
```
eventHandler.onEvent(
event,
nextSequence,
nextSequence == availableSequence
);
```
事件交给业务处理器。

第六步：推进消费进度

sequence.set(availableSequence);

处理器发布自己的消费进度。

第七步：影响下游和生产者

消费进度可能影响：

* 下游消费者能否继续处理。
* 生产者能否复用 RingBuffer 槽位。
* 整个消费拓扑的推进速度。

完整流程：
```
申请序列
|
v
写入事件
|
v
发布事件
|
v
判断事件是否可用
|
v
获取事件并处理
|
v
推进消费进度
|
+-------------------+
|                   |
v                   v
下游消费进度         槽位复用判断

这就是 Disruptor 的核心运行模型。

⸻
```
十二、Disruptor 为什么性能高？

理解了整体架构之后，再分析其性能优势。

需要先明确：Disruptor 并不是在所有场景下都比 BlockingQueue 更快。它的优势取决于工作负载、等待策略、消费者拓扑、CPU 架构和业务代码。

常见的性能设计包括以下几项。

12.1 固定容量的 RingBuffer

固定容量的数组避免了为每个事件动态创建链表节点的需求。

这能够减少一部分对象分配和内存管理开销。

12.2 通过序列号协调

Disruptor 使用序列号表达生产者和消费者的逻辑进度。

序列号不仅用于定位事件，还用于协调槽位复用和消费者依赖。

这避免了将所有协调逻辑都建立在单一的队列锁操作上。

但需要注意，Disruptor 仍然使用原子操作、内存屏障及某些等待策略中的锁等机制，并非完全没有同步开销。

12.3 批量消费

一次等待可以获得多个连续可用事件，减少部分重复等待与进度更新开销。

对于可以批量处理的业务，例如数据库写入或批量统计，这种模式还可以进一步降低外部系统的调用开销。

12.4 降低伪共享风险

通过对频繁更新的 Sequence 等数据结构采用填充设计，减少部分缓存行争用。

12.5 可选择的等待策略

不同的等待策略可以根据 CPU 资源与延迟目标进行调整。

对于高频事件处理，合理的等待策略可能显著影响尾延迟。

但如果消费者业务逻辑本身存在长时间阻塞，等待策略就无法消除这部分延迟。

⸻

十三、Disruptor 的局限性

在架构设计中，不仅要了解优势，也需要知道它的适用边界。
```
局限	说明
仅解决进程内传递	不提供 Kafka 那样的跨进程消息传输能力
不自带持久化日志	进程异常退出后，不能依赖 RingBuffer 恢复全部未处理事件
固定容量	生产者可能受到慢消费者限制
依赖关系设计复杂	消费拓扑不合理时，可能出现不必要的等待
不自动提供业务事务	不保证多个消费者的外部副作用原子提交
消费失败需要额外设计	重试、补偿、幂等需要应用层负责
自旋可能消耗大量 CPU	低延迟策略可能增加资源占用
业务代码仍然可能产生 GC	事件预分配不能消除所有对象分配
```
因此，Disruptor 不能简单地替代 Kafka、RocketMQ 等分布式消息系统。

两者解决的问题不同。

⸻

十四、Disruptor 在交易所系统中的应用

结合交易所的订单、成交和资产处理场景，可以考虑下面的架构。

             Order / Trade Input
                     |
                     v
              Business Validation
                     |
                     v
                Disruptor
                     |
          +----------+----------+
          |          |          |
          v          v          v
       Order       Position    Market
       Update       Update     Update
          |
          v
       Notification

不过，具体拓扑需要根据业务依赖关系确定。

例如：

* 订单状态更新与资产更新是否可以并行？
* 成交通知是否必须等待资产变更完成？
* 行情处理是否需要与订单状态保持严格的序列一致性？
* 资产更新失败后，事件如何重试或补偿？
* 哪些状态需要持久化，哪些只是内存中的临时状态？

这些问题不能仅凭 Disruptor 的 API 决定，而需要根据业务一致性要求设计。

14.1 适合的场景

Disruptor 可以用于：

* JVM 内部的订单事件分发。
* 交易事件的低延迟处理。
* 多模块共享事件流。
* 实时统计与监控。
* 高性能计算管道。
* 需要显式控制消费者依赖关系的处理流程。

14.2 不适合直接承担的职责

不应直接将 Disruptor 当作：

* 跨进程消息中间件。
* 持久化事件日志。
* 可靠的分布式事务协调器。
* 自动保证 Exactly-once 的处理框架。

如果业务需要可靠事件传递，通常需要结合持久化消息系统、事务设计、幂等机制、重试与补偿流程。

⸻

十五、Disruptor 与 BlockingQueue 的区别

这是 Java 后端面试中经常出现的问题。
```
对比维度	Disruptor	BlockingQueue
核心结构	固定容量的 RingBuffer	依具体实现而定
事件对象复用	通常支持预分配和复用	取决于使用方式
消费者模型	支持复杂的消费者依赖关系	通常需要自行编排
批量消费	原生支持批次处理语义	依实现及消费代码而定
等待策略	可选择多种策略	取决于具体队列实现
生产消费协调	基于序列及发布状态等机制	取决于具体实现
使用复杂度	较高	通常较低
持久化	不提供	通常不提供
跨进程传输	不支持	普通队列也不支持
```
这里需要强调：BlockingQueue 是接口，底层可能是数组、链表或其他结构，不应将所有实现都视为相同。

例如，ArrayBlockingQueue、LinkedBlockingQueue 和 SynchronousQueue 的内部机制就存在明显区别。

选型时不能只看理论吞吐量，还应结合维护成本、延迟目标、故障处理与实际压测结果。

⸻

十六、推荐的源码阅读顺序

结合你已经分析过的类，建议按以下顺序继续深入。
```
顺序	类或接口	重点关注
1	RingBuffer	事件存储、序列到数组下标的映射
2	Sequencer	生产消费协调的核心抽象
3	SingleProducerSequencer	单生产者的序列申请和容量控制
4	MultiProducerSequencer	并发序列申请与发布状态管理
5	Sequence	进度管理、原子操作和缓存行填充
6	ProcessingSequenceBarrier	等待事件、依赖序列和可用性检查
7	各种 WaitStrategy	阻塞、自旋、让出和休眠的区别
8	BatchEventProcessor	批量消费、异常处理和进度推进
9	EventHandlerGroup	消费者依赖拓扑
10	ConsumerRepository	消费者注册和组件管理
11	WorkerPool / WorkProcessor	多工作者竞争消费的实现
12	Disruptor	整个 API 如何将组件组装起来
```
阅读时建议始终围绕三个问题：

1. 序列由谁分配？
2. 事件由谁判定为可用？
3. 消费进度由谁更新，以及这个进度会影响谁？

只要这三个问题能够从源码中追踪清楚，Disruptor 的整体机制就基本掌握了。

⸻

十七、最后总结

如果需要用一句话概括 Disruptor：

Disruptor 是一个以预分配环形缓冲区为基础、以序列号为协调机制、以可配置等待策略为辅助、支持批量消费和消费者依赖拓扑的高性能进程内事件处理框架。

它的核心设计可以归纳为：
```
RingBuffer
|
v
Sequencer
|
v
Sequence / 发布状态
|
v
SequenceBarrier + WaitStrategy
|
v
BatchEventProcessor
|
v
EventHandler
```
其中：

* RingBuffer 负责事件存储。
* Sequencer 负责序列分配、发布协调与容量管理。
* Sequence 记录各参与者的进度。
* SequenceBarrier 协调消费者的等待和依赖关系。
* WaitStrategy 决定等待事件的具体方式。
* BatchEventProcessor 驱动事件消费。
* EventHandlerGroup 帮助构建消费者依赖拓扑。

对于架构师而言，最值得掌握的不是某个 API，而是生产者、消费者和消费者依赖关系如何通过 Sequence 协同工作。

理解了这一点，再分析 Disruptor 的源码、性能瓶颈和实际应用场景，就会容易得多。