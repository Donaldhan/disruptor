Disruptor 源码解析：RingBuffer

在 Disruptor 中，RingBuffer 是整个事件处理体系的核心数据结构。它负责存储事件对象，并通过固定大小的循环数组，让生产者和消费者在不使用传统阻塞队列的情况下，高效地交换数据。

理解 RingBuffer，重点不是只看它如何存取数据，而是要理解以下几个问题：

1. 为什么使用环形数组，而不是 LinkedList 或 BlockingQueue？
2. 为什么 RingBuffer 不直接通过 put() 和 take() 实现生产消费？
3. next()、get()、publish() 分别做什么？
4. 多生产者并发写入时，如何避免消费者读取未发布的事件？
5. 消费者处理完事件后，生产者如何知道某个槽位可以复用？
6. RingBuffer 与 Sequencer、Sequence、SequenceBarrier 分别是什么关系？

下面以 Disruptor 3.x 的核心实现思路为主进行分析。不同版本在构造器、API 和内部实现上可能存在差异。

⸻

一、RingBuffer 的整体结构

1.1 什么是 RingBuffer？

RingBuffer 可以理解为一个固定容量的环形数组。

假设容量为 8：
```
                    RingBuffer
        ┌────┬────┬────┬────┬────┬────┬────┬────┐
        │ E0 │ E1 │ E2 │ E3 │ E4 │ E5 │ E6 │ E7 │
        └────┴────┴────┴────┴────┴────┴────┴────┘
          0    1    2    3    4    5    6    7
```
生产者不断向 RingBuffer 写入事件，消费者不断读取事件。

当生产者写入第 8 个事件之后，如果还有空间，它会继续使用下一个逻辑序号对应的槽位。

例如：
```
逻辑序号 Sequence	数组下标 Index	对应槽位
0	0	E0
1	1	E1
2	2	E2
3	3	E3
4	4	E4
5	5	E5
6	6	E6
7	7	E7
8	0	E0
9	1	E1
10	2	E2
```
可以看到：

逻辑序号持续增长，物理数组槽位循环复用。

这也是 RingBuffer 名字的由来。

需要特别注意：逻辑序号为 8 和逻辑序号为 0 时，对应的是同一个物理槽位，但它们代表不同的事件生命周期。

⸻

1.2 为什么容量通常要求是 2 的幂？

Disruptor 通常要求 RingBuffer 的容量是 2 的幂，例如：
```
1024
2048
4096
8192
65536
```
这样就可以使用位运算计算数组下标。

源码中的核心思路是：

index = sequence & (bufferSize - 1);

假设：

bufferSize = 8;

那么：

bufferSize - 1 = 7;

7 的二进制表示为：

00000111

当：

sequence = 10;

其二进制表示为：

00001010

执行：

10 & 7

结果是：

00000010 = 2

因此：

index = 2;

这等价于：

index = (int) (sequence % bufferSize);

但对于容量为 2 的幂的情况，位运算通常更加直接。

为什么不是任意容量？

因为只有当容量是 2 的幂时：

sequence & (bufferSize - 1)

才等价于：

sequence % bufferSize

对于 RingBuffer 来说，这是一个高频操作，因此这种设计很有价值。

⸻

二、RingBuffer 的核心源码结构

在 Disruptor 3.x 中，RingBuffer<T> 实现了相关的数据提供和序号管理接口，并且与 Sequencer 紧密协作。

可以先抽象成下面的结构：
```
public final class RingBuffer<T> {
// 环形数组
private final Object[] entries;
// 数组容量
private final int bufferSize;
// 容量减一，用于位运算计算数组下标
private final int indexMask;
// 负责序号分配、发布和容量协调
private final Sequencer sequencer;
}
```

上面是帮助理解的简化结构，并非所有版本的逐字源码。

实际实现中，RingBuffer 还涉及事件工厂、内存屏障、对象类型转换，以及不同 Sequencer 的协作。

这里最重要的是三个成员：
```
成员	职责
entries	存放事件对象的数组
indexMask	将逻辑序号映射到数组下标
sequencer	管理序号、发布状态以及生产者与消费者之间的容量协调
```
其中，RingBuffer 负责事件存储与访问，Sequencer 负责序号协调与发布控制。

这两个职责虽然紧密配合，但不能混为一谈。

⸻

三、RingBuffer 如何初始化？

