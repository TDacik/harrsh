package at.forsyte.harrsh.seplog.inductive

import at.forsyte.harrsh.seplog.{PtrExpr, PtrVar, Var}
import at.forsyte.harrsh.util.Combinators

/**
  * System of inductive definitions
  * Created by jens on 10/15/16.
  */
case class SID(startPred : String, rules : Set[Rule], description : String = "Unnamed SID") {

  override def toString = {
    description + " (start predicate '" + startPred + "'): " + rules.toSeq.sortBy(_.head).mkString("\n    ", "\n    ", "")
  }

  // TODO Should we record the arity of the predicates explicitly?
  def arityOfStartPred : Int = rules.filter(_.head == startPred).map(rule => rule.freeVars.size).max

}

object SID {

  def apply(startPred : String, description : String, rules : (String, Seq[String], SymbolicHeap)*) = new SID(startPred, Set()++(rules map Rule.fromTuple), description)

  def unfold(sid : SID, depth: Int, reducedOnly : Boolean = false): Seq[SymbolicHeap] = {

    def extractBodies(group : (String,Set[Rule])) = (group._1,group._2 map (_.body))
    val predsToBodies : Map[String, Set[SymbolicHeap]] = Map() ++ sid.rules.groupBy(_.head).map(extractBodies _)

    val initialArgs : Seq[PtrExpr] = (1 to sid.arityOfStartPred) map (i => PtrVar(Var.mkVar(i)).asInstanceOf[PtrExpr])
    val initial = SymbolicHeap(Seq(PredCall(sid.startPred, initialArgs)))

    val unfolded = unfoldStep(predsToBodies, Seq(), Seq(initial), depth)
    if (reducedOnly) unfolded.filter(_.predCalls.isEmpty) else unfolded
  }

  private def unfoldStep(predsToBodies: Map[String, Set[SymbolicHeap]], acc : Seq[SymbolicHeap], curr: Seq[SymbolicHeap], depth: Int): Seq[SymbolicHeap] = {
    if (depth == 0) acc ++ curr
    else {
      val allNewInstances = for {
        h <- curr
        if !h.predCalls.isEmpty
        callReplacements = h.predCalls.map(_.name) map predsToBodies
        replacementChoices: Seq[Seq[SymbolicHeap]] = Combinators.choices(callReplacements)
        newInstances: Seq[SymbolicHeap] = replacementChoices.map(h.instantiateCalls(_))
      } yield newInstances

      unfoldStep(predsToBodies, acc ++ curr, allNewInstances.flatten, depth - 1)
    }
  }

}