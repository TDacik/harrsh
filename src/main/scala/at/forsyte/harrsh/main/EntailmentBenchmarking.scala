package at.forsyte.harrsh.main

import java.util
import java.util.concurrent.TimeUnit

import at.forsyte.harrsh.entailment.EntailmentChecker.EntailmentStats
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.results.{AverageTimeResult, RunResult}
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{Options, OptionsBuilder, TimeValue}

import scala.collection.GenTraversableOnce
import scala.collection.JavaConverters._
import scala.sys.process._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration.{Duration, SECONDS}

sealed trait ToolOutput {
  val statusString = this match {
    case Valid(stats) => "true"
    case Invalid(stats) => "false"
    case ToolError(toolOutput) => "?"
    case Unknown => "?"
    case ToolTimeout => "?"
  }

  val isSuccess = this match {
    case Valid(_) => true
    case Invalid(_) => true
    case ToolError(_) => false
    case Unknown => false
    case ToolTimeout => false
  }

  val maybeStats = this match {
    case Valid(stats) => stats
    case Invalid(stats) => stats
    case ToolError(toolOutput) => None
    case Unknown => None
    case ToolTimeout => None
  }
}

case class Valid(stats: Option[EntailmentStats]) extends ToolOutput
case class Invalid(stats: Option[EntailmentStats]) extends ToolOutput
case class ToolError(toolOutput: String) extends ToolOutput
case object Unknown extends ToolOutput
case object ToolTimeout extends ToolOutput

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class SlideBenchmarking {

  // Subset of benchmarks in Table 1 on which the respective tool didn't fail
  // Slide
  @Param(Array("examples/entailment/tacas2019/dll_backward_forward.hrs", "examples/entailment/tacas2019/dll_forward_backward.hrs", "examples/entailment/tacas2019/even-sll_sll.hrs", "examples/entailment/tacas2019/greater-ptree_leaf-tree.hrs", "examples/entailment/tacas2019/leaf-tree_greater-ptree.hrs", "examples/entailment/tacas2019/sll_odd-sll.hrs", "examples/entailment/tacas2019/small-ptree_leaf-tree.hrs"))
  var file: String = ""

  def runSlide(file: String): String = {
    val slideFileLeft = file + ".lhs.pred"
    val slideFileRight = file + ".rhs.pred"
    //println(sbFile)
    val call = s"python3 ./slide/entailment.py $slideFileLeft $slideFileRight"
    call.!!
  }

  def slideResult(file: String): ToolOutput = {
    try {
      val output = runSlide(file)
      //.lineStream.last
      val lines = output.split("\n")
      val verdict = if (lines.exists(_.startsWith("VALID"))) Valid(None)
      else if (lines.exists(_.startsWith("INVALID"))) Invalid(None)
      else ToolError(output)
      println("Slide Verdict: " + verdict)
      verdict
    } catch {
      case _:java.lang.RuntimeException =>
        println("Slide crashed on " + file)
        ToolError("Crash")
    }
  }

  @Benchmark
  def benchmarkSlide(): String = {
    runSlide(file)
    //slideResult(file).toString
  }

}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class SongbirdBenchmarking {

  // Subset of benchmarks in Table 1 on which the respective tool didn't fail
  // Songbird
  @Param(Array("examples/entailment/tacas2019/acyc-tll_tll.hrs", "examples/entailment/tacas2019/dll_backward_forward.hrs", "examples/entailment/tacas2019/dll_forward_backward.hrs", "examples/entailment/tacas2019/even-sll_sll.hrs", "examples/entailment/tacas2019/greater-ptree_leaf-tree.hrs", "examples/entailment/tacas2019/sll_odd-sll.hrs", "examples/entailment/tacas2019/small-ptree_leaf-tree.hrs", "examples/entailment/tacas2019/tll-classes_tll.hrs", "examples/entailment/tacas2019/tll_acyc-tll.hrs"))
  var file: String = ""

  def runSongbird(file: String): String = {
    val sbFile = file + ".sb"
    //println(sbFile)
    val call = s"./songbird.native $sbFile"
    call.!!
  }

  @Benchmark
  def benchmarkSongbird(): String = {
    runSongbird(file)
    //songbirdResult(file).toString
  }

  def songbirdResult(file: String): ToolOutput = {
    val output = runSongbird(file)
    val verdict = output.trim match {
      case "sat" => Invalid(None)
      case "unsat" => Valid(None)
      case "unknown" => Unknown
      case msg => ToolError(msg)
    }
    println("Songbird Verdict: " + verdict)
    verdict
  }

}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class HarrshBenchmarking {

  // Subset of benchmarks in Table 1 on which the respective tool didn't fail
  // Harrsh
  @Param(Array("examples/entailment/tacas2019/acyc-tll_tll.hrs", "examples/entailment/tacas2019/acyclic-sll_sll.hrs", "examples/entailment/tacas2019/almost-linear-treep_treep.hrs", "examples/entailment/tacas2019/dll_backward_forward.hrs", "examples/entailment/tacas2019/dll_forward_backward.hrs", "examples/entailment/tacas2019/even-sll_sll.hrs", "examples/entailment/tacas2019/external_equality.hrs", "examples/entailment/tacas2019/external_null_missing.hrs", "examples/entailment/tacas2019/greater-ptree_leaf-tree.hrs", "examples/entailment/tacas2019/leaf-tree_greater-ptree.hrs", "examples/entailment/tacas2019/leaf-tree_ptree.hrs", "examples/entailment/tacas2019/leftmost_leaf_to_root.hrs", "examples/entailment/tacas2019/odd-or-even-sll_sll.hrs", "examples/entailment/tacas2019/odd-sll_sll.hrs", "examples/entailment/tacas2019/ptree_leaf-tree.hrs", "examples/entailment/tacas2019/sll_acyclic-sll.hrs", "examples/entailment/tacas2019/sll_odd-sll.hrs", "examples/entailment/tacas2019/small-ptree_leaf-tree.hrs", "examples/entailment/tacas2019/tll-classes_tll.hrs", "examples/entailment/tacas2019/tll_acyc-tll.hrs", "examples/entailment/tacas2019/treep_almost-linear-treep.hrs", "examples/entailment/k-grids/dlgrid-left-right.hrs"))
  var file: String = ""

  @Benchmark
  def benchmarkHarrsh(): EntailmentBatchMode.BenchmarkTrace = {
    runHarrsh(file)
  }

  def runHarrsh(file: String): EntailmentBatchMode.BenchmarkTrace = {
    EntailmentBatchMode.runBenchmark(file, suppressOutput = true)
  }

  def harrshResult(file: String): ToolOutput = {
    val trace = runHarrsh(file)
    trace.result match {
      case Some(value) =>
        if (value) Valid(trace.stats) else Invalid(trace.stats)
      case None => ToolError("")
    }
  }

}

