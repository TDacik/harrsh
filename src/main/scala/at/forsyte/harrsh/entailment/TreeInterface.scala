package at.forsyte.harrsh.entailment

import at.forsyte.harrsh.seplog.{FreeVar, Var}

case class TreeInterface private(root: NodeLabel, leaves: Set[AbstractLeafNodeLabel], usageInfo: VarUsageByLabel, forceRecompilation: Unit) {

  assert(NodeLabel.noRedundantPlaceholders(labels), s"There are redundant placeholders in $this")

  lazy val labels = Seq[NodeLabel](root) ++ leaves

  override def toString: String = {
    val usageStr = usageInfo.mkString(",")
    s"TI(root = $root; leaves = ${leaves.mkString(",")}; usage = $usageStr)"
  }

  def isConcrete: Boolean = leaves.isEmpty

  def hasNamesForRootParams: Boolean = labels.forall{
    label =>
      // TODO: Get the index directly through the pred. (Change this in other places, too)
      val rootParam = label.pred.rootParam.get
      val rootParamIndex = label.pred.params.indexOf(rootParam)
      val labelingVars: Set[Var] = label.subst.toSeq(rootParamIndex)
      labelingVars.exists(v => v.isFreeNonNull && !PlaceholderVar.isPlaceholder(v))
  }

  def asExtensionType: ExtensionType = ExtensionType(Set(this))

  def nonPlaceholderFreeVars: Set[FreeVar] = {
    substs.flatMap(_.freeNonNullVars).filterNot(PlaceholderVar.isPlaceholder).toSet
  }

  def placeholders: Set[PlaceholderVar] = {
    substs.flatMap(_.placeholders).toSet
  }

  def updateSubst(f: SubstitutionUpdate, convertToNormalform: Boolean): TreeInterface = {
    TreeInterface(root.update(f), leaves map (_.update(f)), VarUsageByLabel.update(usageInfo,f), convertToNormalform = convertToNormalform)
  }

  private lazy val substs = labels map (_.subst)
}

object TreeInterface {

  implicit val canComposeTreeInterfaces: CanCompose[TreeInterface] = CanComposeTreeInterface.canComposeTreeInterfaces

  def apply(root: NodeLabel, leaves: Set[AbstractLeafNodeLabel], usageInfo: VarUsageByLabel, convertToNormalform: Boolean): TreeInterface = {
    if (convertToNormalform) {
      normalFormConversion(root, leaves, usageInfo)
    } else {
      new TreeInterface(root, leaves, usageInfo, ())
    }
  }

  def normalFormConversion(root: NodeLabel, leaves: Set[AbstractLeafNodeLabel], usageInfo: VarUsageByLabel): TreeInterface = {
    val dropper = SubstitutionUpdate.redundantPlaceholderDropper(Set(root) ++ leaves)
    val rootAfterDropping = root.update(dropper)
    val leavesAfterDropping = leaves map (_.update(dropper))
    val usageInfoAfterDropping = VarUsageByLabel.update(usageInfo, dropper)

    val establishNormalForm = NodeLabel.labelsToPlaceholderNormalForm(Seq(rootAfterDropping) ++ leavesAfterDropping)

    new TreeInterface(
      rootAfterDropping.update(establishNormalForm),
      leavesAfterDropping map (_.update(establishNormalForm)),
      VarUsageByLabel.update(usageInfoAfterDropping, establishNormalForm),
      ())
  }

  def isInNormalForm(tif: TreeInterface): Boolean = {
    NodeLabel.noRedundantPlaceholders(tif.labels) && PlaceholderVar.noGapsInPlaceholders(tif.placeholders)
  }

  def haveNoConflicts(tif1: TreeInterface, tif2: TreeInterface): Boolean = {
    (tif1.placeholders intersect tif2.placeholders).isEmpty
  }

}