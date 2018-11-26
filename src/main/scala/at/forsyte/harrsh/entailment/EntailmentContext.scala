package at.forsyte.harrsh.entailment

import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.seplog.inductive.PredCall
import at.forsyte.harrsh.seplog.{BoundVar, FreeVar, NullConst, Var}

case class EntailmentContext private(root: PredicateNodeLabel, calls: Set[PredicateNodeLabel], usageInfo: VarUsageByLabel, pureConstraints: PureConstraintTracker) extends HarrshLogging {

  assert(EntailmentContext.noRedundantPlaceholders(labels), s"There are redundant placeholders in $this")
  assert(EntailmentContext.nodeLabelsAndUsageInfoContainSameVars(this), s"Inconsistent entailment context $this: There is a difference between the variables in the usage info and in the substitutions")

  lazy val labels: Seq[NodeLabel] = Seq[NodeLabel](root) ++ calls

  lazy val placeholders: Set[PlaceholderVar] = substs.flatMap(_.placeholders).toSet

  lazy val boundVars: Set[BoundVar] = substs.flatMap(_.boundVars).toSet

  private lazy val substs = labels map (_.subst)

  override def toString: String = {
    val callsString = if (calls.isEmpty) "empty" else calls.mkString(",")
    val usageStr = usageInfo.map{
      case (vs, usage) => vs.mkString(",") + ": " + usage.shortString
    }.mkString("; ")
    val ensuredStr = if (pureConstraints.ensured.nonEmpty) {
      pureConstraints.ensured.mkString("; ensured = {", ",", "}")
    } else ""
    val missingStr = if (pureConstraints.missing.nonEmpty) {
      pureConstraints.missing.mkString("; missing = {", ",", "}")
    } else ""
    s"Ctx(root = $root; calls = $callsString; usage = {$usageStr}$ensuredStr$missingStr)"
  }

  def isConcrete: Boolean = calls.isEmpty

  def hasConsistentPureConstraints: Boolean = pureConstraints.isConsistent

  def hasNamesForRootParams: Boolean = rootParamSubsts.forall {
    labelingVars => labelingVars.exists(PlaceholderVar.isNonPlaceholderNonNullFreeVar)
  }

  def rootParamSubsts: Seq[Set[Var]] = labels flatMap (_.rootParamSubst)

  def isFinalFor(call: PredCall): Boolean = {
    val rootPred = root.pred
    Stream(
      isConcrete, // It represents a concrete tree...
      rootPred.head == call.name, // ...rooted in the correct predicate...
      pureConstraints.missing.isEmpty, // ...without missing pure constraints...
      (call.args, root.subst.toSeq).zipped.forall{
        // ...and with the correct vector of variables at the root (corresponding to the goal predicate call)
        case (arg, substVal) => substVal.contains(arg)
      }
    ).forall(b => b)
  }

  def asExtensionType: ContextDecomposition = ContextDecomposition(Set(this))

  def nonPlaceholderFreeVars: Set[FreeVar] = {
    substs.flatMap(_.freeNonNullVars).filterNot(PlaceholderVar.isPlaceholder).toSet
  }

  def updateSubst(f: SubstitutionUpdate, convertToNormalform: Boolean): EntailmentContext = {
    EntailmentContext(root.update(f),
      calls map (_.update(f)),
      VarUsageByLabel.update(usageInfo, f),
      pureConstraints.update(f),
      convertToNormalform)
  }

  def dropVarsFromPureConstraints(varsToDrop: Set[Var]): EntailmentContext = {
    EntailmentContext(root, calls, usageInfo, pureConstraints.dropVars(varsToDrop), convertToNormalform = false)
  }

  def usageInfoOfNode(n: NodeLabel): VarUsageInfo = {
    val res = n.subst.toSeq.map(usageInfo.getOrElse(_, VarUnused))
    logger.debug(s"Usage info for $n w.r.t. $this: $res")
    res
  }

  def allFreeRootParamsUsed: Boolean = {
    val rootsUsed = for {
      node <- labels.toStream
      rootParam <- node.pred.rootParam
      ix = node.freeVarSeq.indexOf(rootParam)
      // If a root parameter is a placeholder (i.e., not a proper free variable)
      if node.subst.toSeq(ix) exists PlaceholderVar.isNonPlaceholderNonNullFreeVar
      usage = usageInfoOfNode(node)
    } yield usage(ix).isUsed
    rootsUsed.forall(b => b)
  }

  def hasNamesForUsedParams: Boolean = {
    // Everything that's used has a name
    val enoughNamesByNodeAndParam = for {
      node <- labels.toStream
      usageByVar = (node.subst.toSeq, usageInfoOfNode(node)).zipped
      (substVars, usg) <- usageByVar
      res = !usg.isUsed || substVars.exists(!PlaceholderVar.isPlaceholder(_))
      _ = {
        if (!res) logger.debug(s"Not enough names for $node: $substVars is not marked as used")
      }
    } yield res
    enoughNamesByNodeAndParam.forall(b => b)
  }
}

