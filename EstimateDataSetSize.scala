import java.math.BigInteger

import com.holdenkarau.spark.testing.SharedSparkContext
import com.paypal.risk.grs.datamartTest._
import org.apache.spark.sql.hive.HiveUtils
import org.apache.spark.sql.hive.test.TestHiveContext
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.scalatest.{BeforeAndAfter, FunSuite}

import com.paypal.risk.grs.datamart.sql.Constant.EDW
import com.paypal.risk.grs.datamart.sql.scheduler.SerialScheduler
import com.paypal.risk.grs.datamart.{BSI, BSIConf}

class EstimateRddSizeTest extends FunSuite with BeforeAndAfter with SharedSparkContext {

  

  // 1.sample 0.01 's original data
  // 2.compute sample data count
  // 3. if sample data count >0, cache the sample data  and compute sample data size
  // 4.compute original rdd total count
  // 5.estimate the rdd size as ${total count}* ${sampel data size} / ${sample rdd count}
  def estimateRDDSize(df: DataFrame): BigInt = {

    val sampleRDD = df.sample(false, 0.01)
    sampleRDD.persist(StorageLevel.MEMORY_ONLY)

    val sampleRDDCount = sampleRDD.count()
    if (sampleRDDCount != 0) {
      val sampleSize = computeRDDSize(sampleRDD)
      val totalRDDCount = df.count()
      sampleRDD.unpersist()
      BigInteger
        .valueOf(sampleSize)
        .multiply(BigInteger.valueOf(totalRDDCount))
        .divide(BigInteger.valueOf(sampleRDDCount))
    } else {
      sampleRDD.unpersist()
      1
    }
  }

  def computeRDDSize(dataset: Dataset[Row]): Long = {
    dataset.rdd.partitions.length match {
      case 0 =>
        0
      case numPartitions =>
        val rddOfDataframe = dataset.rdd.map(p => {
          p.toString().getBytes("UTF-8").length.toLong //126592
          //SizeEstimator.estimate(p) 2379264
        })

        val originalSize = if (rddOfDataframe.partitions == 0) {
          0
        } else {
          rddOfDataframe.reduce(_ + _)
        }
        originalSize
    }
  }



  def init() {
    initEnv()
    val hadoopConf = sc.hadoopConfiguration
    hadoopConf.set("javax.jdo.option.ConnectionURL", "jdbc:derby:memory:db;create=true")
    val sparkConf = sc.getConf
    val hiveClient = HiveUtils.newClientForMetadata(sparkConf, hadoopConf)
    sqlContext = new TestHiveContext(sc, hiveClient)

    spark = SparkSession.builder().getOrCreate()
    println("initial test environment")
    BSI.refreshEnv(spark)

    hiveMetaStore = BSI.metastore
    hiveCatalog = BSI.catalog
    localMetadata = BSI.metadata

    hiveMetaStore.deleteDatabase(EDW, errorOnNotExist = false)
    hiveMetaStore.createDatabase(EDW, errorOnExist = false, location = Some("/tmp/test/hive"))
    astInterpreter = BSI.astInterpreter

    serialScheduler = new SerialScheduler(spark)
    doctor = BSI.doctor

  }

  before {
    // add this to enable es log  please remove it when publish patch
    BSIConf().set(BSIConf.BSI_FOR_TEST, "true")
    BSIConf().set(BSIConf.BSI_JOB_NOTIFICATION, "true")
    initEnv(sc)

  }

  test("testEstimateRddSize") {

    val df = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("delimiter", "|")
      .option("header", "true")
      .load(s"$currentDirPath/data/score_engine/bt_v01_custom_1.csv")
    val estimated = InsertUtils.estimateRDDSize2(df).doubleValue()
    assert(DoubleUtils.~=(estimated, 126592.0, 1))
  }

}

