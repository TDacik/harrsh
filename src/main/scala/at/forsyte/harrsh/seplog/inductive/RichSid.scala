package at.forsyte.harrsh.seplog.inductive

import at.forsyte.harrsh.seplog.inductive.RichSid.{FocusedVar, RootFocus, SinkFocus}
import at.forsyte.harrsh.seplog.{FreeVar, Var}

case class RichSid(override val startPred : String,
                   override val preds : Seq[Predicate],
                   override val description : String,
                   roots: Map[String, FreeVar],
                   sinks: Map[String, FreeVar] = Map.empty) extends SidLike
{
  lazy val isRooted: Boolean = roots.size == preds.size
  lazy val isReverseRooted: Boolean = sinks.size == preds.size
  lazy val isFocused: Boolean = (roots ++ sinks).size == preds.size
  lazy val hasMixedFoxu: Boolean = isFocused && !isRooted && !isReverseRooted

  lazy val hasEmptyBaseRules: Boolean = predsWithEmptyModels.nonEmpty

  def focus(head: String): FocusedVar = {
    roots.get(head).map(FocusedVar(_, RootFocus)).getOrElse(FocusedVar(sinks(head), SinkFocus))
  }

  lazy val rootParamIndex: Map[String, Int] = {
    for {
      (predIdent, pred) <- predMap
      root <- roots.get(predIdent)
    } yield (predIdent, pred.params.indexOf(root))
  }

  lazy val satisfiesGeneralizedProgress: Boolean = {
    val violatingRules = rulesViolatingProgress
    if (violatingRules.nonEmpty) {
      logger.info(s"SID violates progress:\n${violatingRules.mkString("\n")}")
    }
    violatingRules.isEmpty
  }

  def rulesViolatingProgress: Seq[(Predicate, RuleBody)] = {
    for {
      pred <- preds
      rule <- pred.rules
      if !rule.satisfiesGeneralizedProgress(roots.get(pred.head))
    } yield (pred, rule)
  }

  private lazy val predsWithEmptyModels = EmptyPredicates(this)

  def canBeEmpty(pred: String): Boolean = predsWithEmptyModels(pred).nonEmpty

  def constraintOptionsForEmptyModels(pred: String): Set[Set[PureAtom]] = predsWithEmptyModels(pred)

  def underlying: Sid = Sid(startPred, preds, description)

  override def toString: String = underlying.toString

  def prettyPrint: String = underlying.toString

}

object RichSid {

  def fromSid(sid: SidLike, roots: Map[String, FreeVar], sinks: Map[String, FreeVar] = Map.empty): RichSid = {
    RichSid(sid.startPred, sid.preds, sid.description, roots, sinks)
  }

  def empty : RichSid = RichSid("X", Seq.empty[Predicate], "", Map.empty)

  sealed trait FocusDirection {
    val name: String
    def ptoArgsForDirection(pto: PointsTo): Set[Var]
  }
  case object RootFocus extends FocusDirection {
    override def ptoArgsForDirection(pto: PointsTo): Set[Var] = Set(pto.from)

    override val name: String = "root"
  }
  case object SinkFocus extends FocusDirection {
    override def ptoArgsForDirection(pto: PointsTo): Set[Var] = pto.to.toSet

    override val name: String = "sink"
  }
  case class FocusedVar(fv: FreeVar, dir: FocusDirection)

}