object EntailmentContext extends HarrshLogging {

  def apply(root: PredicateNodeLabel, leaves: Set[PredicateNodeLabel], usageInfo: VarUsageByLabel, pureConstraints: PureConstraintTracker, convertToNormalform: Boolean): EntailmentContext = {
    if (convertToNormalform) {
      normalFormConversion(root, leaves, usageInfo, pureConstraints)
    } else {
      new EntailmentContext(root, leaves, usageInfo, pureConstraints)
    }
  }

  def normalFormConversion(root: PredicateNodeLabel, leaves: Set[PredicateNodeLabel], usageInfo: VarUsageByLabel, pureConstraints: PureConstraintTracker): EntailmentContext = {
    val dropper = SubstitutionUpdate.redundantPlaceholderDropper(Set(root) ++ leaves)
    val rootAfterDropping = root.update(dropper)
    val leavesAfterDropping = leaves map (_.update(dropper))
    val usageInfoAfterDropping = VarUsageByLabel.update(usageInfo, dropper)
    val diseqsAfterDropping = pureConstraints.update(dropper)

    val establishNormalForm = NodeLabel.labelsToPlaceholderNormalForm(Seq(rootAfterDropping) ++ leavesAfterDropping)

    new EntailmentContext(
      rootAfterDropping.update(establishNormalForm),
      leavesAfterDropping map (_.update(establishNormalForm)),
      VarUsageByLabel.update(usageInfoAfterDropping, establishNormalForm),
      diseqsAfterDropping.update(establishNormalForm))
  }

  def isInNormalForm(ctx: EntailmentContext): Boolean = {
    noRedundantPlaceholders(ctx.labels) && PlaceholderVar.noGapsInPlaceholders(ctx.placeholders)
  }

  def haveNoConflicts(ctx1: EntailmentContext, ctx2: EntailmentContext): Boolean = {
    (ctx1.placeholders intersect ctx2.placeholders).isEmpty
  }

  def noRedundantPlaceholders(labels: Iterable[NodeLabel]): Boolean = {
    labels.forall{
      nodeLabel => nodeLabel.subst.toSeq.forall(PlaceholderVar.containsNoRedundantPlaceholder)
    }
  }

  def nodeLabelsAndUsageInfoContainSameVars(ctx: EntailmentContext): Boolean = {
    val varsOccurringInNodeLabels: Set[Set[Var]] = ctx.substs.toSet[Substitution].flatMap(subst => subst.toSeq)
    val varsInUsageInfo = ctx.usageInfo.keySet
    logger.debug(s"Vars in node labels: $varsOccurringInNodeLabels / Vars in usage info: $varsInUsageInfo")
    // FIXME: [ENTAILMENT CLEANUP] Does it make sense to drop null here?
    // FIXME: [ENTAILMENT CLEANUP] Do we really have to enforce that the variables are the same? Isn't it enough that we have usage info for everything that occurs in the nodes? (Supposing that forgetting free vars works correctly, that should be sound? It might even be the only sound thing to do, because we might otherwise lose info about double allocation?)
    // varsOccurringInNodeLabels == varsInUsageInfo
    //varsOccurringInNodeLabels.filterNot(_.contains(NullConst)) == varsInUsageInfo.filterNot(_.contains(NullConst))
    varsOccurringInNodeLabels.filterNot(_.contains(NullConst)) subsetOf varsInUsageInfo.filterNot(_.contains(NullConst))
  }

}