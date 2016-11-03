package at.forsyte.harrsh.heapautomata

import at.forsyte.harrsh.seplog._
import at.forsyte.harrsh.seplog.inductive._
import at.forsyte.harrsh.main.{Var, SlexLogging}
import at.forsyte.harrsh.main.Var._
import BaseTrackingAutomaton._
import at.forsyte.harrsh.heapautomata.utils.{EqualityUtils, ReachabilityMatrix, UnsafeAtomsAsClosure}
import at.forsyte.harrsh.util.Combinators

import scala.annotation.tailrec

/**
  * Created by jkatelaa on 10/19/16.
  */
class BaseReachabilityAutomaton[A](
                                    numFV : Int,
                                    isFinalPredicate : (BaseReachabilityAutomaton[A], BaseReachabilityAutomaton.ReachabilityInfo, A) => Boolean,
                                    tagComputation : (Seq[A], TrackingInfo, Set[(Var,Var)], Set[Var]) => A,
                                    inconsistentTag : A,
                                    valsOfTag : Set[A],
                                    override val description : String = "BASE-REACH") extends BoundedFvAutomatonWithTargetComputation(numFV) {

  import BaseReachabilityAutomaton._

  override type State = (ReachabilityInfo, A)

  lazy val InconsistentState : State = ((inconsistentTrackingInfo(numFV),inconsistentReachability(numFV)), inconsistentTag)

  override lazy val states: Set[State] = for {
    track <- computeTrackingStateSpace(numFV)
    reach <- ReachabilityMatrix.allMatrices(numFV)
    tag <- valsOfTag
  } yield ((track, reach), tag)

  override def isFinal(s: State): Boolean = isFinalPredicate(this, s._1, s._2)

  override def getTargetsFor(src : Seq[State], lab : SymbolicHeap) : Set[State] = {
    logger.debug("Computing possible targets " + src.mkString(", ") + " --[" + lab + "]--> ???")
    if (src.length != lab.calledPreds.length) throw new IllegalStateException("Number of predicate calls " + lab.calledPreds.length + " does not match arity of source state sequence " + src.length)

    // Perform compression + subsequent equality/allocation propagation
    val (consistencyCheckedState,tag) = compressAndPropagateReachability(src, lab, InconsistentState, numFV, tagComputation)
    // Break state down to only the free variables; the other information is not kept in the state space
    val trg = (dropNonFreeVariables(consistencyCheckedState._1), consistencyCheckedState._2)

    logger.debug("Target state: " + trg)

    // There is a unique target state because we always compute the congruence closure
    Set((trg,tag))
  }
}

object BaseReachabilityAutomaton extends SlexLogging {

  type ReachabilityInfo = (TrackingInfo,ReachabilityMatrix)

  // In an inconsistent state, everything is reachable
  def inconsistentReachability(numFV : Int) = ReachabilityMatrix(numFV, Array.fill((numFV+1)*(numFV+1))(true))

  def compressAndPropagateReachability[A](src : Seq[(ReachabilityInfo,A)],
                                          lab : SymbolicHeap,
                                          inconsistentState : (ReachabilityInfo,A),
                                          numFV : Int,
                                          tagComputation : (Seq[A], TrackingInfo, Set[(Var,Var)], Set[Var]) => A) : (ReachabilityInfo,A) = {
    val compressed = reachabilityCompression(lab, src map (_._1))
    logger.debug("Compressed " + lab + " into " + compressed)

    // Compute allocation set and equalities for compressed SH and compare to target
    val allocExplicit: Seq[Var] = compressed.pointers map (_.fromAsVar)

    // TODO: Ensure that we can already assume that constraints returned by compression are ordered and thus drop this step
    val pureExplicit : Set[PureAtom] =  Set() ++ compressed.ptrComparisons map orderedAtom

    // Add inequalities for allocated variables
    val inequalitiesFromAlloc : Seq[PureAtom] = Combinators.square(allocExplicit) map {
      case (l,r) => orderedAtom(l, r, false)
    }
    val pureWithAlloc : Set[PureAtom] = pureExplicit ++ inequalitiesFromAlloc

    // Compute fixed point of inequalities and fill up alloc info accordingly
    val trackingsStateWithClosure : TrackingInfo = EqualityUtils.propagateConstraints(allocExplicit.toSet, pureWithAlloc)
    logger.debug("Tracking info for compressed SH: " + trackingsStateWithClosure)

    // TODO Reduce code duplication wrt BaseTracking. The following part is the only one that is new to reachability
    // If the state is inconsistent, return the unique inconsistent state; otherwise compute reachability info
    if (isConsistent(trackingsStateWithClosure)) {
      // Compute reachability info by following pointers
      val (pairs, newMatrix) = reachabilityFixedPoint(numFV, compressed, trackingsStateWithClosure)
      //tagComputation : (Seq[A], TrackingInfo, Set[(FV,FV)], Set[FV]) => A,
      val tag = tagComputation(src map (_._2), trackingsStateWithClosure, pairs, compressed.getVars)
      ((trackingsStateWithClosure, newMatrix), tag)
    } else inconsistentState
  }

