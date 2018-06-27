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

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.api.python.PythonEvalType
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{FilterExec, ProjectExec, SparkPlan}


/**
 * Extracts all the Python UDFs in logical aggregate, which depends on aggregate expression or
 * grouping key, or doesn't depend on any above expressions, evaluate them after aggregate.
 */
object ExtractPythonUDFFromAggregate extends Rule[LogicalPlan] {

  /**
   * Returns whether the expression could only be evaluated within aggregate.
   */
  private def belongAggregate(e: Expression, agg: Aggregate): Boolean = {
    e.isInstanceOf[AggregateExpression] ||
      PythonUDF.isGroupedAggPandasUDF(e) ||
      agg.groupingExpressions.exists(_.semanticEquals(e))
  }

  private def hasPythonUdfOverAggregate(expr: Expression, agg: Aggregate): Boolean = {
    expr.find {
      e => PythonUDF.isScalarPythonUDF(e) &&
        (e.references.isEmpty || e.find(belongAggregate(_, agg)).isDefined)
    }.isDefined
  }

  private def extract(agg: Aggregate): LogicalPlan = {
    val projList = new ArrayBuffer[NamedExpression]()
    val aggExpr = new ArrayBuffer[NamedExpression]()
    agg.aggregateExpressions.foreach { expr =>
      if (hasPythonUdfOverAggregate(expr, agg)) {
        // Python UDF can only be evaluated after aggregate
        val newE = expr transformDown {
          case e: Expression if belongAggregate(e, agg) =>
            val alias = e match {
              case a: NamedExpression => a
              case o => Alias(e, "agg")()
            }
            aggExpr += alias
            alias.toAttribute
        }
        projList += newE.asInstanceOf[NamedExpression]
      } else {
        aggExpr += expr
        projList += expr.toAttribute
      }
    }
    // There is no Python UDF over aggregate expression
    Project(projList, agg.copy(aggregateExpressions = aggExpr))
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
    case agg: Aggregate if agg.aggregateExpressions.exists(hasPythonUdfOverAggregate(_, agg)) =>
      extract(agg)
  }
}


/**
 * Extracts PythonUDFs from operators, rewriting the query plan so that the UDF can be evaluated
 * alone in a batch.
 *
 * Only extracts the PythonUDFs that could be evaluated in Python (the single child is PythonUDFs
 * or all the children could be evaluated in JVM).
 *
 * This has the limitation that the input to the Python UDF is not allowed include attributes from
 * multiple child operators.
 */
object ExtractPythonUDFs extends Rule[SparkPlan] with PredicateHelper {

  private def hasScalarPythonUDF(e: Expression): Boolean = {
    e.find(PythonUDF.isScalarPythonUDF).isDefined
  }

  private def canEvaluateInPython(e: PythonUDF, evalType: Int): Boolean = {
    if (e.evalType != evalType) {
      false
    } else {
      e.children match {
        // single PythonUDF child could be chained and evaluated in Python
        case Seq(u: PythonUDF) => canEvaluateInPython(u, evalType)
        // Python UDF can't be evaluated directly in JVM
        case children => !children.exists(hasScalarPythonUDF)
      }
    }
  }

  private def collectEvaluableUDF(expr: Expression, evalType: Int): Seq[PythonUDF] = expr match {
    case udf: PythonUDF if PythonUDF.isScalarPythonUDF(udf) && canEvaluateInPython(udf, evalType) =>
      Seq(udf)
    case e => e.children.flatMap(collectEvaluableUDF(_, evalType))
  }

  /**
   * Collect evaluable UDFs from the current node.
   *
   * This function collects Python UDFs or Scalar Python UDFs from expressions of the input node,
   * and returns a list of UDFs of the same eval type.
   *
   * If expressions contain both UDFs eval types, this function will only return Python UDFs.
   *
   * The caller should call this function multiple times until all evaluable UDFs are collected.
   */
  private def collectEvaluableUDFs(plan: SparkPlan): Seq[PythonUDF] = {
    val pythonUDFs =
      plan.expressions.flatMap(collectEvaluableUDF(_, PythonEvalType.SQL_BATCHED_UDF))

    if (pythonUDFs.isEmpty) {
      plan.expressions.flatMap(collectEvaluableUDF(_, PythonEvalType.SQL_SCALAR_PANDAS_UDF))
    } else {
      pythonUDFs
    }
  }

