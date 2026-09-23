这个示例非常值得看，因为它实际上展示了 Disruptor 4.x 如何用 EventHandler + Pipeline/DAG 实现一种“并行处理 + 汇聚”的模式。

但有一个很容易误解的地方：

它不是标准的 Competing Consumers / Work Sharing。

它是：

3 个并行 Handler 共同监听同一个 RingBuffer，但每个 Handler 只处理自己负责的 sequence，最后由一个 JoiningHandler 汇聚。

⸻

1. 先看整体结构

核心代码：
```java
disruptor.handleEventsWith(
new ParallelHandler(0, 3),
new ParallelHandler(1, 3),
new ParallelHandler(2, 3)
).then(new JoiningHandler());
```
形成的逻辑是：

                    RingBuffer
                        │
          ┌─────────────┼─────────────┐
          ↓             ↓             ↓
     Parallel-0    Parallel-1    Parallel-2
          │             │             │
          └─────────────┼─────────────┘
                        ↓
                  JoiningHandler

也就是：

                 Event
                   │
        ┌──────────┼──────────┐
        ↓          ↓          ↓
       P0         P1         P2
        │          │          │
        └──────────┼──────────┘
                   ↓
                Joiner

⸻

2. 为什么三个 Handler 不会重复处理？

关键在：

if (sequence % totalHandlers == ordinal)

这里：

totalHandlers = 3

所以：

ParallelHandler(0, 3)

处理：
```
0 % 3 = 0
3 % 3 = 0
6 % 3 = 0
9 % 3 = 0
...
```
即：

0, 3, 6, 9, 12...

ParallelHandler(1, 3)

处理：

1, 4, 7, 10, 13...

ParallelHandler(2, 3)

处理：

2, 5, 8, 11, 14...

所以：
```
sequence:
0 → Handler 0
1 → Handler 1
2 → Handler 2
3 → Handler 0
4 → Handler 1
5 → Handler 2
6 → Handler 0
...
```
从业务处理逻辑上看，一个 Event 最终只会真正被其中一个 Handler 处理。

⸻

3. 但三个 Handler 实际上都会收到 Event

这是非常重要的区别。

代码：

disruptor.handleEventsWith(
handler0,
handler1,
handler2
)

本质上仍然是 Multicast。

所以：
```
Event 0
│
├── Handler 0 → 真正处理
├── Handler 1 → 收到，但什么都不做
└── Handler 2 → 收到，但什么都不做
```
例如 Event 0：

sequence = 0

那么：
```
Handler 0:
0 % 3 == 0
→ 处理
Handler 1:
0 % 3 != 1
→ 不处理
Handler 2:
0 % 3 != 2
→ 不处理
```
所以它和真正的：

Competing Consumers

仍然有区别。

⸻

4. 那为什么官方示例这么设计？

这是一个 Pipeline Parallelism 的示例。

它主要是在演示：

如何把一个 Pipeline Stage 拆成多个并行 Handler，然后再进入下一个 Stage。

也就是：
```
Stage 1
│
↓
┌───────────────────────┐
│ Parallel Handler      │
│                       │
│ H0   H1   H2          │
│ │    │    │           │
│ └────┼────┘           │
└──────┼────────────────┘
↓
Stage 2
JoiningHandler

⸻
```
5. then() 是真正的关键

这里：
```java
disruptor.handleEventsWith(
handler0,
handler1,
handler2
).then(new JoiningHandler());
```
意味着：

```
Handler 0 ──┐
│
Handler 1 ──┼──→ JoiningHandler
│
Handler 2 ──┘
```

JoiningHandler 不会直接跟 Producer 的 Cursor 同步。

它依赖：
```
Handler 0 的 Sequence
Handler 1 的 Sequence
Handler 2 的 Sequence
```

只有三个 Handler 都推进到某个 sequence，JoiningHandler 才能继续处理对应 Event。

所以这里其实是一个 DAG：
```
             ┌── H0 ──┐
Producer ────┼── H1 ──┼── Join
└── H2 ──┘
```
⸻

6. 为什么 JoiningHandler 可以保证顺序？

注意：

private long lastEvent = -1;

然后：
```
if (event.input != lastEvent + 1 || event.result == null)
{
System.out.println("Error: " + event);
}
```
它期望：
```
0
1
2
3
4
5
...
```
依次到达。

虽然前面的：
```
H0
H1
H2
```
是并行工作的，但是 JoiningHandler 自己仍然按照 RingBuffer sequence 顺序消费。

所以：
```
H0:
sequence 0
3
6
9
H1:
sequence 1
4
7
10
H2:
sequence 2
5
8
11
```
最后 Join：
```

0
1
2
3
4
5
6
7
8
9
...
```

