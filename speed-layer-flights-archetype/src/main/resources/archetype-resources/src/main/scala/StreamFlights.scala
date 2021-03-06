import kafka.serializer.StringDecoder

import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka._
import org.apache.spark.SparkConf
import com.fasterxml.jackson.databind.{ DeserializationFeature, ObjectMapper }
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.ConnectionFactory
import org.apache.hadoop.hbase.client.Get
import org.apache.hadoop.hbase.client.Increment
import org.apache.hadoop.hbase.util.Bytes

object StreamFlights {
  val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)
  val hbaseConf: Configuration = HBaseConfiguration.create()
  hbaseConf.set("hbase.zookeeper.property.clientPort", "2181")
  
  // Use the following two lines if you are building for the cluster 
  // hbaseConf.set("hbase.zookeeper.quorum","mpcs530132017test-hgm1-1-20170924181440.c.mpcs53013-2017.internal,mpcs530132017test-hgm2-2-20170924181505.c.mpcs53013-2017.internal,mpcs530132017test-hgm3-3-20170924181529.c.mpcs53013-2017.internal")
  // hbaseConf.set("zookeeper.znode.parent", "/hbase-unsecure")
  
  // Use the following line if you are building for the VM
  hbaseConf.set("hbase.zookeeper.quorum", "localhost")
  
  val hbaseConnection = ConnectionFactory.createConnection(hbaseConf)
  val speedDelaysByRoute = hbaseConnection.getTable(TableName.valueOf("speed_weather_delays_by_route"))
  val swdr2 = hbaseConnection.getTable(TableName.valueOf("swdr2"))
  val latestWeather = hbaseConnection.getTable(TableName.valueOf("latest_weather"))
  
  def getLatestWeather(station: String) = {
      val result = latestWeather.get(new Get(Bytes.toBytes(station)))
      System.out.println(result.isEmpty())
      if(result.isEmpty())
        None
      else
        Some(WeatherReport(
              station,
              Bytes.toBoolean(result.getValue(Bytes.toBytes("weather"), Bytes.toBytes("fog"))),
              Bytes.toBoolean(result.getValue(Bytes.toBytes("weather"), Bytes.toBytes("rain"))),
              Bytes.toBoolean(result.getValue(Bytes.toBytes("weather"), Bytes.toBytes("snow"))),
              Bytes.toBoolean(result.getValue(Bytes.toBytes("weather"), Bytes.toBytes("hail"))),
              Bytes.toBoolean(result.getValue(Bytes.toBytes("weather"), Bytes.toBytes("thunder"))),
              Bytes.toBoolean(result.getValue(Bytes.toBytes("weather"), Bytes.toBytes("tornado")))))
  }
  
  def incrementDelaysByRoute(kfr : KafkaFlightRecord) : String = {
    val maybeLatestWeather = getLatestWeather(kfr.originName)
    if(maybeLatestWeather.isEmpty)
      return "No weather for " + kfr.originName;
    val latestWeather = maybeLatestWeather.get
    val inc = new Increment(Bytes.toBytes(kfr.originName + kfr.destinationName))
    if(latestWeather.clear) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("clear_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("clear_delays"), kfr.departureDelay)
    }
    if(latestWeather.fog) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("fog_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("fog_delays"), kfr.departureDelay)
    }
    if(latestWeather.rain) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("rain_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("rain_delays"), kfr.departureDelay)
    }
    if(latestWeather.snow) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("snow_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("snow_delays"), kfr.departureDelay)
    }
    if(latestWeather.hail) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("hail_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("hail_delays"), kfr.departureDelay)
    }
    if(latestWeather.hail) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("thunder_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("thunder_delays"), kfr.departureDelay)
    }
    if(latestWeather.hail) {
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("tornado_flights"), 1)
      inc.addColumn(Bytes.toBytes("delay"), Bytes.toBytes("tornado_delays"), kfr.departureDelay)
    }
    speedDelaysByRoute.increment(inc)
    swdr2.increment(inc)
    return "Updated speed layer for flight from " + kfr.originName + " to " + kfr.destinationName
}
  
  def main(args: Array[String]) {
    if (args.length < 1) {
      System.err.println(s"""
        |Usage: StreamFlights <brokers> 
        |  <brokers> is a list of one or more Kafka brokers
        | 
        """.stripMargin)
      System.exit(1)
    }
    
    val Array(brokers) = args

    // Create context with 2 second batch interval
    val sparkConf = new SparkConf().setAppName("StreamFlights")
    val ssc = new StreamingContext(sparkConf, Seconds(2))

    // Create direct kafka stream with brokers and topics
    val topicsSet = Set[String]("flights")
    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)
    val messages = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](
      ssc, kafkaParams, topicsSet)

    // Get the lines, split them into words, count the words and print
    val serializedRecords = messages.map(_._2);

    val kfrs = serializedRecords.map(rec => mapper.readValue(rec, classOf[KafkaFlightRecord]))

    // Update speed table    
    val processedFlights = kfrs.map(incrementDelaysByRoute)
    processedFlights.print()
    // Start the computation
    ssc.start()
    ssc.awaitTermination()
  }

}