在使用 Disruptor 时，通常需要指定事件工厂、RingBuffer 容量和生产者模式。

例如：
```
EventFactory<OrderEvent> factory = OrderEvent::new;
RingBuffer<OrderEvent> ringBuffer =
RingBuffer.createSingleProducer(
factory,
1024
);
```
这里创建的是单生产者模式的 RingBuffer。

3.1 为什么需要 EventFactory？

假设事件类型如下：
```
public class OrderEvent {
private long orderId;
private long price;
private long quantity;
public void set(
long orderId,
long price,
long quantity) {
this.orderId = orderId;
this.price = price;
this.quantity = quantity;
}
}
```
传统队列中，生产者可能每次都创建一个新的事件对象：

queue.put(new OrderEvent());

而 Disruptor 通常在初始化时，就通过 EventFactory 预先创建 RingBuffer 所需的事件对象。

概念上相当于：
```
for (int i = 0; i < bufferSize; i++) {
entries[i] = factory.newInstance();
}
```

这样，生产者后续可以重复使用已有的事件对象。

例如：
```
OrderEvent event = ringBuffer.get(sequence);
event.set(orderId, price, quantity);
```
这有助于减少事件对象的重复分配和垃圾回收压力。

但要注意：

RingBuffer 预分配的是事件槽位及其对应的事件对象，不代表整个业务处理链路不会产生任何对象分配。

如果业务代码在每次处理时创建集合、字符串或临时对象，仍然会产生 GC 压力。

⸻

四、核心方法一：get()

先看最容易理解的方法。

RingBuffer 提供了类似下面的访问方式：

public E get(long sequence)

它根据逻辑序号获取对应的事件对象。

核心逻辑可以简化为：
```
public E get(long sequence) {
int index = (int) sequence & indexMask;
return entries[index];
}
```
假设：
```
bufferSize = 8;
indexMask = 7;
```
执行：

ringBuffer.get(10);

计算：

index = 10 & 7;

得到：

index = 2;

最终返回数组中下标为 2 的事件对象。

4.1 为什么 get() 不检查事件是否已经发布？

这是一个非常重要的设计。

get(sequence) 本质上只是根据序号访问数组槽位。

它不会因为事件尚未发布，就自动等待生产者完成写入。

例如：
```
long sequence = ringBuffer.next();
OrderEvent event = ringBuffer.get(sequence);
event.set(1001, 2000, 10);
```
在 next() 之后，生产者已经获得一个可写序号，但事件可能还没有写完。

此时，如果消费者直接调用：

ringBuffer.get(sequence);

它并不能证明事件已经准备好。

因此：

get() 负责访问槽位，不负责判断槽位中的事件是否已经可以被消费者读取。

真正的发布状态协调，由 Sequencer 和消费者使用的 SequenceBarrier 等组件负责。

⸻

五、核心方法二：next()

生产者不能直接随意写入数组，而是需要先申请一个可用序号。

例如：

long sequence = ringBuffer.next();

这个调用的含义是：

向 Sequencer 申请一个序号，并确保该序号对应的槽位可以安全地被生产者使用。

这是 RingBuffer 与 Sequencer 协作的关键入口。

5.1 单生产者模式

假设 RingBuffer 容量为 8。

生产者依次申请：
```
long sequence1 = ringBuffer.next();
long sequence2 = ringBuffer.next();
long sequence3 = ringBuffer.next();
```
得到：
```
sequence1 = 0
sequence2 = 1
sequence3 = 2
```
实际初始序号和具体行为需要结合版本实现来看，但在典型的 Disruptor 3.x 序号模型中，序号通常从 0 开始。

单生产者模式下，因为只有一个线程申请序号，所以不需要多个生产者之间竞争序号。

不过，仍然必须检查消费者进度，避免覆盖尚未处理完成的事件。

5.2 多生产者模式

假设两个生产者同时执行：

long sequence = ringBuffer.next();

它们可能同时尝试申请下一个序号。

此时必须确保：
```
生产者 A 获得 Sequence = 100
生产者 B 获得 Sequence = 101
```
而不能让两个生产者都获得 100。

这就涉及原子序号申请、CAS 或其他并发协调机制。

在多生产者模式下，MultiProducerSequencer 负责协调这些申请。

RingBuffer 不需要自己实现完整的多线程序号分配算法，而是把这个职责交给 Sequencer。

