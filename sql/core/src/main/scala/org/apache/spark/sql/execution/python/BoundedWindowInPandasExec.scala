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

import java.io.File

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.api.python.{ChainedPythonFunctions, PythonEvalType}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.{AllTuples, ClusteredDistribution, Distribution, Partitioning}
import org.apache.spark.sql.execution.{ExternalAppendOnlyUnsafeRowArray, SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.arrow.ArrowUtils
import org.apache.spark.sql.execution.window._
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

case class BoundedWindowInPandasExec(
    windowExpression: Seq[NamedExpression],
    partitionSpec: Seq[Expression],
    orderSpec: Seq[SortOrder],
    child: SparkPlan) extends UnaryExecNode {

  override def output: Seq[Attribute] =
    child.output ++ windowExpression.map(_.toAttribute)

  override def requiredChildDistribution: Seq[Distribution] = {
    if (partitionSpec.isEmpty) {
      // Only show warning when the number of bytes is larger than 100 MB?
      logWarning("No Partition Defined for Window operation! Moving all data to a single "
        + "partition, this can cause serious performance degradation.")
      AllTuples :: Nil
    } else {
      ClusteredDistribution(partitionSpec) :: Nil
    }
  }

  override def requiredChildOrdering: Seq[Seq[SortOrder]] =
    Seq(partitionSpec.map(SortOrder(_, Ascending)) ++ orderSpec)

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def outputPartitioning: Partitioning = child.outputPartitioning

  private def collectFunctions(udf: PythonUDF): (ChainedPythonFunctions, Seq[Expression]) = {
    udf.children match {
      case Seq(u: PythonUDF) =>
        val (chained, children) = collectFunctions(u)
        (ChainedPythonFunctions(chained.funcs ++ Seq(udf.func)), children)
      case children =>
        // There should not be any other UDFs, or the children can't be evaluated directly.
        assert(children.forall(_.find(_.isInstanceOf[PythonUDF]).isEmpty))
        (ChainedPythonFunctions(Seq(udf.func)), udf.children)
    }
  }

  /**
   * Create the resulting projection.
   *
   * This method uses Code Generation. It can only be used on the executor side.
   *
   * @param expressions unbound ordered function expressions.
   * @return the final resulting projection.
   */
  private[this] def createResultProjection(expressions: Seq[Expression]): UnsafeProjection = {
    val references = expressions.zipWithIndex.map { case (e, i) =>
      // Results of window expressions will be on the right side of child's output
      BoundReference(child.output.size + i, e.dataType, e.nullable)
    }
    val unboundToRefMap = expressions.zip(references).toMap
    val patchedWindowExpression = windowExpression.map(_.transform(unboundToRefMap))
    UnsafeProjection.create(
      child.output ++ patchedWindowExpression,
      child.output)
  }

  /**
   * Create a bound ordering object for a given frame type and offset. A bound ordering object is
   * used to determine which input row lies within the frame boundaries of an output row.
   *
   * This method uses Code Generation. It can only be used on the executor side.
   *
   * @param frame to evaluate. This can either be a Row or Range frame.
   * @param bound with respect to the row.
   * @param timeZone the session local timezone for time related calculations.
   * @return a bound ordering object.
   */
  private[this] def createBoundOrdering(
      frame: FrameType, bound: Expression, timeZone: String): BoundOrdering = {
    (frame, bound) match {
      case (RowFrame, CurrentRow) =>
        RowBoundOrdering(0)

      case (RowFrame, IntegerLiteral(offset)) =>
        RowBoundOrdering(offset)

      case (RangeFrame, CurrentRow) =>
        val ordering = newOrdering(orderSpec, child.output)
        RangeBoundOrdering(ordering, IdentityProjection, IdentityProjection)

      case (RangeFrame, offset: Expression) if orderSpec.size == 1 =>
        // Use only the first order expression when the offset is non-null.
        val sortExpr = orderSpec.head
        val expr = sortExpr.child

        // Create the projection which returns the current 'value'.
        val current = newMutableProjection(expr :: Nil, child.output)

        // Flip the sign of the offset when processing the order is descending
        val boundOffset = sortExpr.direction match {
          case Descending => UnaryMinus(offset)
          case Ascending => offset
        }

        // Create the projection which returns the current 'value' modified by adding the offset.
        val boundExpr = (expr.dataType, boundOffset.dataType) match {
          case (DateType, IntegerType) => DateAdd(expr, boundOffset)
          case (TimestampType, CalendarIntervalType) =>
            TimeAdd(expr, boundOffset, Some(timeZone))
          case (a, b) if a== b => Add(expr, boundOffset)
        }
        val bound = newMutableProjection(boundExpr :: Nil, child.output)

        // Construct the ordering. This is used to compare the result of current value projection
        // to the result of bound value projection. This is done manually because we want to use
        // Code Generation (if it is enabled).
        val boundSortExprs = sortExpr.copy(BoundReference(0, expr.dataType, expr.nullable)) :: Nil
        val ordering = newOrdering(boundSortExprs, Nil)
        RangeBoundOrdering(ordering, current, bound)

      case (RangeFrame, _) =>
        sys.error("Non-Zero range offsets are not supported for windows " +
          "with multiple order expressions.")
    }
  }

  /**
   * Collection containing an entry for each window frame to process. Each entry contains a frame's
   * [[WindowExpression]]s and factory function for the WindowFrameFunction.
   */
  private[this] lazy val windowFrameExpressionFactoryPairs = {
    type FrameKey = (String, FrameType, Expression, Expression)
    type ExpressionBuffer = mutable.Buffer[Expression]
    val framedFunctions = mutable.Map.empty[FrameKey, (ExpressionBuffer, ExpressionBuffer)]

    // Add a function and its function to the map for a given frame.
    def collect(tpe: String, fr: SpecifiedWindowFrame, e: Expression, fn: Expression): Unit = {
      val key = (tpe, fr.frameType, fr.lower, fr.upper)
      val (es, fns) = framedFunctions.getOrElseUpdate(
        key, (ArrayBuffer.empty[Expression], ArrayBuffer.empty[Expression]))
      es += e
      fns += fn
    }

    // Collect all valid window functions and group them by their frame.
    windowExpression.foreach { x =>
      x.foreach {
        case e @ WindowExpression(function, spec) =>
          val frame = spec.frameSpecification.asInstanceOf[SpecifiedWindowFrame]
          function match {
            case f: PythonUDF => collect("AGGREGATE", frame, e, f)
            case f => sys.error(s"Unsupported window function: $f")
          }
        case _ =>
      }
    }

    // Map the groups to a (unbound) expression and frame factory pair.
    var numExpressions = 0
    val timeZone = conf.sessionLocalTimeZone
    framedFunctions.toSeq.map {
      case (key, (expressions, functionSeq)) =>
        val ordinal = numExpressions
        val functions = functionSeq.toArray

        // Construct an aggregate processor if we need one.
        def processor: AggregateProcessor = null

        // Create the factory
        val factory = key match {
          // Offset Frame
          case ("OFFSET", _, IntegerLiteral(offset), _) =>
            target: InternalRow =>
              new OffsetWindowFunctionFrame(
                target,
                ordinal,
                // OFFSET frame functions are guaranteed be OffsetWindowFunctions.
                functions.map(_.asInstanceOf[OffsetWindowFunction]),
                child.output,
                (expressions, schema) =>
                  newMutableProjection(expressions, schema, subexpressionEliminationEnabled),
                offset)

          // Entire Partition Frame.
          case ("AGGREGATE", _, UnboundedPreceding, UnboundedFollowing) =>
            target: InternalRow => {
              new UnboundedWindowFunctionFrame(target, processor)
            }

          // Growing Frame.
          case ("AGGREGATE", frameType, UnboundedPreceding, upper) =>
            target: InternalRow => {
              new UnboundedPrecedingWindowFunctionFrame(
                target,
                processor,
                createBoundOrdering(frameType, upper, timeZone))
            }

          // Shrinking Frame.
          case ("AGGREGATE", frameType, lower, UnboundedFollowing) =>
            target: InternalRow => {
              new UnboundedFollowingWindowFunctionFrame(
                target,
                processor,
                createBoundOrdering(frameType, lower, timeZone))
            }

          // Moving Frame.
          case ("AGGREGATE", frameType, lower, upper) =>
            target: InternalRow => {
              new SlidingWindowFunctionFrame(
                target,
                processor,
                createBoundOrdering(frameType, lower, timeZone),
                createBoundOrdering(frameType, upper, timeZone))
            }
        }

        // Keep track of the number of expressions. This is a side-effect in a map...
        numExpressions += expressions.size

        // Create the Frame Expression - Factory pair.
        (expressions, factory)
    }
  }

  protected override def doExecute(): RDD[InternalRow] = {
    val inputRDD = child.execute()

    // Unwrap the expressions and factories from the map.
    val expressions = windowFrameExpressionFactoryPairs.flatMap(_._1)

    val factories = windowFrameExpressionFactoryPairs.map(_._2).toArray
    val inMemoryThreshold = sqlContext.conf.windowExecBufferInMemoryThreshold
    val spillThreshold = sqlContext.conf.windowExecBufferSpillThreshold

    val bufferSize = inputRDD.conf.getInt("spark.buffer.size", 65536)
    val reuseWorker = inputRDD.conf.getBoolean("spark.python.worker.reuse", defaultValue = true)
    val sessionLocalTimeZone = conf.sessionLocalTimeZone
    val pythonRunnerConf = ArrowUtils.getPythonRunnerConfMap(conf)

    // Extract window expressions and window functions
    val windowExpressions = windowExpression.flatMap(_.collect { case e: WindowExpression => e })
    val udfExpressions = windowExpressions.map(_.windowFunction.asInstanceOf[PythonUDF])

    println("expressions: " + expressions)
    println("windowExpression: " + windowExpressions)
    println("udfExpressions: " + udfExpressions)

    val (pyFuncs, inputs) = udfExpressions.map(collectFunctions).unzip

    // Filter child output attributes down to only those that are UDF inputs.
    // Also eliminate duplicate UDF inputs.
    val allInputs = new ArrayBuffer[Expression]
    val dataTypes = new ArrayBuffer[DataType]
    val argOffsets = inputs.map { input =>
      input.map { e =>
        if (allInputs.exists(_.semanticEquals(e))) {
          allInputs.indexWhere(_.semanticEquals(e))
        } else {
          allInputs += e
          dataTypes += e.dataType
          allInputs.length - 1
        }
      }.toArray
    }.toArray

    // Add index columns and adjust argOffsets by 2 (begin and end indices)
    // TODO: Handle multiple window frames
    // TODO: Handle short circuit code path for unbounded window
    allInputs.prependAll(Seq(
      BoundReference(0, IntegerType, true),
      BoundReference(1, IntegerType, true)))
    dataTypes.prependAll(
      Seq(IntegerType, IntegerType))
    for (i <- argOffsets.indices) {
      argOffsets(i) = Array(0, 1) ++ argOffsets(i).map(_ + 2)
    }

    println("allInputs: " + allInputs)
    println("argOffsets: " + argOffsets.deep)


    // Schema of input rows to the python runner
    val windowInputSchema = StructType(dataTypes.zipWithIndex.map { case (dt, i) =>
      StructField(s"_$i", dt)
    })

    println("window input schema: " + windowInputSchema)

    // Start processing.
    child.execute().mapPartitions { iter =>
      val context = TaskContext.get()

      // Get all relevant projections.
      val resultProj = createResultProjection(expressions)
      val inputSchemaWithIndex: Seq[Attribute] = Seq(
        AttributeReference("begin_index", IntegerType)(),
        AttributeReference("end_index", IntegerType)()
      ) ++ child.output
      val pythonInputProj = UnsafeProjection.create(
        allInputs,
        inputSchemaWithIndex)
      val grouping = UnsafeProjection.create(partitionSpec, child.output)

      // The queue used to buffer input rows so we can drain it to
      // combine input with output from Python.
      val queue = HybridRowQueue(context.taskMemoryManager(),
        new File(Utils.getLocalDir(SparkEnv.get.conf)), child.output.length)

      val stream = iter.map { row =>
        queue.add(row.asInstanceOf[UnsafeRow])
        row
      }

      val pythonInput = new Iterator[Iterator[UnsafeRow]] {

        // Manage the stream and the grouping.
        var nextRow: UnsafeRow = null
        var nextGroup: UnsafeRow = null
        var nextRowAvailable: Boolean = false
        private[this] def fetchNextRow() {
          nextRowAvailable = stream.hasNext
          if (nextRowAvailable) {
            nextRow = stream.next().asInstanceOf[UnsafeRow]
            nextGroup = grouping(nextRow)
          } else {
            nextRow = null
            nextGroup = null
          }
        }
        fetchNextRow()

        // Manage the current partition.
        val buffer: ExternalAppendOnlyUnsafeRowArray =
          new ExternalAppendOnlyUnsafeRowArray(inMemoryThreshold, spillThreshold)
        var bufferIterator: Iterator[UnsafeRow] = _

        val indexRow = new SpecificInternalRow(Seq(IntegerType, IntegerType))

        val frames = factories.map(_(indexRow))
        val numFrames = frames.length
        private[this] def fetchNextPartition() {
          // Collect all the rows in the current partition.
          // Before we start to fetch new input rows, make a copy of nextGroup.
          val currentGroup = nextGroup.copy()

          // clear last partition
          buffer.clear()

          while (nextRowAvailable && nextGroup == currentGroup) {
            buffer.add(nextRow)
            fetchNextRow()
          }

          // Setup the frames.
          var i = 0
          while (i < numFrames) {
            frames(i).prepare(buffer)
            i += 1
          }

          // Setup iteration
          rowIndex = 0
          bufferIterator = buffer.generateIterator()
        }

        // Iteration
        var rowIndex = 0

        override final def hasNext: Boolean =
          (bufferIterator != null && bufferIterator.hasNext) || nextRowAvailable

        override final def next(): Iterator[UnsafeRow] = {
          // Load the next partition if we need to.
          if ((bufferIterator == null || !bufferIterator.hasNext) && nextRowAvailable) {
            fetchNextPartition()
          }

          val join = new JoinedRow

          bufferIterator.zipWithIndex.map {
            case (current, rowIndex) =>
              var i = 0
              while (i < numFrames) {
                frames(i).write(rowIndex, current)
                indexRow.setInt(2 * i + 0, frames(i).currentLowIndex())
                indexRow.setInt(2 * i + 1, frames(i).currentHighIndex())
                i += 1
              }

              val r = pythonInputProj(join(indexRow, current))
              // println(r.numFields())
              // println(r.getInt(0))
              // println(r.getInt(1))
              // println(r.getDouble(2))
              r
          }
        }
      }

      val windowFunctionResult = new ArrowPythonRunner(
        pyFuncs,
        bufferSize,
        reuseWorker,
        PythonEvalType.SQL_BOUNDED_WINDOW_AGG_PANDAS_UDF,
        argOffsets,
        windowInputSchema,
        sessionLocalTimeZone,
        pythonRunnerConf).compute(pythonInput, context.partitionId(), context)

      val joined = new JoinedRow

      windowFunctionResult.flatMap(_.rowIterator.asScala).map { windowOutput =>
        val leftRow = queue.remove()
        val joinedRow = joined(leftRow, windowOutput)
        resultProj(joinedRow)
      }
    }
  }

}
