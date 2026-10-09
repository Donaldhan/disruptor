# com.lmax.disruptor.SingleProducerSequencer.next(int)解析


```java
@Override
    public long next(final int n)
    {
        assert sameThread() : "Accessed by two threads - use ProducerType.MULTI!";

        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }

        long nextValue = this.nextValue;

        long nextSequence = nextValue + n;
        long wrapPoint = nextSequence - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            this.cachedValue = minSequence;
        }

        this.nextValue = nextSequence;

        return nextSequence;
    }
```



这段代码是 Disruptor 中单生产者（ProducerType.SINGLE）申请 RingBuffer 序列号的核心实现。

它主要解决一个问题：

生产者要向 RingBuffer 写入数据时，如何分配新的 Sequence，同时确保不会覆盖消费者尚未处理的数据？

理解这段代码，需要掌握 4 个核心概念：

1. nextValue：生产者上一次申请到的序列号。
2. nextSequence：本次准备申请的序列号。
3. wrapPoint：本次申请后，RingBuffer 即将覆盖到的位置。
4. gatingSequences：消费者的消费进度，用于判断 RingBuffer 中的旧数据能否被覆盖。

我们从整体逻辑开始分析。

⸻

一、先理解 RingBuffer 的工作原理

假设 RingBuffer 的大小为 8。

RingBuffer 容量 = 8
物理槽位：
┌────┬────┬────┬────┬────┬────┬────┬────┐
│  0 │  1 │  2 │  3 │  4 │  5 │  6 │  7 │
└────┴────┴────┴────┴────┴────┴────┴────┘

Disruptor 使用递增的 Sequence 表示逻辑位置。

例如：

Sequence	RingBuffer 物理槽位
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

当逻辑序列号超过 RingBuffer 的容量时，就会循环使用之前的物理槽位。

计算方式为：

index = sequence % bufferSize;

例如：

8 % 8 = 0;
9 % 8 = 1;
10 % 8 = 2;

这就引出了一个问题：

当生产者准备写入 Sequence 8 时，Sequence 0 对应的槽位已经被使用过了，但消费者是否已经处理完 Sequence 0？

如果消费者还没有处理完，生产者就不能覆盖它。

因此，next() 方法的核心职责就是：分配序列号，并在必要时等待消费者释放足够的 RingBuffer 空间。

⸻

二、逐行解析源码

1. 检查是否只有一个生产者线程

assert sameThread() : "Accessed by two threads - use ProducerType.MULTI!";

这里的 sameThread() 用于检查当前调用线程是否符合单生产者模式的线程约束。

ProducerType.SINGLE 假设只有一个生产者线程调用 next()。

为什么需要这个限制？

因为下面两个变量都是生产者本地维护的普通字段：

this.nextValue
this.cachedValue

假设两个线程同时执行：

// Thread A
long nextValue = this.nextValue;
// Thread B
long nextValue = this.nextValue;

如果两个线程读取到相同的 nextValue，就可能申请到相同的序列号，导致两个生产者写入同一个 RingBuffer 槽位。

单生产者实现不需要为这些字段增加 CAS 竞争。

如果存在多个生产者线程，应使用：

ProducerType.MULTI

对应的多生产者实现会使用原子操作等机制协调序列号分配。

面试重点：

单生产者模式通过限制生产者线程数量，减少了序列号分配过程中的线程竞争；多生产者模式则需要处理并发申请序列号的问题。

注意，assert 是否执行还取决于 Java 的断言开关，但这不改变单生产者实现必须遵守的线程约束。

⸻

2. 检查一次申请的数量

if (n < 1 || n > bufferSize)
{
throw new IllegalArgumentException("n must be > 0 and < bufferSize");
}

参数 n 表示本次需要申请多少个连续的 Sequence。

例如：

long sequence = sequencer.next();

通常相当于：

next(1);

如果需要批量申请：

long sequence = sequencer.next(3);

就表示一次申请 3 个连续的序列号。

假设当前生产者的 nextValue = 10，那么：

n = 3;
nextSequence = 10 + 3 = 13;

这次申请对应的序列号是：

11、12、13

注意：源码中 nextValue 表示当前已经分配到的序列号，而不是下一个尚未分配的序列号。

因此，next(n) 返回的是本次申请到的最后一个序列号。

后续调用方可以根据返回值，反推出本次申请区间的起点。

另外，源码中的异常提示字符串与实际判断条件并不完全一致：判断允许 n == bufferSize，所以真正的合法范围是：

1 <= n <= bufferSize

⸻

3. 读取生产者当前的序列号

long nextValue = this.nextValue;

读取当前生产者已经分配到的最后一个 Sequence。

假设：

this.nextValue = 10;

那么：

long nextValue = 10;

为什么使用局部变量？

因为后面需要基于同一个起点进行计算。

