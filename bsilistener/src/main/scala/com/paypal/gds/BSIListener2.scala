package com.paypal.gds

import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationStart, SparkListenerEvent}
import org.apache.spark.sql.execution.ui.{SparkListenerSQLExecutionStart, _}

class BSISparkListener() extends SparkListener {

  override def onOtherEvent(event: SparkListenerEvent): Unit = {
    event match {
      case e: SparkListenerApplicationStart => BSISqlListenerUtil2.onApplicationStart(e)
      case e: SparkListenerSQLExecutionStart => BSISqlListenerUtil2.onExecutionStart(e)
      case e: SparkListenerSQLExecutionEnd => BSISqlListenerUtil2.onExecutionEnd(e)
      case _ => // Ignore
    }

  }


}
