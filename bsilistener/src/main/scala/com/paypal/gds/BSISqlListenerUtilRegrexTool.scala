package com.paypal.gds

import com.paypal.gds.BSISqlListenerUtil2.Table


class BSISqlListenerUtilRegrexTool {

  //extract `edw`.`driver_set` from desc(HiveTableScan [customer_id#88], HiveTableRelation `edw`.`driver_set`, org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, [customer_id#88, name#89, run_date#90, age#91], [dt#92])
  def getHiveTableAndColumn(desc: String): Table = {
    val pattern = """HiveTableScan \[(.*)\], HiveTableRelation (.*), org.apache.hadoop.hive""".r
    val allMatches = pattern.findFirstMatchIn(desc)
    if (allMatches.nonEmpty) {
      val columnNames: List[String] = allMatches.get.group(1).split(",").map(p=>p.split("#")(0).trim()).toList
      val table: String = allMatches.get.group(2)
      Table(table, columnNames)
    } else {
      null
    }
  }
//
//  //(7) SortMergeJoin [customer_id#71], [customer_id1#86], Inner
//  def getJoinCondition(desc: String): Join = {
//    val pattern = """\((.*)\) (.*)Join \[(.*)\], \[(.*)\], """.r
//    val allMatches = pattern.findFirstMatchIn(desc)
//    if (allMatches.nonEmpty) {
//      val joinId: String = allMatches.get.group(1)
//      val joinName: String = allMatches.get.group(2)
//      val column1: String = allMatches.get.group(3)
//      val column2: String = allMatches.get.group(4)
//      Join(joinId, joinName, List(column1,column2))
//    } else {
//      null
//    }
//  }

  def getExchangeCondition(desc:String): List[String] = {
    val pattern = """Exchange hashpartitioning\((.*),.*\)""".r
    val allMatches = pattern.findFirstMatchIn(desc)
    if (allMatches.nonEmpty) {
      val columns: List[String] = allMatches.get.group(1).split(",").map(v=>v.split("#")(0).trim()).toList
      columns
    }else{
      null
    }
  }

  //extract cust_id from "Sort [customer_id#65 ASC NULLS FIRST]"
  def getSort(desc:String):List[String] = {
    val pattern = """Sort \[cast?\(?(.*)\]""".r
    val allMatches = pattern.findFirstMatchIn(desc)
    if (allMatches.nonEmpty) {
      val columns: List[String] = allMatches.get.group(1).split(",").map(v=>v.split("#")(0).trim()).toList
      println("getSort columns:"+columns.mkString(","))
      columns
    }else{
      null
    }
  }

  //extract cust_id from  "(1) HashAggregate(keys=[customer_id#62], functions=[partial_count(1)], output=[customer_id#62, count#130L])"
  def getHashAggregate(desc:String):List[String] = {
    val pattern = """HashAggregate\(keys=\[(.*)\], functions.*\)""".r
    val allMatches = pattern.findFirstMatchIn(desc)
    if (allMatches.nonEmpty) {
      val columns: List[String] = allMatches.get.group(1).split(",").map(v=>v.split("#")(0).trim()).toList
      println("getHashAggregate columns:"+columns.mkString(","))
      columns
    }else{
      null
    }
  }

}
