package at.forsyte.harrsh.heapautomata.instances

import at.forsyte.harrsh.heapautomata.TaggedAutomaton
import at.forsyte.harrsh.heapautomata.utils.{ReachabilityMatrix, StateTag, TrackingInfo}
import at.forsyte.harrsh.refinement.AutomatonTask
import at.forsyte.harrsh.seplog.Var
import at.forsyte.harrsh.seplog.Var._
import at.forsyte.harrsh.seplog.inductive.SymbolicHeap

/**
  * Created by jens on 3/29/17.
  */
class GarbageAutomaton(numFV : Int, negate : Boolean) extends TaggedAutomaton[Boolean, BaseReachabilityAutomaton.UncleanedTrackingInfo, BaseReachabilityAutomaton] {

  override val baseAutomaton = new BaseReachabilityAutomaton(numFV)

  override val tags = StateTag.instances.booleanTag

  override val description = (if (negate) AutomatonTask.keywords.garb else AutomatonTask.keywords.gf) + "_" + numFV

  override def tagComputation(srcTags: Seq[Boolean], lab : SymbolicHeap, baseTrg: baseAutomaton.State, ei : BaseReachabilityAutomaton.UncleanedTrackingInfo): Boolean = {
    val res = if (negate) {
      val childGarbage = srcTags.exists(b => b)
      val matrixGarbage = !isGarbageFree(ei.fullTrackingInfoWithBoundVars, baseTrg.rm.underlyingPairs.get, ei.allVars + mkVar(0), numFV)
      logger.debug("Checking for garbage. In children: " + childGarbage + "; in matrix: " + matrixGarbage)
      childGarbage || matrixGarbage
    } else {
      val childGFree = !srcTags.exists(!_)
      val matrixGFree = isGarbageFree(ei.fullTrackingInfoWithBoundVars, baseTrg.rm.underlyingPairs.get, ei.allVars + mkVar(0), numFV)
      logger.debug("Checking for garbage freedom. In children: " + childGFree + "; in matrix: " + matrixGFree)
      childGFree && matrixGFree
    }

    res
  }

  private def isGarbageFree(ti : TrackingInfo, reachPairs : Set[(Var,Var)], vars : Set[Var], numFV : Int): Boolean = {

    if (!ti.isConsistent) {
      // TODO Does this yield the correct results in the negated setting as well?
      true
    } else {

      // FIXME Null handling?

      logger.debug("Computing garbage freedom for variables " + vars)

      lazy val eqs: Set[(Var, Var)] = ti.equalities.map(atom => (atom.l.getVarOrZero, atom.r.getVarOrZero))

      def isEqualToFV(v: Var) = eqs.exists {
        case (left, right) => left == v && isFV(right) || right == v && isFV(left)
      }

      val (ixs, reach) = ReachabilityMatrix.computeExtendedMatrix(ti, reachPairs, vars)

      // TODO Needlessly inefficient as well...
      def isReachableFromFV(trg: Var): Boolean = {
        val results: Set[Boolean] = for {
          fv <- vars
          if isFV(fv)
        } yield reach.isReachable(ixs(fv), ixs(trg))

        results.exists(b => b)
      }

      // TODO Stop as soon as garbage is found
      val reachableFromFV = for (v <- vars) yield isFV(v) || isEqualToFV(v) || isReachableFromFV(v)

      val garbageFree = !reachableFromFV.exists(!_)

      if (!garbageFree) {
        logger.debug("Discovered garbage: " + (vars filter (v => !(isFV(v) || isEqualToFV(v) || isReachableFromFV(v)))))
      }

      garbageFree
    }
  }

}
