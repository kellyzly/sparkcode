package com.paypal.gds;


import java.io.InputStream
import java.util.Date

import com.paypal.gds.structure.logging.appender.Util
import com.paypal.gds.structure.logging.interfaces.LazyLoggingWithTags
import com.paypal.gds.structure.logging.query.EsEnv
import org.apache.spark.scheduler.SparkListenerApplicationStart
import org.apache.spark.sql.execution.ui.{SparkListenerSQLExecutionEnd, SparkListenerSQLExecutionStart, SparkPlanGraph}
import org.apache.spark.sql.execution.SparkPlanInfo
import org.joda.time.DateTime

import scala.util.control.NonFatal

object BSISqlListenerUtil2 extends LazyLoggingWithTags {

  private case class StartEnd(start: Long, end: Long)
  private class MetricsWrapper(){
    var applicationId:String =null
    var cluster:String = null
    var appName:String  = null
    var user:String = null
    def setApplicationId(applicationId:String): Unit ={
      this.applicationId = applicationId
    }
    def setCluster(cluster:String):Unit = {
      this.cluster = cluster
    }
    def setAppName(appName:String) :Unit = {
      this.appName = appName
    }

    def setUser(user:String):Unit = {
      this.user = user
    }
  }

  def genTags() : scala.Predef.Map[scala.Predef.String, scala.Any]={
   getJobInformation()
  }
  object SqlStatus extends Enumeration {
    type RunType = Value
    val Running, Finished = Value
  }
  private var metricsWrapper:MetricsWrapper = new MetricsWrapper()
  private val regrexTool: BSISqlListenerUtilRegrexTool = new BSISqlListenerUtilRegrexTool
  private var shuffleTableColumnRes: List[ShuffleTableColumn] = List()
  // executionIdDurationMap records the relationship between executionId and sql statement's startend(start time
  // and end time)
  private val executionIdDurationMap = scala.collection.mutable.Map[Long, StartEnd]()
  // insertSqlMap records the relationship between the executionId and insert sql statement
  private val insertSqlMap = scala.collection.mutable.Map[Long, String]()
  private var describeTableCommandSparkPlan = ""

  /** extract ${db}.${tbl} from
    * sparkPlan like "
    * == Physical Plan ==
    * * Execute InsertIntoHiveTable InsertIntoHiveTable `edw`.`xxxx`, org.apache.hadoop.hive.ql.io.parquet.serde
    *
    * @param viewName
    * @param sparkPlan
    * @return tableName
    */
  private def extractTableName(viewName: String, sparkPlan: String): String = {
    val pattern = """.*InsertIntoHiveTable `(.*)`.`(.*)`,""" r
    var db = "default_db"
    var tbl = "default_tbl"
    pattern.findAllIn(sparkPlan).matchData foreach { m =>
      {
        db = m.group(1)
        tbl = m.group(2)
      }
    }
    s"${db}.${tbl}"
  }

  /**extract viewName from the spark plan like
    * == Physical Plan ==
    * Execute DescribeTableCommand
    * +- DescribeTableCommand `a`, false
    *
    * @param sparkPlan
    * @return viewName
    */
  private def extractViewName(sparkPlan: String): String = {
    val pattern = """DescribeTableCommand `(.*)`""" r
    var viewName = "defaultView"
    pattern.findAllIn(sparkPlan).matchData foreach { m =>
      {
        viewName = m.group(1)
      }
    }
    viewName
  }

  private def registerStartTime(executionId: Long, starttime: Long): Unit = {
    executionIdDurationMap += (executionId -> StartEnd(starttime, 0))
  }

  private def getSqlDurationBySec(executionId: Long, endTimeUnixSecs: Long): Long = {
    val startTimeUnixSecs = executionIdDurationMap(executionId).start
    executionIdDurationMap += (executionId -> StartEnd(startTimeUnixSecs, endTimeUnixSecs))
    (endTimeUnixSecs - startTimeUnixSecs) / 1000
  }

  def getInsertSqlMap: scala.collection.mutable.Map[Long, String] = {
    insertSqlMap
  }