⸻

六、为什么 next() 之后还要 publish()？

这是理解 Disruptor 最关键的地方之一。

先看完整的生产者代码：
```
long sequence = ringBuffer.next();
try {
OrderEvent event = ringBuffer.get(sequence);
event.set(1001, 2000, 10);
} finally {
ringBuffer.publish(sequence);
}
```
整个过程可以拆成三个阶段：
```
1. 申请序号
   |
   v
2. 写入事件数据
   |
   v
3. 发布事件
```
对应方法：
```
阶段	方法	作用
申请	next()	申请一个可写序号
写入	get(sequence)	获取对应事件对象并写入数据
发布	publish(sequence)	通知消费端，该序号对应的事件已经可以被处理
```
6.1 为什么不能申请完序号就直接消费？

假设生产者执行：

long sequence = ringBuffer.next();

获得：

Sequence = 100

然后开始写入：
```
OrderEvent event = ringBuffer.get(100);
event.setOrderId(1001);
event.setPrice(2000);
event.setQuantity(10);
```
如果消费者仅仅因为看到了序号 100，就开始读取事件，可能出现以下情况：
```
生产者                         消费者
申请 Sequence = 100
写入 orderId = 1001
读取 orderId = 1001
读取 price = 0
写入 price = 2000
写入 quantity = 10
```
消费者就可能读到一个不完整的事件。

因此，必须明确区分：

* 序号已经被申请。
* 序号对应的事件已经写入并发布。

这两个状态不是一回事。

6.2 publish() 的意义

生产者调用：

ringBuffer.publish(sequence);

表示自己已经完成该序号对应事件的写入，并将其发布给消费端。

消费者随后可以通过 SequenceBarrier 等机制等待这个序号变为可用。

需要强调：

publish() 不意味着消费者已经处理完事件，也不意味着事件已经持久化。

它只负责发布事件的可消费状态。

⸻

七、核心方法三：next()、get()、publish() 的组合

为了更好地理解 RingBuffer，我们把生产者流程完整串起来。
```
public void publishOrder(
RingBuffer<OrderEvent> ringBuffer,
long orderId,
long price,
long quantity) {
long sequence = ringBuffer.next();
try {
OrderEvent event = ringBuffer.get(sequence);
event.set(orderId, price, quantity);
} finally {
ringBuffer.publish(sequence);
}
}
```
这里有几个值得注意的地方。

1. 为什么使用 finally？

生产者申请了序号之后，如果在写入事件时发生异常，却没有发布这个序号，就可能影响后续消费进度。

在多生产者模式下尤其需要关注这种情况。

例如：
```
Sequence 100：已经申请，但尚未发布
Sequence 101：已经申请并发布
Sequence 102：已经申请并发布
```
消费者不能简单地因为 101 和 102 已经发布，就假定 100 也已经可用。

因为 100 可能仍然没有完成写入。

所以，生产者需要认真处理申请后的异常路径。

不过，finally 里的 publish() 也不是万能的：

如果事件写入失败，直接发布可能导致消费者读取一个无效或不完整的事件。

生产级代码应当根据业务要求设计异常处理，例如：

* 为事件设置失败状态。
* 使用明确的无效事件标记。
* 将写入过程设计为不会抛出异常的简单赋值操作。
* 确保消费者能够识别并处理失败事件。

如果采用 try/finally，必须保证发布的事件即使写入失败，也具有明确、可处理的语义。

2. 为什么不直接在 next() 里创建事件？

因为 RingBuffer 的目标之一是重复使用预分配的事件对象。

next() 负责申请序号，而不是创建事件。

get() 负责访问槽位，而不是申请序号。

publish() 负责发布，而不是写入业务数据。

这种职责拆分有利于提高性能，也让事件生命周期更清晰。

⸻

八、tryNext()：非阻塞申请序号

除了：

long sequence = ringBuffer.next();

部分 Disruptor 3.x API 还提供：

long sequence = ringBuffer.tryNext();

二者的重要区别在于：当 RingBuffer 暂时没有可用容量时，如何处理。

通常情况下：

* next() 会等待容量可用。
* tryNext() 在无法立即申请到序号时，会抛出 InsufficientCapacityException。

