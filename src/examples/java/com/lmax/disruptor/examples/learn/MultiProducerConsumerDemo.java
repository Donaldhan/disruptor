package com.lmax.disruptor.examples.learn;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.dsl.EventHandlerGroup;

import java.util.concurrent.ThreadFactory;

public class MultiProducerConsumerDemo {

    /**
     * Event
     */
    public static class OrderEvent {

        private long orderId;
        private String data;

        public long getOrderId() {
            return orderId;
        }

        public void setOrderId(long orderId) {
            this.orderId = orderId;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }


    /**
     * EventFactory
     *
     * RingBuffer 初始化时提前创建 Event 对象。
     */
    public static class OrderEventFactory
            implements com.lmax.disruptor.EventFactory<OrderEvent> {

        @Override
        public OrderEvent newInstance() {
            return new OrderEvent();
        }
    }


    /**
     * EventHandler A
     */
    public static class ConsumerA
            implements EventHandler<OrderEvent> {

        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {

            System.out.printf(
                    "[Consumer-A] sequence=%d orderId=%d data=%s%n",
                    sequence,
                    event.getOrderId(),
                    event.getData()
            );
        }
    }


    /**
     * EventHandler B
     */
    public static class ConsumerB
            implements EventHandler<OrderEvent> {

        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {

            System.out.printf(
                    "[Consumer-B] sequence=%d orderId=%d data=%s%n",
                    sequence,
                    event.getOrderId(),
                    event.getData()
            );
        }
    }


    /**
     * Consumer C
     *
     * 注意：
     * C 不直接依赖 A/B 的业务代码。
     *
     * 依赖关系通过 Disruptor DSL 建立。
     */
    public static class ConsumerC
            implements EventHandler<OrderEvent> {

        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {

            System.out.printf(
                    "[Consumer-C] sequence=%d orderId=%d data=%s%n",
                    sequence,
                    event.getOrderId(),
                    event.getData()
            );
        }
    }


    /**
     * Producer
     */
    public static class Producer {

        private final RingBuffer<OrderEvent> ringBuffer;

        public Producer(RingBuffer<OrderEvent> ringBuffer) {
            this.ringBuffer = ringBuffer;
        }

        public void publish(long orderId, String data) {

            long sequence = ringBuffer.next();

            try {

                OrderEvent event = ringBuffer.get(sequence);

                event.setOrderId(orderId);
                event.setData(data);

            } finally {

                ringBuffer.publish(sequence);
            }
        }
    }


    public static void main(String[] args)
            throws Exception {

        // RingBuffer 大小必须是 2 的幂
        int bufferSize = 1024;

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("disruptor-" + thread.getId());
            return thread;
        };

        /**
         * 创建 Disruptor
         *
         * ProducerType.MULTI
         *
         * 表示：
         *
         * 多生产者
         */
        Disruptor<OrderEvent> disruptor =

                new Disruptor<>(

                        new OrderEventFactory(),

                        bufferSize,

                        threadFactory,

                        ProducerType.MULTI,

                        new BlockingWaitStrategy()

                );
        /**
         * 建立消费者依赖关系
         *
         * A 和 B 并行
         *
         * A ──┐
         *      ├──> C
         * B ──┘
         *
         * C 必须等待 A、B 完成。
         */
        EventHandlerGroup<OrderEvent> group =
                disruptor.handleEventsWith(
                        new ConsumerA(),
                        new ConsumerB()
                );

        group.then(
                new ConsumerC()
        );

        /**
         * 启动 Disruptor
         */
        RingBuffer<OrderEvent> ringBuffer =
                disruptor.start();


        /**
         * 创建多个 Producer
         */
        Producer producer =
                new Producer(ringBuffer);


        /**
         * 多生产者
         *
         * 这里创建 4 个线程，
         * 模拟 4 个 Producer。
         */
        Thread p1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                producer.publish(
                        1000 + i,
                        "Producer-1"
                );
            }
        });

        Thread p2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                producer.publish(
                        2000 + i,
                        "Producer-2"
                );
            }
        });

        Thread p3 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                producer.publish(
                        3000 + i,
                        "Producer-3"
                );
            }
        });

        Thread p4 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                producer.publish(
                        4000 + i,
                        "Producer-4"
                );
            }
        });


        /**
         * 启动多个 Producer
         */
        p1.start();
        p2.start();
        p3.start();
        p4.start();


        /**
         * 等待 Producer 完成
         */
        p1.join();
        p2.join();
        p3.join();
        p4.join();


        /**
         * 等待消费者处理
         */
        Thread.sleep(3000);


        /**
         * 关闭 Disruptor
         */
        disruptor.shutdown();
    }
}