package com.lmax.disruptor.examples.learn;

import com.lmax.disruptor.AlertException;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventProcessorFactory;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DisruptorCompetingConsumersDemo {

    // ============================================================
    // Event
    // ============================================================

    public static class OrderEvent {

        private long orderId;
        private String symbol;
        private double price;
        private long quantity;

        public void set(
                long orderId,
                String symbol,
                double price,
                long quantity) {

            this.orderId = orderId;
            this.symbol = symbol;
            this.price = price;
            this.quantity = quantity;
        }

        public long getOrderId() {
            return orderId;
        }

        public String getSymbol() {
            return symbol;
        }

        public double getPrice() {
            return price;
        }

        public long getQuantity() {
            return quantity;
        }
    }


    // ============================================================
    // EventFactory
    // ============================================================

    public static class OrderEventFactory
            implements com.lmax.disruptor.EventFactory<OrderEvent> {

        @Override
        public OrderEvent newInstance() {
            return new OrderEvent();
        }
    }


    // ============================================================
    // Competing Event Processor
    //
    // 每个 Worker 都拥有：
    //
    //     自己的 Sequence
    //
    // 但所有 Worker 共享：
    //
    //     workSequence
    //
    // Worker 通过 CAS 竞争 workSequence，
    // 从而保证一个 sequence 只被一个 Worker 获得。
    // ============================================================

    public static class CompetingEventProcessor<T>
            implements EventProcessor {

        private final RingBuffer<T> ringBuffer;

        private final SequenceBarrier sequenceBarrier;

        private final Sequence sequence;

        private final AtomicLong workSequence;

        private final EventConsumer<T> consumer;

        private volatile boolean running = false;


        public CompetingEventProcessor(
                RingBuffer<T> ringBuffer,
                Sequence[] barrierSequences,
                AtomicLong workSequence,
                EventConsumer<T> consumer) {

            this.ringBuffer = ringBuffer;

            this.sequenceBarrier =
                    ringBuffer.newBarrier(barrierSequences);

            this.sequence =
                    new Sequence(-1);

            this.workSequence =
                    workSequence;

            this.consumer =
                    consumer;
        }


        // ========================================================
        // EventProcessor
        // ========================================================

        @Override
        public Sequence getSequence() {
            return sequence;
        }


        @Override
        public boolean isRunning() {
            return running;
        }


        @Override
        public void halt() {

            running = false;

            sequenceBarrier.alert();
        }


        // ========================================================
        // Main Worker Loop
        // ========================================================

        @Override
        public void run() {

            if (running) {
                throw new IllegalStateException(
                        "EventProcessor already running");
            }

            running = true;

            try {

                while (running) {

                    long nextSequence =
                            workSequence.getAndIncrement();

                    try {

                        /*
                         * 等待 Producer 发布这个 sequence。
                         *
                         * 如果 Producer 尚未 publish，
                         * Worker 会在 SequenceBarrier 上等待。
                         */
                        long availableSequence =
                                sequenceBarrier.waitFor(
                                        nextSequence);

                        /*
                         * 当前 WorkProcessor 只拿到一个 sequence，
                         * 所以这里处理的是自己的 Event。
                         */
                        T event =
                                ringBuffer.get(nextSequence);

                        consumer.onEvent(
                                event,
                                nextSequence);

                        /*
                         * 非常重要：
                         *
                         * Producer 通过 gating sequence
                         * 判断 RingBuffer 哪些位置可以覆盖。
                         *
                         * 所以 Worker 处理完成后，
                         * 必须推进自己的 Sequence。
                         */
                        sequence.set(nextSequence);

                    } catch (AlertException e) {

                        if (!running) {
                            break;
                        }

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();

                        break;

                    } catch (Throwable e) {

                        /*
                         * Demo 中简单打印。
                         *
                         * 生产环境应该根据业务决定：
                         *
                         * 1. Retry
                         * 2. DLQ
                         * 3. Error Event
                         * 4. Halt
                         */
                        System.err.println(
                                "Worker exception: "
                                        + e.getMessage());

                        /*
                         * 不能让这个 sequence 永远卡住。
                         *
                         * 这里选择认为该 Event 已经处理完成。
                         */
                        sequence.set(nextSequence);
                    }
                }

            } finally {

                running = false;
            }
        }
    }


    // ============================================================
    // Event Consumer
    // ============================================================

    @FunctionalInterface
    public interface EventConsumer<T> {

        void onEvent(
                T event,
                long sequence);
    }


    // ============================================================
    // Factory
    //
    // 重点：
    //
    // 多个 Factory 共享同一个 workSequence
    //
    // 所以最终创建出来的多个 EventProcessor
    // 会竞争同一个工作序列。
    // ============================================================

    public static class CompetingEventProcessorFactory<T>
            implements EventProcessorFactory<T> {

        private final AtomicLong workSequence;

        private final EventConsumer<T> consumer;


        public CompetingEventProcessorFactory(
                AtomicLong workSequence,
                EventConsumer<T> consumer) {

            this.workSequence =
                    workSequence;

            this.consumer =
                    consumer;
        }


        @Override
        public EventProcessor createEventProcessor(
                RingBuffer<T> ringBuffer,
                Sequence[] barrierSequences) {

            return new CompetingEventProcessor<>(
                    ringBuffer,
                    barrierSequences,
                    workSequence,
                    consumer
            );
        }
    }


    // ============================================================
    // Main
    // ============================================================

    public static void main(String[] args)
            throws Exception {

        final int ringBufferSize = 1024;

        final int workerCount = 4;


        // ========================================================
        // ThreadFactory
        //
        // Java 17
        // ========================================================

        AtomicInteger threadId =
                new AtomicInteger();

        ThreadFactory threadFactory =
                runnable ->
                        new Thread(
                                runnable,
                                "worker-"
                                        + threadId.getAndIncrement()
                        );


        // ========================================================
        // Disruptor
        // ========================================================

        Disruptor<OrderEvent> disruptor =
                new Disruptor<>(
                        new OrderEventFactory(),
                        ringBufferSize,
                        threadFactory,
                        ProducerType.MULTI,
                        new com.lmax.disruptor.BlockingWaitStrategy()
                );


        // ========================================================
        // Shared Work Sequence
        //
        // 初始值必须是 -1
        //
        // 因为第一个 Event 的 sequence = 0
        // ========================================================

        AtomicLong workSequence =
                new AtomicLong(-1);


        // ========================================================
        // 创建 Worker Factory
        //
        // 所有 Factory 共享同一个 workSequence
        // ========================================================

        @SuppressWarnings("unchecked")
        EventProcessorFactory<OrderEvent>[] factories =
                new EventProcessorFactory[workerCount];


        for (int i = 0; i < workerCount; i++) {

            final int workerId = i;

            factories[i] =
                    new CompetingEventProcessorFactory<>(
                            workSequence,
                            (event, sequence) -> {

                                System.out.printf(
                                        "[Worker-%d] "
                                                + "sequence=%d "
                                                + "orderId=%d "
                                                + "thread=%s%n",

                                        workerId,

                                        sequence,

                                        event.getOrderId(),

                                        Thread.currentThread()
                                                .getName()
                                );

                                // 模拟业务处理
                                try {

                                    Thread.sleep(50);

                                } catch (InterruptedException e) {

                                    Thread.currentThread()
                                            .interrupt();
                                }
                            }
                    );
        }


        // ========================================================
        // 注册 Custom EventProcessor
        // ========================================================

        disruptor.handleEventsWith(
                factories
        );


        // ========================================================
        // Start
        // ========================================================

        RingBuffer<OrderEvent> ringBuffer =
                disruptor.start();


        // ========================================================
        // Gating Sequences
        //
        // 让 Producer 知道：
        //
        // 哪些 Event 已经被所有相关 Worker 安全处理。
        // ========================================================

        // 这里不能简单把一个 shared workSequence
        // 当作消费完成位置。
        //
        // 每个 EventProcessor 有自己的 Sequence。
        //
        // Disruptor DSL 在注册 EventProcessor 后，
        // 会将 processor sequence 纳入消费链。
        //
        // 因此这里不额外手工 add。
        //
        // ========================================================


        // ========================================================
        // Produce Events
        // ========================================================

        for (long i = 1; i <= 20; i++) {

            long sequence =
                    ringBuffer.next();

            try {

                OrderEvent event =
                        ringBuffer.get(sequence);

                event.set(
                        i,
                        "BTCUSDT",
                        100000 + i,
                        i * 10
                );

            } finally {

                ringBuffer.publish(sequence);
            }
        }


        // ========================================================
        // Wait
        // ========================================================

        Thread.sleep(3000);


        // ========================================================
        // Shutdown
        // ========================================================

        disruptor.shutdown();
    }
}