示例：
```
long sequence;
try {
sequence = ringBuffer.tryNext();
} catch (InsufficientCapacityException e) {
// 当前没有可用容量
return;
}
try {
OrderEvent event = ringBuffer.get(sequence);
event.set(1001, 2000, 10);
} finally {
ringBuffer.publish(sequence);
}
```
具体实现和可用 API 应以使用的 Disruptor 版本为准。

8.1 tryNext() 适用于什么场景？

例如：

* 业务不允许生产者长时间等待。
* 事件可以被上游暂缓处理。
* 调用方可以进行限流或降级。
* 可以把事件暂存到其他地方，之后重新提交。

但要注意：

tryNext() 不是自动丢弃事件的安全机制。

如果申请失败后直接丢弃交易、资产变更或账务事件，可能导致业务数据不一致。

对于交易所资产和订单链路，必须明确设计容量不足时的行为：
```
申请失败
|
+-- 上游限流
|
+-- 有界缓冲
|
+-- 重试或重新调度
|
+-- 明确拒绝请求
|
+-- 触发告警
```
选择哪一种方案取决于业务语义，不能仅仅根据性能决定。

⸻

九、批量操作：next(n) 与 publish(lo, hi)

RingBuffer 还支持批量申请序号。

例如：

long hi = ringBuffer.next(10);

这表示一次申请 10 个连续序号。

假设此前已使用到序号 99，那么这次可能获得：

90?

这里需要注意序号模型：next(n) 返回的是这批序号中的最后一个序号，并不直接返回整个范围。

如果上一次已分配的序号为 99，则典型的下一批范围为：

100 ~ 109

因此：
```
long hi = ringBuffer.next(10);
long lo = hi - 10 + 1;
```
得到：
```
lo = 100
hi = 109
```
然后写入这一批事件：
```
long hi = ringBuffer.next(10);
long lo = hi - 10 + 1;
try {
for (long sequence = lo; sequence <= hi; sequence++) {
OrderEvent event = ringBuffer.get(sequence);
event.set(
sequence,
2000,
10
);
}
} finally {
ringBuffer.publish(lo, hi);
}
```
这里使用了区间发布：

publish(lo, hi);

具体批量方法签名应以对应版本为准。

9.1 为什么批量发布可能更快？

如果每个事件都单独发布：
```
申请 → 写入 → 发布
申请 → 写入 → 发布
申请 → 写入 → 发布
```
就需要反复执行序号申请和发布相关逻辑。

批量处理可以将多个事件的申请和发布集中起来：
```
批量申请
|
v
写入多个事件
|
v
批量发布
```
这可能减少同步协调的开销，并提高吞吐量。

但批量越大，不代表系统一定越快：

* 批量太小，协调开销可能较高。
* 批量太大，单批处理延迟可能增加。
* 单个批次中的慢事件可能拖慢后续事件的处理。
* 内存和缓存局部性也会影响实际效果。

因此，批量大小需要结合吞吐量和延迟目标来选择。

⸻

十、RingBuffer 如何避免覆盖未消费的数据？

这是 RingBuffer 最核心的并发问题。

假设 RingBuffer 容量为 8：

容量 = 8

生产者已经写入序号：

0 ~ 7

此时，它想申请序号：

8

由于：

8 & 7 = 0

序号 8 将会复用数组下标 0，也就是序号 0 曾经使用过的槽位。

问题来了：

如果消费者还没有处理完序号 0 对应的事件，生产者就不能直接覆盖这个槽位。

否则，消费者可能还在读取旧事件，生产者却已经把它改写成新事件。

10.1 如何判断槽位能否复用？

Disruptor 通过 Sequencer 和消费者的 Sequence 协调容量。

假设：

RingBuffer 容量 = 8

消费者进度为：

consumerSequence = 5

表示消费者已经处理到序号 5。

下一个最慢的消费者还没有处理的事件，是序号 6。

生产者想申请序号 8 时，需要考虑即将被覆盖的序号：

8 - 8 = 0

如果消费者已经处理到序号 5，那么序号 0 已经处理完成，可以安全复用。

如果消费者只处理到序号 2，那么序号 0 虽然可能已经处理，但后面的序号 3、4、5、6、7 尚未处理。

申请新序号时，不能仅凭“某个事件已处理”就判断所有相关槽位都可以复用，而必须根据目标序号、容量和消费者进度计算是否安全。

典型的判断关系可以理解为：

wrapPoint = nextSequence - bufferSize

当：

