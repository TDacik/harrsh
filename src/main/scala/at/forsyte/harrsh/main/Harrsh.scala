package at.forsyte.harrsh.main

import at.forsyte.harrsh.entailment.{GreedyUnfoldingModelChecker}
import at.forsyte.harrsh.refinement.{AutomatonTask, DecisionProcedures, RefinementAlgorithms}
import at.forsyte.harrsh.seplog.inductive.SIDUnfolding
import at.forsyte.harrsh.util.{Combinators, IOUtils}

import scala.concurrent.duration.{Duration, SECONDS}
import scalaz.State
import scalaz.State._

/**
  * The main command-line interface of Harrsh.
  */
object Harrsh {
  // TODO Option to feed in arbitrary symbolic heaps in addition to SID (and then automatically adapt the SID with new start predicate)

  val PreviousSidFileName = "LAST"

  val S = scalaz.StateT.stateMonad[Config]

  def main(args : Array[String]) : Unit = {

    val config: Config = parseAll(args)
    //println(config)

    // Run in specified mode unless something is missing from the config
    if (config.mode != Help() && config.oFile.isEmpty) {
      println("No file specified => Terminating")
    }
    else if (config.mode.requiresProp && config.oProp.isEmpty) {
      println("No property specified => Terminating")
    } else {
      Combinators.swallowExceptions(run, config.debug)(config)
    }
  }

  /**
    * Parse command line arguments
    * @param args Array of arguments
    * @return Config built from arguments
    */
  def parseAll(args : Array[String]) : Config = parseAllAux(args).run(Config.DefaultConfig)._1

  private def parseAllAux(args : Array[String]) : State[Config, Unit] = {

    def parseSwitchWithArg(long: String, short: String, default: String): String = {
      val arg = Math.max(args.indexOf(long), args.indexOf(short))
      if (arg > -1 && args.length > arg + 1) args(arg + 1) else default
    }

    def parseSwitch[A](long: String, short: String, update : Config => Config): State[Config, Unit] = for {
      cnf <- get
      _ <- modify[Config](cnf => if (args.contains(long) || args.contains(short)) update(cnf) else cnf)
    } yield ()

    def tryParseMode(long: String, short: String, mode: ExecutionMode): State[Config, Unit] = for {
      cnf <- get[Config]
      file = parseSwitchWithArg(long, short, "")
      _ <- put(if (!file.isEmpty) cnf.copy(mode = mode, oFile = Some(file)) else cnf)
    } yield ()

    for {
      /*
       * Parse mode
       */
      _ <- parseSwitch("--help", "-h", _.copy(mode = Help()))
      _ <- tryParseMode("--batch", "-b", Batch())
      _ <- tryParseMode("--refine", "-r", Refine())
      _ <- tryParseMode("--decide", "-d", Decide())
      _ <- tryParseMode("--show", "--show", Show())
      _ <- tryParseMode("--unfold", "-u", Unfold())
      _ <- tryParseMode("--analyze", "-a", Analyze())
      _ <- tryParseMode("--spec", "-s", ModelChecking())
      _ <- tryParseMode("--gen-aut", "-ga", GenerateEntailmentAutomaton())
      mode <- gets[Config,ExecutionMode](_.mode)

      /*
       * Parse other args
       */
      // Prop
      propertyString = parseSwitchWithArg("--prop", "-p", "")
      prop = AutomatonTask.fromString(propertyString)
      _ <- modify[Config](cnf => cnf.copy(oProp = prop))

      // Timeout
      timeoutString = parseSwitchWithArg("--timeout", "-t", "")
      timeout = tryParseAsInt(timeoutString) map (Duration(_, SECONDS)) getOrElse {
        if (mode.defaultTimeout.toSeconds != 0) {
          println("No timeout specified; will use default " + mode.defaultTimeout)
          mode.defaultTimeout
        } else {
          Duration(Integer.MAX_VALUE, SECONDS)
        }
      }
      _ <- modify[Config](cnf => cnf.copy(oTimeout = Some(timeout)))

      // Unfolding depth
      unfoldingString = parseSwitchWithArg("--depth", "-d", "")
      unfoldingDepth = tryParseAsInt(unfoldingString)
      _ <- modify[Config](cnf => cnf.copy(oUnfoldingDepth = unfoldingDepth))

      // Num. FV for entailment automaton
      numfvString = parseSwitchWithArg("--numfv", "-n", "")
      numfv = tryParseAsInt(numfvString)
      _ <- modify[Config](cnf => cnf.copy(oNumFV = numfv))

      // Model
      modelFile = parseSwitchWithArg("--modelcheck", "-mc", "")
      _ <- modify[Config](cnf => cnf.copy(oModelFile = Some(modelFile)))

      // Boolean flags
      _ <- parseSwitch("--reduced", "-red", _.copy(oUnfoldingsReduced = Some(true)))
      _ <- parseSwitch("--verbose", "-v", _.copy(verbose = true))
      _ <- parseSwitch("--showprogress", "-sp", _.copy(reportProgress = true))
      _ <- parseSwitch("--debug", "--debug", _.copy(debug = true))
    } yield ()
  }