  def apply(plan: SparkPlan): SparkPlan = plan transformUp {
    case plan: SparkPlan => extract(plan)
  }

  /**
   * Extract all the PythonUDFs from the current operator and evaluate them before the operator.
   */
  private def extract(plan: SparkPlan): SparkPlan = {
    val udfs = collectEvaluableUDFs(plan)
      // ignore the PythonUDF that come from second/third aggregate, which is not used
      .filter(udf => udf.references.subsetOf(plan.inputSet))

    if (udfs.isEmpty) {
      // If there aren't any, we are done.
      plan
    } else {
      val inputsForPlan = plan.references ++ plan.outputSet
      val prunedChildren = plan.children.map { child =>
        val allNeededOutput = inputsForPlan.intersect(child.outputSet).toSeq
        if (allNeededOutput.length != child.output.length) {
          ProjectExec(allNeededOutput, child)
        } else {
          child
        }
      }
      val planWithNewChildren = plan.withNewChildren(prunedChildren)

      val attributeMap = mutable.HashMap[PythonUDF, Expression]()
      val splitFilter = trySplitFilter(planWithNewChildren)
      // Rewrite the child that has the input required for the UDF
      val newChildren = splitFilter.children.map { child =>
        // Pick the UDF we are going to evaluate
        val validUdfs = udfs.filter { udf =>
          // Check to make sure that the UDF can be evaluated with only the input of this child.
          udf.references.subsetOf(child.outputSet)
        }
        if (validUdfs.nonEmpty) {
          require(
            validUdfs.forall(PythonUDF.isScalarPythonUDF),
            "Can only extract scalar vectorized udf or sql batch udf")

          val resultAttrs = udfs.zipWithIndex.map { case (u, i) =>
            AttributeReference(s"pythonUDF$i", u.dataType)()
          }

          val evaluation = validUdfs.partition(
            _.evalType == PythonEvalType.SQL_SCALAR_PANDAS_UDF
          ) match {
            case (vectorizedUdfs, plainUdfs) if plainUdfs.isEmpty =>
              ArrowEvalPythonExec(vectorizedUdfs, child.output ++ resultAttrs, child)
            case (vectorizedUdfs, plainUdfs) if vectorizedUdfs.isEmpty =>
              BatchEvalPythonExec(plainUdfs, child.output ++ resultAttrs, child)
            case (vectorizedUdfs, plainUdfs) =>
              throw new AnalysisException(
                "Mixed Python and Scalar Pandas UDFs are not expected here")
          }

          attributeMap ++= validUdfs.zip(resultAttrs)
          evaluation
        } else {
          child
        }
      }
      // Other cases are disallowed as they are ambiguous or would require a cartesian
      // product.
      udfs.filterNot(attributeMap.contains).foreach { udf =>
        sys.error(s"Invalid PythonUDF $udf, requires attributes from more than one child.")
      }

      val rewritten = splitFilter.withNewChildren(newChildren).transformExpressions {
        case p: PythonUDF if attributeMap.contains(p) =>
          attributeMap(p)
      }

      // extract remaining python UDFs recursively
      val newPlan = extract(rewritten)

      if (newPlan.output != plan.output) {
        // Trim away the new UDF value if it was only used for filtering or something.
        ProjectExec(plan.output, newPlan)
      } else {
        newPlan
      }
    }
  }

  // Split the original FilterExec to two FilterExecs. Only push down the first few predicates
  // that are all deterministic.
  private def trySplitFilter(plan: SparkPlan): SparkPlan = {
    plan match {
      case filter: FilterExec =>
        val (candidates, nonDeterministic) =
          splitConjunctivePredicates(filter.condition).partition(_.deterministic)
        val (pushDown, rest) = candidates.partition(!hasScalarPythonUDF(_))
        if (pushDown.nonEmpty) {
          val newChild = FilterExec(pushDown.reduceLeft(And), filter.child)
          FilterExec((rest ++ nonDeterministic).reduceLeft(And), newChild)
        } else {
          filter
        }
      case o => o
    }
  }
}