wrapPoint > 最慢消费者的 Sequence

说明生产者即将覆盖仍可能被消费者使用的槽位，因此必须等待或拒绝申请。

注意，这里是用于理解的简化表达。实际代码还涉及缓存的最小消费者序号、序号边界和并发同步。

10.2 多个消费者时怎么办？

假设存在三个消费者：
```
Consumer A：Sequence = 100
Consumer B：Sequence = 95
Consumer C：Sequence = 98
```
如果这三个消费者都需要独立处理每一个事件，那么生产者通常需要关注它们中最慢的消费者：

minimumSequence = 95

原因很简单：

* A 已经处理到 100。
* C 已经处理到 98。
* B 只处理到 95。

如果覆盖 B 仍然需要读取的数据，整个消费流程就会出错。

因此：

对于参与容量协调的消费者，最慢的消费者通常决定 RingBuffer 的可复用边界。

这也是 Disruptor 的消费进度与容量管理紧密关联的原因。

⸻

十一、Sequence 与 RingBuffer 的关系

前面已经提到，RingBuffer 不会单独决定所有事件的消费进度。

Disruptor 使用 Sequence 表示逻辑序号或处理进度。

例如：
```
RingBuffer
|
+-- Producer Sequencer
|
+-- Consumer A Sequence
|
+-- Consumer B Sequence
|
+-- Consumer C Sequence
```
这里的序号可能分别代表：
```
序号类型	作用
生产者申请进度	表示生产者已申请到的逻辑序号
发布进度	表示哪些序号对应的事件已经发布
消费者进度	表示某个消费者已经处理到哪里
依赖消费者进度	决定下游消费者何时能够继续处理
最慢消费者进度	参与判断哪些槽位可以安全复用
```
这些概念彼此相关，但不能简单地认为它们始终相等。

尤其在多生产者模式下：

已申请的最大序号，不一定代表之前所有序号都已经发布。

例如：
```
生产者 A：申请 100，正在写入
生产者 B：申请 101，已经写入并发布
```
此时，序号 101 已经发布，并不意味着消费者就可以越过 100，直接按顺序处理 101。

消费者必须通过发布状态协调机制判断当前可以安全处理的连续序号范围。

⸻

十二、RingBuffer 与 Sequencer 的分工

很多人在阅读源码时，容易把 RingBuffer 和 Sequencer 混在一起。

可以这样区分：

RingBuffer

负责：

* 保存事件对象。
* 根据序号定位数组槽位。
* 暴露事件访问接口。
* 将序号申请和发布请求委托给 Sequencer。

Sequencer

负责：

* 为生产者申请序号。
* 协调多个生产者的并发申请。
* 管理序号的发布状态。
* 检查消费者进度和可用容量。
* 防止生产者过早覆盖仍在使用的槽位。

两者的协作关系可以表示为：

                Producer
                   |
                   v
             RingBuffer.next()
                   |
                   v
                Sequencer
                   |
                   v
              分配 Sequence
                   |
                   v
            RingBuffer.get(seq)
                   |
                   v
                写入事件
                   |
                   v
          RingBuffer.publish(seq)
                   |
                   v
                Sequencer
                   |
                   v
             更新发布状态
                   |
                   v
               Consumer

这也解释了为什么阅读 RingBuffer 源码时，不能只盯着 RingBuffer.java。

很多关键的并发逻辑实际上位于：

* Sequencer
* SingleProducerSequencer
* MultiProducerSequencer
* Sequence
* SequenceBarrier

等类中。

⸻

十三、RingBuffer 与 SequenceBarrier 的关系

消费者通常不会简单地这样处理：
```
while (true) {
OrderEvent event = ringBuffer.get(sequence);
process(event);
}
```
因为消费者需要知道：

1. 对应的序号是否已经发布？
2. 当前是否有连续可消费的序号？
3. 上游依赖的消费者是否已经处理完成？
4. 当前应该等待，还是继续处理？

因此，消费者会借助 SequenceBarrier 等机制协调消费。

简化后的流程是：
```
消费者
|
v
SequenceBarrier.waitFor(sequence)
|
v
等待事件发布并检查可用序号
|
v
RingBuffer.get(sequence)
|
v
处理事件
|
v
更新消费者 Sequence
```
SequenceBarrier 不是用来存储事件的，也不是用来申请生产者序号的。

