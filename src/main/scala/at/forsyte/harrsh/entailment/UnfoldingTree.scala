package at.forsyte.harrsh.entailment

import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.seplog.{BoundVar, FreeVar, NullConst, Var}
import at.forsyte.harrsh.seplog.inductive._

case class UnfoldingTree(nodeLabels: Map[NodeId,NodeLabel], root: NodeId, children: Map[NodeId, Seq[NodeId]]) extends HarrshLogging {

  // FIXME: Validate that the tree is "sufficiently" labeled: It has free variables for all root parameters of the interface nodes + possibly for some other nodes as well (e.g., parameter corresponding to a backpointer in a dll)

  import at.forsyte.harrsh.entailment.UnfoldingTree._

  lazy val nodes: Set[NodeId] = nodeLabels.keySet

  lazy val abstractLeaves: Set[NodeId] = nodes.filter(n => nodeLabels(n).isAbstractLeaf)

  def isAbstractLeaf(nodeId: NodeId): Boolean = nodeLabels(nodeId).isAbstractLeaf

  assert(nodes.contains(root))
  assert(children.keys forall nodes.contains)
  assert(children.values forall (_ forall nodes.contains))
  assert(abstractLeaves forall (children(_).isEmpty))

  // TODO: The following does not hold for "degenerated" trees as derived from extension types. (Because those don't contain concrete children, so nodes for recursive rules need not have children). Do we want to perform this check in "non-degenerated" cases
//  assert(nodes forall (n => children(n).nonEmpty
//    || isAbstractLeaf(n)
//    || (!isAbstractLeaf(n) && nodeLabels(n).asInstanceOf[RuleNodeLabel].rule.isBaseRule)),
//    s"Inconsistent unfolding tree $this")

  lazy val parents: Map[NodeId, NodeId] = {
    for {
      (parent, succs) <- children
      child <- succs
    } yield (child, parent)
  }

  override def toString: String = {
    val sb = new StringBuilder("UnfoldingTree(\n")
    for {
      id <- nodes.toSeq.sorted
    } {
      sb.appendAll(s"  $id -> ${nodeLabels(id)} : ${children(id).mkString(", ")}\n")
    }
    sb.append(')')
    sb.toString
  }

  def isConcrete: Boolean = abstractLeaves.isEmpty

  def interface: TreeInterface = {
    TreeInterface(nodeLabels(root), abstractLeaves map (nodeLabels(_).asInstanceOf[AbstractLeafNodeLabel]))
  }

  def unfold(leaf: NodeId, sid: SID, rule: RuleBody): UnfoldingTree = {
    // TODO: Split method into smaller pieces
    assert(abstractLeaves.contains(leaf))

    val pred = nodeLabels(leaf).pred
    assert(pred.rules.contains(rule))

    val leafSubst = nodeLabels(leaf).subst
    val rootLabel = RuleNodeLabel(pred, rule, leafSubst)
    val boundVars = rule.body.boundVars
    val allUnusedPlaceholders = for {
      i <- Stream.from(0)
      pv = PlaceholderVar(i)
      if !leafSubst.placeholders.contains(pv)
    } yield pv.toFreeVar
    val boundVarsToPlaceholders = boundVars.zip(allUnusedPlaceholders).toMap

    val mkSubst = {
      args: Seq[Var] =>
        val targets = args.map {
          case fv: FreeVar => Set[Var](fv)
          case NullConst => throw new NotImplementedError
          case bv:BoundVar => Set[Var](boundVarsToPlaceholders(bv))
        }
        Substitution(targets)
    }

    val childLabels = rule.body.predCalls map (call => AbstractLeafNodeLabel(sid(call.name), mkSubst(call.args)))
    val ids = NodeId.freshIds(Set.empty, childLabels.size + 1)
    val ruleTreeNodeLabels = (ids, rootLabel +: childLabels).zipped.toMap
    val ruleTreeChildTuples = (ids.head -> ids.tail) +: ids.tail.zip(Stream.continually(Seq.empty))

    val ruleTree = UnfoldingTree(
      nodeLabels = ruleTreeNodeLabels,
      root = ids.head,
      children = ruleTreeChildTuples.toMap
    )
    val shiftedRuleTree = ruleTree.avoidClashesWith(this)

    // Shifting may have renamed placeholder vars at the root (if there are any), which we revert through unification
    val rootSubst = shiftedRuleTree.nodeLabels(shiftedRuleTree.root).subst
    val unification: Unification = (leafSubst.toSeq, rootSubst.toSeq).zipped.map(_ union _)

    instantiate(leaf, shiftedRuleTree, unification)
  }

  def project(retainCalls: Boolean = false): SymbolicHeap = {

    def projectNode(node: NodeId): SymbolicHeap = {
      val label = nodeLabels(node)
      val withoutSubst = label match {
        case RuleNodeLabel(_, rule, _) =>
          val childProjections = children(node) map projectNode
          rule.body.replaceCalls(childProjections)
        case AbstractLeafNodeLabel(pred, _) =>
          if (retainCalls) pred.defaultCall else SymbolicHeap.empty
      }
      withoutSubst.copy(pure = withoutSubst.pure ++ label.subst.toAtoms(label.pred.params))
    }

    projectNode(root)
  }

  private def avoidClashesWith(other: UnfoldingTree): UnfoldingTree = {
    val shifted = avoidIdClashWith(other)
    val res = shifted.avoidPlaceHolderClashWith(other)
    assert(haveNoConflicts(other, res),
      s"After shifting/renaming, still conflicts between $other and $res (with placeholders ${other.placeholders} and ${res.placeholders} and nodes ${other.nodes} and ${res.nodes})")
    res
  }

  private def avoidPlaceHolderClashWith(other: UnfoldingTree): UnfoldingTree = {
    val clashAvoidanceUpdate = PlaceholderVar.placeholderClashAvoidanceUpdate(other)
    updateSubst(clashAvoidanceUpdate)
  }

  private def avoidIdClashWith(other: UnfoldingTree): UnfoldingTree = {
    val nodes = other.nodes
    val fresh = NodeId.freshIds(usedIds = nodes, numIds = this.nodes.size)
    val renaming: Map[NodeId, NodeId] = (this.nodes.toSeq.sorted, fresh).zipped.toMap
    val renamedLabels = nodeLabels map {
      case (k, l) => (renaming(k), l)
    }
    val renamedChildren = children map {
      case (p, cs) => (renaming(p), cs map renaming)
    }
    UnfoldingTree(renamedLabels, renaming(root), renamedChildren)
  }

  private def cleanUpPlaceholders: UnfoldingTree = {
    val withoutRedundant = dropRedundantPlaceholders
    withoutRedundant.closeGapsInPlaceholders
  }

  private def closeGapsInPlaceholders: UnfoldingTree = {
    val currentPlaceholders = placeholders.toSeq.sortBy(_.index)
    val newPlaceholders = (1 to currentPlaceholders.length) map (PlaceholderVar(_))
    val replacement = (currentPlaceholders, newPlaceholders).zipped.toMap
    val updateF: SubstitutionUpdate = {
      v => PlaceholderVar.fromVar(v) match {
        case Some(pv) => Set(replacement(pv).toFreeVar)
        case None => Set(v)
      }
    }
    updateSubst(updateF)
  }

  private def dropRedundantPlaceholders: UnfoldingTree = {
    def getRedundantVars(vs: Set[Var]): Set[Var] = {
      val (phs, nonPhs) = vs.partition(PlaceholderVar.isPlaceholder)
      if (nonPhs.nonEmpty) {
        // There is a proper free var in this equivalence class => discard all equivalent placeholders
        phs
      } else {
        // Keep only the smalles placeholder among multiple placeholders
        val typedPhs = phs map (ph => PlaceholderVar.fromVar(ph).get)
        phs - PlaceholderVar.min(typedPhs).toFreeVar
      }
    }
    val equivalenceClasses = extractVarEquivClasses(nodeLabels.values)
    val redundantVars = equivalenceClasses.flatMap(getRedundantVars)
    logger.debug(s"Reundant vars: $redundantVars")

    val updateF: SubstitutionUpdate = {
      v => if (redundantVars.contains(v)) Set.empty else Set(v)
    }
    updateSubst(updateF)
  }

  def extendLabeling(unification: Unification): UnfoldingTree = {
    val updateFn : SubstitutionUpdate = {
      v => unification.find(_.contains(v)).getOrElse(Set(v))
    }
    updateSubst(updateFn)
  }

  def instantiate(abstractLeaf: NodeId, replacingTree: UnfoldingTree, unification: Unification): UnfoldingTree = {
    assert(haveNoConflicts(this, replacingTree))
    logger.debug(s"Replacing $abstractLeaf in $this with $replacingTree")
    val thisExtended = this.extendLabeling(unification)
    val otherExtended = replacingTree.extendLabeling(unification)
    // TODO: This instantiation 'leaks' the ID of abstractLeaf: It will not be used in the tree that we get after instantiation. I'm afraid this may complicate debugging.
    val combinedNodeLabels = (thisExtended.nodeLabels ++ otherExtended.nodeLabels) - abstractLeaf

    // Connect the parent of the replaced leaf with the root of the replacing tree
    val maybeParent = thisExtended.parents.get(abstractLeaf)
    val updatedChildren = maybeParent match {
      case Some(parent) =>
        val newParentsChildren = thisExtended.children(parent).map{
          child => if (child == abstractLeaf) otherExtended.root else child
        }
        logger.debug(s"Updating children of $parent from ${thisExtended.children(parent)} to $newParentsChildren")
        thisExtended.children.updated(parent, newParentsChildren)
      case None =>
        thisExtended.children
    }

    // TODO: Another position where the 'leak' manifests
    val combinedChildren = (updatedChildren ++ otherExtended.children) - abstractLeaf

    val newRoot = if (maybeParent.nonEmpty) {
      // The replaced leaf wasn't the root => The root remains unchanged
      thisExtended.root
    } else {
      // The replace leaf was the root => The new root is the root of the replacing tree
      otherExtended.root
    }

    val combinedTree = UnfoldingTree(combinedNodeLabels, newRoot, combinedChildren)
    combinedTree.cleanUpPlaceholders
  }

  def compose(other: UnfoldingTree): Option[(UnfoldingTree, Unification)] = {
    logger.debug(s"Will try to compose $this with $other.")
    val shifted = other.avoidClashesWith(this)
    logger.debug(s"Other after shifting to avoid clashes with $nodes and $placeholders: $shifted")

    (for {
      CompositionInterface(t1, t2, n2) <- compositionCandidates(this, shifted)
      unification <- tryUnify(t1.nodeLabels(t1.root), t2.nodeLabels(n2))
      // Compose using the unification. (This can fail in case the unification leads to double allocation)
      instantiation = t2.instantiate(n2, t1, unification)
      if !instantiation.hasDoubleAllocation
    } yield (instantiation, unification)).headOption
  }

  def hasDoubleAllocation: Boolean = {
    // FIXME: Implement double allocation check (and possibly other validation as well -- sufficiently many names in interface nodes!)
    false
  }

  def placeholders: Set[PlaceholderVar] = nodeLabels.values.flatMap(_.placeholders).toSet

  def updateSubst(f: SubstitutionUpdate): UnfoldingTree = {
    val updatedLabels = nodeLabels.map {
      case (id,label) => (id, label.update(f))
    }
    copy(nodeLabels = updatedLabels)
  }

}