  /** parse the SparkListenerSQLExecutionStart event
    * Spark listener([[com.paypal.risk.grs.datamart.util.BSISparkListener]]) sends event
    * [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart]] (triggered in
    * following function when a sql statement starts and
    * [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd]] when the
    * statement ends(triggered in [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd]]). We can get the
    * viewname,tablename of insert sql statement(like insert ${viewname} into table ${tablename}...) by analyzing
    * [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart]]. The way to verify a statement is finished
    * or running is to check whether the [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd]] event
    * (which has same the executionId) is received or not.
    *
    * an insert sql statment example:
    * ```
    * a=select name from edw.driver_set;
    * insert a into table edw.b partition by (seq_num='0') location '/tmp/d_compress1/seq_num=0';
    * ```
    * these two sql statements will send 2 spark plans within SparkListenerSQLExecutionStart events
    * first:
    * ```
    * == Physical Plan ==
    * Execute DescribeTableCommand
    * +- DescribeTableCommand `a`, false
    * ```
    * second:
    * ```
    * == Physical Plan ==
    * Execute InsertIntoHiveTable InsertIntoHiveTable `edw`.`b`, org.apache.hadoop.hive.ql.io.parquet.serde.
    * ParquetHiveSerDe, Map(seq_num -> None), true, false, [name#128, seq_num#129]
    * +- *(1) Project [name#53 AS name#128, 0 AS seq_num#129]
    * +- Exchange RoundRobinPartitioning(200)
    * +- HiveTableScan [name#53], HiveTableRelation `edw`.`driver_set`, org.apache.hadoop.hive.serde2.lazy.
    * LazySimpleSerDe, [customer_id#52, name#53, run_date#54, age#55], [dt#56]
    * ```
    * viewname(describeTableCommandSparkPlan in following function) a is captured in the first
    * [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart]] event
    * (which physical plan is about [[org.apache.spark.sql.execution.command.DescribeTableCommand]])
    * and tablename is edw.b is captured in the second [[org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart]] event.
    */
  def onExecutionStart(event: SparkListenerSQLExecutionStart): Unit = {

    val SparkListenerSQLExecutionStart(executionId, description, details, physicalPlan, sparkPlanInfo, startTime) =
      event
    if (physicalPlan.contains("DescribeTableCommand")) {
      describeTableCommandSparkPlan = physicalPlan
    } else if (physicalPlan.contains("InsertIntoTable")) {
      println("zly:" + physicalPlan)
      val planGraph = SparkPlanGraph(sparkPlanInfo)
      planGraph.allNodes.map(p => {
        if (p.desc.contains("InsertIntoHiveTable")) {
          val viewName = extractViewName(describeTableCommandSparkPlan)
          val tableName = extractTableName(viewName, p.desc)
          val insertSql = s"insert ${viewName} into table ${tableName}"
          insertSqlMap += (executionId -> insertSql)
        }
      })

      extractShuffleKeyAndHiveTable(sparkPlanInfo)
      // save startTime in map
      registerStartTime(executionId, startTime)

      val explain = physicalPlan
      logMetricsForSqlPlanWrapper(
        Map("insertSql" -> insertSqlMap(executionId), "explain" -> explain, "executionId" -> executionId),
        executionId)
      logMetricsForSqlWrapper(
        Map(
          "executionId" -> executionId,
          "insertSql" -> insertSqlMap(executionId),
          "status" -> SqlStatus.Running.toString,
          "shuffleTableColumns" -> shuffleTableColumnRes.toString()
        ),
        executionId
      )
    }
  }

  def extractHiveTableScan(p: SparkPlanInfo): List[Table] = {
    if (p.nodeName.contains("HiveTableScan")) {
      List(regrexTool.getHiveTableAndColumn(p.simpleString))
    } else {
      if (p.children != null) {
        p.children.toList.map(p => extractHiveTableScan(p)).flatten
      } else {
        null
      }
    }
  }


  def onExecutionEnd(event: SparkListenerSQLExecutionEnd): Unit = {
    val SparkListenerSQLExecutionEnd(executionId, endTime) = event
    if (insertSqlMap.contains(executionId)) {
      val sqlDuration = getSqlDurationBySec(executionId, endTime)
      logMetricsForSqlWrapper(Map(
          "executionId" -> executionId,
          "duration" -> sqlDuration,
          "insertSql" -> insertSqlMap(executionId),
          "status" -> SqlStatus.Finished.toString,
          "shuffleTableColumns" -> shuffleTableColumnRes.toString()
        ),
        executionId)
    }
  }

  def onlyHasOneHiveTableScan(currentNode: SparkPlanInfo): Boolean = {
    var allHiveTables: List[Table] = List()
    currentNode.children.toList.map(p => allHiveTables = extractHiveTableScan(p) ::: allHiveTables)
    allHiveTables.size == 1
  }