它主要负责协调消费者等待事件可用，以及检查消费依赖。

需要注意，waitFor() 返回的可用序号范围和最终能否读取每个序号，还要结合对应的 Sequencer 实现与发布状态判断。

⸻

十四、RingBuffer 为什么比普通队列更适合高性能场景？

RingBuffer 的性能优势主要来自整体设计，而不是某一个方法。

14.1 预分配对象

传统队列可能频繁创建节点对象。

例如，链表队列可能需要不断分配节点：

Node → Node → Node → Node

而 RingBuffer 使用固定数组保存预创建的事件对象：

[E0, E1, E2, E3, E4, E5, E6, E7]

这有助于减少节点分配和垃圾回收。

14.2 数组访问

RingBuffer 根据序号直接计算数组下标：

index = (int) sequence & indexMask;

避免了链表遍历。

14.3 减少不必要的锁竞争

Disruptor 的生产消费协调主要依靠序号、原子操作和内存可见性机制。

单生产者模式可以省去多个生产者之间的竞争。

但这并不意味着整个系统完全没有锁，也不意味着所有场景下都比 BlockingQueue 快。

14.4 批量消费

消费者可以一次获得一段可用序号，然后连续处理多个事件：
```
等待一次
|
v
读取 Event 100
读取 Event 101
读取 Event 102
读取 Event 103
|
v
更新消费进度
```
这有机会降低重复等待和协调的开销。

14.5 缓存局部性

固定大小的数组布局通常有利于内存访问局部性。

但实际缓存行为还取决于事件对象本身、线程调度、CPU 架构和业务访问模式。

⸻

十五、RingBuffer 的关键 API 汇总

以下是理解 Disruptor 3.x RingBuffer 时最值得关注的方法。
```
方法	作用	注意事项
get(sequence)	根据序号获取事件对象	不负责判断事件是否已发布
next()	申请一个可写序号	容量不足时可能等待
next(n)	批量申请序号	返回这批序号中的最后一个序号
tryNext()	尝试申请一个序号	容量不足时通常抛出异常
tryNext(n)	尝试批量申请序号	具体可用性和签名取决于版本
publish(sequence)	发布一个序号	表示事件可供消费，不表示消费完成
publish(lo, hi)	发布一个连续序号范围	适合批量生产
getCursor()	获取 Sequencer 的游标	多生产者模式下不能简单理解为所有之前的序号均已发布
```
在实际阅读源码时，建议直接检查你正在使用的版本，因为一些方法在不同版本中的可见性、参数类型和内部实现可能不同。

⸻

十六、实际业务示例：订单事件处理

假设交易系统有一个订单事件：
```
public class OrderEvent {
private long orderId;
private long userId;
private long price;
private long quantity;
public void set(
long orderId,
long userId,
long price,
long quantity) {
this.orderId = orderId;
this.userId = userId;
this.price = price;
this.quantity = quantity;
}
}

生产者提交订单事件：

public void publishOrder(
RingBuffer<OrderEvent> ringBuffer,
long orderId,
long userId,
long price,
long quantity) {
long sequence = ringBuffer.next();
try {
OrderEvent event = ringBuffer.get(sequence);
event.set(
orderId,
userId,
price,
quantity
);
} finally {
ringBuffer.publish(sequence);
}
}
```
消费者处理事件：
```
public class OrderEventHandler
implements EventHandler<OrderEvent> {
@Override
public void onEvent(
OrderEvent event,
long sequence,
boolean endOfBatch) {
processOrder(
event.getOrderId(),
event.getUserId(),
event.getPrice(),
event.getQuantity()
);
}
private void processOrder(
long orderId,
long userId,
long price,
long quantity) {
// 订单业务处理
}
}
```
这段代码体现了 RingBuffer 的核心使用模式：
```
生产者
|
v
申请序号
|
v
获取预分配的事件对象
|
v
写入订单数据
|
v
发布事件
|
v
消费者等待事件可用
|
v
处理订单
|
v
推进消费者 Sequence
|
v
生产者最终可以复用槽位
```
这里有一个重要的工程问题：

RingBuffer 中的事件对象会被重复使用，因此消费者不能默认事件对象在处理完成后仍然保持原值。

例如，如果消费者把 OrderEvent 对象引用保存到一个异步任务中，而消费者很快又处理完当前事件，那么生产者可能在后续复用同一个槽位时修改该对象。

