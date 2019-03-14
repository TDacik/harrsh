package at.forsyte.harrsh.entailment

import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.seplog.inductive.RichSid

object ContextDecompositionComposition extends HarrshLogging {

  def apply(sid: RichSid, fst: ContextDecomposition, snd: ContextDecomposition): Seq[ContextDecomposition] = {
    logger.debug(s"Will compose decompositions\n$fst and\n$snd")
    for {
      union <- unionWithoutMerges(fst, snd).toSeq
      merged <- allMergeOptions(sid, union)
      _ = assert(merged.isInPlaceholderNormalForm, s"Composition of decomposition failed to re-establish normal form: $merged")
    } yield merged
  }

  private def unionWithoutMerges(fst: ContextDecomposition, snd: ContextDecomposition): Option[ContextDecomposition] = {
    val (shiftedFst, shiftedSnd) = makePlaceholdersDisjoint(fst, snd)
    val sharedAllocation = shiftedFst.allocedVars.intersect(shiftedSnd.allocedVars)
    if (sharedAllocation.nonEmpty) {
      logger.debug(s"Trying to merge $shiftedFst with $shiftedSnd, but they both allocate $sharedAllocation => Can't compose")
      None
    }
    else {
      logger.trace(s"Decompositions after shifting:\n$shiftedFst and\n$shiftedSnd")

      val mergeNondisjointVarLabelSets = SubstitutionUpdate.unionUpdate(shiftedFst.constraints, shiftedSnd.constraints)
      val maybeUnion = mergeWithUnifyingUpdate(shiftedFst, shiftedSnd, mergeNondisjointVarLabelSets)

      maybeUnion match {
        case Some(union) => logger.debug("Union decomposition (on which merge options will be computed):\n" + union)
        case None => logger.debug("Union is undefined => Discarding decomposition.")
      }

      maybeUnion
    }
  }

  private def mergeWithUnifyingUpdate(fst: ContextDecomposition, snd: ContextDecomposition, upd: SubstitutionUpdate): Option[ContextDecomposition] = {
    val mergedParts = (fst.parts ++ snd.parts).map(_.updateSubst(upd))
    for {
      mergedConstraint <- fst.constraints.mergeUsingUpdate(snd.constraints, upd)
      consistent = mergedConstraint.isConsistent
      _ = if (!consistent) logger.debug(s"Union $mergedConstraint is inconsistent")
      if consistent
    } yield ContextDecomposition(mergedParts, mergedConstraint)
  }

  private def makePlaceholdersDisjoint(fst: ContextDecomposition, snd: ContextDecomposition): (ContextDecomposition, ContextDecomposition) = {
    val clashAvoidanceUpdate = PlaceholderVar.placeholderClashAvoidanceUpdate(snd.placeholders)
    (fst.updateSubst(clashAvoidanceUpdate).get, snd)
  }

  private def allMergeOptions(sid: RichSid, unionDecomp: ContextDecomposition): Seq[ContextDecomposition] = {
    allMergeOptions(sid, Seq.empty, unionDecomp.parts.toSeq, unionDecomp.constraints)
  }

  private def allMergeOptions(sid: RichSid, processed: Seq[EntailmentContext], unprocessed: Seq[EntailmentContext], constraints: VarConstraints): Seq[ContextDecomposition] = {
    if (unprocessed.isEmpty) {
      logger.debug(s"At the end of the merge process:\n$processed")
      // Since the constraints are updated context by context, not all processed contexts will already reflect the new constraints.
      // We thus perform another unification step.
      val upd = SubstitutionUpdate.fromSetsOfEqualVars(constraints.classes)
      val processedAndPropagated = processed map (_.updateSubst(upd))
      logger.debug(s"After propagation of constraints:\n$processedAndPropagated")

      // TODO Do we actually have to clean again or is this ensured automatically via the step-by-step update of the constraints? I think it should be the latter
      // TODO Code duplication w.r.t. ContextDecomposition.occurringLabels
      //val occurringVarSets = processedAndPropagated.toSet[EntailmentContext].flatMap(_.labels).flatMap(_.subst.toSeq)
      //val cleanedUsageInfo = VarUsageByLabel.restrictToOccurringLabels(usageInfo, occurringVarSets)
      //logger.debug("Cleaning usage info: " + usageInfo + " into " + cleanedUsageInfo)
      //val cleanedPureConstraints = propagatedPureConstraints.restrictPlaceholdersTo(occurringVarSets.flatten)
      //val pureWithoutImpliedMissing = cleanedPureConstraints.dropMissingIfImpliedByAllocation(cleanedUsageInfo)

      val composed = ContextDecomposition(processedAndPropagated.toSet, constraints)
      val res = Seq(composed.toPlaceholderNormalForm)
      logger.debug(s"New merge result: $res")
      res
    } else {
      for {
        (nowProcessed, stillUnprocessed, newConstraints, variableMergingImposedByComposition) <- optionalMerge(sid, processed, unprocessed, constraints)
        unprocessedWithMergedVars = stillUnprocessed.map(_.updateSubst(variableMergingImposedByComposition))
        merged <- allMergeOptions(sid, nowProcessed, unprocessedWithMergedVars, newConstraints)
      } yield merged
    }
  }

  private def optionalMerge(sid: RichSid, processed: Seq[EntailmentContext], unprocessed: Seq[EntailmentContext], constraints: VarConstraints): Seq[(Seq[EntailmentContext], Seq[EntailmentContext], VarConstraints, SubstitutionUpdate)] = {
    val (fst, other) = (unprocessed.head, unprocessed.tail)
    (
      // Don't merge fst with anything, just add to processed
      Seq((processed :+ fst, other, constraints, SubstitutionUpdate.fromPairs(Seq.empty)))
        ++ tryMerge(sid, fst, other, constraints).map(t => (processed, t._1 +: t._2, t._3, t._4))
      )
  }

  private def tryMerge(sid: RichSid, fst: EntailmentContext, other: Seq[EntailmentContext], constraints: VarConstraints): Stream[(EntailmentContext, Seq[EntailmentContext], VarConstraints, SubstitutionUpdate)] = {
    for {
      candidate <- other.toStream
      _ = logger.debug(s"Will try to compose $fst with $candidate wrt constraints $constraints.")
      ((composed, newConstraints, variableMergingImposedByComposition), i) <- EntailmentContextComposition(sid, fst, candidate, constraints).zipWithIndex
      _ = logger.debug(s"Composition success #${i+1}: Composed context $composed with constraints $newConstraints")
    } yield (composed, other.filter(_ != candidate), newConstraints, variableMergingImposedByComposition)
  }

}
