package com.swordfall.streamingkafka

import com.swordfall.common.KafkaOffsetUtils
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, HasOffsetRanges, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import redis.clients.jedis.Pipeline

/**
  * 获取topic最小的offset
  */
object SparkStreamingKafka {

  def main(args: Array[String]): Unit = {
    val brokers = "192.168.187.201:9092"
    val topic = "nginx"
    val partition: Int = 0 // 测试topic只有一个分区
    val start_offset: Long = 0L

    // Kafka参数
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "test",
      "enable.auto.commit" -> (false: java.lang.Boolean),
      "auto.offset.reset" -> "latest"
    )

    // Redis configurations
    val maxTotal = 10
    val maxIdle = 10
    val minIdle = 1
    val redisHost = "192.168.187.201"
    val redisPort = 6379
    val redisTimeout = 30000
    // 默认db，用户存放Offset和pv数据
    val dbDefaultIndex = 8
    InternalRedisClient.makePool(redisHost, redisPort, redisTimeout, maxTotal, maxIdle, minIdle)

    val conf = new SparkConf().setAppName("SparkStreamingKafka").setIfMissing("spark.master", "local[2]")
    val ssc = new StreamingContext(conf, Seconds(10))

    // 从Redis获取上一次存的Offset
    val jedis = InternalRedisClient.getPool.getResource
    jedis.select(dbDefaultIndex)
    val topic_partition_key = topic + "_" + partition

    val lastSavedOffset = jedis.get(topic_partition_key)
    var fromOffsets: Map[TopicPartition, Long] = null
    if (null != lastSavedOffset){
      var lastOffset = 0L
      try{
        lastOffset = lastSavedOffset.toLong
      }catch{
        case ex: Exception => println(ex.getMessage)
          println("get lastSavedOffset error, lastSavedOffset from redis [" + lastSavedOffset + "]")
          System.exit(1)
      }
      // 设置每个分区起始的Offset
      fromOffsets = Map{ new TopicPartition(topic, partition) -> lastOffset }

      println("lastOffset from redis -> " + lastOffset)
    }else{
      //等于null，表示第一次, redis里面没有存储偏移量，但是可能会存在kafka存在一部分数据丢失或者过期，但偏移量没有记录在redis里面，
      // 偏移量还是按0的话，会导致偏移量范围出错，故需要拿到earliest或者latest的偏移量
      fromOffsets = KafkaOffsetUtils.getCurrentOffset(kafkaParams, List(topic))
    }
    InternalRedisClient.getPool.returnResource(jedis)


    // 使用Direct API 创建Stream
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      LocationStrategies.PreferConsistent,
      ConsumerStrategies.Assign[String, String](fromOffsets.keys.toList, kafkaParams, fromOffsets)
    )

    // 开始处理批次消息
    stream.foreachRDD{
      rdd =>
        val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
        val result = processLogs(rdd)
        println("================= Total " + result.length + " events in this batch ..")

        val jedis = InternalRedisClient.getPool.getResource
        // redis是单线程的，下一次请求必须等待上一次请求执行完成后才能继续执行，然而使用Pipeline模式，客户端可以一次性的发送多个命令，无需等待服务端返回。这样就大大的减少了网络往返时间，提高了系统性能。
        val pipeline: Pipeline = jedis.pipelined()
        pipeline.select(dbDefaultIndex)
        pipeline.multi() // 开启事务

        // 逐条处理消息
        result.foreach{
          record =>

            // 增加网站小时pv
            val site_pv_by_hour_key = "site_pv_" + record.site_id + "_" + record.hour
            pipeline.incr(site_pv_by_hour_key)

            // 增加小时总pv
            val pv_by_hour_key = "pv_" + record.hour
            pipeline.incr(pv_by_hour_key)

            // 使用set保存当天每个小时网站的uv
            val site_uv_by_hour_key = "site_uv_" + record.site_id + "_" + record.hour
            pipeline.sadd(site_uv_by_hour_key, record.user_id)

            // 使用set保存当天每个小时的uv
            val uv_by_hour_key = "uv_" + record.hour
            pipeline.sadd(uv_by_hour_key, record.user_id)
        }

        // 更新Offset
        offsetRanges.foreach{
          offsetRange =>
            println("partition: " + offsetRange.partition + " fromOffset: " + offsetRange.fromOffset + " untilOffset: " + offsetRange.untilOffset)
            val topic_partition_key = offsetRange.topic + "_" + offsetRange.partition
            pipeline.set(topic_partition_key, offsetRange.untilOffset + "")
        }

        pipeline.exec() // 提交事务
        pipeline.sync() // 关闭pipeline

        InternalRedisClient.getPool.returnResource(jedis)
    }

    ssc.start()
    ssc.awaitTermination()
  }

  case class MyRecord(hour: String, user_id: String, site_id: String)

  def processLogs(messages: RDD[ConsumerRecord[String, String]]): Array[MyRecord] = {
    messages.map(_.value()).flatMap(parseLog).collect()
  }

  def parseLog(line: String): Option[MyRecord] = {
    val ary: Array[String] = line.split("\\|~\\|", -1)
    try{
      val hour = ary(0).substring(0, 13).replace("T", "-")
      val uri = ary(2).split("[=|&]", -1)
      val user_id = uri(1)
      val site_id = uri(3)
      return scala.Some(MyRecord(hour, user_id, site_id))
    }catch{
      case ex: Exception => println(ex.getMessage)
    }
    return None
  }

}