异步任务最终可能读到已经被覆盖的新数据。

因此，如果需要跨越当前消费生命周期保存数据，通常应该：

* 在处理过程中提取必要字段。
* 复制为独立的数据对象。
* 或者设计明确的对象所有权和生命周期管理。

对于交易、资产和账务类系统，尤其不能把 RingBuffer 的事件对象直接当成永久有效的数据记录。

⸻

十七、几个常见误区

误区 1：RingBuffer 是一个线程安全的数组

不完全准确。

RingBuffer 本身提供事件存储和访问能力，但其并发安全依赖正确使用序号申请、事件写入、发布和消费进度协调机制。

随意调用 get() 并不能保证读写安全。

误区 2：调用 next() 就意味着事件已经写入

错误。

next() 只是申请序号。

写入完成之后，还需要调用 publish() 发布事件。

误区 3：调用 publish() 就意味着消费者已经处理完成

错误。

publish() 表示事件已经发布，不表示消费完成。

消费者完成处理后，需要推进自己的消费进度。

误区 4：RingBuffer 满了就会覆盖旧数据

错误。

正常的容量协调会防止生产者覆盖仍被消费者使用的槽位。

容量不足时，生产者需要等待，或者使用支持失败返回的申请方式。

误区 5：多生产者模式下，最大序号就是最大可消费序号

错误。

多生产者可能以不同速度完成写入和发布。

已经申请到更大的序号，并不意味着之前所有序号都已经发布。

误区 6：使用 RingBuffer 就不会产生 GC

错误。

预分配可以减少事件对象分配，但业务逻辑、临时对象、装箱、字符串处理等仍然可能产生垃圾对象。

误区 7：RingBuffer 是持久化消息队列

错误。

Disruptor 是进程内的高性能事件协调机制，不具备 Kafka 那样的持久化消息存储能力。

如果进程崩溃，RingBuffer 中尚未持久化的事件不能指望自动恢复。

⸻

十八、面试重点：如何用一分钟解释 RingBuffer？

如果面试官问：

“请解释一下 Disruptor 的 RingBuffer，以及它为什么性能高。”

可以这样回答：

RingBuffer 是 Disruptor 的核心事件存储结构，本质上是一个固定容量、预分配事件对象的环形数组。生产者通过逻辑序号访问数组槽位，利用位运算将序号映射到物理下标。

在生产流程中，生产者先通过 next() 向 Sequencer 申请序号，再通过 get(sequence) 获取事件对象并写入数据，最后通过 publish(sequence) 发布事件。

RingBuffer 本身主要负责事件存储和访问，序号申请、发布状态管理、容量协调等工作由 Sequencer 完成。消费者通过 SequenceBarrier 等组件等待事件可用，并通过自己的 Sequence 表示消费进度。

当 RingBuffer 即将循环覆盖旧槽位时，Sequencer 会结合消费者进度判断槽位能否安全复用，避免覆盖尚未处理完成的事件。

RingBuffer 的性能优势主要来自事件对象预分配、数组访问、减少不必要的对象分配、序号协调以及批量处理。但实际性能仍然取决于生产者数量、等待策略、事件处理逻辑和系统负载。

⸻

十九、源码阅读建议：RingBuffer 应该重点看哪些地方？

如果你正在逐个阅读 Disruptor 源码，建议按照以下顺序：
```
顺序	类	重点
1	RingBuffer	数组初始化、事件访问、序号申请和发布的委托关系
2	Sequencer	序号管理接口、容量协调与发布接口
3	SingleProducerSequencer	单生产者如何申请序号、检查容量
4	MultiProducerSequencer	多生产者如何申请序号、跟踪发布状态
5	Sequence	消费者进度、原子读写与内存可见性
6	SequenceBarrier	消费者如何等待事件发布
7	BatchEventProcessor	如何批量读取并处理事件
```
其中，我最建议你下一步深入研究的是：

SingleProducerSequencer 和 MultiProducerSequencer 的 next()、publish() 实现。

因为 RingBuffer 本身的代码并不是最复杂的部分，真正决定其并发行为的，是 Sequencer 如何分配序号、识别已发布事件，以及避免覆盖尚未消费的数据。

把这两部分理解透彻，你就能真正理解 Disruptor 的生产消费机制，而不仅仅是知道 RingBuffer 是一个环形数组。