/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.python

import scala.collection.JavaConverters._

import org.apache.spark.TaskContext
import org.apache.spark.api.python.{ChainedPythonFunctions, PythonEvalType}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Ascending, Attribute, AttributeSet, Expression, NamedExpression, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.{ClusteredDistribution, Distribution, Partitioning}
import org.apache.spark.sql.execution.{GroupedIterator, SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.arrow.{ArrowConverters, ArrowPayload}
import org.apache.spark.sql.types.{StructField, StructType}

case class FlatMapGroupsInPandasExec(
    grouping: Seq[Expression],
    func: Expression,
    override val output: Seq[Attribute],
    override val child: SparkPlan
) extends UnaryExecNode {

  val groupingAttributes: Seq[Attribute] = grouping.map {
    case ne: NamedExpression => ne.toAttribute
  }

  private val pandasFunction = func.asInstanceOf[PythonUDF].func

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def producedAttributes: AttributeSet = AttributeSet(output)

  override def requiredChildDistribution: Seq[Distribution] =
    ClusteredDistribution(groupingAttributes) :: Nil

  override def requiredChildOrdering: Seq[Seq[SortOrder]] =
    Seq(groupingAttributes.map(SortOrder(_, Ascending)))

  override protected def doExecute(): RDD[InternalRow] = {
    val inputRDD = child.execute()

    val bufferSize = inputRDD.conf.getInt("spark.buffer.size", 65536)
    val reuseWorker = inputRDD.conf.getBoolean("spark.python.worker.reuse", defaultValue = true)
    val chainedFunc = Seq(ChainedPythonFunctions(Seq(pandasFunction)))
    val argOffsets = Array(Array(0))

    val schemaOut = StructType.fromAttributes(output.drop(child.output.length).zipWithIndex
      .map { case (attr, i) => attr.withName(s"_$i") })

    inputRDD.mapPartitionsInternal { iter =>
      val grouped = GroupedIterator(iter, groupingAttributes, child.output)
      val context = TaskContext.get()

      val columnarBatchIter = new ArrowPythonRunner(
        chainedFunc, bufferSize, reuseWorker,
        PythonEvalType.SQL_PANDAS_UDF, argOffsets, child.schema)
        .compute(grouped.map(_._2), context.partitionId(), context)


      new Iterator[InternalRow] {
        private var currentIter = if (columnarBatchIter.hasNext) {
          val batch = columnarBatchIter.next()
          assert(schemaOut.equals(batch.schema),
            s"Invalid schema from pandas_udf: expected $schemaOut, got ${batch.schema}")
          batch.rowIterator.asScala
        } else {
          Iterator.empty
        }

        override def hasNext: Boolean = currentIter.hasNext || {
          if (columnarBatchIter.hasNext) {
            currentIter = columnarBatchIter.next().rowIterator.asScala
            hasNext
          } else {
            false
          }
        }

        override def next(): InternalRow = currentIter.next()
      }
    }
  }
}