object ToolRunner {

  val Timeout = Duration(5, SECONDS)

  def apply(file: String, run: String => ToolOutput): ToolOutput = {

    val f: Future[ToolOutput] = Future {
      run(file)
    }

    try {
      Await.result(f, Timeout)
    } catch {
      case e : TimeoutException =>
        ToolTimeout
    }
  }

}

class EntailmentBenchmarking {
  // Don't delete this class, otherwise JMH will crash.
}
object EntailmentBenchmarking {

  val ResultTexFile = "complete-results.tex"
  val BenchmarkPath = "examples/entailment/tacas2019"

  // Benchmark times
  val secs = (i: Int) => TimeValue.seconds(i)
  val WarmupTime = secs(2)
  val IterationTime = secs(5)
  val WarmupIterations = 1
  val MeasurementIterations = 1

  val Harrsh = "at.forsyte.harrsh.main.HarrshBenchmarking.benchmarkHarrsh"
  val Songbird = "at.forsyte.harrsh.main.SongbirdBenchmarking.benchmarkSongbird"
  val Slide = "at.forsyte.harrsh.main.SlideBenchmarking.benchmarkSlide"
  val AllTools = Set(Harrsh, Songbird, Slide)

  case class ToolBenchResult(tool: String, time: Double) {
    assert(AllTools.contains(tool))
  }

  case class BenchResult(file: String, timeByTool: Map[String, Double])

  private def runJmhBenchmark(opt: Options): ToolBenchResult = {
    val res = new Runner(opt).run()
    processJmhResults(res)
  }

  private def processJmhResults(results: util.Collection[RunResult]): ToolBenchResult = {
    (for {
      res <- results.asScala
      tool = res.getParams.getBenchmark
      file = res.getParams.getParam("file")
      avg: AverageTimeResult = res.getAggregatedResult.getPrimaryResult.asInstanceOf[AverageTimeResult]
      score = avg.getScore
      _ = println(s"Finished Benchmark: $tool / $file / $score")
    } yield ToolBenchResult(tool, score)).head
  }


  private def runJmhOnBenchmarkFile(file: String): BenchResult = {
    val toolClasses = Seq(classOf[HarrshBenchmarking], classOf[SongbirdBenchmarking], classOf[SlideBenchmarking])
    val byTool = for {
      tool <- toolClasses
    } yield runJmhBenchmarkForTool(file, tool.getSimpleName)
    BenchResult(file, byTool.map(tbr => (tbr.tool, tbr.time)).toMap)
  }