  def reachabilityFixedPoint(numFV : Int, compressedHeap : SymbolicHeap, tracking : TrackingInfo) : (Set[(Var,Var)], ReachabilityMatrix) = {

    def ptrToPairs(ptr : PointsTo) : Seq[(Var,Var)] = ptr.to map (to => (ptr.fromAsVar, to.getVarOrZero))

    val directReachability : Seq[(Var,Var)] = compressedHeap.pointers flatMap ptrToPairs
    val equalities : Set[(Var,Var)] = tracking._2.filter(_.isInstanceOf[PtrEq]).map(_.asInstanceOf[PtrEq]).map(atom => (atom.l.getVarOrZero, atom.r.getVarOrZero))
    val pairs = reachabilityFixedPoint(compressedHeap, equalities, directReachability.toSet)
    logger.trace("Reached fixed point " + pairs)

    val reach = ReachabilityMatrix.emptyMatrix(numFV)
    for {
      (from, to) <- pairs
      if isFV(from) && isFV(to)
    } {
      reach.update(from, to, setReachable = true)
    }
    logger.trace("Reachability matrix for compressed SH: " + reach)

    (pairs, reach)
  }

  @tailrec
  private def reachabilityFixedPoint(compressedHeap : SymbolicHeap, equalities: Set[(Var,Var)], pairs : Set[(Var, Var)]) : Set[(Var, Var)] = {

    logger.trace("Iterating reachability computation from " + pairs + " modulo equalities " + equalities)

    // FIXME: Reachability computation is currently extremely inefficient; should replace with a path search algorithm (that regards equalities as steps as well)
    // Propagate equalities
    val transitiveEqualityStep : Set[(Var,Var)] = (for {
      (left, right) <- equalities
      (from, to) <- pairs
      if left == from || left == to || right == from || right == to
    } yield (
      Seq[(Var,Var)]()
        ++ (if (left == from) Seq((right,to)) else Seq())
        ++ (if (right == from) Seq((left,to)) else Seq())
        ++ (if (left == to) Seq((from,right)) else Seq())
        ++ (if (right == to) Seq((from, left)) else Seq()))).flatten
    logger.trace("Equality propagation: " + transitiveEqualityStep)

    // Propagate reachability
    val transitivePointerStep = for {
      (from, to) <- pairs
      (from2, to2) <- pairs
      if to == from2
    } yield (from, to2)
    logger.trace("Pointer propagation: " + transitivePointerStep)

    val newPairs = pairs union transitiveEqualityStep union transitivePointerStep

    if (newPairs == pairs) pairs else reachabilityFixedPoint(compressedHeap, equalities, newPairs)
  }

  def reachabilityCompression(sh : SymbolicHeap, qs : Seq[ReachabilityInfo]) : SymbolicHeap = compressWithKernelization(reachabilityKernel)(sh, qs)