  // extract column from sort operator and hive table from nearest hive table
  //== Physical Plan ==
  //Execute InsertIntoHiveTable InsertIntoHiveTable `edw`.`d_compress`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, Map(seq_num -> None), true, false, [customer_id#143, customer_id1#144, seq_num#145]
  //+- *(6) Project [customer_id#65 AS customer_id#143, customer_id1#80 AS customer_id1#144, 0 AS seq_num#145]
  //   +- Exchange RoundRobinPartitioning(200)
  //      +- *(5) SortMergeJoin [customer_id#65], [customer_id1#80], Inner
  //         :- *(2) Sort [customer_id#65 ASC NULLS FIRST], false, 0
  //         :  +- Exchange hashpartitioning(customer_id#65, 5)
  //         :     +- *(1) Filter isnotnull(customer_id#65)
  //         :        +- HiveTableScan [customer_id#65], HiveTableRelation `edw`.`driver_set`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, [customer_id#65, name#66, run_date#67, age#68], [dt#69]
  //         +- *(4) Sort [customer_id1#80 ASC NULLS FIRST], false, 0
  //            +- Exchange hashpartitioning(customer_id1#80, 5)
  //               +- *(3) Project [customer_id#82 AS customer_id1#80]
  //                  +- *(3) Filter isnotnull(customer_id#82)
  //                     +- HiveTableScan [customer_id#82], HiveTableRelation `edw`.`driver_set`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, [customer_id#82, name#83, run_date#84, age#85], [dt#86]
  def extractShuffleKeyAndHiveTable(plan: SparkPlanInfo): Unit = {
    if (plan.nodeName == "Sort") {
      val currentNode = plan
      val columns = regrexTool.getSort(currentNode.simpleString)
      val hiveTableScan = extractHiveTableScan(currentNode)
      plan.children.toList.map(p => extractShuffleKeyAndHiveTable(p))
      println("insert shuffle table columns in sort:")
      shuffleTableColumnRes = ShuffleTableColumn(hiveTableScan.head.table, columns) :: shuffleTableColumnRes
    } else if (plan.nodeName == "HashAggregate") {
      val currentNode = plan
      val columns = regrexTool.getHashAggregate(currentNode.simpleString)
      val hiveTableScan = extractHiveTableScan(currentNode)
      plan.children.toList.map(p => extractShuffleKeyAndHiveTable(p))
// for case like insertSql3.sql there are two  hive tables as children of HashAggregate, we don't consider this cas
//      --== Physical Plan ==
//        --Execute InsertIntoHiveTable InsertIntoHiveTable `edw`.`d_compress`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, Map(seq_num -> None), true, false, [run_date#255, seq_num#256]
//      --+- *(7) Project [run_date#49 AS run_date#255, seq_num#233 AS seq_num#256]
//      --   +- Exchange RoundRobinPartitioning(200)
//      --      +- *(6) HashAggregate(keys=[test_txn_customer_id#128, run_date#49], functions=[], output=[run_date#49, seq_num#233])
//      --         +- Exchange hashpartitioning(test_txn_customer_id#128, run_date#49, 5)
//      --            +- *(5) HashAggregate(keys=[test_txn_customer_id#128, run_date#49], functions=[], output=[test_txn_customer_id#128, run_date#49])
//      --               +- *(5) Project [test_txn_customer_id#128, run_date#49]
//      --                  +- *(5) SortMergeJoin [cast(test_txn_customer_id#128 as string)], [cast(txn_base_customer_id#125 as string)], Inner, (cast(cast(dedup_ts#108 as date) as string) <= run_date#49)
//      --                     :- *(2) Sort [cast(test_txn_customer_id#128 as string) ASC NULLS FIRST], false, 0
//      --                     :  +- Exchange hashpartitioning(cast(test_txn_customer_id#128 as string), 5)
//      --                     :     +- *(1) Project [if ((((upd_ts#83 = ) || isnull(upd_ts#83)) || (upd_ts#83 = #))) if ((((cre_ts#84 = ) || isnull(cre_ts#84)) || (cre_ts#84 = #))) dt#85 else cre_ts#84 else upd_ts#83 AS dedup_ts#108, customer_id#81 AS test_txn_customer_id#128]
//      --                     :        +- *(1) Filter (isnotnull(customer_id#81) && isnotnull(if ((((upd_ts#83 = ) || isnull(upd_ts#83)) || (upd_ts#83 = #))) if ((((cre_ts#84 = ) || isnull(cre_ts#84)) || (cre_ts#84 = #))) dt#85 else cre_ts#84 else upd_ts#83))
//      --                     :           +- HiveTableScan [cre_ts#84, customer_id#81, dt#85, upd_ts#83], HiveTableRelation `edw`.`test_txn_increment`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, [customer_id#81, name#82, upd_ts#83, cre_ts#84], [dt#85], [isnotnull(dt#85), dt#85 IN (2015-10-10), (dt#85 <= 2015-10-10)]
//      --                     +- *(4) Sort [cast(txn_base_customer_id#125 as string) ASC NULLS FIRST], false, 0
//      --                        +- Exchange hashpartitioning(cast(txn_base_customer_id#125 as string), 5)
//      --                           +- *(3) Project [run_date#49, customer_id#47 AS txn_base_customer_id#125]
//      --                              +- *(3) Filter (isnotnull(run_date#49) && isnotnull(customer_id#47))
//      --                                 +- HiveTableScan [customer_id#47, run_date#49], HiveTableRelation `edw`.`driver_set`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, [customer_id#47, name#48, run_date#49, age#50], [dt#51]

      val isOneHiveTable = onlyHasOneHiveTableScan(currentNode)
      if (isOneHiveTable == true) {
        shuffleTableColumnRes = ShuffleTableColumn(hiveTableScan.head.table, columns) :: shuffleTableColumnRes
      }
    } else if (plan.children.size != 0) {
      plan.children.map(p => extractShuffleKeyAndHiveTable(p))
    } else {
      null
    }
  }