⸻

7. result 为什么不会出现问题？

例如：

Event 0

只有：

ParallelHandler 0

执行：

event.result = Long.toString(event.input);

所以：

Event 0
input = 0
result = "0"

然后：

JoiningHandler

检查：

event.result != null

通过。

然后：

event.result = null;

这非常重要。

因为 RingBuffer 中的 Event 对象是复用的。

下一轮：

sequence = 1024

可能重新使用之前 sequence 0 对应的内存槽位。

所以处理完后需要清理：

event.result = null;

⸻

8. 这个例子真正展示了什么？

可以概括成：

                 Producer
                    │
                    ↓
               RingBuffer
                    │
       ┌────────────┼────────────┐
       ↓            ↓            ↓
      H0            H1           H2
       │            │            │
       │ sequence   │ sequence   │ sequence
       │ 0,3,6...   │ 1,4,7...   │ 2,5,8...
       │            │            │
       └────────────┼────────────┘
                    ↓
                 Joiner
                    │
                    ↓
                 Output

它体现了三个非常重要的 Disruptor 能力：

① Multicast

handleEventsWith(H0, H1, H2)

多个 Handler 都观察同一个事件流。

② Pipeline / DAG

.then(JoiningHandler)

JoiningHandler 依赖前面三个 Handler。

③ Sequence 分片

sequence % totalHandlers

将不同 sequence 分配给不同 Handler。

⸻

9. 它和真正的 Work Sharing 差在哪里？

这个正好和我们刚才讨论的问题对应起来。

官方这个例子
```

Event
│
├── H0 ──→ 判断 sequence % 3
│
├── H1 ──→ 判断 sequence % 3
│
└── H2 ──→ 判断 sequence % 3
```

实际上：

三个 Handler 都被调用，只是其中两个发现不是自己的 sequence 后直接返回。

⸻

真正的 Competing Consumers

应该是：
```

Event
│
├── Worker 0 ──┐
├── Worker 1 ──┼── 竞争
├── Worker 2 ──┤
└── Worker 3 ──┘
↓
一个 Worker
```

例如：
```

Event 0 → W2
Event 1 → W1
Event 2 → W3
Event 3 → W2
```

Event 0 根本不会调用 W0/W1/W3 的业务处理函数。

所以两者的区别：

	官方 Pipeliner 示例	Competing Consumers
Handler 数量	3	3
每个 Event 是否被所有 Handler 看到	是	否
真正处理 Event 的 Handler	1	1
分配方式	sequence % N	竞争/claim
是否是 Multicast	是	否
是否是原生 WorkerPool	否	4.x 没有
主要目的	Pipeline 并行化	Work Sharing

⸻

10. 为什么官方示例反而很有价值？

因为它实际上说明了 Disruptor 4.x 官方推荐的思路之一：

不一定需要 WorkerPool 才能做并行处理。

例如：
```

new ParallelHandler(0, 3)
new ParallelHandler(1, 3)
new ParallelHandler(2, 3)
```

通过 sequence 分片：

0  → H0
1  → H1
2  → H2
3  → H0
4  → H1
5  → H2
...

实现：

          Event Stream
               │
      ┌────────┼────────┐
      ↓        ↓        ↓
     H0       H1       H2
      │        │        │
      └────────┼────────┘
               ↓
             Join

这实际上就是一种：

Static Work Sharing / Deterministic Sharding

而不是：

Dynamic Competing Consumers

⸻

11. 对你来说最重要的理解

结合你刚才一直问的 Disruptor 4.x Consumer 模型，现在可以形成一个很清晰的分类：
```
Disruptor 4.x
│
├── Multicast
│      │
│      └── 一个 Event → 多个 Handler
│
├── Pipeline / DAG
│      │
│      └── A → B → C
│          A + B → C
│
└── Parallel Pipeline
│
├── H0 → sequence 0,3,6...
├── H1 → sequence 1,4,7...
├── H2 → sequence 2,5,8...
│
└── Join
```
而：
```
Disruptor 3.x
│
└── WorkerPool
│
└── Dynamic Work Sharing
```
这就是为什么你在 Disruptor 4.0.0 里找不到 WorkHandler。

⸻

一句话总结这个官方示例

Pipeliner 通过 handleEventsWith(H0,H1,H2).then(Join) 构建了一个“并行 Stage → 汇聚 Stage”的 DAG；三个 ParallelHandler 都收到所有事件，但通过 sequence % 3 各自只处理自己负责的 sequence，最后 JoiningHandler 等待三个并行 Handler 都完成后按顺序汇聚结果。它是 Disruptor 4.x 中“静态分片并行处理”的典型示例，而不是传统 WorkerPool 式的动态 Competing Consumers。