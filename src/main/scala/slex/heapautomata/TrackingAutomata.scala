package slex.heapautomata

import slex.Combinators
import slex.heapautomata.utils.{EqualityUtils, UnsafeAtomsAsClosure}
import slex.main._
import slex.seplog.{NullPtr, PointsTo, PureAtom, SpatialAtom, SymbolicHeap}

import scala.annotation.tailrec

/**
  * Created by jens on 10/16/16.
  */
object TrackingAutomata {

  /**
    * Get tracking automaton for the given number of free variables, whose target states are defined by alloc and pure.
    * @param numFV
    * @param alloc
    * @param pure
    * @return
    */
  def apply(numFV : Int, alloc : Set[FV], pure : Set[PureAtom]) = new HeapAutomaton with SlexLogging {

    override val description: String = "TRACK(" + numFV + ")"

    private lazy val allFVs = 0 to numFV
    private lazy val allEQs = allEqualitiesOverFVs(numFV)

    override type State = (Set[FV], Set[PureAtom])

    override lazy val states: Set[State] = {
      for {
        // TODO: This also computes plenty (but not all) inconsistent states
        alloc <- Combinators.powerSet(Set() ++ ((1 to numFV) map fv))
        pure <- Combinators.powerSet(allEQs)
      } yield (alloc, pure)
    }

    override def isFinal(s: State): Boolean = s._1 == alloc && s._2 == pure

    // TODO Restriction regarding number of FVs
    override def isDefinedOn(lab: SymbolicHeap): Boolean = true

    override def isTransitionDefined(src: Seq[State], trg: State, lab: SymbolicHeap): Boolean = {
      if (src.length != lab.calledPreds.length) throw new IllegalStateException("Number of predicate calls " + lab.calledPreds.length + " does not match arity of source state sequence " + src.length)

      val compressed = compress(lab, src)
      logger.debug("Compressed " + lab + " into " + compressed)

      // Compute allocation set and equalities for compressed SH and compare to target
      // FIXME: Should we have sanity checks that they are all distinct?
      val allocExplicit: Seq[FV] = compressed.pointers map (_.from)
      val pureExplicit : Set[PureAtom] =  Set() ++ compressed.ptrEqs map orderedAtom

      // Add inequalities for allocated variables
      val inequalitiesFromAlloc : Seq[PureAtom] = Combinators.square(allocExplicit) map {
        case (l,r) => orderedAtom(l, r, false)
      }
      val pureWithAlloc : Set[PureAtom] = pureExplicit ++ inequalitiesFromAlloc

      // Compute fixed point of inequalities and fill up alloc info accordingly
      val computedTrg : State = EqualityUtils.propagateConstraints(allocExplicit.toSet, pureWithAlloc)
      logger.debug("State for compressed SH: " + computedTrg)

      // The transition is enabled iff the target state is equal to the state computed for the compressed SH
      val res = computedTrg == trg
      logger.debug("Transition " + src.mkString(", ") + "--[" + lab + "]-->" + trg + " : " + res)
      res
    }


    private def kernel(s : State) : SymbolicHeap = {

      val pure = s._2

      // FIXME: Here we now assume that the state already contains a closure. If this is not the case, the following does not work.
      //val closure = new ClosureOfAtomSet(pure)
      val closure = UnsafeAtomsAsClosure(pure)

      val nonredundantAlloc = s._1 filter (closure.isMinimumInItsClass(_))

      val alloc : Set[SpatialAtom] = nonredundantAlloc map (p => PointsTo(p, NullPtr()))

      val res = SymbolicHeap(pure.toSeq, alloc.toSeq)

      logger.debug("Converting " + s + " to " + res)

      res
    }

    private def compress(sh : SymbolicHeap, qs : Seq[State]) : SymbolicHeap = {
      val shFiltered = sh.removeCalls
      val newHeaps = qs map kernel
      val combined = SymbolicHeap.combineAllHeaps(shFiltered +: newHeaps)
      combined
    }

  }

}
