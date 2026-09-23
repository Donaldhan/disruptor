package com.lmax.disruptor.examples.learn;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class DisruptorMulticastDemo {

    /**
     * ============================
     * Event
     * ============================
     */
    public static class OrderEvent {

        private long orderId;
        private String symbol;
        private double price;
        private double quantity;

        public long getOrderId() {
            return orderId;
        }

        public void setOrderId(long orderId) {
            this.orderId = orderId;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double price) {
            this.price = price;
        }

        public double getQuantity() {
            return quantity;
        }

        public void setQuantity(double quantity) {
            this.quantity = quantity;
        }
    }


    /**
     * ============================
     * Consumer A
     *
     * 例如：订单落库
     * ============================
     */
    public static class DatabaseConsumer
            implements EventHandler<OrderEvent> {

        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {

            System.out.printf(
                    "[DB] thread=%s sequence=%d orderId=%d symbol=%s%n",
                    Thread.currentThread().getName(),
                    sequence,
                    event.getOrderId(),
                    event.getSymbol()
            );
        }
    }


    /**
     * ============================
     * Consumer B
     *
     * 例如：ES
     * ============================
     */
    public static class ElasticsearchConsumer
            implements EventHandler<OrderEvent> {

        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {

            System.out.printf(
                    "[ES] thread=%s sequence=%d orderId=%d symbol=%s%n",
                    Thread.currentThread().getName(),
                    sequence,
                    event.getOrderId(),
                    event.getSymbol()
            );
        }
    }


    /**
     * ============================
     * Consumer C
     *
     * 例如：发送 MQ
     * ============================
     */
    public static class MQConsumer
            implements EventHandler<OrderEvent> {

        @Override
        public void onEvent(
                OrderEvent event,
                long sequence,
                boolean endOfBatch) {

            System.out.printf(
                    "[MQ] thread=%s sequence=%d orderId=%d symbol=%s%n",
                    Thread.currentThread().getName(),
                    sequence,
                    event.getOrderId(),
                    event.getSymbol()
            );
        }
    }


    /**
     * ============================
     * Producer
     * ============================
     */
    public static class OrderProducer {

        private final RingBuffer<OrderEvent> ringBuffer;

        public OrderProducer(
                RingBuffer<OrderEvent> ringBuffer) {

            this.ringBuffer = ringBuffer;
        }

        public void publish(
                long orderId,
                String symbol,
                double price,
                double quantity) {

            long sequence = ringBuffer.next();

            try {

                OrderEvent event =
                        ringBuffer.get(sequence);

                event.setOrderId(orderId);
                event.setSymbol(symbol);
                event.setPrice(price);
                event.setQuantity(quantity);

            } finally {

                /*
                 * 非常重要：
                 *
                 * publish 后消费者才可以看到这个 Event
                 */
                ringBuffer.publish(sequence);
            }
        }
    }


    /**
     * ============================
     * main
     * ============================
     */
    public static void main(String[] args)
            throws Exception {

        int bufferSize = 1024;


        /*
         * Java 17 ThreadFactory
         */
        AtomicInteger threadId =
                new AtomicInteger();

        ThreadFactory threadFactory =
                runnable -> new Thread(
                        runnable,
                        "disruptor-" +
                                threadId.getAndIncrement()
                );


        /*
         * MULTI：
         *
         * 多生产者
         */
        Disruptor<OrderEvent> disruptor =
                new Disruptor<>(
                        OrderEvent::new,
                        bufferSize,
                        threadFactory,
                        ProducerType.MULTI,
                        new YieldingWaitStrategy()
                );


        /*
         * ============================
         * 核心：
         *
         * 多个 Consumer
         *
         * 直接注册在同一个 handleEventsWith()
         *
         * => Multicast
         * ============================
         */
        disruptor.handleEventsWith(

                new DatabaseConsumer(),

                new ElasticsearchConsumer(),

                new MQConsumer()
        );


        /*
         * 启动
         */
        RingBuffer<OrderEvent> ringBuffer =
                disruptor.start();


        /*
         * Producer
         */
        OrderProducer producer =
                new OrderProducer(ringBuffer);


        /*
         * 发布 5 个订单
         */
        for (int i = 0; i < 5; i++) {

            producer.publish(
                    1000 + i,
                    "BTCUSDT",
                    60000 + i,
                    0.1
            );
        }


        /*
         * 等待 Consumer
         */
        Thread.sleep(1000);


        /*
         * 关闭
         */
        disruptor.shutdown();
    }
}