  // TODO Reduce code duplication in kernelization? cf BaseTracking
  // TODO This is the kernel from the paper, i.e. introducing free vars; this is NOT necessary in our implementation with variable-length pointers
  def reachabilityKernel(s : (TrackingInfo, ReachabilityMatrix)) : SymbolicHeap = {
    val ((alloc,pure),reach) = s

    // FIXME: Here we now assume that the state already contains a closure. If this is not the case, the following does not work.
    //val closure = new ClosureOfAtomSet(pure)
    val closure = UnsafeAtomsAsClosure(pure)

    val nonredundantAlloc = alloc filter closure.isMinimumInItsClass

    val freshVar = Var.getFirstBoundVar

    val kernelPtrs : Set[SpatialAtom] = nonredundantAlloc map (reachInfoToPtr(_, reach, freshVar))

    val res = SymbolicHeap(pure.toSeq, kernelPtrs.toSeq)
    logger.trace("Converting source state " + s + " to " + res)
    res
  }

  private def reachInfoToPtr(src : Var, reach : ReachabilityMatrix, placeholder : Var) : PointsTo = {
    val info : Seq[Boolean] = reach.getRowFor(src)

    val targets = info.zipWithIndex map {
      case (r,i) => if (r) mkVar(i) else placeholder
    }
    PointsTo(PtrVar(src), targets map PtrExpr.fromFV)
  }

  /**
    * Computes reachability matrix for the given set of variables (possibly including the nullptr)
    * @param ti Tracking information AFTER congruence closure computation
    * @param reachPairs Reachability between pairs of variables AFTER transitive closure computation
    * @param vars Variables to take into account; add nullptr explicitly to have it included
    * @return (variable-to-matrix-index map, matrix)
    */
  def computeExtendedMatrix(ti : TrackingInfo, reachPairs : Set[(Var,Var)], vars : Set[Var]) : (Map[Var, Int], ReachabilityMatrix) = {
    val ixs : Map[Var, Int] = Map() ++ vars.zipWithIndex

    // TODO Code duplication in matrix computation (plus, we're computing a second matrix on top of the FV-reachability matrix...)
    // Note: Subtract 1, because the null pointer is either explicitly in vars, or to be ignored
    val reach = ReachabilityMatrix.emptyMatrix(vars.size - 1)
    for ((from, to) <- reachPairs) {
      reach.update(ixs(from), ixs(to), setReachable = true)
    }

    logger.debug("Extended matrix for variable numbering " + ixs.toSeq.sortBy(_._2).map(p => p._1 + " -> " + p._2).mkString(", ") + ": " + reach)

    (ixs, reach)
  }

  def isGarbageFree(ti : TrackingInfo, reachPairs : Set[(Var,Var)], vars : Set[Var], numFV : Int): Boolean = {

    // FIXME Null handling?

    logger.debug("Computing garbage freedom for variables " + vars)

    lazy val eqs : Set[(Var,Var)] = ti._2.filter(_.isInstanceOf[PtrEq]).map(_.asInstanceOf[PtrEq]).map(atom => (atom.l.getVarOrZero,atom.r.getVarOrZero))

    def isEqualToFV(v : Var) = eqs.exists {
      case (left, right) => left == v && isFV(right) || right == v && isFV(left)
    }

    val (ixs, reach) = computeExtendedMatrix(ti, reachPairs, vars)

    // TODO Needlessly inefficient as well...
    def isReachableFromFV(trg : Var) : Boolean = {
      val results : Set[Boolean] = for {
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

  def isAcyclic(ti : TrackingInfo, reachPairs : Set[(Var,Var)], vars : Set[Var], numFV : Int): Boolean = {

    // FIXME Null handling?

    logger.debug("Computing acyclicity for variables " + vars)

    val (ixs, reach) = computeExtendedMatrix(ti, reachPairs, vars)

    // TODO Stop as soon as cycle is found (but iterating over everything here is needlessly expensive, but through the also needless transformation to Seq, we at least get nice logging below...)
    val cycles = for (v <- vars.toSeq) yield reach.isReachable(ixs(v), ixs(v))

    logger.debug("Cycles: " + (vars zip cycles))

    !cycles.exists(b => b)
  }

}
