import java.util.Properties

import config.Config
import model.TopViews
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010
import org.apache.flink.table.api.{EnvironmentSettings, GroupWindow, Tumble}
import org.apache.flink.table.api.scala.StreamTableEnvironment
import serde.{PageViewsSerDe, UserSerDe}
import org.apache.flink.table.api.scala._

object FlinkStreamingApp {

  def main(args: Array[String]): Unit = {

    val properties = new Properties()
    properties.setProperty("bootstrap.servers", Config.kafkaServerAddress)
   // properties.setProperty("zookeeper.connect", "***.**.*.***:2181")
    properties.setProperty("group.id","stream_group")
    properties.setProperty("auto.offset.reset", "earliest")

    val userKafkaConsumer = new FlinkKafkaConsumer010(Config.usersTopic, new SimpleStringSchema(),
      properties)
    val pageViewsKafkaConsumer = new FlinkKafkaConsumer010(Config.pageViewsTopic, new SimpleStringSchema(), properties)

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime)

    val tEnv = StreamTableEnvironment.create(env, EnvironmentSettings.newInstance().useBlinkPlanner().inStreamingMode
    ().build())

    val userstream = env.addSource(userKafkaConsumer).name("user")
      .map(raw => UserSerDe.deser.deserialize(raw.getBytes))

    val joinedWindowStream = env.addSource(pageViewsKafkaConsumer).name("page")
      .map(raw => PageViewsSerDe.deser.deserialize(raw.getBytes))
      .join(userstream)
      .where(_.userid).equalTo(_.userid)
      .window(TumblingEventTimeWindows.of(Time.minutes(1), Time.seconds(10)))
      .apply((p,u) => (p.pageid, u.gender, p.userid, p.viewtime))
     // .reduce((v1,v2) => (v1._1, v1._2, v1._3, v1._4 + v2._4))

    tEnv.registerDataStream("windowedUserPageViewStream", joinedWindowStream,
      '_1, '_2, '_3, '_4, 'proctime.proctime)

        val result = tEnv.sqlQuery("""SELECT _1 as pageid, _2 as gender, SUM(_4) as viewtime, COUNT(DISTINCT _3) as
                             distinctUserCount
                             FROM windowedUserPageViewStream
                             GROUP BY _1, _2
                             """.stripMargin).orderBy('viewtime.desc)

    /*
     earlier quwry
     """
                         SELECT *
                         FROM (
                             SELECT _1 as pageid, _2 as gender, SUM(_4) as viewtime, COUNT(DISTINCT _3) as
                             distinctUserCount,
                             ROW_NUMBER() OVER (PARTITION BY _1 ORDER BY _4 DESC) as row_num
                             FROM windowedUserPageViewStream
                             GROUP BY TUMBLE(proctime, INTERVAL '1' MINUTE), _1, _2)
                         WHERE row_num <= 10
                             """
     */


   /* val result = tEnv.fromDataStream(joinedWindowStream,
      '_1, '_2, '_3, '_4, 'UserActionTime.proctime)
      .window( Tumble over 1.minutes on 'UserActionTime as 'w)
      .groupBy("w, _1,_2")
      .select("_1 as pageid, _2 as gender, _4.sum as viewtime, _3.count.distinct as distinctUserCount")
      .orderBy("viewtime.desc")
      .fetch(10)*/


    result.toAppendStream(TypeInformation.of(classOf[TopViews])).print()

   /* val viewstream = CepEnvironment.env.addSource(kafkaConsumer2).name("view")
      .assignTimestampsAndWatermarks(extractor).flatMap().keyBy(_.get("userid").asText()).window(TumblingEventTimeWindows.of(Time.seconds(60)))
      .reduce(new ReduceFunction[ObjectNode] {
        override def reduce(t: ObjectNode, t1: ObjectNode): ObjectNode = {
          //Calculate the sum of view time
          t
        }
      })

    val fstream = userstream
      .intervalJoin(viewstream)
      .between(Time.milliseconds(0), Time.milliseconds(10000))
      .process(new StreamCombineJoinFunction).sink(kafkasink)-topview topic*/

    env.execute()
  }
}
