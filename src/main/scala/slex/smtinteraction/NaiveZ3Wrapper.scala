package slex.smtinteraction

import java.io.{BufferedWriter, File, FileWriter}

import slex.main.Defaults
import slex.smtsyntax.SmtCommand
import sys.process._

/**
  * Created by jkatelaa on 9/30/16.
  */
class NaiveZ3Wrapper(pathToZ3 : Option[String]) extends SmtWrapper {

  private val FileName = "tmp.smt2"

  private val path = pathToZ3 getOrElse Defaults.PathToZ3

  override def runSmtQuery(query : Seq[SmtCommand]) : String = {
    writeSmtFile(query)
    val command = path + " " + FileName
    val process = Process(command)
    println("Will run: " + process.toString)

    var errors : List[String] = Nil
    var msgs : List[String] = Nil
    val logger = ProcessLogger(x =>  msgs = msgs ++ List(x), x => errors = errors ++ List(x))

    try {
      val res = process.!!(logger)
      println("Z3 returned result: " + res)
      res
    } catch {
      case e : RuntimeException =>
        println("Damn it, " + e)
        println("Output: " + msgs.mkString("\n"))
        println("Errors: " + errors.mkString("\n"))
        "error"
    }
  }

  private def writeSmtFile(input : Seq[SmtCommand]): Unit = {
    val file = new File(FileName)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(input.mkString("\n"))
    bw.close()
  }

}