这样可以让本次申请中的序列号计算更加清晰，也避免在计算过程中反复读取成员字段。

⸻

4. 计算本次申请的最后一个序列号

long nextSequence = nextValue + n;

假设：

nextValue = 10;
n = 3;

那么：

nextSequence = 13;

本次申请的序列号为：

11、12、13

可以把 nextSequence 理解为：

本次批量申请结束后，生产者将占用到的最后一个逻辑序列号。

这里的 nextSequence 还只是一个计算结果，接下来需要检查 RingBuffer 是否有足够的可用空间。

⸻

5. 计算 RingBuffer 的覆盖边界

long wrapPoint = nextSequence - bufferSize;

这是整个方法最关键的一行之一。

它表示：

本次申请结束后，生产者将开始覆盖到的最早逻辑序列号。

假设：

bufferSize = 8;
nextSequence = 13;

那么：

wrapPoint = 13 - 8 = 5;

为什么是 5？

因为 RingBuffer 只能保存最近 8 个逻辑序列号。

当生产者申请到 Sequence 13 时，RingBuffer 对应的逻辑范围为：

6、7、8、9、10、11、12、13

其中：

Sequence 5 和 Sequence 13

映射到同一个物理槽位：

5 % 8 = 5;
13 % 8 = 5;

所以写入 Sequence 13，就意味着覆盖 Sequence 5 原本使用的槽位。

因此，在写入 Sequence 13 之前，生产者必须确保消费者已经处理完 Sequence 5。

换句话说：

wrapPoint = 5
消费者最小消费进度必须 >= 5

否则，生产者就必须等待。

需要注意，wrapPoint 并不意味着生产者一定要覆盖数据，而是用于判断本次申请是否可能越过消费者尚未释放的空间边界。

⸻

6. 读取缓存的消费者进度

long cachedGatingSequence = this.cachedValue;

这里读取的是生产者缓存的消费者最小进度。

为什么需要缓存？

假设消费者的进度是：

Consumer A: 100
Consumer B: 95
Consumer C: 98

真正限制生产者的是最慢的消费者：

min(100, 95, 98) = 95

生产者需要保证不会覆盖这个最慢消费者尚未处理完的数据。

但是，每次申请序列号都遍历所有消费者并计算最小值，会产生额外开销。

因此，Disruptor 使用：

this.cachedValue

缓存之前计算得到的最小消费者进度。

只要缓存的进度足以证明 RingBuffer 有足够空间，就可以直接继续分配序列号，而不必每次都重新遍历消费者。

这里的缓存是一种性能优化，不是消费者的实时消费进度。

消费者可能已经处理了更多数据，但生产者还没有必要立即更新缓存。

⸻

三、核心判断：什么时候需要等待？

if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)

这个判断决定了是否需要检查消费者进度。

我们分两部分理解。

条件一：wrapPoint > cachedGatingSequence

例如：

bufferSize = 8;
nextValue = 10;
n = 3;
nextSequence = 13;
wrapPoint = 5;
cachedGatingSequence = 4;

那么：

5 > 4

条件成立。

这说明：

本次申请需要保证 Sequence 5 对应的旧数据已经被消费。
但是缓存的消费者进度只有 4。
因此，缓存不能证明当前空间足够。

此时必须检查消费者的实际进度。

如果消费者的最小 Sequence 已经达到 5，就可以继续。

如果仍然小于 5，就必须等待。

⸻

条件二：cachedGatingSequence > nextValue

这个条件比较特殊。

它用于检测缓存的消费者进度是否超出了当前生产者的进度。

正常情况下：

cachedGatingSequence <= nextValue

例如：

生产者进度：10
缓存消费者进度：8

这是正常的。

但如果：

生产者进度：10
缓存消费者进度：12

那么：

cachedGatingSequence > nextValue

条件成立。

这通常意味着生产者本地维护的进度与缓存的消费者进度之间出现了不符合预期的关系，需要重新确认。

例如，在某些边界情况下，消费者可能已经越过生产者之前缓存的进度，或者生产者状态发生了重置。

此时源码选择重新读取消费者进度，而不是直接相信缓存。

注意：这个条件不是用来判断 RingBuffer 是否已满的；它是一个额外的状态校验条件。

⸻

四、为什么要调用 cursor.setVolatile(nextValue)？

cursor.setVolatile(nextValue);  // StoreLoad fence

这行代码涉及 Java 并发中的内存可见性和内存屏障。

在理解它之前，需要区分两个不同的 Sequence：

* nextValue：生产者自己维护的分配进度。
* cursor：对外发布的生产者进度，其他线程可以读取。

在 Disruptor 中，生产者分配到序列号，不代表已经完成数据写入，也不代表数据已经对消费者发布。

通常的发布流程是：

申请序列号
↓
写入 RingBuffer 对应槽位的数据
↓
publish(sequence)
↓
消费者读取已发布的数据