object UnfoldingTree extends HarrshLogging {

  implicit val treeToLatex = ForestsToLatex.treeToLatex

  private def getSubstOrDefault(subst: Option[Substitution], pred: Predicate): Substitution = {
    subst.getOrElse(Substitution.identity(pred.params))
  }

  def singleton(sid: SID, pred: Predicate, subst: Option[Substitution] = None): UnfoldingTree = {
    val label = AbstractLeafNodeLabel(pred, getSubstOrDefault(subst, pred))
    val id = NodeId.zero
    UnfoldingTree(Map(id -> label), id, Map(id -> Seq.empty))
  }

  def extractVarEquivClasses(labels: Iterable[NodeLabel]) : Set[Set[Var]] = Substitution.extractVarEquivClasses(labels map (_.subst))

  def haveNoConflicts(ut1: UnfoldingTree, ut2: UnfoldingTree) : Boolean = {
    (ut1.nodes intersect ut2.nodes).isEmpty && (ut1.placeholders intersect ut2.placeholders).isEmpty
  }

  def fromPredicate(sid: SID, pred: String, labeling: Substitution): UnfoldingTree = {
    val node = AbstractLeafNodeLabel(sid(pred), labeling)
    UnfoldingTree(Map(NodeId.zero -> node), NodeId.zero, Map.empty)
  }

