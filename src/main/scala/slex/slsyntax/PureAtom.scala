package slex.slsyntax

import slex.Sorts.Location
import slex.algs.{Evaluator, Stack}
import slex.smtsyntax.SmtExpr
import slex.smtsyntax.SmtExpr._

/**
  * Created by jkatelaa on 10/3/16.
  */
sealed trait PureAtom extends SepLogFormula with PureFormula {

  override def isSpatial = false

  override def isPure = true

  override def isSymbolicHeap = true

  override def toSymbolicHeap = Some(SymbolicHeap(Seq(this), Seq(), Seq()))



  private def syntaxBasedConstantEval : Option[Boolean] = {
    def foldIfSyntacticallyEqual(res : => Boolean, l : Expr, r : Expr) : Option[Boolean] = if (l == r) Some(res) else None

    this match {
      case True() => None
      case False() => None
      case IxEq(l, r) => foldIfSyntacticallyEqual(true, l, r)
      case IxGT(l, r) => foldIfSyntacticallyEqual(false, l, r)
      case IxLT(l, r) => foldIfSyntacticallyEqual(false, l, r)
      case IxLEq(l, r) => foldIfSyntacticallyEqual(true, l, r)
      case IxGEq(l, r) => foldIfSyntacticallyEqual(true, l, r)
      case IntNEq(l, r) => foldIfSyntacticallyEqual(false, l, r)
      case PtrEq(l, r) => foldIfSyntacticallyEqual(true, l, r)
      case PtrNEq(l, r) => foldIfSyntacticallyEqual(false, l, r)
    }
  }

  override def constantEval: Option[Boolean] = {
    //println("Trying to eval " + this + " to a constant")
    try {
      // Try to evaluate on constant stack. If that's possible, we have a constant value...
      val res = Evaluator.eval(dummyStack, this)
      //println("Constant eval " + this + " --> " + res)
      Some(res)
    } catch {
      case _ : DummyException =>
        // ...otherwise we also have a constant value if the two arguments of a binary atom are synactically equal
        // TODO Of course richer rewriting rules than just checking for equality would also be possible here. Is that worth exploring?
        val res = syntaxBasedConstantEval
//        res map {
//          b => println("Syntax-based constant eval of " + this + " to " + b)
//        }
        res
    }
  }

  def foldConstants : PureFormula = {
    println("Trying to eval " + this + " to a constant yielding " + constantEval)
    PureAtom.replaceByConstIfDefined(this, constantEval)
  }

  private case class DummyException() extends Throwable

  private lazy val dummyStack = new Stack() {
    override def apply(ptr: PtrExpr): Location = throw new DummyException
    override def apply(ix: IntExpr): Int  = throw new DummyException
  }

}

case class True() extends PureAtom {
  override def toString = "true"

  override def toSmtExpr: SmtExpr = "true"
}

case class False() extends PureAtom {
  override def toString = "false"

  override def toSmtExpr: SmtExpr = "false"
}

case class IxEq(l : IntExpr, r : IntExpr) extends PureAtom {
  override def toString = l + " \u2248 " + r

  override def toSmtExpr: SmtExpr = eqExpr(l.toSmtExpr, r.toSmtExpr)
}

case class IxGT(l : IntExpr, r : IntExpr) extends PureAtom {
  override def toString = l + " > " + r

  override def toSmtExpr: SmtExpr = gtExpr(l.toSmtExpr, r.toSmtExpr)
}

case class IxLT(l : IntExpr, r : IntExpr) extends PureAtom {
  override def toString = l + " < " + r

  override def toSmtExpr: SmtExpr = ltExpr(l.toSmtExpr, r.toSmtExpr)
}

case class IxLEq(l : IntExpr, r : IntExpr) extends PureAtom {
  override def toString = l + " \u2264 " + r

  override def toSmtExpr: SmtExpr = leqExpr(l.toSmtExpr, r.toSmtExpr)
}

case class IxGEq(l : IntExpr, r : IntExpr) extends PureAtom {
  override def toString = l + " \u2265 " + r

  override def toSmtExpr: SmtExpr = geqExpr(l.toSmtExpr, r.toSmtExpr)
}

case class IntNEq(l : IntExpr, r : IntExpr) extends PureAtom {
  override def toString = l + " \u2249 " + r

  override def toSmtExpr: SmtExpr = neqExpr(l.toSmtExpr, r.toSmtExpr)

}

case class PtrEq(l : PtrExpr, r : PtrExpr) extends PureAtom {
  override def toString = l + " \u2248 " + r

  override def toSmtExpr: SmtExpr = eqExpr(l.toSmtExpr, r.toSmtExpr)
}

case class PtrNEq(l : PtrExpr, r : PtrExpr) extends PureAtom {
  override def toString = l + " \u2249 " + r

  override def toSmtExpr: SmtExpr = neqExpr(l.toSmtExpr, r.toSmtExpr)
}

object PureAtom {

  def replaceByConstIfDefined(pf : PureFormula, const : Option[Boolean]) : PureFormula = const match {
    case Some(true) => True()
    case Some(false) => True()
    case None => pf
  }

//  def tryConstantFolding(op : (Int, Int) => Boolean)(l : IntExpr, r : IntExpr, ifSyntaxEqual : => Boolean) = {
//    if (l == r)
//      ifSyntaxEqual
//    else
//      for {
//        cl <- l.constantEval
//        cr <- r.constantEval
//      } yield op(cl, cr)
//  }

}