  val toolSpecs = Seq(
    ("HRS", classOf[HarrshBenchmarking], new HarrshBenchmarking().harrshResult(_)),
    ("SB", classOf[SongbirdBenchmarking], new SongbirdBenchmarking().songbirdResult(_)),
    ("SLD", classOf[SlideBenchmarking], new SlideBenchmarking().slideResult(_))
  )

  case class TableEntry(file: String, tools: Seq[ToolTableEntry]) {
    def toColumnSeq: Seq[String] = {
      val hrs = tools.find(_.toolName == "HRS").get
      val sb = tools.find(_.toolName == "SB").get
      val sld = tools.find(_.toolName == "SLD").get

      val query = file.split("/").last.replace("_","\\_")
      Seq(query, hrs.output.statusString, hrs.timeString, sb.timeString, sld.timeString) ++ hrs.statsColumns
    }

    def errorMessages: Seq[String] = Seq() ++ tools.flatMap(_.errorMsg)
  }
  case class ToolTableEntry(toolName: String, output: ToolOutput, jmhRes: Option[ToolBenchResult]) {

    private val jmhTime = jmhRes match {
      case Some(tbr) => tbr.time.toInt.toString
      case None => "?"
    }

    val timeString: String = output match {
      case Valid(_) => jmhTime
      case Invalid(_) => jmhTime
      case ToolError(toolOutput) => "(X)"
      case Unknown => "(U)"
      case ToolTimeout => "TO"
    }

    def statsColumns: Seq[String] = output.maybeStats match {
      case Some(stats) => Seq(stats.numProfiles, stats.totalNumDecomps, stats.totalNumContexts) map (_.toString)
      case None => Seq("?", "?", "?")
    }

    def errorMsg: Option[String] = output match {
      case ToolError(toolOutput) => Some(s"$toolName failed. Diagnostic info (if any): $toolOutput")
      case _ => None
    }
  }

  private def runBenchmarkFile(file: String): TableEntry = {
    TableEntry(file, toolSpecs map (runToolOnBenchmarkFile(file, _)))
  }

  private def runToolOnBenchmarkFile(file: String, toolSpec: (String, Class[_], String => ToolOutput)): ToolTableEntry = {
    // First see if the tool fails/times out
    val output = ToolRunner(file, toolSpec._3)
    if (output.isSuccess) {
      // Run JMH only in case of success
      ToolTableEntry(toolSpec._1, output, Some(runJmhBenchmarkForTool(file, toolSpec._2.getSimpleName)))
    } else {
      ToolTableEntry(toolSpec._1, output, None)
    }
  }

  private def runJmhBenchmarkForTool(file: String, toolClass: String): ToolBenchResult = {
    val opt: Options = new OptionsBuilder()
      .include(toolClass)
      .param("file", file)
      .forks(1)
      .threads(1)
      .warmupTime(WarmupTime)
      .measurementTime(IterationTime)
      .warmupIterations(WarmupIterations)
      .measurementIterations(MeasurementIterations)
      .build()

    runJmhBenchmark(opt)
  }

  private def exportResultsToLatex(results: Seq[TableEntry]): Unit = {
    val headings = Seq("File", "Status", "HRS", "SB", "SLD", "\\#P", "\\#D", "\\#C")
    val entries = results map (_.toColumnSeq)
    val bulletPoints = for {
      res <- results
      msg <- res.errorMessages
    } yield res.file + ": " + msg
    MainIO.writeLatexFile(ResultTexFile, headings, entries, bulletPoints)
  }

  def main(args: Array[String]): Unit = {
    val bms = EntailmentBatchMode.allHarrshEntailmentFilesInPath(BenchmarkPath)
    //val bms = Seq("examples/entailment/tacas2019/2-grid.hrs", "examples/entailment/tacas2019/acyclic-sll_sll.hrs", "examples/entailment/tacas2019/almost-linear-treep_treep.hrs", "examples/entailment/tacas2019/dlgrid.hrs", "examples/entailment/tacas2019/dlgrid-left-right.hrs", "examples/entailment/various/list-segments-different-order.hrs")
    //val bms = Seq("examples/entailment/tacas2019/dlgrid.hrs", "examples/entailment/tacas2019/dlgrid-left-right.hrs", "examples/entailment/various/list-segments-different-order.hrs")

    val resultsByFile = bms map runBenchmarkFile
    //val resultsByFile = aggregateResults(resultsByTool)
    println("FINISHED ALL BENCHMARKS")
    println(resultsByFile.mkString("\n"))
    println("Writing table to: " + ResultTexFile)
    exportResultsToLatex(resultsByFile)
  }
}