  def placeholderNormalForm(ut: UnfoldingTree, nodeLabelOrder: Seq[NodeLabel]): UnfoldingTree = {
    val updateF = NodeLabel.labelsToPlaceholderNormalForm(nodeLabelOrder)
    ut.updateSubst(updateF)
  }

  /**
    * Ensure that placeholder vars are named ?1, ?2... without gap and increasing with the distance to the root.
    * This leads to a normal form for the node labels. (Not for the trees themselves, because the node IDs themselves may have gaps.)
    * @param ut
    * @return
    */
  def placeholderNormalForm(ut: UnfoldingTree): UnfoldingTree = {
    placeholderNormalForm(ut, breadthFirstTraversal(ut))
  }

  def breadthFirstTraversal(tree: UnfoldingTree): Stream[NodeLabel] = {
    def traverseByLayer(nodes: Seq[NodeId]): Stream[NodeLabel] = {
      if (nodes.isEmpty) {
        Stream.empty
      }
      else {
        val childLayer = nodes.flatMap(n => tree.children(n))
        nodes.toStream.map(tree.nodeLabels(_)) ++ traverseByLayer(childLayer)
      }
    }
    traverseByLayer(Seq(tree.root))
  }

  case class CompositionInterface(treeToEmbed: UnfoldingTree, embeddingTarget: UnfoldingTree, leafToReplaceInEmbedding: NodeId)

