package at.forsyte.harrsh.seplog.inductive

import java.util.NoSuchElementException

import at.forsyte.harrsh.entailment.GreedyUnfoldingModelChecker._
import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.util.Combinators

/**
  * Created by jens on 3/6/17.
  */
object SIDUnfolding extends HarrshLogging {

  def unfoldSingleCall(sh : SymbolicHeap, call : PredCall, sid : SID) : Seq[SymbolicHeap] = {
    logger.debug("Unfolding " + call + " in " + sh)

    (for (body <- sid.rulesAsHeadToBodyMap(call.name)) yield sh.replaceCall(call, body, performAlphaConversion = true)).toSeq
  }

  def unfold(sid : SID, depth: Int, reducedOnly : Boolean = false): Seq[SymbolicHeap] = {

    logger.debug("Unfolding sid " + sid)

    val predsToBodies: Map[String, Set[SymbolicHeap]] = sid.rulesAsHeadToBodyMap

    val initial: SymbolicHeap = sid.callToStartPred

    logger.debug("Will unfold using the following rules: ")
    for ((k,vs) <- predsToBodies) {
      logger.debug("Pred " + k + ":")
      for (v <- vs) {
        logger.debug(" * " + v)
      }
    }

    val unfolded = try {
      unfoldStep(predsToBodies, Seq(), Seq(initial), depth)
    } catch {
      case e : NoSuchElementException =>
        println("Aborting. The SID appears to contain undefined predicates: " + e.getMessage)
        Seq()
    }

    if (reducedOnly) unfolded.filter(_.predCalls.isEmpty) else unfolded
  }

  /**
    * Unfold all given heaps exactly once, returning both reduced and non-reduced results
    * @param sid Underlying SID
    * @param heaps Heaps to unfold
    * @return Unfolded heaps
    */
  def unfoldOnce(sid : SID, heaps : Seq[SymbolicHeap]) : Seq[SymbolicHeap] = unfoldStep(sid.rulesAsHeadToBodyMap, Seq.empty, heaps, 1, doAccumulateSteps = false)

  private def unfoldStep(predsToBodies: Map[String, Set[SymbolicHeap]], acc : Seq[SymbolicHeap], curr: Seq[SymbolicHeap], depth: Int, doAccumulateSteps: Boolean = true): Seq[SymbolicHeap] = {
    logger.debug("Currently active instances: " + curr.mkString(", "))
    if (depth == 0) if (doAccumulateSteps) acc ++ curr else curr
    else {
      val allNewInstances = for {
        sh <- curr
        if !sh.predCalls.isEmpty
        callReplacements = sh.predCalls.map(_.name) map predsToBodies
        replacementChoices: Seq[Seq[SymbolicHeap]] = Combinators.choices(callReplacements)
        newInstances: Seq[SymbolicHeap] = replacementChoices.map(sh.replaceCalls(_, performAlphaConversion = true))
      } yield newInstances

      unfoldStep(predsToBodies, acc ++ curr, allNewInstances.flatten, depth - 1, doAccumulateSteps)
    }
  }

  def firstReducedUnfolding(sid : SID) : SymbolicHeap = {

    // TODO This is an extremely inefficient way to implement this functionality; we should at least short circuit the unfold process upon finding an RSH, or better, implement the obvious linear time algorithm for generating the minimal unfolding
    def unfoldAndGetFirst(depth : Int) : SymbolicHeap = unfold(sid, depth, true).headOption match {
      case None => unfoldAndGetFirst(depth+1)
      case Some(sh) => sh
    }

    unfoldAndGetFirst(1)
  }

  /**
    * Replaces all remaining calls in sh with rule bodies with empty spatial parts. If no such bodies exist, an empty set is returned.
    * @param predsToBodies Preds-to-bodies map of the SID
    * @param sh Arbitrary symbolic heap to unfold
    * @return Set of all possible instantiations of calls with empty spatial part
    */
  // FIXME Do we want to be able to unfold multiple calls here?
//  def unfoldCallsByEmpty(predsToBodies: Map[String, Set[SymbolicHeap]], sh: SymbolicHeap): Set[SymbolicHeap] = {
//    if (sh.hasPredCalls) {
//      unfoldFirstCallWithSatisfyingBodies(predsToBodies, sh, body => !body.hasPointer && !body.hasPredCalls)
//    } else {
//      Set(sh)
//    }
//  }

  /**
    * Unfolds the first call in the given symbolic heap using only and all the rules that satisfy the predicate pBody
    * @param predsToBodies Preds-to-bodies map of the SID
    * @param sh Arbitrary symbolic heap to unfold
    * @param pBody Predicate indicating which rules are enabled
    * @return The given symbolic heap with the first call replaced by enabled bodies
    */
  def unfoldFirstCallWithSatisfyingBodies(predsToBodies: Map[String, Set[SymbolicHeap]], sh: SymbolicHeap, pBody: SymbolicHeap => Boolean): Set[SymbolicHeap] = {
    val call = sh.predCalls.head
    val applicableBodies = predsToBodies(call.name) filter pBody
    logger.debug("Will unfold " + call + " by...\n" + applicableBodies.map("  - " + _).mkString("\n"))
    val unfolded = for (body <- applicableBodies) yield sh.replaceCall(call, body, performAlphaConversion = true)
    unfolded
  }

}