那么，为什么这里还要提前执行：

cursor.setVolatile(nextValue);

主要涉及两个方面。

1. 提供可观察的生产者进度

在等待 RingBuffer 空间时，生产者会把当前已经分配到的进度写入 cursor。

这里写入的是 nextValue，而不是尚未完成申请的 nextSequence。

例如：

nextValue = 10;
nextSequence = 13;

此时：

cursor.setVolatile(10);

它不会把尚未完成本次申请的 Sequence 11、12、13 发布为可消费数据。

2. 配合内存屏障约束内存访问顺序

setVolatile 会涉及 volatile 写入的内存语义。

源码注释中的 StoreLoad fence 强调的是这里对内存访问顺序的约束。

在多核 CPU 上，编译器和处理器可能对部分内存访问进行重排序。内存屏障用于约束这种重排序，确保并发代码满足预期的可见性和顺序要求。

不过，不能简单地认为：

cursor.setVolatile(nextValue);

就等于发布了 nextValue 对应的数据。

数据是否可以被消费者读取，仍然取决于正确的发布协议。

生产者申请序列号和发布序列号是两个不同的阶段。

⸻

五、真正的等待逻辑

long minSequence;
while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
{
LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
}

这段代码负责保证 RingBuffer 不会覆盖消费者尚未处理的数据。

1. 获取所有消费者中的最小 Sequence

minSequence = Util.getMinimumSequence(gatingSequences, nextValue);

假设有三个消费者：

Consumer A: 10
Consumer B: 7
Consumer C: 9

那么：

minSequence = 7;

为什么取最小值？

因为所有被纳入 gating 的消费者，都必须拥有足够的消费进度，生产者才能安全地复用相应槽位。

例如：

RingBuffer 容量：8
生产者准备申请到 Sequence 13
wrapPoint = 5
Consumer A = 10
Consumer B = 7
Consumer C = 4

那么：

minSequence = 4;

因为：

5 > 4

所以生产者不能继续。

即使 A、B 已经消费得很快，C 仍然没有处理完 Sequence 5。

生产者必须等到所有相关消费者都达到安全边界。

⸻

2. 为什么用 while，而不是 if？

while (wrapPoint > minSequence)

因为消费者可能还没有消费到要求的位置。

假设：

wrapPoint = 5

消费者进度不断变化：

第一次检查：4
第二次检查：4
第三次检查：5

前两次：

5 > 4

条件成立，生产者继续等待。

第三次：

5 > 5

条件不成立，生产者可以继续。

因此，while 会反复检查消费者进度，直到满足安全条件。

⸻

3. 为什么使用 LockSupport.parkNanos(1L)？

LockSupport.parkNanos(1L);

它让当前线程短暂地进入等待状态，避免在消费者进度尚未推进时持续进行高频轮询。

这里的 1L 表示 1 纳秒的请求等待时间。

但要注意：

这不意味着线程一定只等待 1 纳秒。

实际等待时间取决于操作系统调度、JVM 实现以及当前线程的运行状态。

当等待结束后，线程会再次检查消费者进度。

这里使用的不是消费者侧的 WaitStrategy，而是生产者在 RingBuffer 空间不足时采用的等待方式。

不同的等待策略会在延迟、CPU 使用率和线程调度开销之间进行权衡。

⸻

六、为什么更新 cachedValue？

this.cachedValue = minSequence;

只有在缓存不足以证明空间安全时，代码才进入前面的判断分支。

经过等待后，程序已经确定：

minSequence >= wrapPoint

此时可以更新缓存：

this.cachedValue = minSequence;

例如：

之前缓存的消费者进度：4
要求的安全边界：5
等待后实际最小消费者进度：7

于是：

this.cachedValue = 7;

后续申请时，如果：

wrapPoint <= 7

就可以直接利用缓存判断空间是否充足，避免每次都重新扫描全部 gating sequences。

这是一种典型的缓存最小进度、按需刷新的优化方式。

⸻

七、更新生产者进度并返回结果

this.nextValue = nextSequence;
return nextSequence;

假设：

nextValue = 10;
n = 3;
nextSequence = 13;

当程序确认 RingBuffer 有足够的空间后，执行：

this.nextValue = 13;

然后返回：

return 13;

这表示生产者本次申请的连续序列号为：

11、12、13

返回的是最后一个序列号。

后续生产者会使用这些序列号访问 RingBuffer 并写入数据，最后再通过发布操作让消费者能够看到相应的数据。

例如：
```java
long sequence = sequencer.next();
try
{
Event event = ringBuffer.get(sequence);
event.setValue("hello");
}
finally
{
sequencer.publish(sequence);
}
```

这段示例展示了单个事件的基本流程。

对于批量申请，生产者则需要正确处理整个申请区间，并发布对应的序列号。