  private def compositionCandidates(tree1: UnfoldingTree, tree2: UnfoldingTree): Stream[CompositionInterface] = {
    for {
      (treeWithRoot, treeWithAbstractLeaf) <- Stream((tree1,tree2), (tree2,tree1))
      root = treeWithRoot.root
      abstractLeaf <- treeWithAbstractLeaf.abstractLeaves
      // Only consider for composition if the labeling predicates are the same
      if treeWithRoot.nodeLabels(root).pred == treeWithAbstractLeaf.nodeLabels(abstractLeaf).pred
    } yield CompositionInterface(treeWithRoot, treeWithAbstractLeaf, abstractLeaf)
  }

  private def tryUnify(n1: NodeLabel, n2: NodeLabel): Option[Unification] = {
    logger.debug(s"Will try to unify $n1 with $n2")
    // FIXME: Proper unification
    assert(n1.freeVarSeq == n2.freeVarSeq)
    val fvars = n1.freeVarSeq
    if ((n1.rootVarSubst intersect n2.rootVarSubst).nonEmpty) {
      logger.debug(s"Can unify: Overlap between labels of root vars, ${n1.rootVarSubst} and ${n2.rootVarSubst}")
      Some((n1.subst.toSeq, n2.subst.toSeq).zipped.map(_ union _))
    } else {
      logger.debug("No unification possible")
      None
    }
  }

}
