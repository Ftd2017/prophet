package com.closer.prophet;

import kafka.common.TopicAndPartition;
import kafka.serializer.StringDecoder;
import kafka.utils.ZKGroupTopicDirs;
import org.I0Itec.zkclient.ZkClient;
import org.apache.spark.SparkException;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaPairInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.HasOffsetRanges;
import org.apache.spark.streaming.kafka.KafkaCluster;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.apache.spark.streaming.kafka.OffsetRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map$;
import scala.collection.mutable.ArrayBuffer;
import scala.util.Either;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class KafkaConsumer implements Serializable {

    public static Logger logger = LoggerFactory.getLogger(KafkaConsumer.class);
    private Map<String, String> kafkaParams;
    private String checkPointDir;
    private JavaStreamingContext jssc;
    private String zkHost;
    private String topic;
    private String brokerList;
    private String groupId;
    private Boolean fromBeginning = false;

    private JavaPairInputDStream<String, String> directPairStream;
    private JavaInputDStream<String> directStream;
    //    private  ArrayList<OffsetRange> offsetRange;
    private Map<TopicAndPartition, Long> fromOffsets;
    private ZkClient zkClient;
    private ZKGroupTopicDirs topicDir;
    private KafkaCluster kc;

    public KafkaConsumer() {
    }


    public KafkaConsumer(JavaStreamingContext jssc, String zkHost,
                         String topic, String brokerList, String groupId, boolean fromBeginning) {
        this(jssc, zkHost, topic, brokerList, groupId);
        this.fromBeginning = fromBeginning;
    }


    public KafkaConsumer(JavaStreamingContext jssc, String zkHost, String topic, String brokerList, String groupId, String checkPoint_Dir) {
        this(jssc, zkHost, topic, brokerList, groupId);
        this.checkPointDir = checkPoint_Dir;
    }

    public KafkaConsumer(JavaStreamingContext jssc, String zkHost, String topic, String brokerList, String groupId, String checkPoint_Dir, boolean fromBeginning) {
        this(jssc, zkHost, topic, brokerList, groupId);
        this.checkPointDir = checkPoint_Dir;
        this.fromBeginning = fromBeginning;
    }

    public KafkaConsumer(JavaStreamingContext jssc, String zkHost, String topic, String brokerList, String groupId) {
        //???????????????????????? ???????????????build??????
        this.jssc = jssc;
        this.zkHost = zkHost;
        this.topic = topic;
        this.brokerList = brokerList;
        this.groupId = groupId;
        this.build();
    }

    public static scala.collection.immutable.Map<String, String> JavaMapConvertToScalaMap(Map<String, String> kafkaParams) {
        scala.collection.mutable.Map<String, String> mapTemp = JavaConverters.mapAsScalaMapConverter(kafkaParams).asScala();

        Object objTest = Map$.MODULE$.<String, String>newBuilder().$plus$plus$eq(mapTemp.toSeq());
        Object resultTest = ((scala.collection.mutable.Builder) objTest).result();
        scala.collection.immutable.Map<String, String> scalaMap = (scala.collection.immutable.Map) resultTest;

        return scalaMap;

    }

    public static scala.collection.immutable.Map JavaMapConvertToScala(Map fromOffsets) {

        scala.collection.mutable.Map tempMap = JavaConversions.mapAsScalaMap(fromOffsets);

        Object objTest = scala.collection.Map$.MODULE$.<Object, Object>newBuilder().$plus$plus$eq(tempMap.toSeq());
        Object resultTest = ((scala.collection.mutable.Builder) objTest).result();

        scala.collection.immutable.Map<Object, Object> scalaMap = (scala.collection.immutable.Map) resultTest;
        return scalaMap;
    }

    /**
     * ????????????DStream????????????kafka??????????????????
     * <p>
     * ?????????????????????offset
     *
     * @return JavaDStream
     */
    public JavaInputDStream<String> createKafkaStream() throws SparkException {

        HashSet<String> topics = new HashSet<>();
        topics.add(topic);

        //??????TopicAndOffset
        Either<ArrayBuffer<Throwable>, scala.collection.immutable.Set<TopicAndPartition>> partitionsEither =
                kc.getPartitions(JavaConversions.asScalaSet(topics).toSet());
        //??????topicAndOffset ?????????
        if (partitionsEither.isLeft()) {
            throw new SparkException("get kafka partition failed:" + partitionsEither.left().get());
        }

        scala.collection.immutable.Set<TopicAndPartition> topicAndPartitionSet
                = partitionsEither.right().get();

        java.util.Set<TopicAndPartition> topicAndPartitions = JavaConversions.setAsJavaSet(topicAndPartitionSet);

        //?????????groupId ?????????topic ?????????offset
        Either<ArrayBuffer<Throwable>, scala.collection.immutable.Map<TopicAndPartition, Object>> consumerOffsetsE
                = kc.getConsumerOffsets(groupId, topicAndPartitionSet);

        //???????????????offset ???????????????offset??????????????????
        if (!consumerOffsetsE.isLeft()) {
            //??????????????? ??????zk????????????offset??????????????? ?????????offsetOutOfRangeException
            //??????????????????,??????????????????zk??????consumerOffset???leaderEarliestOffset?????????
            //??????consumerOffset???leaderEarliestOffset???????????? ??????????????????offset??????consumerOffset???leaderEarliestOffset??????
            scala.collection.immutable.Map<TopicAndPartition, Object> topicAndPartitionMap
                    = consumerOffsetsE.right().get();
            Map<TopicAndPartition, Object> consumerOffsets = JavaConversions.mapAsJavaMap(topicAndPartitionMap);

            scala.collection.immutable.Map<TopicAndPartition, KafkaCluster.LeaderOffset> leaderOffsetMap
                    = kc.getEarliestLeaderOffsets(topicAndPartitionSet).right().get();

            Map<TopicAndPartition, KafkaCluster.LeaderOffset> leaderOffsets = JavaConversions.mapAsJavaMap(leaderOffsetMap);

            //Error :java Error:(79, 41) java: ??????forall((tup)->{ l[...]et; }), ????????????????????????

//            boolean flag = leaderOffsets.forall(tup -> {
//                long leaderOffset = tup._2.offset();
//                long offset = (Long) consumerOffset.get(tup._1);
//                return leaderOffset > offset;
//            });

            boolean flag = true;
            for (Map.Entry<TopicAndPartition, KafkaCluster.LeaderOffset> leaderEntry :
                    leaderOffsets.entrySet()) {
                long leaderOffset = leaderEntry.getValue().offset();
                long consumerOffset = (Long) consumerOffsets.get(leaderEntry.getKey());
                if (leaderOffset > consumerOffset) {
                    flag = false;
                    break;
                }
            }


            if (!flag) {
                // offset??????
                logger.warn("consumer group :" + groupId + " offset ????????????,?????????leaderEarliestOffsets???");

                for (TopicAndPartition topicAndPartition : topicAndPartitions) {
                    Long offset = leaderOffsets.get(topicAndPartition).offset();
                    fromOffsets.put(topicAndPartition, offset);
                }
            } else {
                //offset??????
                logger.info("consumer group :" + groupId + " offset??????,????????????.");
                for (TopicAndPartition topicAndPartition : topicAndPartitions) {
                    Long offset = (Long) consumerOffsets.get(topicAndPartition);
                    fromOffsets.put(topicAndPartition, offset);
                }
            }
        }
        //???????????????offset
        else {
//            for ( TopicAndPartition topicAndPartition :topicAndPartitions){
//                fromOffset.put(topicAndPartition,0L);
//            }

            Map<TopicAndPartition, KafkaCluster.LeaderOffset> offsetMap = null;

            if (fromBeginning) {
                scala.collection.immutable.Map<TopicAndPartition, KafkaCluster.LeaderOffset> earliestOffset
                        = kc.getEarliestLeaderOffsets(topicAndPartitionSet).right().get();

                offsetMap = JavaConversions.mapAsJavaMap(earliestOffset);

                logger.info("consumer group :" + groupId + " ???????????????,?????????leaderEarliestOffsets???");

            } else {
                scala.collection.immutable.Map<TopicAndPartition, KafkaCluster.LeaderOffset> latestOffset
                        = kc.getLatestLeaderOffsets(topicAndPartitionSet).right().get();

                offsetMap = JavaConversions.mapAsJavaMap(latestOffset);

                logger.info("consumer group :" + groupId + " ???????????????,?????????leaderLatestOffsets???");
            }
            for (Map.Entry<TopicAndPartition, KafkaCluster.LeaderOffset> entry :
                    offsetMap.entrySet()) {
                fromOffsets.put(entry.getKey(), entry.getValue().offset());
            }
        }

        //??????offset ????????????????????????
//        kc.setConsumerOffsets(groupId,JavaMapConvertToScala(fromOffsets));

        JavaInputDStream<String> directStream = KafkaUtils.createDirectStream(jssc, String.class, String.class,
                StringDecoder.class, StringDecoder.class,
                String.class, kafkaParams, fromOffsets,
                mam -> {
                    return mam.message();
                }
        );
        return directStream;

    }

    public void submit(JavaDStream<String> dStream) {
        dStream.foreachRDD(rdd -> {
            OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
            for (OffsetRange offsetRange : offsetRanges) {
                TopicAndPartition topicAndPartition = new TopicAndPartition(offsetRange.topic(), offsetRange.partition());
                Long offset = offsetRange.untilOffset();
                HashMap<TopicAndPartition, Object> partitionOffset = new HashMap<>();
                partitionOffset.put(topicAndPartition, offset);
                scala.collection.immutable.Map map = JavaMapConvertToScala(partitionOffset);

                Either either = kc.setConsumerOffsets(groupId, map);

                if (either.isLeft()) {
                    logger.error("Error updating the offset to KafkaCluster :" + either.left().get());
                }
            }

        });
    }

    public KafkaConsumer build() {
        this.zkClient = new ZkClient(zkHost);
        this.kafkaParams = new HashMap<String, String>();
        this.fromOffsets = new HashMap<TopicAndPartition, Long>();
        this.kafkaParams.put("metadata.broker.list", brokerList);
        this.kafkaParams.put("zookeeper.connect", zkHost);
        this.kafkaParams.put("group.id", groupId);
        this.kc = new KafkaCluster(JavaMapConvertToScalaMap(kafkaParams));
        this.topicDir = new ZKGroupTopicDirs(groupId, topic);
        return this;
    }

    public JavaStreamingContext getJssc() {

        return jssc;
    }

    public void setJssc(JavaStreamingContext jssc) {
        this.jssc = jssc;
    }

    public String getZkHost() {
        return zkHost;
    }

    public void setZkHost(String zkHost) {
        this.zkHost = zkHost;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getBrokerList() {
        return brokerList;
    }

    public void setBrokerList(String brokerList) {
        this.brokerList = brokerList;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Map<String, String> getKafkaParams() {
        return kafkaParams;
    }

    public void setKafkaParams(Map<String, String> kafkaParams) {
        this.kafkaParams = kafkaParams;
    }

    public ZkClient getZkClient() {
        return zkClient;
    }

    public void setZkClient(ZkClient zkClient) {
        this.zkClient = zkClient;
    }

    public ZKGroupTopicDirs getTopicDir() {
        return topicDir;
    }

    public void setTopicDir(ZKGroupTopicDirs topicDir) {
        this.topicDir = topicDir;
    }

    public Map<TopicAndPartition, Long> getFromOffsets() {
        return fromOffsets;
    }

    public void setFromOffsets(Map<TopicAndPartition, Long> fromOffsets) {
        this.fromOffsets = fromOffsets;
    }

    public Boolean getFromBeginning() {
        return fromBeginning;
    }

    public void setFromBeginning(Boolean fromBeginning) {
        this.fromBeginning = fromBeginning;
    }

    public void submit(KafkaCluster kafkaCluster, Map<TopicAndPartition, Long> fromOffsets) {
        if (fromOffsets == null || fromOffsets.isEmpty()) {
            logger.error("Error updating the offset to KafkaCluster !");

            return;
        }

        kc.setConsumerOffsets(groupId, JavaMapConvertToScala(fromOffsets));
        logger.info("=====================================" + fromOffsets.toString());
        logger.info("updating offset completed.");
    }

    public static class SelfFunction<T1, R> implements Function<T1, R>, Serializable {

        @Override
        public R call(T1 v1) throws Exception {
            Tuple2<String, String> tup = (Tuple2<String, String>) v1;
            return (R) tup._2;
        }
    }

//

}