⸻

八、用一个完整案例串起所有逻辑

假设：

bufferSize = 8;
nextValue = 10;
cachedValue = 4;
n = 3;

消费者当前进度：

Consumer A = 8
Consumer B = 6
Consumer C = 4

现在执行：

next(3);

第一步：计算本次申请范围

nextSequence = 10 + 3 = 13;

本次申请：

11、12、13

第二步：计算覆盖边界

wrapPoint = 13 - 8 = 5;

意味着必须确保 Sequence 5 已经不再被消费者使用。

第三步：检查缓存
```java
wrapPoint = 5;
cachedGatingSequence = 4;
```
因为：

5 > 4

所以必须重新检查消费者进度。

第四步：获取最小消费者进度

Consumer A = 8
Consumer B = 6
Consumer C = 4
minSequence = 4

由于：

5 > 4

生产者不能继续。

第五步：等待消费者推进

假设 C 消费到 Sequence 6：

Consumer A = 8
Consumer B = 7
Consumer C = 6
minSequence = 6

现在：

5 > 6

不成立。

说明所有相关消费者都已经达到安全边界。

第六步：更新缓存

this.cachedValue = 6;

第七步：更新生产者进度

this.nextValue = 13;

第八步：返回结果

return 13;

生产者获得了：

Sequence 11、12、13

可以继续写入对应的 RingBuffer 槽位。

整个流程可以总结为：

                 next(n)
                    |
                    v
          计算 nextSequence
                    |
                    v
          计算 wrapPoint
                    |
                    v
         检查 cachedValue
                    |
          +---------+---------+
          |                   |
      缓存足够             缓存不足
          |                   |
          |                   v
          |          获取最小消费者进度
          |                   |
          |             是否满足边界？
          |              /         \
          |            否           是
          |            |            |
          |            v            v
          |           等待       更新缓存
          |            |            |
          |            +------------+
          |                   |
          +-------------------+
                    |
                    v
        更新 this.nextValue
                    |
                    v
          返回 nextSequence

⸻

九、面试中最值得掌握的 5 个问题

1. 为什么 wrapPoint = nextSequence - bufferSize？

因为 RingBuffer 是固定容量的循环数组。

当生产者申请到 nextSequence 时，需要判断该位置对应的旧数据是否已经消费完毕。

wrapPoint 就是用于判断 RingBuffer 是否可能覆盖尚未消费数据的边界。

2. 为什么取所有消费者中的最小 Sequence？

因为生产者必须保证所有相关消费者都已经越过待复用槽位对应的安全边界。

最慢的消费者决定了生产者能否继续复用 RingBuffer 空间。

3. cachedValue 的作用是什么？

缓存上次检查得到的最小消费者进度。

当缓存足以证明空间安全时，生产者不必每次都遍历消费者序列，从而减少开销。

4. 为什么单生产者不需要 CAS？

因为 ProducerType.SINGLE 的前提是只有一个生产者线程负责分配序列号。

不存在多个生产者同时争抢 nextValue 的竞争，因此可以直接读取和更新普通字段。

如果是多生产者模式，则需要通过原子操作等机制协调序列号分配。

5. next() 和 publish() 有什么区别？

这是理解 Disruptor 的关键。

操作	作用	是否代表数据可以被消费者读取
next()	申请序列号	否
get(sequence)	获取对应的 RingBuffer 槽位	否
写入事件数据	填充事件内容	否
publish(sequence)	发布已写入的数据	是，但消费者还需要通过自己的消费机制获取并处理它

例如：

```java
long sequence = ringBuffer.next();
try
{
Event event = ringBuffer.get(sequence);
event.setValue("hello");
}
finally
{
ringBuffer.publish(sequence);
}
```
其中：

* next() 负责分配位置。
* get() 负责定位槽位。
* 业务代码负责写入数据。
* publish() 负责发布数据。

申请序列号不等于数据已经写入，更不等于数据已经发布。

⸻

最后总结

这段代码的核心可以浓缩成一句话：

单生产者通过递增 Sequence 分配 RingBuffer 槽位，并使用最慢消费者的进度判断是否可以安全地复用旧槽位；如果空间不足，就等待消费者推进。

理解它时，建议重点记住这几个变量：

变量	含义
nextValue	生产者上次分配到的序列号
nextSequence	本次申请的最后一个序列号
wrapPoint	本次申请对应的覆盖安全边界
cachedValue	缓存的最小消费者进度
gatingSequences	用于限制生产者复用空间的消费者序列
cursor	对外可观察的生产者进度，参与发布协议

其中最关键的判断是：

while (wrapPoint > Util.getMinimumSequence(gatingSequences, nextValue))
{
LockSupport.parkNanos(1L);
}

它保证了：只要还有相关消费者没有达到安全边界，生产者就不能继续复用对应的 RingBuffer 空间。