  /**
    * Run Harrsh according to the given config
    * @param config Configuration specifying what to run and how to run it
    */
  private def run(config : Config) : Unit = config.mode match {
      case Help() =>
        printUsage()

      case Decide() =>
        val task = TaskConfig(config.file, config.prop, None)
        val result = DecisionProcedures.decideInstance(task, config.timeout, config.verbose, config.reportProgress)
        MainIO.printAnalysisResult(task, result)

      case Refine() =>
        println("Will refine SID definition in file " + config.file + " by " + config.prop)
        val (sid, ha) = MainIO.getSidAndAutomaton(config.file, config.prop)
        val result = RefinementAlgorithms.refineSID(sid, ha, config.timeout, reportProgress = config.reportProgress)

        result match {
          case Some(vsid) =>
            println(vsid._1)
            IOUtils.writeFile(PreviousSidFileName, vsid._1.toHarrshFormat)

            if (vsid._2) {
              IOUtils.printWarningToConsole("Language of refined SID is empty (no rules for start predicate '" + vsid._1.startPred + "').")
            }
          case None =>
            IOUtils.printWarningToConsole("Refinement failed.")
        }

      case Batch() =>
          println("Will run all benchmarks in " + config.file)
          val tasks = MainIO.readTasksFromFile(config.file)
          val (results, stats) = DecisionProcedures.decideInstances(tasks, config.timeout, config.verbose, config.reportProgress)

          MainIO.writeBenchmarkFile(results, "previous-batch.bms")
          MainIO.printAnalysisResults(results, stats)

          val diffs = DecisionProcedures.deviationsFromExpectations(results)
          if (diffs.nonEmpty) {
            println()
            IOUtils.printWarningToConsole("Some analysis results differed from the expected results as specified in " + config.file)
            for {
              (taskConfig, result) <- diffs
            } IOUtils.printWarningToConsole(taskConfig.fileName + " " + taskConfig.decisionProblem + ": Expected " + taskConfig.expectedResult.get + ", actual " + !result.isEmpty)
          }

      case Show() =>
          val sid = MainIO.getSidFromFile(config.file)
          println(sid)
          IOUtils.writeFile(PreviousSidFileName, sid.toHarrshFormat)

      case Unfold() =>
          val sid = MainIO.getSidFromFile(config.file)
          println(SIDUnfolding.unfold(sid, config.unfoldingDepth, config.unfoldingsReduced).mkString("\n"))

      case Analyze() =>
          val sid = MainIO.getSidFromFile(config.file)
          RefinementAlgorithms.performFullAnalysis(sid, sid.numFV, config.timeout, config.verbose)

      case ModelChecking() =>
          val sid = MainIO.getSidFromFile(config.file)
          val model = MainIO.getModelFromFile(config.modelFile)
          val modelChecker = GreedyUnfoldingModelChecker
          val result = modelChecker.isModel(model, sid)
          println("Finished model checking. Result: " + result)

      case GenerateEntailmentAutomaton() =>
          val sid = MainIO.getSidFromFile(config.file)
          val autNumfv = config.oNumFV.getOrElse{
            IOUtils.printWarningToConsole("Number of free variables for entailment automaton not specified. Will default to number of free variables in start predicate (" + sid.numFV + ")")
            sid.numFV
          }
          // val aut = GenerateEntailmentAutomata(autNumfv, sid, config.reportProgress)
          // TODO Write automaton to file
          // println("Finished computation of entailment automaton")
          println("Generation of entailment automata is not available in this branch.")
  }


  private def tryParseAsInt(s : String) : Option[Int] = {
    try {
      Some(Integer.parseInt(s))
    } catch {
      case _ : Throwable => None
    }
  }

  private def printUsage() = {
    println("This is HARRSH. Usage:")
    println()
    println("Refinement mode:")
    println("  --refine <relative-path-to-sid-file> --prop <property>      refine sid by prop")
    println()
    println("Decision procedure mode:")
    println("  --decide <relative-path-to-sid-file> --prop <property>   check if sid has prop")
    println()
    println("Model checking mode:")
    println("  --modelcheck <path-to-model> --spec <path-to-sid>       check if model |= spec")
    println()
    println("Batch / benchmarking mode:")
    println("  --batch <relative-path-to-file-with-list-of-tasks>          batch benchmarking")
    println()
    println("Analysis mode:")
    println(" --analyze <relative-path-to-sid-file>           analyze robustness of given sid")
    println()
    println("Exploration mode:")
    println("  --show <relative-path-to-sid-file>                        print sid to std out")
    println("  --unfold <relative-path-to-sid-file>     generate all unfoldings of the sid...")
    println("     [--depth <depth>]                    ...up to depth <depth> (default: 3)...")
    println("     [--reduced]                          ...showing only reduced symbolic heaps")
    println()
    println("Optional arguments:")
    println("  --timeout <timeout in s                                       optional timeout")
    println("  --showprogress                                    print progress of refinement")
    println("  --verbose                                                  produce more output")
    println()
    println("Properties:")
    println("ACYC               Weakly acyclic unfoldings")
    println("EST                Established unfoldings")
    println("GF                 Garbage-free unfoldings")
    println("HASPTR             Unfoldings that allocate memory")
    println("NON-EST            Non-established unfoldings")
    println("REACH(a,b)         Unfoldings where b is reachable from a, for a,b in {x1,x2,..,}")
    println("SAT                Satisfiable unfoldings")
    println("TRACK(a,b,...)     Unfoldings in which free variables a,b,... are def. allocated")
    println("UNSAT              Unsatisfiable unfoldings")
  }

}
