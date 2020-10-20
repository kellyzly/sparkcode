package com.paypal.gds


import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.rand
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions._

class InsertSqlTest extends FunSuite with BeforeAndAfter with DataFrameSuiteBase {

  def getData(): DataFrame = {
    val data = Seq(
      ("Michael ", 1.00, "", "40288", "M", 4000),
      ("James ", 1.00, "Williams", "42114", "M", 4000),
      ("James ", 1.00, "Williams", "42114", "M", 4000),
      ("James ", 1.00, "Williams", "42114", "M", 4000),
      ("James ", 1.00, "Williams", "42114", "M", 4000),
      ("James ", 1.00, "Jones", "39192", "F", 4000),
      ("Jen", 1.00, "Brown", "", "F", -1)
    )


    val columns = Seq("firstname", "target_id", "lastname", "dob", "gender", "salary")
    import spark.sqlContext.implicits._
    val df = data.toDF(columns: _*)
    df
  }

  def getSimpleData():DataFrame = {
    val simpleData = Seq(
      ("James", "Sales", 3000),
      ("Michael", "Sales", 4600),
      ("Robert", "Sales", 4100),
      ("Maria", "Finance", 3000),
      ("James", "Sales", 3000),
      ("Scott", "Finance", 3300),
      ("Jen", "Finance", 3900),
      ("Jeff", "Marketing", 3000),
      ("Kumar", "Marketing", 2000),
      ("Saif", "Sales", 4100)
    )
    import spark.sqlContext.implicits._
    val df = simpleData.toDF("employee_name", "department", "salary")
    df
  }

  test("test skewed remove") {
    val df = getData()
    val df2 =
      df.groupBy(col("firstname"))
        .count()
        .withColumnRenamed("count", "target_id_count")
    val df3 = df2.filter(df2("target_id_count") > 2)
    df3.printSchema()
    df3.show()
    val newDf = df.join(df3, df3("firstname") === df("firstname"), "left_anti")
    newDf.show()
  }

  test("add new column for random_id") {
    val df = getData()
    val df4 = df.withColumn("random_id", rand(10))
    df4.show()

//    +---------+---------+--------+-----+------+------+-------------------+
//    |firstname|target_id|lastname|  dob|gender|salary|          random_id|
//    +---------+---------+--------+-----+------+------+-------------------+
//    | Michael |      1.0|        |40288|     M|  4000|0.41371264720975787|
//      |   James |      1.0|Williams|42114|     M|  4000| 0.7311719281896606|
//      |   James |      1.0|Williams|42114|     M|  4000| 0.9031701155118229|
//      |   James |      1.0|Williams|42114|     M|  4000|0.09430205113458567|
//      |   James |      1.0|Williams|42114|     M|  4000|0.38340505276222947|
//      |   James |      1.0|   Jones|39192|     F|  4000| 0.5569246135523511|
//      |      Jen|      1.0|   Brown|     |     F|    -1| 0.4977441406613893|
//      +---------+---------+--------+-----+------+------+-------------------+
  }


  test("percent_rank") {
    val windowSpec = Window.partitionBy("department").orderBy("salary")
    //percent_rank
    getSimpleData().withColumn("percent_rank", percent_rank().over(windowSpec))
      .show()
//    +-------------+----------+------+------------+
//    |employee_name|department|salary|percent_rank|
//    +-------------+----------+------+------------+
//    |        James|     Sales|  3000|         0.0|
//      |        James|     Sales|  3000|         0.0|
//      |       Robert|     Sales|  4100|         0.5|
//      |         Saif|     Sales|  4100|         0.5|
//      |      Michael|     Sales|  4600|         1.0|
//      |        Maria|   Finance|  3000|         0.0|
//      |        Scott|   Finance|  3300|         0.5|
//      |          Jen|   Finance|  3900|         1.0|
//      |        Kumar| Marketing|  2000|         0.0|
//      |         Jeff| Marketing|  3000|         1.0|
//      +-------------+----------+------+------------+

  }

  test("test row_number"){
    val df = getData()
    val df2 =
      df.groupBy(col("firstname"))
        .count()
        .withColumnRenamed("count", "appear_count")
    val windowSpec = Window.partitionBy("firstname").orderBy("firstname")
    val df4 = df.withColumn("rank", row_number().over(windowSpec)).filter(col("rank") === 1)
    df4.show()

//    +---------+---------+--------+-----+------+------+----+
//    |firstname|target_id|lastname|  dob|gender|salary|rank|
//    +---------+---------+--------+-----+------+------+----+
//    |      Jen|      1.0|   Brown|     |     F|    -1|   1|
//      | Michael |      1.0|        |40288|     M|  4000|   1|
//      |   James |      1.0|Williams|42114|     M|  4000|   1|
//      +---------+---------+--------+-----+------+------+----+

  }


}
