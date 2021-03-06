package org.apache.spark.scheduler

import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.ConcurrentHashMap
import  java.util.TreeMap

import org.apache.spark.{SparkEnv, SparkConf, Logging}
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.memory.TaskMemoryManager

import scala.collection.mutable.ArrayBuffer

/**
 * This Scheduler is based on Memory-Usage-Rate, and running on executor.
 */
class MURScheduler(
     executorId: String, conf: SparkConf, env: SparkEnv) extends Serializable with Logging {

  // the second value of runningTasks save the taskType(shuffle, result) of this task
  private val runningTasks = new ConcurrentHashMap[Long, Int]
  private val runningTasksMemoryManage = new ConcurrentHashMap[Long, TaskMemoryManager]
  private val finishedTasks = new ArrayBuffer[Long]()

  private var reStartIndex = 0
  private var stopIndex = 0
  private val mursStopTasks = new ConcurrentHashMap[Int, Long]()

  private val runningTasksSampleFlag = new ConcurrentHashMap[Long, Boolean]

  val taskMURSample = new MURSchedulerSample

  private val isResultTask = new ConcurrentHashMap[Long, Boolean]()
  private val isHadoopTask = new ConcurrentHashMap[Long, Boolean]()

  private val multiTasks = conf.getDouble("spark.murs.multiTasks", 1.0)

  /**
   * Show sample message of one task
   *
   */

  def showMessage(taskId: Long): Unit = {

    if( (! runningTasks.containsKey(taskId)) || (taskId % 4 != 0) )
      return

    val bytesRead_input = taskMURSample.getBytesReadInput(taskId)
    val bytesRead_shuffle = taskMURSample.getBytesReadShuffle(taskId)

    val totalRecords = taskMURSample.getTotalRecords(taskId)
    val recordsRead_input = taskMURSample.getRecordsReadInput(taskId)
    val recordsRead_shuffle = taskMURSample.getRecordsReadShuffle(taskId)
    val recordsRead_cache = taskMURSample.getRecordsReadCache(taskId)
    val recordsRead_total = taskMURSample.getRecordsReadCogroup(taskId)

    val bytesOutput = taskMURSample.getBytesOutput(taskId)
    val bytesShuffleWrite = taskMURSample.getBytesShuffleWrite(taskId)

    val shuffleMemoryUsage = taskMURSample.getShuffleMemoryUsage(taskId)
    val cacheMemoryUsage = taskMURSample.getCacheMemoryUsage(taskId)
    logInfo(s"Task $taskId has bytes read $bytesRead_input/$bytesRead_shuffle, " +
      s"records $totalRecords, read records $recordsRead_input/$recordsRead_shuffle/$recordsRead_cache/$recordsRead_total, " +
      s"bytes output $bytesOutput, shuffle write $bytesShuffleWrite, " +
      s"memory usage $shuffleMemoryUsage/$cacheMemoryUsage.")
  }

  def registerTask(taskId: Long, taskMemoryManager: TaskMemoryManager): Unit = {
    runningTasks.put(taskId, 0)
    runningTasksMemoryManage.put(taskId, taskMemoryManager)
    runningTasksSampleFlag.put(taskId, false)
    taskMURSample.registerTask(taskId)
    isResultTask.put(taskId, false)
    isHadoopTask.put(taskId, false)
  }

  def removeFinishedTask(taskId: Long): Unit = {
    finishedTasks.append(taskId)
    removeStopTask()
    runningTasks.remove(taskId)
    runningTasksSampleFlag.remove(taskId)
    taskMURSample.removeFinishedTask(taskId)
    isResultTask.remove(taskId)
    isHadoopTask.remove(taskId)
  }

  /**
   *  1. Executor use [updateAllSampleFlag] to update the sample flag of all running tasks.
   *  2. While the running tasks use [getSampleFlag] to decide whether they should do sample
   *  and report the results.
   *  3. After they report the results, they use [updateSingleTaskSampleFlag] to tell
   *  the Scheduler they have finish themselves.
   *
   */

  def getSampleFlag(taskId: Long): Boolean = {
    runningTasksSampleFlag.get(taskId)
  }

  def updateAllSampleFlag(): Unit = {
    val keyIter = runningTasksSampleFlag.keySet.iterator()
    while(keyIter.hasNext)
      runningTasksSampleFlag.replace(keyIter.next(), true)
  }

  def updateSingleTaskSampleFlag(taskId: Long): Unit = {
    runningTasksSampleFlag.replace(taskId, false)
  }

  /**
   * All these update* functions are used to invoke the function of taskMURSample
   * because all result are stored in the taskMURSample.
   */

  // Before the task read it's input, we update the total records first
  def updateTotalRecords(taskId: Long, totalRecords: Long) =
    taskMURSample.updateTotalRecords(taskId, totalRecords)

  // this method will only be used in cache operation
  def updateWriteRecordsInCache(taskId: Long, readRecords: Long) =
    taskMURSample.updateWriteRecordsInCache(taskId, readRecords)

  // this method will only be used in cogroup operation
  def updateReadRecordsInCoCroup(taskId: Long, readRecords: Long) =
    taskMURSample.updateReadRecordsInCoCroup(taskId, readRecords)

  def updateShuffleSampleResult(taskId: Long, sampleResult: Long): Unit = {
    updateSingleTaskSampleFlag(taskId)
    taskMURSample.updateShuffleSampleResult(taskId, sampleResult)
  }

  def updateCacheSampleResult(taskId: Long, sampleResult: Long): Unit = {
    updateSingleTaskSampleFlag(taskId)
    taskMURSample.updateCacheSampleResult(taskId, sampleResult)
  }

  def updateTaskInformation(taskId: Long, taskMetrics: TaskMetrics): Unit = {
    taskMURSample.updateTaskInformation(taskId, taskMetrics)
  }

  // the shuffleTask has all memory usage and begin write to disk
  // we set the percent to 1
  def updateShuffleWrite(taskId: Long): Unit ={
    taskMURSample.addDoingShuffleWrite(taskId)
  }

  /**
   * Scheduler Implementation
   *
   */

  def addStopTask(taskId: Long): Unit = {
    logInfo(s"Add stop task: $taskId.")
    if(!mursStopTasks.containsValue(taskId)) {
      mursStopTasks.put(stopIndex, taskId)
      stopIndex += 1
    }
  }

  def removeStopTask(): Unit ={
    synchronized {
      if (mursStopTasks.containsKey(reStartIndex)) {
        logInfo("Remove stop task: " + mursStopTasks.get(reStartIndex))
        mursStopTasks.remove(reStartIndex)
        reStartIndex += 1
      }
    }
  }

  def removeAllStopTasks(): Unit = {
    synchronized {
      val title = reStartIndex
      for (i <- title to stopIndex) {
        if (mursStopTasks.containsKey(i)) {
          logInfo("Remove stop task: " + mursStopTasks.get(i))
          mursStopTasks.remove(i)
          reStartIndex += 1
        }
      }
    }
  }

  def shouldStop(taskId: Long): Boolean = mursStopTasks.contains(taskId)

  def hasStopTask(): Boolean = !mursStopTasks.isEmpty

  private var totalMemory: Long = 0
  private var yellowMemoryUsage: Long = 0
  private var perMemoryUsageJVM: Long = 0
  private var lastPerMemoryUsageJVM: Long = 0
  private var lastPerMaxMemoryUsageJVM: Long = 0
  private var lastTotalMemoryUsageJVM: Long = 0
  private var lastTotalMemoryUsage: Long = 0
  private var ensureStop = false
  private var redMemoryUsage: Long = 0
  private var processResultTask = false

  def setResultTask(taskId: Long): Unit ={
    isResultTask.put(taskId, true)
  }

  def setHadoopTask(taskId: Long): Unit = {
    isHadoopTask.put(taskId, true)
  }

  /**
    * The line of memory can be set by users. The red value cannot be set by users.
    * @param total total memory
    * @param yellowLine yellow line can be set by the configuration
    */
  def updateMemroyLine(total: Long, yellowLine: Long): Unit = {
    totalMemory = total
    lastTotalMemoryUsageJVM = total
    yellowMemoryUsage = yellowLine
    redMemoryUsage = (total * 0.66 * 0.8).toLong
  }

  private var testStopTaskNum: Int = 8
  private var testStopTaskNumHadoopRDD: Int = 0
  private var estimateMul: Int = 5

  def setTestStopNum(stopNum: Int, stopNumHadoop: Int): Unit ={
    testStopTaskNum = stopNum
    testStopTaskNumHadoopRDD = stopNumHadoop
    estimateMul = conf.getInt("spark.murs.estimate", 5)
  }

  def turnOnProcessResultTask(): Unit ={
    processResultTask = true
  }

  var runningTasksArray: Array[Long] = null
  var tasksMemoryConsumption: Array[Long] = null
  var tasksMemoryUsage: Array[Long] = null
  var tasksMemoryUsageRate: Array[Double] = null
  var tasksCompletePercent: Array[Double] = null

  /**
    * Developing API :: computeStopTask()
    * This method is called within each schedule. It computes the
    * tasks that should be stopped.
    */
  def computeStopTask(): Unit = {
    logInfo(s"Now Task: $stopIndex, $reStartIndex and running " + runningTasks.size())
    val memoryManager = env.memoryManager

    // only have stop tasks
    if (runningTasks.size() == (stopIndex - reStartIndex)) {
      removeStopTask()
      ensureStop = false
    }

    val usedMemoryJVM = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
    // minor gc
    if (usedMemoryJVM < lastTotalMemoryUsageJVM) {
      perMemoryUsageJVM = usedMemoryJVM
    }
    // full gc
    var errorFullGC = 0L
    if (perMemoryUsageJVM < lastPerMemoryUsageJVM) {
      errorFullGC = if (perMemoryUsageJVM > yellowMemoryUsage)
        perMemoryUsageJVM - yellowMemoryUsage
      else 0L
    }
    if (perMemoryUsageJVM > lastPerMaxMemoryUsageJVM)
      lastPerMaxMemoryUsageJVM = perMemoryUsageJVM
    lastTotalMemoryUsageJVM = usedMemoryJVM

    val freeMemoryJVM = totalMemory - perMemoryUsageJVM
    val usedMemory = memoryManager.executionMemoryUsed + memoryManager.storageMemoryUsed
    val freeMemory = memoryManager.maxStorageMemory - memoryManager.storageMemoryUsed
    // logInfo(s"Memory usage.($usedMemoryJVM/$perMemoryUsageJVM/$usedMemory/$yellowMemoryUsage/$freeMemoryJVM/$freeMemory)")

    if (!hasStopTask() && perMemoryUsageJVM > yellowMemoryUsage) {
      if (usedMemory > lastTotalMemoryUsage)
        ensureStop = true
      else if (usedMemoryJVM > redMemoryUsage)
        ensureStop = true
      else if (usedMemoryJVM > yellowMemoryUsage * 2)
        ensureStop = true

      //logInfo(s"Memory pressure must be optimized.")
      if (ensureStop && runningTasks.size() > 0 && runningTasks.size() > testStopTaskNum) {
        //logInfo("Ensure stop")

        runningTasksArray = taskMURSample.getTasks()
        tasksMemoryConsumption = runningTasksArray.map(taskId => {
          val taskMemoryManger = runningTasksMemoryManage.get(taskId)
          taskMemoryManger.getMemoryConsumptionForThisTask
        })

        tasksMemoryUsage = runningTasksArray.map(taskMURSample.getMemoryUsage(_))
        tasksMemoryUsageRate = runningTasksArray.map(taskMURSample.getMemoryUsageRate(_))
        tasksCompletePercent = runningTasksArray.map(taskMURSample.getCompletePercent(_))
        //logInfo("memory usage: " + tasksMemoryUsage.mkString(","))
        //logInfo("memory usage rate: " + tasksMemoryUsageRate.mkString(","))
        //logInfo("memory consumption: " + tasksMemoryConsumption.mkString(","))
        //logInfo("complete percent: " + tasksCompletePercent.mkString(","))

        var stopCount = runningTasksArray.length
        var flagTaskCompletePercent = 1.0
        var maxTaskCompletePercentIndex = 0
        var lastMemoryConsumption: Long = 0
        var freeMemoryToConsumption = freeMemory
        if(isHadoopTask.values().contains(false) && isResultTask.values.contains(false)) {
          while (freeMemoryToConsumption > 0 && stopCount > testStopTaskNum) {
            var firstCompareIndex = true
            for (i <- 0 until runningTasksArray.length) {
              if (tasksCompletePercent(i) < flagTaskCompletePercent) {
                if (firstCompareIndex) {
                  maxTaskCompletePercentIndex = i
                  firstCompareIndex = false
                }
                if (tasksCompletePercent(i) >= tasksCompletePercent(maxTaskCompletePercentIndex))
                  maxTaskCompletePercentIndex = i
              }
            }
            stopCount -= 1
            val currentTaskMemoryConsumption = tasksMemoryConsumption(maxTaskCompletePercentIndex)
            if (runningTasks.size() != 0 && currentTaskMemoryConsumption != 0) {
              val currentMemoryConsumption = (currentTaskMemoryConsumption
                * (1 / tasksCompletePercent(maxTaskCompletePercentIndex) - 1) * estimateMul).toLong
              lastMemoryConsumption = currentMemoryConsumption + currentTaskMemoryConsumption
              freeMemoryToConsumption -= currentMemoryConsumption
            } else if (currentTaskMemoryConsumption == 0) {
              freeMemoryToConsumption -= lastMemoryConsumption
            }
            flagTaskCompletePercent = tasksCompletePercent(maxTaskCompletePercentIndex)
            maxTaskCompletePercentIndex = 0
          }

          for (i <- 0 until runningTasksArray.length) {
            if (flagTaskCompletePercent != 0 && tasksCompletePercent(i) < flagTaskCompletePercent && !isResultTask.get(runningTasksArray(i))) {
              addStopTask(runningTasksArray(i))
            } else if (stopCount >= 0 && flagTaskCompletePercent == 0 && tasksCompletePercent(i) <= flagTaskCompletePercent) {
              addStopTask(runningTasksArray(i))
            }
            stopCount -= 1
          }
        }else{
          for(i <- 0 until testStopTaskNumHadoopRDD){
            if(!isResultTask.get(runningTasksArray(i)) && !processResultTask)
              addStopTask(runningTasksArray(i))
            else if(isResultTask.get(runningTasksArray(i)) && processResultTask)
              addStopTask(runningTasksArray(i))
          }
        }
      }
    } else if (hasStopTask() && perMemoryUsageJVM < yellowMemoryUsage) {
      // full gc has worked but task still stop
      removeAllStopTasks()
    } else if (hasStopTask() && perMemoryUsageJVM > redMemoryUsage) {
      // spill will occur
      val willTasksSpill = new Array[Boolean](runningTasksArray.length)
      for (i <- 0 until runningTasksArray.length) {
        val needMemory = (tasksMemoryUsage(i) * 2 *
          (1 / tasksCompletePercent(i))).toLong
        val willSpill = if (needMemory > tasksMemoryConsumption(i) * 0.8 && tasksCompletePercent(i) < 0.8)
          true
        else false
        willTasksSpill.update(i, willSpill)
      }
      var freeMemoryBeforeSpill = freeMemory.toLong
      for (i <- 0 until runningTasksArray.length) {
        if (!shouldStop(runningTasksArray(i)) && willTasksSpill(i)) {
          freeMemoryBeforeSpill -= 2 * tasksMemoryConsumption(i)
          if (freeMemoryBeforeSpill < 0)
            addStopTask(runningTasksArray(i))
        }
      }
    }
    lastTotalMemoryUsage = usedMemory
    lastPerMemoryUsageJVM = perMemoryUsageJVM
  }

}
