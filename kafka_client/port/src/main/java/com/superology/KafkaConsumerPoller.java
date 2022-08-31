package com.superology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import com.ericsson.otp.erlang.*;

final class KafkaConsumerPoller implements Runnable {
  private Properties consumerProps;
  private Collection<String> topics;
  private KafkaConsumerOutput output;
  private long pollInterval;
  private BlockingQueue<TopicPartition> acks = new LinkedBlockingQueue<>();

  public static KafkaConsumerPoller start(
      Properties consumerProps,
      Collection<String> topics,
      long pollInterval,
      KafkaConsumerOutput output) {
    var poller = new KafkaConsumerPoller(consumerProps, topics, pollInterval, output);

    var consumerThread = new Thread(poller);
    consumerThread.setDaemon(true);
    consumerThread.start();

    return poller;
  }

  private KafkaConsumerPoller(
      Properties consumerProps,
      Collection<String> topics,
      long pollInterval,
      KafkaConsumerOutput output) {
    this.consumerProps = consumerProps;
    this.topics = topics;
    this.output = output;
    this.pollInterval = pollInterval;
  }

  @Override
  public void run() {
    var isConsuming = false;
    var pausedPartitions = new HashSet<TopicPartition>();
    var bufferUsages = new HashMap<TopicPartition, BufferUsage>();

    try (var consumer = new KafkaConsumer<String, byte[]>(consumerProps)) {
      startConsuming(consumer);

      while (true) {
        var assignedPartitions = consumer.assignment();

        var pendingAcks = new ArrayList<TopicPartition>();
        acks.drainTo(pendingAcks);

        for (var topicPartition : pendingAcks) {
          var bufferUsage = bufferUsages.get(topicPartition);
          if (bufferUsage != null) {
            bufferUsage.recordProcessed();

            if (bufferUsage.shouldResume()) {
              pausedPartitions.remove(topicPartition);

              if (assignedPartitions.contains(topicPartition))
                consumer.resume(Arrays.asList(new TopicPartition[] { topicPartition }));
            }
          }
        }

        var assignedPausedPartitions = new HashSet<TopicPartition>();
        assignedPausedPartitions.addAll(assignedPartitions);
        assignedPausedPartitions.retainAll(pausedPartitions);
        consumer.pause(assignedPausedPartitions);

        var records = consumer.poll(java.time.Duration.ofMillis(pollInterval));

        if (!isConsuming && !consumer.assignment().isEmpty()) {
          var message = new OtpErlangAtom("consuming");
          output.write(message);
          isConsuming = true;
        }

        for (var record : records) {
          output.write(record);

          var topicPartition = new TopicPartition(record.topic(), record.partition());
          var bufferUsage = bufferUsages.get(topicPartition);
          if (bufferUsage == null) {
            bufferUsage = new BufferUsage();
            bufferUsages.put(topicPartition, bufferUsage);
          }

          bufferUsage.recordProcessing(record.value().length);
          if (bufferUsage.shouldPause())
            pausedPartitions.add(topicPartition);
        }
      }
    } catch (Exception e) {
      System.err.println(e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  public void ack(TopicPartition topicPartition) {
    acks.add(topicPartition);
  }

  private void startConsuming(KafkaConsumer<String, byte[]> consumer) {
    if (consumerProps.getProperty("group.id").equals("kafka_client_consumer_anonymous")) {
      var allPartitions = new ArrayList<TopicPartition>();
      for (var topic : topics) {
        for (var partitionInfo : consumer.partitionsFor(topic)) {
          allPartitions.add(new TopicPartition(partitionInfo.topic(), partitionInfo.partition()));
        }
      }
      consumer.assign(allPartitions);
    } else
      consumer.subscribe(topics);
  }
}

class BufferUsage {
  private LinkedList<Integer> messageSizes = new LinkedList<>();
  private int totalBytes = 0;

  public boolean isEmpty() {
    return numMessages() == 0;
  }

  public void recordProcessing(int messageSize) {
    messageSizes.add(messageSize);
    totalBytes += messageSize;
  }

  public void recordProcessed() {
    var messageSize = messageSizes.remove();
    totalBytes -= messageSize;
  }

  public boolean shouldPause() {
    return (numMessages() >= 2 && (totalBytes >= 1000000 || numMessages() >= 1000));
  }

  public boolean shouldResume() {
    return (numMessages() < 2 || (totalBytes <= 500000 && numMessages() <= 500));
  }

  private int numMessages() {
    return messageSizes.size();
  }
}
