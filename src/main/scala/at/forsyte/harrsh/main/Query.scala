package at.forsyte.harrsh.main

import at.forsyte.harrsh.entailment.EntailmentInstance
import at.forsyte.harrsh.parsers.EntailmentParsers
import at.forsyte.harrsh.seplog.Var.Naming
import at.forsyte.harrsh.seplog.inductive.{Predicate, RuleBody, SID, SymbolicHeap}
import at.forsyte.harrsh.util.ToLatex
import at.forsyte.harrsh.util.ToLatex._

sealed trait Query {
  val status: InputStatus
}

case class SatQuery(sid: SID, query: SymbolicHeap, override val status: InputStatus) extends Query {

  val StartPred = "ASSERT"

  override def toString: String = {
    val sb = new StringBuilder()
    sb.append("SatBenchmark {\n")
    sb.append("  SID = {\n")
    for (line <- sid.toString.lines) sb.append(s"    $line\n")
    sb.append("  }\n  Query = {\n")
    for (line <- query.toString.lines) sb.append(s"    $line\n")
    sb.append(s"  }\n  Status = $status\n")
    sb.append("}")
    sb.mkString
  }

  /**
    * Integrate the top-level query with the predicates by making the query the start predicate.
    * @return Combined SID
    */
  def toIntegratedSid: SID = {
    // TODO: Make sure that all automata deal correctly with top-level formulas
    startRule match {
      case None =>
        // The query is a single predicate call => Extract start predicate from that
        val startPred = query.predCalls.head.name
        SID(startPred, sid.preds, sid.description)
      case Some(rule) =>
        // Need an additional rule to represent query => Derive integrated SID from that
        val allPreds = Predicate(StartPred, Seq(rule)) +: sid.preds
        SID(StartPred, allPreds, sid.description)
    }

  }

  private def startRule: Option[RuleBody] = {
    if (isRedundantSingleCall(query))
      None
    else
      Some(RuleBody(Nil, query))
  }

  private def isRedundantSingleCall(heap: SymbolicHeap) = {
    if (heap.hasPointer || heap.pure.nonEmpty || heap.predCalls.size != 1) {
      // Query isn't even a single call
      false
    } else {
      // It's a single call => Check if it's redundant...
      val call = heap.predCalls.head
      // ...i.e. if the call does *not* contain null + its args are pairwise different
      !call.args.exists(_.isNull) && call.args.toSet.size == call.args.size
    }
  }

}

case class EntailmentQuery(lhs: SymbolicHeap, rhs: SymbolicHeap, sid: SID, override val status: InputStatus) extends Query

object EntailmentQuery {

  implicit val entailmentBenchmarkToLatex: ToLatex[EntailmentQuery] = (epr: EntailmentQuery, naming: Naming) => {
    val query = "Check entailment $" + epr.lhs.toLatex(naming) + " \\models " + epr.rhs.toLatex(naming) + "$"
    val sid = epr.sid.toLatex(naming)
    query + "\n%\n" + "w.r.t.\n%\n" + sid + "\n"
  }

}

object Query {

  def queryToEntailmentInstance(q: Query, computeSeparateSidsForEachSide: Boolean): Option[EntailmentInstance] = q match {
    case q: SatQuery => {
      println("Cannot convert SAT-Query to entailment instance")
      None
    }
    case q: EntailmentQuery => {
      EntailmentParsers.normalize(q, computeSeparateSidsForEachSide)
    }
  }
}