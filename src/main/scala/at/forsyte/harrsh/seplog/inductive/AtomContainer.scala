package at.forsyte.harrsh.seplog.inductive

import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.seplog.{BoundVar, FreeVar, Renaming, Var}

import scala.collection.SortedSet

case class AtomContainer(pure : Seq[PureAtom], pointers: Seq[PointsTo], predCalls : Seq[PredCall]) extends HarrshLogging {

  lazy val vars = Set.empty ++ pure.flatMap(_.getNonNullVars) ++ pointers.flatMap(_.getNonNullVars) ++ predCalls.flatMap(_.getNonNullVars)

  lazy val freeVarSeq: Seq[FreeVar] = {
    Var.freeNonNullVars(vars).toSeq.sorted
  }

  private val boundVarOrdering = Ordering.fromLessThan[BoundVar](_ < _)

  lazy val boundVars: SortedSet[BoundVar] = {
    SortedSet.empty(boundVarOrdering) ++ Var.boundVars(vars)
  }

  private def renameWithoutDoubleCaptureCheck(f : Renaming) : AtomContainer = {
    AtomContainer(pure.map(_.renameVars(f)), pointers.map(_.renameVars(f)), predCalls.map(_.renameVars(f)))
  }

  /**
    * Applies renaming f to all atoms, returning the resulting renamed atoms
    * @param f The renaming function applied to the symbolic heap
    * @param avoidDoubleCapture If the codomain and f contains bound variables of this symbolic heap, they will renamed to avoid double capture iff this parameter is true.
    * @return
    */
  def rename(f : Renaming, avoidDoubleCapture : Boolean) : AtomContainer = {
    if (avoidDoubleCapture) renameWithoutDoubleCapture(f)._1 else {
      val res = renameWithoutDoubleCaptureCheck(f)
      logger.debug(s"After renaming: $res")
      res
    }
  }

  def renameWithoutDoubleCapture(f : Renaming) : (AtomContainer, Renaming) = {
    logger.info(s"Renaming vars in $this")

    val extendedF : Renaming = {
      logger.debug(s"Will check whether any vars in $boundVars have to be renamed")
      boundVars.foldLeft(f)({
        case (intermediateF, v) =>
          if (!f.isDefinedAt(v)) intermediateF.addBoundVarWithOptionalAlphaConversion(v) else intermediateF
        //intermediateF.addBoundVarWithOptionalAlphaConversion(v)
      })
    }
    logger.debug(s"Map for renaming $f extended to $extendedF")

    val res = renameWithoutDoubleCaptureCheck(extendedF)
    logger.debug(s"After renaming: $res")
    (res, extendedF)
  }

  /**
    * Instantiates the free variables with the given argument expressions.
    *
    * Use case: Rename the free variables of SH to the actual arguments of the predicate calls,
    *
    * @param args Actual args
    * @return
    */
  def instantiateFVs(freeVars : Seq[FreeVar], args : Seq[Var]): AtomContainer = {
    if (freeVars.length != args.length) {
      throw new IllegalArgumentException(s"Trying to substitute a symbolic heap with free vars $freeVars for a symbolic heap with different number of free vars ($args)")
    }
    val pairs: Seq[(Var, Var)] = freeVars zip args
    rename(Renaming.fromPairs(pairs), avoidDoubleCapture = true)
  }

  def shiftBoundVars(vars : Set[BoundVar], shiftTo : Int) : AtomContainer = {
    assert(vars.subsetOf(boundVars))
    val pairs = vars.zipWithIndex.map(pair => (pair._1, BoundVar(pair._2 + shiftTo)))
    logger.trace(s"Will execute shifting to $shiftTo => using pairs " + pairs.mkString(", "))
    renameWithoutDoubleCaptureCheck(Renaming.fromPairs(pairs))
  }

  /**
    * Closes the gaps in the sequence of bound variables (if any) by renaming bound vars
    * @return
    */
  def closeGapsInBoundVars: AtomContainer = {
    if (boundVars.nonEmpty && boundVars.size != boundVars.maxBy(_.index).index) {
      logger.trace("Gap in bound vars: Max bound var " + boundVars.maxBy(_.index).index + ", #bound vars " + boundVars.size + " (bound vars: " + boundVars.mkString(", ") + " in " + this + ")")
      val renamingPairs: Seq[(Var, Var)] = boundVars.toSeq.zipWithIndex.map {
        pair => (pair._1, BoundVar(pair._2 + 1))
      }
      logger.trace("Will close gaps by renaming " + renamingPairs.mkString(", "))
      val renaming = Renaming.fromPairs(renamingPairs)
      val res = renameWithoutDoubleCaptureCheck(renaming)
      logger.debug(s"Closed gaps in $this yielding: $res")
      res
    } else {
      this
    }
  }

}
