package at.forsyte.harrsh.entailment.learning

import at.forsyte.harrsh.entailment._
import at.forsyte.harrsh.entailment.learning.EntailmentLearningLog.RedEntCheck.ExtensionCompatibilityCheck
import at.forsyte.harrsh.entailment.learning.EntailmentLearningLog._
import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.pure.ConsistencyCheck
import at.forsyte.harrsh.seplog.inductive.{PredCall, SID, SymbolicHeap}

/**
  * Created by jens on 4/25/17.
  */
case class ObservationTable private (sid : SID, entries : Seq[TableEntry], override val learningLog: EntailmentLearningLog) extends HarrshLogging with ReducedEntailmentEngine {

  override val reportProgress: Boolean = learningLog.reportProgress && EntailmentAutomatonLearning.ReportMCProgress

  override def toString: String = entries.map("  " + _).mkString("ObservationTable(\n", "\n", "\n)")

  override def equals(o: scala.Any): Boolean = {
    // Overriding equals to ignore the log
    o match {
      case other : ObservationTable => other.entries == entries
      case _ => false
    }
  }

  override def hashCode(): Int = {
    // Overriding equals to ignore the log
    entries.hashCode()
  }

  def numClasses : Int = entries.size

  def finalClasses = entries.filter(_.isFinal)

  /**
    * Searches for an entry whose associated equivalence class contains sh
    * @param sh Heap against which the entries/equivalence classes are checked
    * @return Some table entry representing the equivalence class of sh or None
    */
  def findEntryForEquivalenceClassOf(sh : SymbolicHeap, filterPredicate : TableEntry => Boolean) : Option[TableEntry] = {
    logger.debug("Trying to find equivalence class for " + sh)
    val entriesToCheck = entries filter filterPredicate
    val res = entriesToCheck.find {
      _.equivalenceClassContains(sh, sid, this)
    }
    res.foreach(entry => {
      logger.debug("Can reduce " + sh + " to " + entry.reps)
      learningLog.logEvent(TableLookupOperation(TableOperations.FoundReduction(sh, entry)))
    })
    res
  }

  def getAllMatchingEntries(sh : SymbolicHeap) : Seq[TableEntry] = {
    // TODO Code duplication wrt findEntryForEquivalenceClassOf
    logger.debug("Finding all entries for " + sh)
    val res = entries filter {
      entry =>
        entry.equivalenceClassContains(sh, sid, this)
    }
    res.foreach(entry => {
      logger.debug("Can reduce " + sh + " to " + entry.reps)
      learningLog.logEvent(TableLookupOperation(TableOperations.FoundReduction(sh, entry)))
    })
    res
  }

  def accepts(sh : SymbolicHeap, verbose : Boolean = false) : Boolean = {
    // Note: Checking only final classes is not only good for performance, but actually necessary at this points,
    // because the ObservationTable does *not* resolve all nondeterminism!
    val entry = findEntryForEquivalenceClassOf(sh, _.isFinal)
    if (verbose) entry match {
      case Some(x) => println(sh + " is in class of " + x) // + " generated by " + x.repSid)
      case None => println("No match for " + sh)
    }
    entry.exists(_.isFinal)
  }

  def rejects(sh : SymbolicHeap, verbose : Boolean = false) : Boolean = !accepts(sh, verbose)

  def addRepresentativeToEntry(entry : TableEntry, rep : SymbolicHeap) : ObservationTable = {
    addRepresentativeToEntryAndReturnEntry(entry, rep)._1
  }

  def addRepresentativeToEntryAndReturnEntry(entry : TableEntry, rep : SymbolicHeap) : (ObservationTable,TableEntry) = {
    learningLog.logEvent(TableUpdateOperation(TableOperations.EnlargedEntry(entry, rep, isNewRepresentative = true)))

    // TODO Maybe come up with a more efficient implementation of table entry update... Also code duplication
    val ix = entries.indexOf(entry)
    val newEntry = entry.addRepresentative(rep)
    val newEntries = entries.updated(ix, newEntry)
    (copy(entries = newEntries), newEntry)
  }

  def addExtensionToEntry(entry: TableEntry, partition: SymbolicHeapPartition, it: Int): ObservationTable = {

    if (entry.reps.size == 1) {
      // Just one representative => new extension for the same representative => simply extend entry
      addExtensionToEntryWithoutCompatibilityCheck(entry, partition.ext, partition.extPredCall)
    } else {
      logger.debug("Entry has " + entry.reps.size + " representatives => consider splitting in two")

      // There is more than one representative
      // The new extension only corresponds to one of those representatives
      // => We might have to split the entry depending on entailment versus the new extension
      val (compatibleWithNewExtension, incompatibleWithNewExtension) = entry.reps.partition {
        rep =>
          val combination = SymbolicHeapPartition(rep, partition.ext, partition.extPredCall).recombined
          // If the combination is inconsistent, we conclude incompatibility,
          // since inconsistent heaps are never SID unfoldings
          // (recall that our algorithm assumes that all unfoldings of the SID are satisfiable!)
          if (!ConsistencyCheck.isConsistent(combination)) {
            logger.debug("Checking compatibility of " + rep + " with " + partition.ext + " => Incompatible, since combination " + combination + " is inconsistent")
            false
          } else {
            logger.debug("Checking compatibility of " + rep + " with " + partition.ext + " (combination: " + combination + ")")
            reducedEntailment(combination, sid.callToStartPred, sid, ExtensionCompatibilityCheck(rep, partition.ext))
          }
      }

      if (incompatibleWithNewExtension.isEmpty) {
        logger.debug("No splitting necessary")
        addExtensionToEntryWithoutCompatibilityCheck(entry, partition.ext, partition.extPredCall)
      } else {
        logger.debug("Splitting into compatible reps " + compatibleWithNewExtension.mkString(", ") + " and incomptaible reps " + incompatibleWithNewExtension.mkString(", "))
        splitEntry(entry, compatibleWithNewExtension, incompatibleWithNewExtension, partition.ext, partition.extPredCall)
      }
    }

  }

  private def addExtensionToEntryWithoutCompatibilityCheck(entry : TableEntry, ext : SymbolicHeap, extPredCall : PredCall) : ObservationTable = {
    learningLog.logEvent(TableUpdateOperation(TableOperations.EnlargedEntry(entry, ext, isNewRepresentative = false)))

    // TODO Maybe come up with a more efficient implementation of table entry update... Also code duplication
    val ix = entries.indexOf(entry)
    val newEntries = entries.updated(ix, entry.addExtension(ext, extPredCall))
    copy(entries = newEntries)
  }

  private def splitEntry(entry: TableEntry, compatibleWithNewExtension: Set[SymbolicHeap], incompatibleWithNewExtension: Set[SymbolicHeap], newExtension: SymbolicHeap, newExtensionCall : PredCall): ObservationTable = {
    learningLog.logEvent(TableUpdateOperation(TableOperations.SplitTableEntry(compatibleWithNewExtension, incompatibleWithNewExtension, newExtension)))

    // TODO Same concern about efficient table entry as above...
    val entriesWithoutOldEntry = entries.filterNot(_ == entry)
    val compatibleEntry = entry.copy(reps = compatibleWithNewExtension).addExtension(newExtension, newExtensionCall)
    val incompatibleEntry = entry.copy(reps = incompatibleWithNewExtension)

    copy(entries = entriesWithoutOldEntry :+ compatibleEntry :+ incompatibleEntry)
  }

  def addNewEntryForPartition(partition : SymbolicHeapPartition, iteration : Int): ObservationTable = {
    val cleanedPartition = if (EntailmentAutomatonLearning.CleanUpSymbolicHeaps) partition.simplify else partition
    learningLog.logEvent(TableUpdateOperation(EntailmentLearningLog.TableOperations.NewEntry(entries.size+1, cleanedPartition)))
    copy(entries = entries :+ tableEntryFromPartition(cleanedPartition, iteration))
  }

  private def tableEntryFromPartition(part : SymbolicHeapPartition, iteration : Int) : TableEntry = {
    TableEntry(
      Set(part.rep),
      Set((part.ext,part.extPredCall)),
      //RepresentativeSIDComputation.adaptSIDToRepresentative(sid, part.rep),
      // TODO It should be sufficient to just check for emp in the extension set, but then we might have to update "finality" later, because it is possible that we first discover rep with a nonempty extension
      reducedEntailment(part.rep, sid.callToStartPred, sid, EntailmentLearningLog.RedEntCheck.FinalityCheck()),
      iteration,
      introducedThroughClosure = false)
  }

}

object ObservationTable {

  def empty(sid : SID, entailmentLearningLog: EntailmentLearningLog) : ObservationTable = ObservationTable(sid, Seq.empty, entailmentLearningLog)

}
