package org.apache.kylin.source.kafka.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import kafka.api.OffsetRequest;
import kafka.cluster.Broker;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.message.MessageAndOffset;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.engine.streaming.StreamingMessage;
import org.apache.kylin.source.kafka.KafkaStreamingMessage;
import org.apache.kylin.source.kafka.Parser;
import org.apache.kylin.source.kafka.config.KafkaClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;

/**
 */
public final class KafkaUtils {

    private static final Logger logger = LoggerFactory.getLogger(KafkaUtils.class);

    private static final int MAX_RETRY_TIMES = 6;

    private KafkaUtils() {
    }

    public static Broker getLeadBroker(KafkaClusterConfig kafkaClusterConfig, int partitionId) {
        final PartitionMetadata partitionMetadata = KafkaRequester.getPartitionMetadata(kafkaClusterConfig.getTopic(), partitionId, kafkaClusterConfig.getBrokers(), kafkaClusterConfig);
        if (partitionMetadata != null && partitionMetadata.errorCode() == 0) {
            return partitionMetadata.leader();
        } else {
            return null;
        }
    }

    private static void sleep(int retryTimes) {
        int seconds = (int) Math.pow(2, retryTimes);
        logger.info("retry times:" + retryTimes + " sleep:" + seconds + " seconds");
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static MessageAndOffset getKafkaMessage(KafkaClusterConfig kafkaClusterConfig, int partitionId, long offset) {
        final String topic = kafkaClusterConfig.getTopic();
        int retry = 0;
        while (retry < MAX_RETRY_TIMES) {//max sleep time 63 seconds
            final Broker leadBroker = getLeadBroker(kafkaClusterConfig, partitionId);
            if (leadBroker == null) {
                logger.warn("unable to find leadBroker with config:" + kafkaClusterConfig + " partitionId:" + partitionId);
                sleep(retry++);
                continue;
            }
            final FetchResponse response = KafkaRequester.fetchResponse(topic, partitionId, offset, leadBroker, kafkaClusterConfig);
            if (response.errorCode(topic, partitionId) != 0) {
                logger.warn("errorCode of FetchResponse is:" + response.errorCode(topic, partitionId));
                sleep(retry++);
                continue;
            }
            final Iterator<MessageAndOffset> iterator = response.messageSet(topic, partitionId).iterator();
            if (!iterator.hasNext()) {
                logger.warn("messageSet is empty");
                sleep(retry++);
                continue;
            }
            return iterator.next();
        }
        throw new IllegalStateException(String.format("try to get timestamp of topic: %s, partitionId: %d, offset: %d, failed to get StreamMessage from kafka", topic, partitionId, offset));
    }

    public static long findClosestOffsetWithDataTimestamp(KafkaClusterConfig kafkaClusterConfig, int partitionId, long timestamp, Parser parser) {
        Pair<Long, Long> firstAndLast = getFirstAndLastOffset(kafkaClusterConfig, partitionId);
        final String topic = kafkaClusterConfig.getTopic();

        logger.info(String.format("topic: %s, partitionId: %d, try to find closest offset with timestamp: %d between offset {%d, %d}", topic, partitionId, timestamp, firstAndLast.getFirst(), firstAndLast.getSecond()));
        final long result = binarySearch(kafkaClusterConfig, partitionId, firstAndLast.getFirst(), firstAndLast.getSecond(), timestamp, parser);
        logger.info(String.format("topic: %s, partitionId: %d, found offset: %d", topic, partitionId, result));
        return result;
    }

    public static Pair<Long, Long> getFirstAndLastOffset(KafkaClusterConfig kafkaClusterConfig, int partitionId) {
        final String topic = kafkaClusterConfig.getTopic();
        final Broker leadBroker = Preconditions.checkNotNull(getLeadBroker(kafkaClusterConfig, partitionId), "unable to find leadBroker with config:" + kafkaClusterConfig + " partitionId:" + partitionId);
        final long earliestOffset = KafkaRequester.getLastOffset(topic, partitionId, OffsetRequest.EarliestTime(), leadBroker, kafkaClusterConfig);
        final long latestOffset = KafkaRequester.getLastOffset(topic, partitionId, OffsetRequest.LatestTime(), leadBroker, kafkaClusterConfig) - 1;
        return Pair.newPair(earliestOffset, latestOffset);
    }

    private static long binarySearch(KafkaClusterConfig kafkaClusterConfig, int partitionId, long startOffset, long endOffset, long targetTimestamp, Parser parser) {
        Map<Long, Long> cache = Maps.newHashMap();

        while (startOffset < endOffset) {
            long midOffset = startOffset + ((endOffset - startOffset) >> 1);
            long startTimestamp = getDataTimestamp(kafkaClusterConfig, partitionId, startOffset, parser, cache);
            long endTimestamp = getDataTimestamp(kafkaClusterConfig, partitionId, endOffset, parser, cache);
            long midTimestamp = getDataTimestamp(kafkaClusterConfig, partitionId, midOffset, parser, cache);
            // hard to ensure these 2 conditions
            //            Preconditions.checkArgument(startTimestamp <= midTimestamp);
            //            Preconditions.checkArgument(midTimestamp <= endTimestamp);
            if (startTimestamp >= targetTimestamp) {
                return startOffset;
            }
            if (endTimestamp <= targetTimestamp) {
                return endOffset;
            }
            if (targetTimestamp == midTimestamp) {
                return midOffset;
            } else if (targetTimestamp < midTimestamp) {
                endOffset = midOffset - 1;
                continue;
            } else {
                startOffset = midOffset + 1;
                continue;
            }
        }
        return startOffset;
    }

    private static long getDataTimestamp(KafkaClusterConfig kafkaClusterConfig, int partitionId, long offset, Parser parser, Map<Long, Long> cache) {
        if (cache.containsKey(offset)) {
            return cache.get(offset);
        } else {
            long t = getDataTimestamp(kafkaClusterConfig, partitionId, offset, parser);
            cache.put(offset, t);
            return t;
        }
    }

    public static long getDataTimestamp(KafkaClusterConfig kafkaClusterConfig, int partitionId, long offset, Parser parser) {
        final String topic = kafkaClusterConfig.getTopic();
        final MessageAndOffset messageAndOffset = getKafkaMessage(kafkaClusterConfig, partitionId, offset);
        final ByteBuffer payload = messageAndOffset.message().payload();
        byte[] bytes = new byte[payload.limit()];
        payload.get(bytes);
        final StreamingMessage streamingMessage = parser.parse(new KafkaStreamingMessage(messageAndOffset.offset(), bytes));
        logger.debug(String.format("The timestamp of topic: %s, partitionId: %d, offset: %d is: %d", topic, partitionId, offset, streamingMessage.getTimestamp()));
        return streamingMessage.getTimestamp();

    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
        }

        if ("calculatemargin".equals(args[0])) {
        }
    }
}
