/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package io.confluent.kafkarest.v2;

import io.confluent.kafkarest.ConsumerInstanceId;
import io.confluent.kafkarest.ConsumerRecordAndSize;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.kafkarest.entities.ConsumerAssignmentRequest;
import io.confluent.kafkarest.entities.ConsumerCommittedRequest;
import io.confluent.kafkarest.entities.ConsumerCommittedResponse;
import io.confluent.kafkarest.entities.ConsumerOffsetCommitRequest;
import io.confluent.kafkarest.entities.ConsumerSeekToOffsetRequest;
import io.confluent.kafkarest.entities.ConsumerSeekToRequest;
import io.confluent.kafkarest.entities.ConsumerSubscriptionRecord;
import io.confluent.kafkarest.entities.TopicPartitionOffset;
import io.confluent.kafkarest.entities.TopicPartitionOffsetMetadata;
import kafka.serializer.Decoder;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Tracks all the state for a consumer. This class is abstract in order to support multiple
 * serialization formats. Implementations must provide decoders and a method to convert Kafka
 * MessageAndMetadata<K,V> values to ConsumerRecords that can be returned to the client (including
 * translation if the decoded Kafka consumer type and ConsumerRecord types differ).
 */
public abstract class KafkaConsumerState<KafkaK, KafkaV, ClientK, ClientV>
    implements Comparable<KafkaConsumerState> {

  private KafkaRestConfig config;
  private ConsumerInstanceId instanceId;
  private Consumer consumer;
  private ConsumerRecords<ClientK, ClientV> consumerRecords = null;
  private Iterator<ConsumerRecord<ClientK, ClientV>> iter = null;

  private List<ConsumerRecord<ClientK, ClientV>> consumerRecordList = null;
  private int index = 0;


  private long expiration;
  // A read/write lock on the KafkaConsumerState allows concurrent readRecord calls, but allows
  // commitOffsets to safely lock the entire state in order to get correct information about all
  // the topic/stream's current offset state. All operations on individual TopicStates must be
  // synchronized at that level as well (so, e.g., readRecord may modify a single TopicState, but
  // only needs read access to the KafkaConsumerState).
  private ReadWriteLock lock;

  public KafkaConsumerState(KafkaRestConfig config, ConsumerInstanceId instanceId,
      Consumer consumer) {
    this.config = config;
    this.instanceId = instanceId;
    this.consumer = consumer;
    this.expiration = config.getTime().milliseconds() +
        config.getInt(KafkaRestConfig.CONSUMER_INSTANCE_TIMEOUT_MS_CONFIG);
    this.lock = new ReentrantReadWriteLock();
  }

  public ConsumerInstanceId getId() {
    return instanceId;
  }

  /**
   * Gets the key decoder for the Kafka consumer.
   */
  protected abstract Decoder<KafkaK> getKeyDecoder();

  /**
   * Gets the value decoder for the Kafka consumer.
   */
  protected abstract Decoder<KafkaV> getValueDecoder();

  /**
   * Converts a MessageAndMetadata using the Kafka decoder types into a ConsumerRecord using the
   * client's requested types. While doing so, computes the approximate size of the message in
   * bytes, which is used to track the approximate total payload size for consumer read responses to
   * determine when to trigger the response.
   */
  public abstract ConsumerRecordAndSize<ClientK, ClientV> createConsumerRecord(
      ConsumerRecord<KafkaK, KafkaV> msg);


  public void startRead() {
    lock.readLock().lock();
  }

  public void finishRead() {
    lock.readLock().unlock();
  }


  /**
   * Commit the given list of offsets
   */
  public List<TopicPartitionOffset> commitOffsets(String async,
      ConsumerOffsetCommitRequest offsetCommitRequest) {
    lock.writeLock().lock();
    try {
      // If no offsets are given, then commit all the records read so far
      if (offsetCommitRequest == null) {
        if (async == null) {
          consumer.commitSync();
        } else {
          consumer.commitAsync();
        }
      } else {
        Map<TopicPartition, OffsetAndMetadata> offsetMap = new HashMap<TopicPartition, OffsetAndMetadata>();

        //commit each given offset
        for (TopicPartitionOffsetMetadata t : offsetCommitRequest.offsets) {
          if (t.getMetadata() == null) {
            offsetMap.put(new TopicPartition(t.getTopic(), t.getPartition()),
                new OffsetAndMetadata(t.getOffset() + 1));
          } else {
            offsetMap.put(new TopicPartition(t.getTopic(), t.getPartition()),
                new OffsetAndMetadata(t.getOffset() + 1, t.getMetadata()));
          }

        }
        consumer.commitSync(offsetMap);
      }
      List<TopicPartitionOffset> result = new Vector<TopicPartitionOffset>();
      return result;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Seek to the first offset for each of the given partitions.
   */
  public void seekToBeginning(ConsumerSeekToRequest seekToRequest) {
    lock.writeLock().lock();
    try {
      if (seekToRequest != null) {
        Vector<TopicPartition> topicPartitions = new Vector<TopicPartition>();

        for (io.confluent.kafkarest.entities.TopicPartition t : seekToRequest.partitions) {
          topicPartitions.add(new TopicPartition(t.getTopic(), t.getPartition()));
        }
        consumer.seekToBeginning(topicPartitions);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Seek to the last offset for each of the given partitions.
   */
  public void seekToEnd(ConsumerSeekToRequest seekToRequest) {
    lock.writeLock().lock();
    try {
      if (seekToRequest != null) {
        Vector<TopicPartition> topicPartitions = new Vector<TopicPartition>();

        for (io.confluent.kafkarest.entities.TopicPartition t : seekToRequest.partitions) {
          topicPartitions.add(new TopicPartition(t.getTopic(), t.getPartition()));
        }
        consumer.seekToEnd(topicPartitions);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Overrides the fetch offsets that the consumer will use on the next poll(timeout).
   */
  public void seekToOffset(ConsumerSeekToOffsetRequest seekToOffsetRequest) {
    lock.writeLock().lock();
    try {
      if (seekToOffsetRequest != null) {
        Vector<TopicPartition> topicPartitions = new Vector<TopicPartition>();

        for (TopicPartitionOffsetMetadata t : seekToOffsetRequest.offsets) {
          TopicPartition topicPartition = new TopicPartition(t.getTopic(), t.getPartition());
          consumer.seek(topicPartition, t.getOffset());
        }

      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Manually assign a list of partitions to this consumer.
   */
  public void assign(ConsumerAssignmentRequest assignmentRequest) {
    lock.writeLock().lock();
    try {
      if (assignmentRequest != null) {
        Vector<TopicPartition> topicPartitions = new Vector<TopicPartition>();

        for (io.confluent.kafkarest.entities.TopicPartition t : assignmentRequest.partitions) {
          topicPartitions.add(new TopicPartition(t.getTopic(), t.getPartition()));
        }
        consumer.assign(topicPartitions);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Close the consumer,
   */

  public void close() {
    lock.writeLock().lock();
    try {
      if (consumer != null) {
        consumer.close();
      }
      // Marks this state entry as no longer valid because the consumer group is being destroyed.
      consumer = null;
    } finally {
      lock.writeLock().unlock();
    }
  }


  /**
   * Subscribe to the given list of topics to get dynamically assigned partitions.
   */
  public void subscribe(ConsumerSubscriptionRecord subscription) {
    if (subscription == null) {
      return;
    }

    lock.writeLock().lock();
    try {
      if (consumer != null) {
        if (subscription.topics != null) {
          consumer.subscribe(subscription.topics);
        } else if (subscription.getTopicPattern() != null) {
          Pattern topicPattern = Pattern.compile(subscription.getTopicPattern());
          NoOpOnRebalance noOpOnRebalance = new NoOpOnRebalance();
          consumer.subscribe(topicPattern, noOpOnRebalance);
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Unsubscribe from topics currently subscribed with subscribe(Collection).
   */
  public void unsubscribe() {
    lock.writeLock().lock();
    try {
      if (consumer != null) {
        consumer.unsubscribe();
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Get the current list of topics subscribed.
   */
  public java.util.Set<String> subscription() {
    java.util.Set<String> currSubscription = null;
    lock.writeLock().lock();
    try {
      if (consumer != null) {
        currSubscription = consumer.subscription();
      }
    } finally {
      lock.writeLock().unlock();
    }
    return currSubscription;
  }

  /**
   * Get the set of partitions currently assigned to this consumer.
   */
  public java.util.Set<TopicPartition> assignment() {
    java.util.Set<TopicPartition> currAssignment = null;
    lock.writeLock().lock();
    try {
      if (consumer != null) {
        currAssignment = consumer.assignment();
      }
    } finally {
      lock.writeLock().unlock();
    }
    return currAssignment;
  }


  /**
   * Get the last committed offset for the given partition (whether the commit happened by
   * this process or another).
   */
  public ConsumerCommittedResponse committed(ConsumerCommittedRequest request) {
    ConsumerCommittedResponse response = new ConsumerCommittedResponse();
    response.offsets = new Vector<TopicPartitionOffsetMetadata>();
    lock.writeLock().lock();
    try {
      if (consumer != null) {
        for (io.confluent.kafkarest.entities.TopicPartition t : request.partitions) {
          TopicPartition partition = new TopicPartition(t.getTopic(), t.getPartition());
          OffsetAndMetadata offsetMetadata = consumer.committed(partition);
          if (offsetMetadata != null) {
            response.offsets.add(
                new TopicPartitionOffsetMetadata(partition.topic(), partition.partition(),
                    offsetMetadata.offset(), offsetMetadata.metadata()));
          }
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
    return response;
  }


  public boolean expired(long nowMs) {
    return expiration <= nowMs;
  }

  public void updateExpiration() {
    this.expiration = config.getTime().milliseconds() +
        config.getInt(KafkaRestConfig.CONSUMER_INSTANCE_TIMEOUT_MS_CONFIG);
  }

  public long untilExpiration(long nowMs) {
    return this.expiration - nowMs;
  }

  public KafkaRestConfig getConfig() {
    return config;
  }

  public void setConfig(KafkaRestConfig config) {
    this.config = config;
  }

  @Override
  public int compareTo(KafkaConsumerState o) {
    if (this.expiration < o.expiration) {
      return -1;
    } else if (this.expiration == o.expiration) {
      return 0;
    } else {
      return 1;
    }
  }

  /**
   * An iterator / cursor to track the current position of the records sent to the client so far
   */
  public Iterator<ConsumerRecord<ClientK, ClientV>> getIterator() {
    if (consumerRecords == null) {
      return null;
    }
    if (iter == null) {
      iter = consumerRecords.iterator();
    }
    return iter;
  }

  /**
   * Initiate poll(timeout) request to retrieve consummer records, or return the existing
   * consumer records if the records have not been fully consumed by client yet
   */
  public void getOrCreateConsumerRecords(long timeout) {
    try {
      lock.writeLock().lock();
      if (!hasNext()) {
        //reset index
        this.index = 0;
        consumerRecordList = new ArrayList<ConsumerRecord<ClientK, ClientV>>();
        consumerRecords = consumer.poll(timeout);
        //drain the iterator and buffer to list
        for (ConsumerRecord<ClientK, ClientV> consumerRecord : consumerRecords) {
          consumerRecordList.add(consumerRecord);
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public ConsumerRecord<ClientK, ClientV> peek() {
    if (hasNext()) {
      ConsumerRecord<ClientK, ClientV> record = consumerRecordList.get(this.index);
      return record;
    }
    return null;
  }

  public boolean hasNext() {
    return consumerRecordList != null && this.index < consumerRecordList.size();
  }

  public ConsumerRecord<ClientK, ClientV> next() {
    if (hasNext()) {
      ConsumerRecord<ClientK, ClientV> record = consumerRecordList.get(index);
      this.index = this.index + 1;
      return record;
    }
    return null;
  }


  private class NoOpOnRebalance implements ConsumerRebalanceListener {

    public NoOpOnRebalance() {
    }

    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    }

    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    }
  }

}