  // case class Join(id: String, joinName: String, columnNames: List[String])
  case class Table(table: String, column: List[String])
  case class ShuffleTableColumn(table: String, columns: List[String]) {
    override def toString: String = {
      columns.mkString(",")
      s"table:${table}, columns: ${columns.mkString(",")}"
    }
  }
  def getShuffleTableColumns(): List[ShuffleTableColumn] = {
    shuffleTableColumnRes
  }

  def logMetricsForSqlWrapper(metrics: Map[String, Any], executionId: Long) = {
    val info = getJobInformation()
    val outputMetrics = info ++ metrics ++ Map(
      "jobNotification" -> "false")
    val json = scala.util.parsing.json.JSONObject(outputMetrics).toString()
    val esEnv = getEsEnv()
    super.logMetricsForInsertSql(
      esEnv,
      Util.getIndexIdForBLoggerSql(metricsWrapper.applicationId, metricsWrapper.cluster, executionId),
      json)
  }


  def logMetricsForSqlPlanWrapper(metrics: Map[String, Any], executionId: Long) = {

    val info = getJobInformation()
    val outputMetrics = info ++ metrics
    val json = scala.util.parsing.json.JSONObject(outputMetrics).toString()
    val esEnv = getEsEnv()
    super.logMetricsForSqlPlan(
      esEnv,
      Util.getIndexIdForBLoggerSql(metricsWrapper.applicationId, metricsWrapper.cluster, executionId),
      json)
  }

  private def getEsEnv(): EsEnv.Value = {
    // in bsi-core/pom.xml, it will move structured_logging.xml  to metadata/structured_logging.xml
    // so need load resource from metadata/structured_logging.xml
    val inputStream = getClass.getClassLoader.getResourceAsStream(s"metadata/structured_logging.xml")
    var exception: Throwable = null
    try {
      if (inputStream != null) {
        getEsConfigFromInputStream(inputStream)
      } else {
        throw new Exception("can not find structured_logging.xml in resource file")
      }
    } catch {
      case NonFatal(e) =>
        exception = e
        throw e
    } finally {
      closeAndAddSuppressed(exception, inputStream)
    }
  }

  private def getEsConfigFromInputStream(structuredInputStream: InputStream): EsEnv.Value = {
    val structuredBuffer = scala.io.Source
      .fromInputStream(structuredInputStream)
    val structuredConfig = structuredBuffer.getLines().toList
    if (structuredConfig.nonEmpty) {
      // search whether there exists localhost:9200 or not
      val urlNode: scala.xml.NodeSeq = scala.xml.XML.loadString(structuredConfig.mkString("\n")) \\ "url"
      val notFound = !urlNode.toList.exists(p => p.text.contains("localhost:9200"))
      if (notFound) {
        EsEnv.product
      } else {
        EsEnv.local
      }
    } else {
      throw new Exception("structured_logging.xml is empty!")
    }
  }

  private def closeAndAddSuppressed(e: Throwable, resource: AutoCloseable): Unit = {
    if (e != null) {
      try {
        resource.close()
      } catch {
        case NonFatal(suppressed) =>
          e.addSuppressed(suppressed)
      }
    } else {
      resource.close()
    }
  }

  def onApplicationStart(event: SparkListenerApplicationStart): Unit ={
    val SparkListenerApplicationStart(appName, appId,time,sparkUser, appAttemptId,  driverLogs)=event
    println(s"appName:${appName}, appId:${appId} time:${time} sparkUser:${sparkUser} driverLogs:${driverLogs}.")
    metricsWrapper.setApplicationId(appId.get)
    metricsWrapper.setCluster("horton")
    metricsWrapper.setAppName(appName)
    metricsWrapper.setUser(sparkUser)
  }

  private def getJobInformation() = {
      Map(
        "BSI_app" -> "BSI",
        "BSI_appName" -> metricsWrapper.appName,
        "BSI_appID" -> metricsWrapper.applicationId,
        "BSI_user" -> metricsWrapper.user,
        "BSI_logTime" -> new DateTime().toString,
        "BSI_logTimeUnix" -> new Date().getTime,
        "BSI_cluster" -> metricsWrapper.cluster
      )
  }

}
