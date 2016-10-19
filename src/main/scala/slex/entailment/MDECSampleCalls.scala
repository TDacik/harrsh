package slex.entailment

import slex.main.main.examples.{SymbolicHeapExamples, UFExample}
import slex.seplog.SepLogAxioms
import slex.smtinteraction.SmtWrapper

/**
  * Created by jkatelaa on 9/30/16.
  */
object MDECSampleCalls {

  def mdecExample() : Unit = {
    println("Let's test the model-driven entailment checker...")
    SmtWrapper.withZ3 { z3 =>
      val res = MDEC(z3).prove(SymbolicHeapExamples.PaperExampleEntailmentLeft, SymbolicHeapExamples.PaperExampleEntailmentRight)
      println("Result: " + res)
    }
  }

  private def callSmtExample() : Unit = {
    SmtWrapper.withZ3 { z3 =>
      val example = UFExample.Example
      println("Will run the following example:")
      println(example.mkString("\n"))
      println("Running Z3 now...")
      z3.restart()
      z3.addCommands(example)
      val res = z3.computeModel()
      println(res)
    }
  }



  private def formulaExamples() : Unit = {
    println("Look, we can write SL definitions! With indices! Here's one for list segments:")
    println(SepLogAxioms.LSegDef)

    println("We also support the restriction to symbolic heaps such as")
    println(SymbolicHeapExamples.SingleList)
    println(SymbolicHeapExamples.SplitList)
    println(SymbolicHeapExamples.LassoList)
  }

}